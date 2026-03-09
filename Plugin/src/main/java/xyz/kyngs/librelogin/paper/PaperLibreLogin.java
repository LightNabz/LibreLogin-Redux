/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.paper;

import co.aikar.commands.BukkitCommandIssuer;
import co.aikar.commands.CommandIssuer;
import co.aikar.commands.CommandManager;
import co.aikar.commands.PaperCommandManager;
import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import net.kyori.adventure.audience.Audience;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.event.exception.EventCancelledException;
import xyz.kyngs.librelogin.common.AuthenticLibreLogin;
import xyz.kyngs.librelogin.common.SLF4JLogger;
import xyz.kyngs.librelogin.common.image.AuthenticImageProjector;
import xyz.kyngs.librelogin.common.util.CancellableTask;
import xyz.kyngs.librelogin.paper.protocol.PacketListener;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.DEBUG;
import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.REMEMBER_LOCATION;
import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.REMEMBER_LOCATION_WORLDS;
import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.REMEMBER_LOCATION_MAX_AGE_DAYS;
import static xyz.kyngs.librelogin.common.config.ConfigurationKeys.REMEMBER_LOCATION_SAFETY_CHECKS;

public class PaperLibreLogin extends AuthenticLibreLogin<Player, World> {

    private final PaperBootstrap bootstrap;
    private PaperListeners listeners;
    private boolean started;

    public PaperLibreLogin(PaperBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.started = false;

        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(bootstrap));

        PacketEvents.getAPI().getSettings()
                .checkForUpdates(false)
                .bStats(false);

