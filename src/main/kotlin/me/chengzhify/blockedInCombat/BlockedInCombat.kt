package me.chengzhify.blockedInCombat

import me.chengzhify.blockedInCombat.commands.BicCommand
import me.chengzhify.blockedInCombat.config.ConfigManager
import me.chengzhify.blockedInCombat.formatting.AdminSettingsMenu
import me.chengzhify.blockedInCombat.formatting.SpectatorTeleportMenu
import me.chengzhify.blockedInCombat.formatting.TeamSelectMenu
import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.listener.CombatListener
import me.chengzhify.blockedInCombat.listener.PlayerGameListener
import me.chengzhify.blockedInCombat.map.MapGenerator
import me.chengzhify.blockedInCombat.team.TeamManager
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class BlockedInCombat : JavaPlugin() {
    private var configManager: ConfigManager? = null
    private var teamManager: TeamManager? = null
    private var gameManager: GameManager? = null


    override fun onEnable() {
        logger.info("惊天矿工团 " + description.version + " 已启用!")
        val configManager = ConfigManager(this).also {
            it.loadAllConfigurations()
            configManager = it
            logger.info("配置文件加载完成!")
        }
        val teamManager = TeamManager().also { this.teamManager = it }
        val mapGenerator = MapGenerator(configManager)
        val gameManager = GameManager(this, configManager, mapGenerator, teamManager).also {
            this.gameManager = it
        }
        val adminSettingsMenu = AdminSettingsMenu(configManager)
        val teamSelectMenu = TeamSelectMenu(gameManager, teamManager, configManager)
        val spectatorTeleportMenu = SpectatorTeleportMenu(gameManager, teamManager)
        gameManager.initializeUI()

        server.pluginManager.registerEvents(PlayerGameListener(gameManager), this)
        server.pluginManager.registerEvents(CombatListener(gameManager, teamManager, configManager), this)
        server.pluginManager.registerEvents(adminSettingsMenu, this)
        server.pluginManager.registerEvents(teamSelectMenu, this)
        server.pluginManager.registerEvents(spectatorTeleportMenu, this)

        val bicCommand = BicCommand(gameManager, teamManager, adminSettingsMenu, teamSelectMenu, configManager)
        val command = getCommand("bic")
        if (command != null) {
            command.setExecutor(bicCommand)
            command.tabCompleter = bicCommand
        } else {
            logger.warning("Command 'bic' not found in plugin.yml")
        }

        logger.info("BlockedInCombat enabled.")
    }

    override fun onDisable() {
        gameManager?.forceCleanupOnDisable()
        teamManager?.clearAllData()
        logger.info("BlockedInCombat disabled.")
    }
}
