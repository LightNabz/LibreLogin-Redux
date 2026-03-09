/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.reflection.Reflection;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.config.ConfigurationKeys;
import xyz.kyngs.librelogin.common.config.MessageKeys;
import xyz.kyngs.librelogin.common.listener.AuthenticListeners;
import xyz.kyngs.librelogin.common.util.GeneralUtil;
import xyz.kyngs.librelogin.paper.protocol.ClientPublicKey;

import java.util.concurrent.CompletableFuture;
import xyz.kyngs.librelogin.paper.protocol.EncryptionUtil;
import xyz.kyngs.librelogin.paper.protocol.ProtocolUtil;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.*;

import static xyz.kyngs.librelogin.paper.protocol.ProtocolUtil.getServerVersion;

public class PaperListeners extends AuthenticListeners<PaperLibreLogin, Player, World> implements Listener {

    private static final String ENCRYPTION_CLASS_NAME = "MinecraftEncryption";
    private static final Class<?> ENCRYPTION_CLASS;
    private static Method encryptMethod;
    private static Method cipherMethod;

    static {
        try {
            ENCRYPTION_CLASS = Class.forName("net.minecraft.util." + ENCRYPTION_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private final KeyPair keyPair = EncryptionUtil.generateKeyPair();
    private final Random random = new SecureRandom();
    private final Cache<String, EncryptionData> encryptionDataCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .build();
    private final FloodgateHelper floodgateHelper;
    private final Cache<Player, String> ipCache;
    private final Cache<UUID, User> readOnlyUserCache;
    private final Cache<Player, Location> spawnLocationCache;

    public PaperListeners(PaperLibreLogin plugin) {
        super(plugin);

        floodgateHelper = this.plugin.floodgateEnabled() ? new FloodgateHelper() : null;

        ipCache = Caffeine.newBuilder()
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .build();

        readOnlyUserCache = Caffeine.newBuilder()
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .build();

        spawnLocationCache = Caffeine.newBuilder()
                .expireAfterWrite(2, TimeUnit.MINUTES)
                .build();
    }

    public Cache<Player, Location> getSpawnLocationCache() {
        return spawnLocationCache;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        GeneralUtil.runAsync(() -> {
            // Save player location if feature is enabled
            if (plugin.getConfiguration().get(ConfigurationKeys.REMEMBER_LOCATION)) {
                savePlayerLocation(event.getPlayer());
            }
            onPlayerDisconnect(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPostLogin(PlayerLoginEvent event) {
        ipCache.put(event.getPlayer(), event.getAddress().getHostAddress());

        // Asynchronously fetch and cache the user for all players (including Floodgate)
        // This ensures the cache is ready for PlayerSpawnLocationEvent without main-thread DB calls
        CompletableFuture.supplyAsync(() -> plugin.getDatabaseProvider().getByUUID(event.getPlayer().getUniqueId()))
                .thenAccept(user -> {
                    if (user != null) {
                        readOnlyUserCache.put(event.getPlayer().getUniqueId(), user);
                        if (plugin.getConfiguration().get(ConfigurationKeys.DEBUG)) {
                            plugin.getLogger().debug("Cached user for player " + event.getPlayer().getName() + " (UUID: " + event.getPlayer().getUniqueId() + ")");
                        }
                    } else if (plugin.getConfiguration().get(ConfigurationKeys.DEBUG)) {
                        plugin.getLogger().debug("No user record found for player " + event.getPlayer().getName() + " (UUID: " + event.getPlayer().getUniqueId() + ")");
                    }
                });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        var data = readOnlyUserCache.getIfPresent(event.getPlayer().getUniqueId());
        if (data == null && !plugin.fromFloodgate(event.getPlayer().getName())) {
            event.getPlayer().kick(Component.text("Internal error, please try again later."));
            return;
        }
        readOnlyUserCache.invalidate(event.getPlayer().getUniqueId());
        onPostLogin(event.getPlayer(), data);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (plugin.fromFloodgate(event.getName())) return;

        var user = plugin.getDatabaseProvider().getByName(event.getName());

        var newProfile = Bukkit.createProfileExact(user.getUuid(), event.getName());

        event.setPlayerProfile(newProfile);

        readOnlyUserCache.put(user.getUuid(), user);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void chooseWorld(PlayerSpawnLocationEvent event) {
        var ip = ipCache.getIfPresent(event.getPlayer());
        if (ip == null) {
            event.getPlayer().kick(Component.text("Internal error, please try again later."));
            return;
        }
        // attempt to get user info from cache; if not present (e.g. floodgate) skip for now - will be handled in onPostLogin
        var cached = readOnlyUserCache.getIfPresent(event.getPlayer().getUniqueId());
        var world = chooseServer(event.getPlayer(), ip, cached);
        ipCache.invalidate(event.getPlayer());
        spawnLocationCache.invalidate(event.getPlayer());
        if (world.value() == null) {
            event.getPlayer().kick(plugin.getMessages().getMessage("kick-no-" + (world.key() ? "lobby" : "limbo")));
        } else {
            if (event.getPlayer().getHealth() == 0) {
                //Fixes bug where player is dead when logging in
                event.getPlayer().setHealth(event.getPlayer().getMaxHealth());
                var bed = event.getPlayer().getBedSpawnLocation();
                event.setSpawnLocation(bed == null ? world.value().getSpawnLocation() : bed);
            }

            // Check for saved location if player is authorized (going to lobby/world)
            if (world.key() && plugin.getConfiguration().get(ConfigurationKeys.REMEMBER_LOCATION)) {
                // Use cached user only - DB fallback removed to prevent main thread I/O
                if (cached != null) {
                    var savedLocation = tryRestoreSavedLocationForSpawn(cached);
                    if (savedLocation != null) {
                        event.setSpawnLocation(savedLocation);
                        if (plugin.getConfiguration().get(ConfigurationKeys.DEBUG)) {
                            plugin.getLogger().info("Setting initial spawn location for " + event.getPlayer().getName() + " to saved location at " + savedLocation.getWorld().getName() + " (" + savedLocation.getX() + ", " + savedLocation.getY() + ", " + savedLocation.getZ() + ")");
                        }
                        return; // Skip the rest of the logic
                    }
                }
            }

            //This is terrible, but should work
            if (event.getPlayer().hasPlayedBefore() && !plugin.getConfiguration().get(ConfigurationKeys.LIMBO).contains(event.getSpawnLocation().getWorld().getName())) {
                if (plugin.getConfiguration().get(ConfigurationKeys.LIMBO).contains(world.value().getName())) {
                    spawnLocationCache.put(event.getPlayer(), event.getSpawnLocation());
                } else {
                    return;
                }
            }

            event.setSpawnLocation(world.value().getSpawnLocation());

        }
    }

    /* Commented out when migrating to PacketEvents
    //Unused, might be useful in the future
    public void setUUID(Player player, String username) {
        var profile = plugin.getDatabaseProvider().getByName(username);

        try {
            var network = getNetworkManager(player);

            var clazz = network.getClass();
            var accessor = Accessors.getFieldAccessorOrNull(clazz, "spoofedUUID", UUID.class);
            accessor.set(network, profile.getUuid());
        } catch (Exception e) {
            e.printStackTrace();
            kickPlayer("Internal error", player);
        }
    }*/

    public void asyncPacketReceive(PacketReceiveEvent event) {
        var user = event.getUser();
        var type = event.getPacketType();

        plugin.getLogger().debug("Packet received " + type + " from " + user.getName() + " (" + user.getAddress().toString() + ")");

        if (type == PacketType.Login.Client.LOGIN_START) {
            var packet = new WrapperLoginClientLoginStart(event);
            var sessionKey = user.getAddress().toString();

            encryptionDataCache.invalidate(sessionKey);

            if (plugin.floodgateEnabled()) {
                var success = floodgateHelper.processFloodgateTasks(event, packet);
                // don't continue execution if the player was kicked by Floodgate
                if (!success) {
                    return;
                }
            }
            var username = packet.getUsername();

            Optional<ClientPublicKey> clientKey;

            if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
                clientKey = Optional.empty();
            } else {
                var signature = packet.getSignatureData();

                clientKey = signature.map(data -> {
                    var expires = data.getTimestamp();
                    var key = data.getPublicKey();
                    var signatureData = data.getSignature();

                    return new ClientPublicKey(expires, key, signatureData);
                });
            }

            if (Bukkit.getPlayer(username) != null) {
                kickPlayer(plugin.getMessages().getMessage(MessageKeys.KICK_ALREADY_CONNECTED.key()), user);
                return;
            }

            if (plugin.fromFloodgate(username)) {
                //Floodgate player, do not handle, only retransmit the packet. The UUID will be set by Floodgate
                receiveFakeStartPacket(username, clientKey.orElse(null), event.getChannel(), UUID.randomUUID());
                return;
            }
            var preLoginResult = onPreLogin(username, user.getAddress().getAddress());
            switch (preLoginResult.state()) {
                case DENIED -> {
                    assert preLoginResult.message() != null;
                    kickPlayer(preLoginResult.message(), user);
                }
                case FORCE_ONLINE -> {
                    byte[] token;
                    try {
                        token = EncryptionUtil.generateVerifyToken(random);

                        var newPacket = new WrapperLoginServerEncryptionRequest("", keyPair.getPublic(), token);

                        encryptionDataCache.put(sessionKey, new EncryptionData(username, token, clientKey.orElse(null), preLoginResult.user().getUuid()));

                        PacketEvents.getAPI().getProtocolManager().sendPacket(event.getChannel(), newPacket);
                    } catch (Exception e) {
                        plugin.getLogger().error("Failed to send encryption begin packet for player " + username + "! Kicking player.");
                        e.printStackTrace();
                        kickPlayer("Internal error", user);
                    }
                }
                default -> {
                    // The original event has been cancelled, so we need to send a fake start packet. It should be safe to set a random UUID as it will be replaced by the real one later
                    receiveFakeStartPacket(username, clientKey.orElse(null), event.getChannel(), UUID.randomUUID());
                }
            }
        } else {
            var packet = new WrapperLoginClientEncryptionResponse(event);
            var sharedSecret = packet.getEncryptedSharedSecret();

            var data = encryptionDataCache.getIfPresent(user.getAddress().toString());

            if (data == null) {
                kickPlayer("Illegal encryption state", user);
                return;
            }

            var expectedToken = data.token().clone();

            if (!verifyNonce(packet, data.publicKey(), expectedToken)) {
                kickPlayer("Invalid nonce", user);
            }

            //Verify session
            var privateKey = keyPair.getPrivate();

            SecretKey loginKey;

            try {
                loginKey = EncryptionUtil.decryptSharedKey(privateKey, sharedSecret);
            } catch (GeneralSecurityException securityEx) {
                kickPlayer("Cannot decrypt shared secret", user);
                return;
            }

            try {
                if (!enableEncryption(loginKey, user, event.getChannel())) {
                    return;
                }
            } catch (Exception e) {
                kickPlayer("Cannot decrypt shared secret", user);
                return;
            }

            var serverId = EncryptionUtil.getServerIdHashString("", loginKey, keyPair.getPublic());
            var username = data.username();
            var address = user.getAddress();

            try {
                if (hasJoined(username, serverId, address.getAddress())) {
                    receiveFakeStartPacket(username, data.publicKey(), event.getChannel(), data.uuid());
                } else {
                    kickPlayer("Invalid session", user);
                }
            } catch (IOException e) {
                if (e instanceof SocketTimeoutException) {
                    plugin.getLogger().warn("Session verification timed out (5 seconds) for " + username);
                }
                kickPlayer("Cannot verify session", user);
            }
        }
    }

    public void onPacketReceive(PacketReceiveEvent event) {
        event.setCancelled(true);

        var copy = event.clone();

        AuthenticLibreLogin.EXECUTOR.execute(() -> {
            try {
                asyncPacketReceive(copy);
            } finally {
                copy.cleanUp();
            }
        });
    }

    /**
     * fake a new login packet in order to let the server handle all the other stuff
     *
     * @author games647 and FastLogin contributors
     */
    private void receiveFakeStartPacket(String username, ClientPublicKey clientKey, Object channel, UUID uuid) {
        WrapperLoginClientLoginStart startPacket;
        if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_20)) {
            startPacket = new WrapperLoginClientLoginStart(getServerVersion().toClientVersion(), username, clientKey == null ? null : clientKey.toSignatureData(), uuid);
        } else if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19)) {
            startPacket = new WrapperLoginClientLoginStart(getServerVersion().toClientVersion(), username, clientKey == null ? null : clientKey.toSignatureData());
        } else {
            startPacket = new WrapperLoginClientLoginStart(getServerVersion().toClientVersion(), username);
        }
        PacketEvents.getAPI().getProtocolManager().receivePacketSilently(channel, startPacket);
    }

    public boolean hasJoined(String username, String serverHash, InetAddress hostIp) throws IOException {
        String url;
        if (hostIp instanceof Inet6Address || plugin.getConfiguration().get(ConfigurationKeys.ALLOW_PROXY_CONNECTIONS)) {
            url = String.format("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s", username, serverHash);
        } else {
            var encodedIP = URLEncoder.encode(hostIp.getHostAddress(), StandardCharsets.UTF_8);
            url = String.format("https://sessionserver.mojang.com/session/minecraft/hasJoined?username=%s&serverId=%s&ip=%s", username, serverHash, encodedIP);
        }

        var conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();
        int responseCode = conn.getResponseCode();
        conn.disconnect();
        return responseCode != 204;
    }

    /**
     * @author games647 and FastLogin contributors, kyngs
     */
    private boolean enableEncryption(SecretKey loginKey, com.github.retrooper.packetevents.protocol.player.User user, Object channel) throws IllegalArgumentException {
        // Initialize method reflections
        if (encryptMethod == null) {
            Class<?> networkManagerClass = SpigotReflectionUtil.getNetworkManagers().get(0).getClass();

            // Try to get the old (pre MC 1.16.4) encryption method
            encryptMethod = Reflection.getMethod(networkManagerClass, "setupEncryption", SecretKey.class);

            if (encryptMethod == null) {
                // Try to get the new encryption method
                encryptMethod = Reflection.getMethod(networkManagerClass, "setEncryptionKey", SecretKey.class);
            }

            if (encryptMethod == null) {
                // Get the 1.16.4-1.21.0 encryption method
                encryptMethod = Reflection.getMethod(networkManagerClass, "setEncryptionKey", Cipher.class, Cipher.class);

                // Get the needed Cipher helper method (used to generate ciphers from login key)
                cipherMethod = Reflection.getMethod(ENCRYPTION_CLASS, "a", int.class, Key.class);
            }
        }

        try {
            Object networkManager = ProtocolUtil.findNetworkManager(channel);

            // If cipherMethod is null - use old encryption (pre MC 1.16.4), otherwise use the new cipher one
            if (cipherMethod == null) {
                // Encrypt/decrypt packet flow, this behaviour is expected by the client
                encryptMethod.invoke(networkManager, loginKey);
            } else {
                // Create ciphers from login key
                Object decryptionCipher = cipherMethod.invoke(null, Cipher.DECRYPT_MODE, loginKey);
                Object encryptionCipher = cipherMethod.invoke(null, Cipher.ENCRYPT_MODE, loginKey);

                // Encrypt/decrypt packet flow, this behaviour is expected by the client
                encryptMethod.invoke(networkManager, decryptionCipher, encryptionCipher);
            }
        } catch (Exception ex) {
            kickPlayer("Couldn't enable encryption", user);
            ex.printStackTrace();
            return false;
        }

        return true;
    }

    private void kickPlayer(String reason, com.github.retrooper.packetevents.protocol.player.User player) {
        kickPlayer(Component.text(reason), player);
    }

    private void kickPlayer(Component reason, com.github.retrooper.packetevents.protocol.player.User player) {
        // Cannot use Player#kick(Component) because it doesn't work in the login state
        var kickPacket = new WrapperLoginServerDisconnect(reason);
        try {
            //send kick packet at login state
            PacketEvents.getAPI().getProtocolManager().sendPacket(player.getChannel(), kickPacket);
        } finally {
            //tell the server that we want to close the connection
            player.closeConnection();
        }
    }

    /**
     * @author games647 and FastLogin contributors
     */
    private boolean verifyNonce(WrapperLoginClientEncryptionResponse packet,
                                ClientPublicKey clientPublicKey, byte[] expectedToken) {
        try {
            if (getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19)
                && !getServerVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3)) {
                if (clientPublicKey == null) {
                    return EncryptionUtil.verifyNonce(expectedToken, keyPair.getPrivate(), packet.getEncryptedVerifyToken().get());
                } else {
                    PublicKey publicKey = clientPublicKey.key();
                    var optSignature = packet.getSaltSignature();
                    if (optSignature.isEmpty()) {
                        return false;
                    }
                    var signature = optSignature.get();

                    return EncryptionUtil.verifySignedNonce(expectedToken, publicKey, signature.getSalt(), signature.getSignature());
                }
            } else {
                byte[] nonce = packet.getEncryptedVerifyToken().get();
                return EncryptionUtil.verifyNonce(expectedToken, keyPair.getPrivate(), nonce);
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException | NoSuchPaddingException
                 | IllegalBlockSizeException | BadPaddingException signatureEx) {
            return false;
        }
    }

    private void savePlayerLocation(Player player) {
        try {
            var uuid = player.getUniqueId();
            var user = plugin.getDatabaseProvider().getByUUID(uuid);
            // optionally create a record for floodgate players if we want to remember their location
            if (user == null && plugin.fromFloodgate(uuid) && plugin.getConfiguration().get(ConfigurationKeys.REMEMBER_LOCATION_CREATE_FLOODGATE)) {
                // minimal user record; other fields may remain null
                user = plugin.createUser(
                        uuid,
                        null,
                        null,
                        player.getName(),
                        new java.sql.Timestamp(System.currentTimeMillis()),
                        new java.sql.Timestamp(System.currentTimeMillis()),
                        null,
                        plugin.getPlatformHandle().getIP(player),
                        null,
                        null,
                        null
                );
                plugin.getDatabaseProvider().insertUser(user);
            }
            if (user == null) return;

            var location = player.getLocation();
            var worldName = location.getWorld().getName();

            // Only save location if world is in allowed list (and not limbo)
            var allowedWorlds = plugin.getConfiguration().get(ConfigurationKeys.REMEMBER_LOCATION_WORLDS);
            if (!allowedWorlds.contains(worldName)) {
                return;
            }

            // Save location data
            user.setLastX(location.getX());
            user.setLastY(location.getY());
            user.setLastZ(location.getZ());
            user.setLastWorld(worldName);
            user.setLastLocationTimestamp(new java.sql.Timestamp(System.currentTimeMillis()));

            plugin.getDatabaseProvider().updateUser(user);

            if (plugin.getConfiguration().get(ConfigurationKeys.DEBUG)) {
                plugin.getLogger().info("Saved location for player " + player.getName() + " at " + worldName + " (" + location.getX() + ", " + location.getY() + ", " + location.getZ() + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save player location for " + player.getName(), e);
        }
    }

    /**
     * Tries to restore a saved location for spawn location setting.
     * This is a simplified version of the validation in PaperLibreLogin for use during spawn events.
     *
     * @param user the user object containing saved location data
     * @return the validated location to spawn at, or null if location should not be restored
     */
    private Location tryRestoreSavedLocationForSpawn(User user) {
        // Check if location data exists
        if (Double.isNaN(user.getLastX()) || Double.isNaN(user.getLastY()) || Double.isNaN(user.getLastZ())
                || user.getLastWorld() == null) {
            return null;
        }

        String worldName = user.getLastWorld();
        List<String> allowedWorlds = plugin.getConfiguration().get(ConfigurationKeys.REMEMBER_LOCATION_WORLDS);

        // Check if world is in allowed list
        if (!allowedWorlds.contains(worldName)) {
            return null;
        }

        // Check if world exists
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        // Check location age if configured
        int maxAgeDays = plugin.getConfiguration().get(ConfigurationKeys.REMEMBER_LOCATION_MAX_AGE_DAYS);
        if (maxAgeDays >= 0 && user.getLastLocationTimestamp() != null) {
            long ageMillis = System.currentTimeMillis() - user.getLastLocationTimestamp().getTime();
            long maxAgeMillis = (long) maxAgeDays * 24 * 60 * 60 * 1000;

            if (ageMillis > maxAgeMillis) {
                return null;
            }
        }

        Location location = new Location(world, user.getLastX(), user.getLastY(), user.getLastZ());

        // Perform safety checks if enabled
        if (plugin.getConfiguration().get(ConfigurationKeys.REMEMBER_LOCATION_SAFETY_CHECKS)) {
            if (!isLocationSafe(location)) {
                return null;
            }
        }

        return location;
    }

    /**
     * Checks if a location is safe for the player to spawn at.
     * A location is considered safe if there are solid blocks below it.
     *
     * @param location the location to check
     * @return true if the location is safe, false otherwise
     */
    private boolean isLocationSafe(Location location) {
        // Check if block below is solid
        Location belowLocation = location.clone().subtract(0, 1, 0);
        if (!belowLocation.getBlock().isSolid()) {
            return false;
        }

        // Check if location block and block above are not solid (for head room)
        if (location.getBlock().isSolid() || location.clone().add(0, 1, 0).getBlock().isSolid()) {
            return false;
        }

        return true;
    }
}
