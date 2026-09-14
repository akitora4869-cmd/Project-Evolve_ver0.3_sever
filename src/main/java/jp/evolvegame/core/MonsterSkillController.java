package jp.evolvegame.core;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Monster hotbar is an input device, not an inventory.
 * Tagged skill items are server owned and cannot be moved/dropped/swapped.
 */
public final class MonsterSkillController implements Listener {
    private final ProjectEvolvePlugin plugin;
    private final MatchManager match;
    private final NamespacedKey skillKey;

    public MonsterSkillController(ProjectEvolvePlugin plugin, MatchManager match) {
        this.plugin = plugin;
        this.match = match;
        this.skillKey = new NamespacedKey(plugin, "monster_skill");
    }

    public void equip(Player player) {
        if (!isMonster(player)) return;
        player.getInventory().clear();
        player.getInventory().setItem(0, skill(Material.IRON_SWORD, "attack", ChatColor.RED + "CLAW SWIPE",
                List.of(ChatColor.GRAY + "左クリック: 前方扇形の爪攻撃")));
        player.getInventory().setItem(1, skill(Material.GRAY_DYE, "skill_1", ChatColor.DARK_GRAY + "SKILL 1",
                List.of(ChatColor.GRAY + "開発中")));
        player.getInventory().setItem(2, skill(Material.GRAY_DYE, "skill_2", ChatColor.DARK_GRAY + "SKILL 2",
                List.of(ChatColor.GRAY + "開発中")));
        player.getInventory().setItem(3, skill(Material.GRAY_DYE, "skill_3", ChatColor.DARK_GRAY + "SKILL 3",
                List.of(ChatColor.GRAY + "開発中")));
        player.getInventory().setItem(4, skill(Material.GRAY_DYE, "skill_4", ChatColor.DARK_GRAY + "SKILL 4",
                List.of(ChatColor.GRAY + "開発中")));
        player.getInventory().setItem(7, skill(Material.ROTTEN_FLESH, "feed", ChatColor.DARK_RED + "FEED",
                List.of(ChatColor.GRAY + "死骸を右クリックして捕食")));
        refreshEvolveItem(player);
        player.getInventory().setHeldItemSlot(0);
    }

    public void refreshEvolveItem(Player player) {
        if (!isMonster(player)) return;
        String state;
        Material material;
        if (match.getMonsterStage() >= 3) {
            state = ChatColor.GRAY + "MAX STAGE";
            material = Material.NETHER_STAR;
        } else if (match.isMonsterEvolving()) {
            state = ChatColor.LIGHT_PURPLE + "EVOLVING... " + match.getEvolutionSecondsRemaining() + "s";
            material = Material.AMETHYST_SHARD;
        } else if (match.canStartEvolution()) {
            state = ChatColor.GREEN + "READY - 右クリックで進化";
            material = Material.NETHER_STAR;
        } else {
            int needed = match.getEvolutionNeededForNextStage();
            state = ChatColor.GRAY + "Evolution " + match.getEvolution() + " / " + needed;
            material = Material.ECHO_SHARD;
        }
        player.getInventory().setItem(8, skill(material, "evolve", ChatColor.LIGHT_PURPLE + "EVOLVE",
                List.of(state, ChatColor.DARK_GRAY + "開始後はキャンセル不可")));
    }

    public void clear(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (isSkill(item)) player.getInventory().setItem(i, null);
        }
    }

    private ItemStack skill(Material material, String id, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
        meta.getPersistentDataContainer().set(skillKey, PersistentDataType.STRING, id);
        item.setItemMeta(meta);
        return item;
    }

    private String skillId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(skillKey, PersistentDataType.STRING);
    }

    private boolean isSkill(ItemStack item) { return skillId(item) != null; }
    private boolean isMonster(Player player) {
        return player != null && player.getUniqueId().equals(match.getMonsterId())
                && match.getRole(player.getUniqueId()) == Role.MONSTER;
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (!isMonster(event.getPlayer())) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        String id = skillId(event.getItem());
        if (id == null) return;
        if (id.equals("evolve")) {
            event.setCancelled(true);
            match.startEvolution(event.getPlayer());
            refreshEvolveItem(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isMonster(player)) return;
        // Monster inventory is a protected skill deck during a match.
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isMonster(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isMonster(event.getPlayer()) && isSkill(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isMonster(event.getPlayer()) && (isSkill(event.getMainHandItem()) || isSkill(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }
}
