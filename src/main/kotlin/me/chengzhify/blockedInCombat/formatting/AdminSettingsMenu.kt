package me.chengzhify.blockedInCombat.formatting

import me.chengzhify.blockedInCombat.config.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class AdminSettingsMenu(private val configManager: ConfigManager) : Listener {
    companion object {
        private val TITLE: String = ChatColor.GOLD.toString() + "✦ " + ChatColor.YELLOW + "BIC 管理面板"
    }

    fun open(player: Player) {
        val inventory = Bukkit.createInventory(null, 45, TITLE)
        fillBackground(inventory)

        val settings = configManager.settings()
        val friendlyFire = settings.getBoolean("settings.friendly-fire", false)
        val pvpDelay = settings.getInt("settings.pvp-delay-seconds", 60)
        val respawnTickets = settings.getInt("settings.respawn.max-respawns-per-player", 1)
        val respawnDelay = settings.getInt("settings.respawn.delay-seconds", 3)
        val gameDuration = settings.getInt("settings.game-duration-seconds", 900)
        val minPlayers = settings.getInt("settings.min-players", 2)

        inventory.setItem(10, button(Material.DIAMOND_SWORD, "&e队友伤害", listOf(
            "&7当前: " + if (friendlyFire) "&a开启" else "&c关闭",
            "&8点击切换开关"
        )))
        inventory.setItem(12, button(Material.BLAZE_POWDER, "&6PVP 开启延迟", listOf(
            "&7当前: &b${pvpDelay}秒",
            "&8点击循环: 30/60/90/120"
        )))
        inventory.setItem(16, button(Material.TOTEM_OF_UNDYING, "&d重生次数", listOf(
            "&7当前: &b$respawnTickets",
            "&8点击循环: 0/1/2/3"
        )))

        inventory.setItem(28, button(Material.CLOCK, "&b重生延迟", listOf(
            "&7当前: &b${respawnDelay}秒",
            "&8点击循环: 2/3/5/8"
        )))
        inventory.setItem(30, button(Material.NETHER_STAR, "&6对局时长", listOf(
            "&7当前: &b${gameDuration}秒",
            "&8点击循环: 600/900/1200"
        )))
        inventory.setItem(32, button(Material.PLAYER_HEAD, "&e最小开局人数", listOf(
            "&7当前: &b$minPlayers",
            "&8点击循环: 2/4/6/8"
        )))
        inventory.setItem(34, button(Material.EMERALD_BLOCK, "&a保存设置", listOf(
            "&7将当前规则写入 settings.yml",
            "&8点击立即保存"
        )))

        player.openInventory(inventory)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        if (event.view.title != TITLE) {
            return
        }

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return

        if (!player.hasPermission("bic.admin")) {
            player.sendMessage(color("&c你没有权限操作管理面板。"))
            return
        }

        val settings: FileConfiguration = configManager.settings()
        when (event.rawSlot) {
            10 -> {
                val enabled = !settings.getBoolean("settings.friendly-fire", false)
                settings.set("settings.friendly-fire", enabled)
                player.sendMessage(color("&7队友伤害: " + if (enabled) "&a开启" else "&c关闭"))
                open(player)
            }
            12 -> {
                val value = cycle(settings.getInt("settings.pvp-delay-seconds", 60), 30, 60, 90, 120)
                settings.set("settings.pvp-delay-seconds", value)
                player.sendMessage(color("&7PVP 延迟已设置为 &b${value}秒"))
                open(player)
            }
            16 -> {
                val value = cycle(settings.getInt("settings.respawn.max-respawns-per-player", 1), 0, 1, 2, 3)
                settings.set("settings.respawn.max-respawns-per-player", value)
                player.sendMessage(color("&7重生次数已设置为 &b$value"))
                open(player)
            }
            28 -> {
                val value = cycle(settings.getInt("settings.respawn.delay-seconds", 3), 2, 3, 5, 8)
                settings.set("settings.respawn.delay-seconds", value)
                player.sendMessage(color("&7重生延迟已设置为 &b${value}秒"))
                open(player)
            }
            30 -> {
                val value = cycle(settings.getInt("settings.game-duration-seconds", 900), 600, 900, 1200)
                settings.set("settings.game-duration-seconds", value)
                player.sendMessage(color("&7对局时长已设置为 &b${value}秒"))
                open(player)
            }
            32 -> {
                val value = cycle(settings.getInt("settings.min-players", 2), 2, 4, 6, 8)
                settings.set("settings.min-players", value)
                player.sendMessage(color("&7最小开局人数已设置为 &b$value"))
                open(player)
            }
            34 -> {
                configManager.save("settings.yml")
                player.sendMessage(color("&a设置已保存。"))
                open(player)
            }
        }
    }

    private fun fillBackground(inventory: Inventory) {
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        val meta = filler.itemMeta
        if (meta != null) {
            meta.setDisplayName(" ")
            filler.itemMeta = meta
        }

        for (i in 0 until inventory.size) {
            inventory.setItem(i, filler)
        }
    }

    private fun button(material: Material, name: String, loreLines: List<String>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        if (meta != null) {
            meta.setDisplayName(color(name))
            meta.lore = loreLines.map { color(it) }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            item.itemMeta = meta
        }
        return item
    }

    private fun cycle(current: Int, vararg options: Int): Int {
        for (i in options.indices) {
            if (options[i] == current) {
                return options[(i + 1) % options.size]
            }
        }
        return options[0]
    }

    private fun color(msg: String): String {
        return ChatColor.translateAlternateColorCodes('&', msg)
    }
}
