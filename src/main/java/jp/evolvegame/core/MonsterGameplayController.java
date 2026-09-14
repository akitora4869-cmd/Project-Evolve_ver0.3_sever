package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Gameplay logic separated from the visual renderer.
 * This class is intentionally Model Engine independent: attack volumes,
 * wildlife/corpses and evolution can be retained when the renderer is replaced.
 */
public final class MonsterGameplayController implements Listener {
    private static final String WILDLIFE_TAG = "project_evolve_wildlife";
    private static final String CORPSE_TAG = "project_evolve_corpse";

    private final ProjectEvolvePlugin plugin;
    private final MatchManager match;
    private final TestMonsterController monsterRenderer;
    private final Map<UUID, Long> lastAttackTick = new HashMap<>();
    private final Map<UUID, Corpse> corpses = new HashMap<>();

    private record Corpse(UUID interactionId, UUID displayId, int evolution, long expiresAtTick) {}

    public MonsterGameplayController(ProjectEvolvePlugin plugin, MatchManager match, TestMonsterController monsterRenderer) {
        this.plugin = plugin;
        this.match = match;
        this.monsterRenderer = monsterRenderer;
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireCorpses, 20L, 20L);
    }

    public void spawnTestWildlife(Player center, int requestedCount) {
        int count = Math.max(1, Math.min(30, requestedCount));
        EntityType[] types = {EntityType.PIG, EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN};

        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0 * i / count) + (Math.random() * 0.35);
            double radius = 4.0 + Math.random() * 5.0;
            Location loc = center.getLocation().clone().add(Math.cos(angle) * radius, 0.2, Math.sin(angle) * radius);
            loc.setY(center.getWorld().getHighestBlockYAt(loc) + 1.0);

            Entity spawned = center.getWorld().spawnEntity(loc, types[i % types.length]);
            spawned.addScoreboardTag(WILDLIFE_TAG);
            if (spawned instanceof LivingEntity living) {
                living.setCustomName(ChatColor.GREEN + "Wildlife");
                living.setCustomNameVisible(false);
                living.setPersistent(true);
            }
        }
        center.sendMessage(ChatColor.GREEN + "テスト用Wildlifeを " + count + " 体スポーンしました。");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMonsterSwing(PlayerAnimationEvent event) {
        Player player = event.getPlayer();
        if (!monsterRenderer.isControlled(player)) return;
        performMonsterAttack(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMonsterLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!monsterRenderer.isControlled(player)) return;
        performMonsterAttack(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void cancelVanillaMonsterHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!monsterRenderer.isControlled(player)) return;
        // Entity clicks can bypass PlayerInteractEvent, so trigger our melee here too.
        // The cooldown de-duplicates this from PlayerAnimationEvent.
        performMonsterAttack(player);
        event.setCancelled(true);
    }

    private void performMonsterAttack(Player monster) {
        if (match.isMonsterDead()) return;
        if (match.isMonsterEvolving()) {
            monster.sendActionBar(ChatColor.LIGHT_PURPLE + "進化中は攻撃できません。");
            return;
        }

        int stage = match.getMonsterStage();
        int cooldown = plugin.getConfig().getInt("monster.attack.cooldown-ticks", 16);
        long now = Bukkit.getCurrentTick();
        long last = lastAttackTick.getOrDefault(monster.getUniqueId(), Long.MIN_VALUE / 2);
        if (now - last < cooldown) return;
        lastAttackTick.put(monster.getUniqueId(), now);

        String key = "monster.attack.stage-" + stage;
        double damage = plugin.getConfig().getDouble(key + ".damage", 6.0 + stage * 2.0);
        double range = plugin.getConfig().getDouble(key + ".range", 3.0 + stage);
        double arcDegrees = plugin.getConfig().getDouble(key + ".arc-degrees", 100.0 + stage * 5.0);
        double height = plugin.getConfig().getDouble(key + ".height", 2.8 + stage);

        LivingEntity body = monsterRenderer.getBody(monster.getUniqueId());
        Location base = (body != null && body.isValid()) ? body.getLocation().clone() : monster.getLocation().clone();
        Location eye = monster.getEyeLocation();
        Vector forward = eye.getDirection().clone();
        forward.setY(0);
        if (forward.lengthSquared() < 0.0001) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Vector origin = base.toVector().add(new Vector(0, Math.max(1.0, height * 0.36), 0));

        showClawAttackEffect(monster, origin, forward, right, range, arcDegrees, stage);

        int hits = 0;
        Collection<Entity> candidates = monster.getWorld().getNearbyEntities(base, range + 1.5, height + 2.0, range + 1.5);
        double halfArcRadians = Math.toRadians(arcDegrees * 0.5);

        for (Entity entity : candidates) {
            if (!(entity instanceof LivingEntity target)) continue;
            if (entity.getUniqueId().equals(monster.getUniqueId())) continue;
            if (body != null && entity.getUniqueId().equals(body.getUniqueId())) continue;
            if (entity instanceof ArmorStand) continue;

            boolean isWildlife = entity.getScoreboardTags().contains(WILDLIFE_TAG);
            boolean isHunter = entity instanceof Player p
                    && match.getJoined().contains(p.getUniqueId())
                    && match.getRole(p.getUniqueId()) != Role.MONSTER;
            if (!isWildlife && !isHunter) continue;

            BoundingBox box = entity.getBoundingBox();
            Vector center = new Vector(
                    (box.getMinX() + box.getMaxX()) * 0.5,
                    (box.getMinY() + box.getMaxY()) * 0.5,
                    (box.getMinZ() + box.getMaxZ()) * 0.5);
            Vector delta = center.clone().subtract(origin);

            double targetHalfHeight = (box.getMaxY() - box.getMinY()) * 0.5;
            if (Math.abs(delta.getY()) > (height * 0.5) + targetHalfHeight) continue;

            Vector horizontal = delta.clone().setY(0);
            double distance = horizontal.length();
            double targetPadding = Math.max(box.getWidthX(), box.getWidthZ()) * 0.5;
            if (distance > range + targetPadding) continue;
            if (distance > 0.001) {
                horizontal.normalize();
                double dot = Math.max(-1.0, Math.min(1.0, horizontal.dot(forward)));
                double angle = Math.acos(dot);
                double paddingAngle = Math.atan2(targetPadding, Math.max(0.25, distance));
                if (angle > halfArcRadians + paddingAngle) continue;
            }

            target.damage(damage);
            Vector push = horizontal.lengthSquared() > 0.001 ? horizontal.normalize() : forward.clone();
            push.setY(0.16).multiply(0.58 + 0.16 * stage);
            target.setVelocity(target.getVelocity().add(push));

            Location hitLoc = center.toLocation(monster.getWorld());
            monster.getWorld().spawnParticle(Particle.CRIT, hitLoc, 18, 0.3, 0.35, 0.3, 0.14);
            monster.getWorld().playSound(hitLoc, Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.9f, 0.72f);
            hits++;
        }

        monster.getWorld().playSound(monster.getLocation(), Sound.ENTITY_RAVAGER_ATTACK, 0.85f, 0.82f + stage * 0.06f);
        if (hits > 0) monster.sendActionBar(ChatColor.RED + "CLAW SWIPE" + ChatColor.GOLD + "  HIT x" + hits);
        else monster.sendActionBar(ChatColor.RED + "CLAW SWIPE" + ChatColor.GRAY + "  MISS");
    }

    /**
     * Three curved traces sweep across the same fan used by the actual hit test.
     * They are deliberately server-side particles so every player sees the claw.
     */
    private void showClawAttackEffect(Player monster, Vector origin, Vector forward, Vector right,
                                      double range, double arcDegrees, int stage) {
        World world = monster.getWorld();
        int samples = Math.max(12, (int) Math.ceil(range * 4.0));
        double arc = Math.toRadians(arcDegrees * 0.62);
        double clawGap = 0.26 + stage * 0.07;

        for (int claw = -1; claw <= 1; claw++) {
            for (int i = 0; i <= samples; i++) {
                double t = i / (double) samples;
                double d = 0.45 + range * (0.88 * t);
                double sweepAngle = (-arc * 0.5) + arc * t + claw * 0.045;
                Vector dir = forward.clone().multiply(Math.cos(sweepAngle))
                        .add(right.clone().multiply(Math.sin(sweepAngle)));
                double sideOffset = claw * clawGap;
                double y = 0.52 - 0.72 * t + claw * 0.12 + Math.sin(t * Math.PI) * 0.12;
                Vector point = origin.clone()
                        .add(dir.multiply(d))
                        .add(right.clone().multiply(sideOffset))
                        .add(new Vector(0, y, 0));
                world.spawnParticle(Particle.CRIT, point.toLocation(world), 1, 0, 0, 0, 0.02);
                if (i == samples || i == samples / 2) {
                    world.spawnParticle(Particle.SWEEP_ATTACK, point.toLocation(world), 1, 0, 0, 0, 0);
                }
            }
        }

        world.playSound(monster.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.15f, 0.62f + stage * 0.07f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMonsterDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player monster)) return;
        if (!monsterRenderer.isControlled(monster)) return;
        if (match.isMonsterDead()) {
            event.setCancelled(true);
            return;
        }

        double incoming = Math.max(0.0, event.getFinalDamage());
        event.setCancelled(true);
        if (incoming <= 0.0) return;

        MatchManager.DamageResult result = match.applyMonsterDamage(incoming);
        if (result.armorDamage() > 0.0) {
            monster.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, monster.getLocation().add(0, 1.2, 0), 12, 0.6, 0.8, 0.6, 0.08);
            monster.getWorld().playSound(monster.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8f, 0.72f);
        }
        if (result.healthDamage() > 0.0) {
            monster.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, monster.getLocation().add(0, 1.1, 0), 9, 0.45, 0.65, 0.45, 0.08);
            monster.getWorld().playSound(monster.getLocation(), Sound.ENTITY_RAVAGER_HURT, 0.9f, 0.82f);
        }

        monster.sendActionBar(ChatColor.RED + "HP " + (int)Math.ceil(result.healthAfter()) + "/" + (int)Math.ceil(match.getMonsterMaxHealth())
                + ChatColor.AQUA + "  外皮 " + (int)Math.ceil(result.armorAfter()) + "/" + (int)Math.ceil(match.getMonsterMaxArmor()));

        if (match.isMonsterDead()) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "[EVOLVE] " + ChatColor.RED + "Monster defeated!");
            monster.sendTitle(ChatColor.DARK_RED + "DEFEATED", ChatColor.GRAY + "Monsterの体力が尽きた", 10, 70, 20);
        }
    }

    @EventHandler
    public void onWildlifeDeath(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (!dead.getScoreboardTags().contains(WILDLIFE_TAG)) return;

        event.getDrops().clear();
        event.setDroppedExp(0);
        int evolution = evolutionFor(dead.getType());
        createCorpse(dead.getLocation(), evolution);
    }

    private int evolutionFor(EntityType type) {
        return switch (type) {
            case COW -> plugin.getConfig().getInt("wildlife.evolution.cow", 25);
            case SHEEP -> plugin.getConfig().getInt("wildlife.evolution.sheep", 20);
            case CHICKEN -> plugin.getConfig().getInt("wildlife.evolution.chicken", 10);
            default -> plugin.getConfig().getInt("wildlife.evolution.pig", 20);
        };
    }

    private void createCorpse(Location location, int evolution) {
        Location loc = location.clone().add(0, 0.25, 0);

        ItemDisplay display = (ItemDisplay) location.getWorld().spawnEntity(loc, EntityType.ITEM_DISPLAY);
        display.setItemStack(new ItemStack(Material.ROTTEN_FLESH));
        display.setCustomName(ChatColor.DARK_RED + "死骸" + ChatColor.LIGHT_PURPLE + "  +" + evolution + " Evolution");
        display.setCustomNameVisible(true);
        display.addScoreboardTag(CORPSE_TAG);
        display.setPersistent(true);

        Interaction interaction = (Interaction) location.getWorld().spawnEntity(loc, EntityType.INTERACTION);
        interaction.setInteractionWidth(1.5f);
        interaction.setInteractionHeight(1.2f);
        interaction.setResponsive(true);
        interaction.addScoreboardTag(CORPSE_TAG);
        interaction.setPersistent(true);

        int lifeSeconds = plugin.getConfig().getInt("wildlife.corpse-expire-seconds", 120);
        corpses.put(interaction.getUniqueId(), new Corpse(
                interaction.getUniqueId(), display.getUniqueId(), evolution,
                Bukkit.getCurrentTick() + lifeSeconds * 20L));
    }

    @EventHandler(ignoreCancelled = true)
    public void onCorpseInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!clicked.getScoreboardTags().contains(CORPSE_TAG)) return;
        Corpse corpse = corpses.get(clicked.getUniqueId());
        if (corpse == null) return;

        Player player = event.getPlayer();
        if (!monsterRenderer.isControlled(player)) {
            player.sendActionBar(ChatColor.GRAY + "Monsterだけが捕食できます。");
            return;
        }
        if (match.isMonsterEvolving()) {
            player.sendActionBar(ChatColor.LIGHT_PURPLE + "進化中は捕食できません。");
            event.setCancelled(true);
            return;
        }

        double feedRange = plugin.getConfig().getDouble("wildlife.feed-range", 3.5);
        LivingEntity body = monsterRenderer.getBody(player.getUniqueId());
        Location feedOrigin = (body != null && body.isValid()) ? body.getLocation() : player.getLocation();
        if (!player.getWorld().equals(clicked.getWorld()) || feedOrigin.distance(clicked.getLocation()) > feedRange) {
            player.sendActionBar(ChatColor.RED + "死骸に近づいてください。");
            return;
        }

        consumeCorpse(player, corpse);
        event.setCancelled(true);
    }

    private void consumeCorpse(Player monster, Corpse corpse) {
        match.addEvolution(corpse.evolution());
        double armorPerEvolution = plugin.getConfig().getDouble("monster.vitals.armor-per-evolution", 1.0);
        double restoredArmor = match.restoreMonsterArmor(corpse.evolution() * armorPerEvolution);
        removeCorpse(corpse.interactionId());

        monster.getWorld().playSound(monster.getLocation(), Sound.ENTITY_GENERIC_EAT, 1.0f, 0.65f);
        monster.sendMessage(ChatColor.LIGHT_PURPLE + "+" + corpse.evolution() + " Evolution"
                + ChatColor.AQUA + "  +" + (int)Math.ceil(restoredArmor) + " 外皮" + ChatColor.GRAY + " (捕食)");

    }

    private void expireCorpses() {
        long now = Bukkit.getCurrentTick();
        for (Corpse corpse : new ArrayList<>(corpses.values())) {
            if (now >= corpse.expiresAtTick()) removeCorpse(corpse.interactionId());
        }
    }

    private void removeCorpse(UUID interactionId) {
        Corpse corpse = corpses.remove(interactionId);
        if (corpse == null) return;
        Entity interaction = Bukkit.getEntity(corpse.interactionId());
        Entity display = Bukkit.getEntity(corpse.displayId());
        if (interaction != null) interaction.remove();
        if (display != null) display.remove();
    }

    public void clearAll() {
        for (UUID id : new ArrayList<>(corpses.keySet())) removeCorpse(id);
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(WILDLIFE_TAG)) entity.remove();
            }
        }
        lastAttackTick.clear();
    }
}
