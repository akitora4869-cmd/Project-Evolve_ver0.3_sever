package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

public final class PlayerListener implements Listener {
    private final ProjectEvolvePlugin plugin;
    private final MatchManager match;

    public PlayerListener(ProjectEvolvePlugin plugin, MatchManager match) {
        this.plugin = plugin;
        this.match = match;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> match.autoJoin(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) return;
        if (!player.getUniqueId().equals(match.getMonsterId())) return;
        if (match.getRole(player.getUniqueId()) != Role.MONSTER) return;
        event.setCancelled(true);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
    }


    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        org.bukkit.Location anchor = match.getEvolutionAnchorFor(event.getPlayer());
        if (anchor == null || event.getTo() == null) return;

        org.bukkit.Location to = event.getTo();
        boolean changedPosition = Math.abs(to.getX() - anchor.getX()) > 1.0E-5
                || Math.abs(to.getY() - anchor.getY()) > 1.0E-5
                || Math.abs(to.getZ() - anchor.getZ()) > 1.0E-5;
        if (!changedPosition) return;

        // Position is locked, but preserve the player's current camera direction.
        anchor.setYaw(to.getYaw());
        anchor.setPitch(to.getPitch());
        event.setTo(anchor);
        event.getPlayer().setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    @EventHandler(ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (match.getEvolutionAnchorFor(event.getPlayer()) == null) return;
        // Evolution cannot be displaced by knockback, explosions or other velocity sources.
        event.setCancelled(true);
        event.getPlayer().setVelocity(new org.bukkit.util.Vector(0, 0, 0));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (match.getJoined().contains(event.getPlayer().getUniqueId())) {
            match.leave(event.getPlayer());
        }
    }
}
