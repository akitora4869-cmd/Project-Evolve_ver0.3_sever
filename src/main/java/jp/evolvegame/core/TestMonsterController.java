package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * v0.4.1 Monster controller.
 *
 * The old Skeleton/Wither surrogate entities are intentionally gone.
 * The real Player is the authoritative movement/combat entity; EVOLVE Client
 * replaces that player's renderer with the Stage model.
 */
public final class TestMonsterController {
    private final MatchManager match;

    public TestMonsterController(ProjectEvolvePlugin plugin, MatchManager match) {
        this.match = match;
    }

    public void becomeTestMonster(Player player) {
        match.startTestMonster(player);
        showStage(player, 1);
        player.sendMessage(ChatColor.DARK_RED + "EVOLVE Client Monster mode: Stage 1");
    }

    public boolean levelUp(Player player) {
        if (!isControlled(player)) {
            player.sendMessage(ChatColor.RED + "先に /evolve monster を実行してください。");
            return false;
        }
        int current = match.getMonsterStage();
        if (current >= 3) {
            player.sendMessage(ChatColor.YELLOW + "すでにStage 3です。");
            return false;
        }
        match.setMonsterStage(current + 1);
        showStage(player, current + 1);
        return true;
    }

    public void refreshMonsterBody() {
        UUID id = match.getMonsterId();
        if (id == null) return;
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline()) {
            showStage(player, match.getMonsterStage());
        }
    }

    private void showStage(Player player, int stage) {
        String modelName = switch (stage) {
            case 2 -> "WITHER SKELETON";
            case 3 -> "WITHER";
            default -> "SKELETON";
        };
        player.sendTitle(
                ChatColor.DARK_PURPLE + "EVOLUTION",
                ChatColor.RED + "STAGE " + stage + ChatColor.GRAY + " - " + modelName,
                5, 35, 10
        );
        player.sendMessage(ChatColor.RED + "HP " + (int)Math.ceil(match.getMonsterHealth()) + "/" + (int)Math.ceil(match.getMonsterMaxHealth())
                + ChatColor.AQUA + "  外皮 " + (int)Math.ceil(match.getMonsterArmor()) + "/" + (int)Math.ceil(match.getMonsterMaxArmor()));
        match.updateAllSidebars();
    }

    public LivingEntity getBody(UUID controllerId) {
        return null; // v0.4.1: visual body exists only in the client renderer.
    }

    public boolean isControlled(Player player) {
        return player != null
                && player.getUniqueId().equals(match.getMonsterId())
                && match.getRole(player.getUniqueId()) == Role.MONSTER;
    }

    public void restorePlayer(Player player) {
        // No surrogate body / invisibility / camera state exists in v0.4.1.
    }

    /**
     * Compatibility hook used by /evolve reset.
     * v0.4.x no longer creates server-side surrogate Monster entities,
     * so there is nothing to restore here. Keeping this method lets the
     * reset command keep a stable controller API.
     */
    public void restoreAll() {
        // Intentionally empty. Client renderer state is cleared by match.reset().
    }

    public void shutdown() {
        // Nothing to remove. Client renderer follows scoreboard state.
    }
}
