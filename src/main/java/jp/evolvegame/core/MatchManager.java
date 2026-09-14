package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.*;

public final class MatchManager {
    public record DamageResult(double armorDamage, double healthDamage, double armorAfter, double healthAfter) {}

    private final ProjectEvolvePlugin plugin;
    private final Set<UUID> joined = new LinkedHashSet<>();
    private final Map<UUID, Role> roles = new HashMap<>();
    private MatchState state = MatchState.LOBBY;
    private UUID monsterId;
    private int monsterStage;
    private int evolution;
    private double monsterHealth;
    private double monsterArmor;
    private int taskId = -1;
    private int evolutionTaskId = -1;
    private boolean monsterEvolving = false;
    private int evolvingToStage = 0;
    private long evolutionEndsAtTick = 0L;
    private Location evolutionAnchor;
    private MonsterSkillController monsterSkillController;

    public void setMonsterSkillController(MonsterSkillController controller) { this.monsterSkillController = controller; }

    public MatchManager(ProjectEvolvePlugin plugin) {
        this.plugin = plugin;
        this.monsterStage = plugin.getConfig().getInt("monster.starting-stage", 1);
        resetMonsterVitals();
        startSidebarTask();
    }

    public void autoJoin(Player player) {
        if (state == MatchState.LOBBY) {
            if (joined.add(player.getUniqueId())) roles.put(player.getUniqueId(), Role.SPECTATOR);
            updateAllSidebars();
            return;
        }
        if (joined.contains(player.getUniqueId())) updateSidebar(player);
    }

    public void join(Player player) {
        if (state != MatchState.LOBBY) {
            player.sendMessage(ChatColor.RED + "ゲーム進行中のため参加できません。");
            return;
        }
        joined.add(player.getUniqueId());
        roles.put(player.getUniqueId(), Role.SPECTATOR);
        player.sendMessage(ChatColor.GREEN + "Project EVOLVE のロビーに参加しました。");
        updateAllSidebars();
    }

    public void leave(Player player) {
        joined.remove(player.getUniqueId());
        roles.remove(player.getUniqueId());
        if (Objects.equals(monsterId, player.getUniqueId())) {
            if (monsterSkillController != null) monsterSkillController.clear(player);
            resetPlayerClientState(player);
            monsterId = null;
        }
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        updateAllSidebars();
    }

    public boolean start(CommandSender sender) {
        if (state != MatchState.LOBBY) {
            sender.sendMessage(ChatColor.RED + "すでにゲームが開始されています。");
            return false;
        }
        if (joined.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "参加者がいません。");
            return false;
        }

        List<UUID> players = new ArrayList<>(joined);
        if (monsterId == null || !joined.contains(monsterId)) monsterId = players.get(0);

        roles.put(monsterId, Role.MONSTER);
        Role[] hunterRoles = {Role.ASSAULT, Role.TRACKER, Role.MEDIC, Role.SUPPORT};
        int i = 0;
        for (UUID id : players) {
            if (id.equals(monsterId)) continue;
            roles.put(id, hunterRoles[Math.min(i, hunterRoles.length - 1)]);
            i++;
        }

        monsterStage = 1;
        evolution = 0;
        clearEvolutionState();
        resetMonsterVitals();
        state = MatchState.RUNNING;

