package com.hpfxd.spectatorplus.paper;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import com.hpfxd.spectatorplus.paper.util.ReflectionUtil;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SpectatorWorkarounds implements Listener {
    private final SpectatorPlugin plugin;

    private final Map<UUID, UUID> tempTargets = new HashMap<>();
    private boolean directTeleportFailed;
    private boolean cameraPacketFailed;
    private boolean suppressStopSpectatingEvent;

    public SpectatorWorkarounds(SpectatorPlugin plugin) {
        this.plugin = plugin;

        if (plugin.getServerConfig().workaroundTeleportTicker) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::updateSpectatorPositions, 20, 20);
        }

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Garder le spectateur collé à sa cible quand ils sont dans le MÊME monde.
     * On ne tente jamais de teleport cross-world ici.
     */
    private void updateSpectatorPositions() {
        for (final Player spectator : Bukkit.getOnlinePlayers()) {
            final Entity target = spectator.getSpectatorTarget();

            if (target != null && spectator.getWorld().equals(target.getWorld())) {
                if (!this.directTeleportFailed) {
                    try {
                        ReflectionUtil.directTeleport(spectator, target.getLocation());
                    } catch (Throwable e) {
                        this.directTeleportFailed = true;
                        this.plugin.getSLF4JLogger().warn(
                            "auto-update-position workaround: Failed to call directTeleport, will not try again",
                            e
                        );
                        if (this.plugin.getServerConfig().workaroundsAllowFallback) {
                            this.plugin.getSLF4JLogger().warn(
                                "\"allow-fallback\" is enabled in the plugin configuration. " +
                                "This has a few drawbacks, it is recommended to view the notes in the config about this option."
                            );
                        }
                    }
                }

                if (this.directTeleportFailed && this.plugin.getServerConfig().workaroundsAllowFallback) {
                    // Fallback vanilla : clear + re-apply, mais toujours dans le même monde
                    spectator.setSpectatorTarget(null);
                    spectator.setSpectatorTarget(target);
                }
            }
        }
    }

    /**
     * La cible est untracked (souvent parce qu'elle s'est tp loin ou a changé de monde).
     * - Même monde : on essaie le directTeleport.
     * - Monde différent : on fait un teleport Bukkit avec cause SPECTATE.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent event) {
        if (!this.plugin.getServerConfig().workaroundTeleportOnUntrack || !event.getPlayer().isConnected()) {
            return;
        }

        final Player spectator = event.getPlayer();
        final Entity target = event.getEntity();

        if (!target.equals(spectator.getSpectatorTarget())) {
            return;
        }

        // la cible a été untrack : on veut garder le spectateur dessus
        this.tempTargets.put(spectator.getUniqueId(), target.getUniqueId());

        final boolean sameWorld = spectator.getWorld().equals(target.getWorld());

        // Essayer le directTeleport UNIQUEMENT si même monde
        if (!this.directTeleportFailed && sameWorld) {
            try {
                ReflectionUtil.directTeleport(spectator, target.getLocation());
                return;
            } catch (Throwable e) {
                this.directTeleportFailed = true;
            }
        }

        // Fallback : on utilise un teleport Bukkit
        if (this.plugin.getServerConfig().workaroundsAllowFallback) {
            if (!sameWorld) {
                // On doit nettoyer le spectate côté serveur AVANT de changer de monde,
                // sinon le client peut se retrouver à spectate une entité dans un autre dimension -> Network protocol error.
                this.suppressStopSpectatingEvent = true;
                try {
                    spectator.setSpectatorTarget(null);
                } finally {
                    this.suppressStopSpectatingEvent = false;
                }
            }

            spectator.teleport(target, PlayerTeleportEvent.TeleportCause.SPECTATE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (!this.plugin.getServerConfig().workaroundTeleportOnUntrack) {
            return;
        }

        final Player spectator = event.getPlayer();
        final Entity target = event.getEntity();

        if (this.tempTargets.remove(spectator.getUniqueId(), target.getUniqueId()) && !event.isCancelled()) {
            // On doit ré-appliquer un tick plus tard, sinon la cible n'est pas encore vraiment trackée
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (!this.cameraPacketFailed) {
                    try {
                        // Essayer d'envoyer directement le packet camera pour éviter la logique
                        // complète de setSpectatorTarget (qui fait un teleport en plus)
                        ReflectionUtil.sendCameraPacket(spectator, target);
                        return;
                    } catch (Throwable e) {
                        this.cameraPacketFailed = true;
                        this.plugin.getSLF4JLogger().warn(
                            "auto-teleport-on-untrack workaround: Failed to send ClientboundSetCameraPacket directly",
                            e
                        );
                        if (this.plugin.getServerConfig().workaroundsAllowFallback) {
                            this.plugin.getSLF4JLogger().warn(
                                "\"allow-fallback\" is enabled in the plugin configuration, " +
                                "falling back to Bukkit setSpectatorTarget(). This is unlikely to cause issues."
                            );
                        }
                    }
                }

                if (this.cameraPacketFailed) {
                    spectator.setSpectatorTarget(null);
                    spectator.setSpectatorTarget(target);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStartSpectating(PlayerStartSpectatingEntityEvent event) {
        if (!this.plugin.getServerConfig().workaroundTeleportOnUntrack) {
            return;
        }

        final Player spectator = event.getPlayer();
        final Entity target = event.getNewSpectatorTarget();

        if (!target.getTrackedBy().contains(spectator)) {
            this.tempTargets.put(spectator.getUniqueId(), target.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        if (!this.plugin.getServerConfig().workaroundTeleportOnUntrack) {
            return;
        }

        if (this.suppressStopSpectatingEvent) {
            // On est en train de faire un clear temporaire (cross-world fix), ne touche pas à tempTargets
            return;
        }

        final Player spectator = event.getPlayer();
        final Entity target = event.getSpectatorTarget();

        // Le joueur a vraiment arrêté de spectate cette cible -> on ne veut plus ré-appliquer
        this.tempTargets.remove(spectator.getUniqueId(), target.getUniqueId());
    }

    /**
     * Si LE JOUEUR (spectateur) se téléporte lui-même vers un autre monde
     * (portail, /warp, etc.) alors qu’il est en train de spectate quelqu’un,
     * on casse proprement le spectate pour éviter les désyncs.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!this.plugin.getServerConfig().workaroundTeleportOnUntrack) {
            return;
        }

        if (event.getFrom().getWorld().equals(event.getTo().getWorld())) {
            return;
        }

        final Player player = event.getPlayer();

        // Si la cause est SPECTATE, c’est notre propre fallback (onUntrack) -> ne pas toucher
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            return;
        }

        if (player.getSpectatorTarget() != null) {
            this.suppressStopSpectatingEvent = true;
            try {
                player.setSpectatorTarget(null);
            } finally {
                this.suppressStopSpectatingEvent = false;
            }

            // On s'assure aussi de ne pas garder d'état en attente
            this.tempTargets.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.tempTargets.remove(event.getPlayer().getUniqueId());
    }
}
