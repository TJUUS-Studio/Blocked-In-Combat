package me.chengzhify.blockedInCombat

import me.chengzhify.blockedInCombat.config.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

class BlockedInCombat : JavaPlugin() {
    private var configManager: ConfigManager? = null

    override fun onEnable() {
        logger.info("惊天矿工团 " + description.version + " 已启用!")
        val configManager = ConfigManager(this).also {
            it.loadAllConfigurations()
            configManager = it
            logger.info("配置文件加载完成!")
        }
    }

    override fun onDisable() {

    }
}
