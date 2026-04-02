package me.chengzhify.blockedInCombat.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException

class ConfigManager(private val plugin: JavaPlugin) {
    private val configs: MutableMap<String, FileConfiguration> = HashMap()
    private val configFiles: MutableMap<String, File> = HashMap()

    fun loadAllConfigurations() {
        loadConfig("map.yml")
        loadConfig("teams.yml")
        loadConfig("recipes.yml")
        loadConfig("items.yml")
        loadConfig("messages.yml")
        loadConfig("settings.yml")
    }

    private fun loadConfig(fileName: String) {
        val dataFolder = plugin.dataFolder;
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.logger.warning("无法创建插件配置文件。")
        }

        val file = File(dataFolder, fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false)
        }

        configFiles[fileName] = file;
        configs[fileName] = YamlConfiguration.loadConfiguration(file);
    }

    fun save(fileName: String) {
        val configuration = configs[fileName]
        val file = configFiles[fileName]
        if (configuration == null || file == null) {
            plugin.logger.warning("无法创建文件: $fileName")
            return
        }

        try {
            configuration.save(file)
        } catch (ex: IOException) {
            plugin.logger.warning("保存文件失败: $fileName: ${ex.message}")
        }
    }

    fun reloadAll() {
        configs.clear()
        configFiles.clear()
        loadAllConfigurations()
    }

    fun getConfig(fileName: String): FileConfiguration? = configs[fileName]

    fun map(): FileConfiguration = getConfig("map.yml")!!

    fun teams(): FileConfiguration = getConfig("teams.yml")!!

    fun recipes(): FileConfiguration = getConfig("recipes.yml")!!

    fun items(): FileConfiguration = getConfig("items.yml")!!

    fun messages(): FileConfiguration = getConfig("messages.yml")!!

    fun settings(): FileConfiguration = getConfig("settings.yml")!!

}