        PacketEvents.getAPI().load();
    }

    public PaperBootstrap getBootstrap() {
        return bootstrap;
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return bootstrap.getResource(name);
    }

    @Override
    public File getDataFolder() {
        return bootstrap.getDataFolder();
    }

    @Override
    public String getVersion() {
        return bootstrap.getDescription().getVersion();
    }

    @Override
    public boolean isPresent(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null;
    }

    @Override
    public boolean multiProxyEnabled() {
        return false;
    }

    @Override
    public Player getPlayerForUUID(UUID uuid) {
        return Bukkit.getPlayer(uuid);
    }

    @Override
    protected PaperPlatformHandle providePlatformHandle() {
        return new PaperPlatformHandle(this);
    }

    @Override
    protected Logger provideLogger() {
        return new SLF4JLogger(bootstrap.getSLF4JLogger(), () -> getConfiguration().get(DEBUG));
    }

    @Override
    public CommandManager<?, ?, ?, ?, ?, ?> provideManager() {
        return new PaperCommandManager(bootstrap);
    }

    @Override
    protected boolean mainThread() {
        return Bukkit.isPrimaryThread() && started;
    }

    @Override
    public Player getPlayerFromIssuer(CommandIssuer issuer) {
        var bukkitIssuer = (BukkitCommandIssuer) issuer;

        return bukkitIssuer.getPlayer();
    }

    @Override
    protected void disable() {
        PacketEvents.getAPI().terminate();
        if (getDatabaseProvider() == null) return; //Not initialized

        super.disable();
    }

    @Override
    protected void enable() {

        logger = provideLogger();

        if (Bukkit.getOnlineMode()) {
            getLogger().error("!!!The server is running in online mode! LibreLogin won't start unless you set it to false!!!");
            disable();
            return;
        }

        if (Bukkit.spigot().getSpigotConfig().getBoolean("settings.bungeecord") || Bukkit.spigot().getPaperConfig().getBoolean("settings.velocity-support.enabled")) {
            getLogger().error("!!!This server is running under a proxy, LibreLogin won't start!!!");
            getLogger().error("If you want to use LibreLogin under a proxy, place it on the proxy and remove it from the server.");
            disable();
            return;
        }

        try {
            super.enable();
        } catch (ShutdownException e) {
            return;
        }

        var provider = getEventProvider();

        provider.subscribe(provider.getTypes().authenticated, event -> {
            var player = event.getPlayer();
            if (player == null) return;
            player.setInvisible(false);
        });

        listeners = new PaperListeners(this);

        Bukkit.getPluginManager().registerEvents(listeners, bootstrap);
        Bukkit.getPluginManager().registerEvents(new Blockers(this), bootstrap);
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListener(listeners));

        started = true;
    }

    @Override
    public void authorize(Player player, User user, Audience audience) {
        try {

            var location = listeners.getSpawnLocationCache().getIfPresent(player);

            if (location == null) {
                // Try to restore saved location if enabled
                location = tryRestoreSavedLocation(player, user);

                // If no saved location, use lobby spawn
                if (location == null) {
                    var world = getServerHandler().chooseLobbyServer(user, player, true, false);

                    if (world == null) {
                        getPlatformHandle().kick(player, getMessages().getMessage("kick-no-lobby"));
                        return;
                    }

                    location = world.getSpawnLocation();
                }
            } else {
                listeners.getSpawnLocationCache().invalidate(player);
            }

            // Check if player is already at or very close to the target location (for premium users who spawned there)
            var currentLocation = player.getLocation();
            if (location.getWorld().equals(currentLocation.getWorld()) &&
                location.distanceSquared(currentLocation) < 1.0) {
                // Player is already at the target location, no need to teleport
                if (getConfiguration().get(DEBUG)) {
                    getLogger().info("Player " + player.getName() + " is already at target location, skipping teleport");
                }
                return;
            }

            var finalLocation = location;
            PaperUtil.runSyncAndWait(() -> player.teleportAsync(finalLocation), this);

        } catch (EventCancelledException ignored) {}
    }

    /**
     * Tries to restore a saved location for the player if enabled and valid.
     *
     * @param player the player to restore location for
     * @param user   the user object containing saved location data
     * @return the validated location to restore, or null if location should not be restored
     */
    private Location tryRestoreSavedLocation(Player player, User user) {
        if (!getConfiguration().get(REMEMBER_LOCATION)) {
            return null;
        }

        // Check if location data exists
        if (Double.isNaN(user.getLastX()) || Double.isNaN(user.getLastY()) || Double.isNaN(user.getLastZ())
                || user.getLastWorld() == null) {
            return null;
        }

        String worldName = user.getLastWorld();
        List<String> allowedWorlds = getConfiguration().get(REMEMBER_LOCATION_WORLDS);

        // Check if world is in allowed list
        if (!allowedWorlds.contains(worldName)) {
            if (getConfiguration().get(DEBUG)) {
                getLogger().info("Skipping location restore for " + player.getName() + ": world '" + worldName + "' not in allowed worlds list");
            }
            return null;
        }

        // Check if world exists
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            if (getConfiguration().get(DEBUG)) {
                getLogger().info("Skipping location restore for " + player.getName() + ": world '" + worldName + "' does not exist");
            }
            return null;
        }

        // Check location age if configured
        int maxAgeDays = getConfiguration().get(REMEMBER_LOCATION_MAX_AGE_DAYS);
        if (maxAgeDays >= 0 && user.getLastLocationTimestamp() != null) {
            long ageMillis = System.currentTimeMillis() - user.getLastLocationTimestamp().getTime();
            long maxAgeMillis = (long) maxAgeDays * 24 * 60 * 60 * 1000;

            if (ageMillis > maxAgeMillis) {
                if (getConfiguration().get(DEBUG)) {
                    getLogger().info("Skipping location restore for " + player.getName() + ": saved location is " + (ageMillis / 1000 / 60 / 60 / 24) + " days old (max: " + maxAgeDays + ")");
                }
                return null;
            }
        }

        Location location = new Location(world, user.getLastX(), user.getLastY(), user.getLastZ());

        // Perform safety checks if enabled
        if (getConfiguration().get(REMEMBER_LOCATION_SAFETY_CHECKS)) {
            if (!isLocationSafe(location)) {
                if (getConfiguration().get(DEBUG)) {
                    getLogger().info("Skipping location restore for " + player.getName() + ": location at " + user.getLastX() + ", " + user.getLastY() + ", " + user.getLastZ() + " in " + worldName + " is not safe");
                }
                return null;
            }
        }

        if (getConfiguration().get(DEBUG)) {
            getLogger().info("Restoring saved location for " + player.getName() + " at " + worldName + " (" + user.getLastX() + ", " + user.getLastY() + ", " + user.getLastZ() + ")");
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

    @Override
    public CancellableTask delay(Runnable runnable, long delayInMillis) {
        var task = Bukkit.getScheduler().runTaskLaterAsynchronously(bootstrap, runnable, delayInMillis / 50);
        return task::cancel;
    }

    @Override
    public CancellableTask repeat(Runnable runnable, long delayInMillis, long repeatInMillis) {
        var task = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(bootstrap, runnable, delayInMillis / 50, repeatInMillis / 50);
        return task::cancel;
    }

    @Override
    public boolean pluginPresent(String pluginName) {
        return Bukkit.getPluginManager().isPluginEnabled(pluginName);
    }

    @Override
    protected AuthenticImageProjector<Player, World> provideImageProjector() {
        return null;
    }

    @Override
    protected void initMetrics(CustomChart... charts) {
        var metrics = new Metrics(bootstrap, 17915);

        for (var chart : charts) {
            metrics.addCustomChart(chart);
        }

        var isVelocity = new SimplePie("is_velocity", () -> "Paper");

        metrics.addCustomChart(isVelocity);
    }

    @Override
    protected void shutdownProxy(int code) {
        bootstrap.disable();
        bootstrap.getServer().shutdown();
        throw new ShutdownException();
    }

    @Override
    public Audience getAudienceFromIssuer(CommandIssuer issuer) {
        return ((BukkitCommandIssuer) issuer).getIssuer();
    }
}
