package jp.evolvegame.core;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class ProjectEvolvePlugin extends JavaPlugin {
    private MatchManager matchManager;
    private TestMonsterController testMonsterController;
    private MonsterGameplayController monsterGameplayController;
    private MonsterSkillController monsterSkillController;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.matchManager = new MatchManager(this);
        this.testMonsterController = new TestMonsterController(this, matchManager);
        this.monsterGameplayController = new MonsterGameplayController(this, matchManager, testMonsterController);
        this.monsterSkillController = new MonsterSkillController(this, matchManager);
        this.matchManager.setMonsterSkillController(monsterSkillController);

        EvolveCommand command = new EvolveCommand(this, matchManager, testMonsterController, monsterGameplayController);
        PluginCommand evolve = getCommand("evolve");
        if (evolve != null) {
            evolve.setExecutor(command);
            evolve.setTabCompleter(command);
        }

        Bukkit.getPluginManager().registerEvents(new PlayerListener(this, matchManager), this);
        Bukkit.getPluginManager().registerEvents(monsterGameplayController, this);
        Bukkit.getPluginManager().registerEvents(monsterSkillController, this);
        getLogger().info("Project EVOLVE v0.5.2 enabled (EVOLVE Client renderer integration).");
    }

    @Override
    public void onDisable() {
        if (monsterGameplayController != null) monsterGameplayController.clearAll();
        if (testMonsterController != null) testMonsterController.shutdown();
        if (matchManager != null) matchManager.shutdown();
    }
}