        forEachOnline(player -> {
            Role role = roles.getOrDefault(player.getUniqueId(), Role.SPECTATOR);
            if (role == Role.MONSTER) {
                player.sendTitle(ChatColor.DARK_RED + "MONSTER", ChatColor.GOLD + "三人称視点 / Stage 1", 10, 70, 15);
                player.sendMessage(ChatColor.DARK_RED + "HP " + (int) monsterHealth + "/" + (int) getMonsterMaxHealth()
                        + ChatColor.AQUA + "  外皮 " + (int) monsterArmor + "/" + (int) getMonsterMaxArmor());
            } else if (isHunterRole(role)) {
                player.sendTitle(ChatColor.AQUA + role.name(), ChatColor.WHITE + "一人称視点", 10, 70, 15);
            } else {
                player.sendTitle(ChatColor.DARK_RED + "PROJECT EVOLVE", ChatColor.WHITE + role.name(), 10, 50, 10);
            }
            player.sendMessage(ChatColor.GOLD + "ゲーム開始！ 役割: " + ChatColor.WHITE + role.name());
        });
        Player monster = monsterId == null ? null : Bukkit.getPlayer(monsterId);
        if (monster != null && monsterSkillController != null) monsterSkillController.equip(monster);
        updateAllSidebars();
        return true;
    }

    public void reset() {
        Player oldMonster = monsterId == null ? null : Bukkit.getPlayer(monsterId);
        if (oldMonster != null) {
            if (monsterSkillController != null) monsterSkillController.clear(oldMonster);
            resetPlayerClientState(oldMonster);
        }
        state = MatchState.LOBBY;
        monsterId = null;
        monsterStage = 1;
        evolution = 0;
        cancelEvolutionTask();
        clearEvolutionState();
        resetMonsterVitals();
        roles.replaceAll((id, role) -> Role.SPECTATOR);
        updateAllSidebars();
    }

    public void startTestMonster(Player player) {
        if (!joined.contains(player.getUniqueId())) joined.add(player.getUniqueId());
        monsterId = player.getUniqueId();
        roles.put(player.getUniqueId(), Role.MONSTER);
        monsterStage = 1;
        evolution = 0;
        cancelEvolutionTask();
        clearEvolutionState();
        resetMonsterVitals();
        state = MatchState.RUNNING;
        if (monsterSkillController != null) monsterSkillController.equip(player);
        updateAllSidebars();
    }

    public void setMonsterStage(int stage) {
        cancelEvolutionTask();
        clearEvolutionState();
        int next = Math.max(1, Math.min(3, stage));
        int old = monsterStage;
        if (next != old) {
            monsterStage = next;
            growHealthForStageChange(old, next);
            if (next > old) monsterArmor = 0.0;
        }
        updateAllSidebars();
    }

    public void setMonster(Player player) {
        cancelEvolutionTask();
        clearEvolutionState();
        if (!joined.contains(player.getUniqueId())) joined.add(player.getUniqueId());
        monsterId = player.getUniqueId();
        roles.put(player.getUniqueId(), Role.MONSTER);
        resetMonsterVitals();
        if (monsterSkillController != null) monsterSkillController.equip(player);
        updateAllSidebars();
    }

    public void addEvolution(int amount) {
        if (state != MatchState.RUNNING) return;
        evolution = Math.max(0, evolution + amount);
        updateAllSidebars();
    }

    public boolean canStartEvolution() {
        if (state != MatchState.RUNNING || monsterStage >= 3 || monsterEvolving) return false;
        return evolution >= getEvolutionNeededForNextStage();
    }

    public int getEvolutionNeededForNextStage() {
        if (monsterStage <= 1) return plugin.getConfig().getInt("monster.evolution-needed.stage-2", 100);
        if (monsterStage == 2) return plugin.getConfig().getInt("monster.evolution-needed.stage-3", 250);
        return Integer.MAX_VALUE;
    }

    public boolean startEvolution(Player monster) {
        if (monster == null || !monster.getUniqueId().equals(monsterId) || getRole(monster.getUniqueId()) != Role.MONSTER) {
            return false;
        }
        if (monsterEvolving) {
            monster.sendActionBar(ChatColor.LIGHT_PURPLE + "すでに進化中です。キャンセルできません。");
            return false;
        }
        if (isMonsterDead()) {
            monster.sendMessage(ChatColor.RED + "倒されているため進化できません。");
            return false;
        }
        if (monsterStage >= 3) {
            monster.sendMessage(ChatColor.YELLOW + "すでにStage 3です。");
            return false;
        }
        int needed = getEvolutionNeededForNextStage();
        if (evolution < needed) {
            monster.sendMessage(ChatColor.RED + "Evolutionが足りません。 " + evolution + " / " + needed);
            return false;
        }

        evolvingToStage = monsterStage + 1;
        monsterEvolving = true;
        int seconds = plugin.getConfig().getInt("monster.evolution-time-seconds.stage-" + evolvingToStage,
                evolvingToStage == 2 ? 8 : 12);
        seconds = Math.max(1, seconds);
        evolutionEndsAtTick = Bukkit.getCurrentTick() + seconds * 20L;
        evolutionAnchor = monster.getLocation().clone();
        monster.setVelocity(new org.bukkit.util.Vector(0, 0, 0));

        final int targetStage = evolvingToStage;
        final UUID expectedMonster = monster.getUniqueId();
        monster.sendTitle(ChatColor.DARK_PURPLE + "EVOLVING", ChatColor.GRAY + "進化はキャンセルできない", 5, 30, 10);
        Bukkit.broadcastMessage(ChatColor.GOLD + "[EVOLVE] " + ChatColor.DARK_RED + "Monsterが進化を開始した！");
        updateAllSidebars();

        evolutionTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            evolutionTaskId = -1;
            if (state != MatchState.RUNNING || !monsterEvolving || evolvingToStage != targetStage
                    || monsterId == null || !monsterId.equals(expectedMonster)) {
                clearEvolutionState();
                updateAllSidebars();
                return;
            }
            completeEvolution(targetStage);
        }, seconds * 20L);
        return true;
    }

    private void completeEvolution(int newStage) {
        int old = monsterStage;
        monsterStage = Math.max(1, Math.min(3, newStage));
        growHealthForStageChange(old, monsterStage);
        // Requested rule: every evolution sheds the current armor completely.
        monsterArmor = 0.0;
        clearEvolutionState();
        broadcastStageUp();
        updateAllSidebars();
    }

    private void growHealthForStageChange(int oldStage, int newStage) {
        double oldMaxHealth = maxHealthForStage(oldStage);
        double newMaxHealth = maxHealthForStage(newStage);
        if (newStage > oldStage) {
            // Lost permanent health remains meaningful. Only the newly gained max-health
            // portion is granted as fresh health when evolving.
            monsterHealth = Math.min(newMaxHealth, monsterHealth + Math.max(0.0, newMaxHealth - oldMaxHealth));
        } else {
            monsterHealth = Math.min(monsterHealth, newMaxHealth);
            monsterArmor = Math.min(monsterArmor, maxArmorForStage(newStage));
        }
    }

    private void cancelEvolutionTask() {
        if (evolutionTaskId != -1) {
            Bukkit.getScheduler().cancelTask(evolutionTaskId);
            evolutionTaskId = -1;
        }
    }

    private void clearEvolutionState() {
        monsterEvolving = false;
        evolvingToStage = 0;
        evolutionEndsAtTick = 0L;
        evolutionAnchor = null;
    }

    /**
     * Locks the Monster to the point where evolution started while still allowing
     * free camera rotation. Used by PlayerMoveEvent.
     */
    public Location getEvolutionAnchorFor(Player player) {
        if (!monsterEvolving || evolutionAnchor == null || player == null || monsterId == null) return null;
        if (!player.getUniqueId().equals(monsterId) || getRole(player.getUniqueId()) != Role.MONSTER) return null;
        return evolutionAnchor.clone();
    }

    private void broadcastStageUp() {
        forEachOnline(p -> p.sendTitle(
                ChatColor.DARK_PURPLE + "EVOLUTION",
                ChatColor.RED + "MONSTER STAGE " + monsterStage,
                10, 60, 20));
    }

    public DamageResult applyMonsterDamage(double incoming) {
        double damage = Math.max(0.0, incoming);
        double armorBefore = monsterArmor;
        double absorbed = Math.min(monsterArmor, damage);
        monsterArmor -= absorbed;
        damage -= absorbed;
        double healthDamage = Math.min(monsterHealth, damage);
        monsterHealth = Math.max(0.0, monsterHealth - damage);
        updateAllSidebars();
        return new DamageResult(armorBefore - monsterArmor, healthDamage, monsterArmor, monsterHealth);
    }

    public double restoreMonsterArmor(double amount) {
        double before = monsterArmor;
        monsterArmor = Math.min(getMonsterMaxArmor(), monsterArmor + Math.max(0.0, amount));
        updateAllSidebars();
        return monsterArmor - before;
    }

    public void resetMonsterVitals() {
        monsterHealth = getMonsterMaxHealth();
        monsterArmor = getMonsterMaxArmor();
    }

    private double maxHealthForStage(int stage) {
        return plugin.getConfig().getDouble("monster.vitals.stage-" + Math.max(1, Math.min(3, stage)) + ".health", switch (stage) {
            case 2 -> 140.0;
            case 3 -> 180.0;
            default -> 100.0;
        });
    }

    private double maxArmorForStage(int stage) {
        return plugin.getConfig().getDouble("monster.vitals.stage-" + Math.max(1, Math.min(3, stage)) + ".armor", switch (stage) {
            case 2 -> 80.0;
            case 3 -> 120.0;
            default -> 50.0;
        });
    }

    public double getMonsterMaxHealth() { return maxHealthForStage(monsterStage); }
    public double getMonsterMaxArmor() { return maxArmorForStage(monsterStage); }
    public double getMonsterHealth() { return monsterHealth; }
    public double getMonsterArmor() { return monsterArmor; }
    public boolean isMonsterDead() { return monsterHealth <= 0.0; }

    public MatchState getState() { return state; }
    public Set<UUID> getJoined() { return Collections.unmodifiableSet(joined); }
    public Role getRole(UUID id) { return roles.getOrDefault(id, Role.SPECTATOR); }
    public int getMonsterStage() { return monsterStage; }
    public int getEvolution() { return evolution; }
    public boolean isMonsterEvolving() { return monsterEvolving; }
    public int getEvolvingToStage() { return evolvingToStage; }
    public int getEvolutionSecondsRemaining() {
        if (!monsterEvolving) return 0;
        long ticks = Math.max(0L, evolutionEndsAtTick - Bukkit.getCurrentTick());
        return (int) Math.ceil(ticks / 20.0);
    }
    public UUID getMonsterId() { return monsterId; }

    private void syncMonsterClientState(Player player) {
        if (player == null || !player.getUniqueId().equals(monsterId) || getRole(player.getUniqueId()) != Role.MONSTER) return;
        double ratio = getMonsterMaxHealth() <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, monsterHealth / getMonsterMaxHealth()));
        double vanillaMax = Math.max(1.0, player.getMaxHealth());
        // Keep the vanilla player alive; the dedicated HUD interprets this as a ratio only.
        player.setHealth(Math.max(0.5, Math.min(vanillaMax, vanillaMax * ratio)));
        AttributeInstance maxAbsorption = player.getAttribute(Attribute.GENERIC_MAX_ABSORPTION);
        if (maxAbsorption != null) maxAbsorption.setBaseValue(Math.max(0.0, getMonsterMaxArmor()));
        player.setAbsorptionAmount(Math.max(0.0, monsterArmor));
        if (monsterStage < 3) {
            int needed = Math.max(1, getEvolutionNeededForNextStage());
            player.setExp((float)Math.max(0.0, Math.min(1.0, evolution / (double)needed)));
        } else {
            player.setExp(1.0f);
        }
        // Experience level is hidden by the client and transports exact virtual HP.
        player.setLevel(Math.max(0, (int)Math.ceil(monsterHealth)));
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
        double multiplier = plugin.getConfig().getDouble("monster.movement-speed.stage-" + monsterStage,
                monsterStage == 1 ? 1.20 : monsterStage == 2 ? 1.25 : 1.30);
        player.setWalkSpeed((float)Math.max(0.0, Math.min(1.0, 0.2 * multiplier)));
        if (monsterSkillController != null) monsterSkillController.refreshEvolveItem(player);
    }

    private void resetPlayerClientState(Player player) {
        if (player == null) return;
        player.setWalkSpeed(0.2f);
        player.setAbsorptionAmount(0.0);
        AttributeInstance maxAbsorption = player.getAttribute(Attribute.GENERIC_MAX_ABSORPTION);
        if (maxAbsorption != null) maxAbsorption.setBaseValue(0.0);
        player.setExp(0.0f);
        player.setLevel(0);
        player.setFoodLevel(20);
        player.setSaturation(20.0f);
    }

    public void updateSidebar(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        Scoreboard board = manager.getNewScoreboard();
        boolean sidebarEnabled = plugin.getConfig().getBoolean("ui.sidebar", false);
        Objective obj = board.registerNewObjective("evolve", "dummy", ChatColor.DARK_RED + "PROJECT EVOLVE");
        if (sidebarEnabled) obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        Role clientRole = getRole(player.getUniqueId());
        if (state == MatchState.RUNNING && clientRole == Role.MONSTER) {
            org.bukkit.scoreboard.Team clientTeam = board.registerNewTeam("evolve_monster_s" + monsterStage + (monsterEvolving ? "_e" : ""));
            clientTeam.addEntry(player.getName());
        } else if (state == MatchState.RUNNING && isHunterRole(clientRole)) {
            org.bukkit.scoreboard.Team clientTeam = board.registerNewTeam("evolve_hunter");
            clientTeam.addEntry(player.getName());
        }

        if (state == MatchState.LOBBY) {
            obj.getScore(ChatColor.GRAY + "────────────").setScore(6);
            obj.getScore(ChatColor.WHITE + "Status: " + ChatColor.YELLOW + "LOBBY").setScore(5);
            obj.getScore(ChatColor.WHITE + "Players: " + ChatColor.GREEN + joined.size()).setScore(4);
            obj.getScore(ChatColor.AQUA + "Waiting for start...").setScore(3);
            obj.getScore(ChatColor.GRAY + "/evolve start").setScore(2);
            obj.getScore(ChatColor.DARK_GRAY + "v0.5.2").setScore(1);
        } else {
            Role role = getRole(player.getUniqueId());
            if (role == Role.MONSTER) {
                obj.getScore(ChatColor.GRAY + "────────────").setScore(9);
                obj.getScore(ChatColor.WHITE + "Role: " + ChatColor.DARK_RED + role.name()).setScore(8);
                obj.getScore(ChatColor.WHITE + "Stage: " + ChatColor.RED + monsterStage).setScore(7);
                obj.getScore(ChatColor.RED + "HP: " + ChatColor.WHITE + (int)Math.ceil(monsterHealth) + "/" + (int)Math.ceil(getMonsterMaxHealth())).setScore(6);
                obj.getScore(ChatColor.AQUA + "外皮: " + ChatColor.WHITE + (int)Math.ceil(monsterArmor) + "/" + (int)Math.ceil(getMonsterMaxArmor())).setScore(5);
                String evoText;
                if (monsterEvolving) {
                    evoText = "進化中 " + getEvolutionSecondsRemaining() + "s";
                } else if (monsterStage >= 3) {
                    evoText = "MAX";
                } else {
                    int needed = getEvolutionNeededForNextStage();
                    evoText = evolution + " / " + needed;
                    if (evolution >= needed) evoText += " READY";
                }
                obj.getScore(ChatColor.WHITE + "Evolution: " + ChatColor.LIGHT_PURPLE + evoText).setScore(4);
                obj.getScore(ChatColor.WHITE + "Players: " + ChatColor.GREEN + joined.size()).setScore(3);
                obj.getScore(ChatColor.WHITE + "State: " + ChatColor.YELLOW + state.name()).setScore(2);
                obj.getScore(ChatColor.DARK_GRAY + "v0.5.2").setScore(1);
            } else {
                obj.getScore(ChatColor.GRAY + "────────────").setScore(6);
                obj.getScore(ChatColor.WHITE + "Role: " + ChatColor.AQUA + role.name()).setScore(5);
                obj.getScore(ChatColor.WHITE + "Stage: " + ChatColor.RED + monsterStage).setScore(4);
                obj.getScore(ChatColor.WHITE + "Players: " + ChatColor.GREEN + joined.size()).setScore(3);
                obj.getScore(ChatColor.WHITE + "State: " + ChatColor.YELLOW + state.name()).setScore(2);
                obj.getScore(ChatColor.DARK_GRAY + "v0.5.2").setScore(1);
            }
        }
        player.setScoreboard(board);
        if (clientRole == Role.MONSTER) syncMonsterClientState(player);
    }

    private boolean isHunterRole(Role role) {
        return role == Role.ASSAULT || role == Role.TRACKER || role == Role.MEDIC || role == Role.SUPPORT;
    }

    public void updateAllSidebars() { forEachOnline(this::updateSidebar); }

    private void forEachOnline(java.util.function.Consumer<Player> consumer) {
        for (UUID id : joined) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) consumer.accept(p);
        }
    }

    private void startSidebarTask() {
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::updateAllSidebars, 20L, 40L);
    }

    public void shutdown() {
        cancelEvolutionTask();
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }
}
