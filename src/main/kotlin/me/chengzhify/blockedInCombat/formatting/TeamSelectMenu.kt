package me.chengzhify.blockedInCombat.formatting

import me.chengzhify.blockedInCombat.config.ConfigManager
import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.game.GameState
import me.chengzhify.blockedInCombat.team.GameTeam
import me.chengzhify.blockedInCombat.team.TeamManager
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

class TeamSelectMenu(
    private val gameManager: GameManager,
    private val teamManager: TeamManager,
    private val configManager: ConfigManager
) : Listener {
    companion object {
        private val TITLE: String = ChatColor.GOLD.toString() + "✦ " + ChatColor.YELLOW + "队伍选择"
        private val PREFIX: String = ChatColor.GOLD.toString() + "✦ " + ChatColor.YELLOW + "BIC" + ChatColor.DARK_GRAY + " » " + ChatColor.RESET
    }

    fun open(player: Player) {
        if (gameManager.state == GameState.PLAYING) {
            player.sendMessage(PREFIX + ChatColor.RED + "对局已开始，当前无法切换队伍。")
            return
        }

        val inventory = Bukkit.createInventory(null, 27, TITLE)
        setTeamItem(inventory, 10, GameTeam.RED, Material.RED_TERRACOTTA, player)
        setTeamItem(inventory, 12, GameTeam.BLUE, Material.BLUE_TERRACOTTA, player)
        setTeamItem(inventory, 14, GameTeam.GREEN, Material.GREEN_TERRACOTTA, player)
        setTeamItem(inventory, 16, GameTeam.YELLOW, Material.YELLOW_TERRACOTTA, player)
        player.openInventory(inventory)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        giveSelector(event.player)
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (gameManager.state == GameState.PLAYING) {
            return
        }

        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return
        }

        val item = event.item
        if (item == null || item.type != Material.COMPASS) {
            return
        }

        event.isCancelled = true
        open(event.player)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        if (event.view.title != TITLE) {
            return
        }

        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return

        if (gameManager.state == GameState.PLAYING) {
            player.closeInventory()
            return
        }

        val team = when (event.rawSlot) {
            10 -> GameTeam.RED
            12 -> GameTeam.BLUE
            14 -> GameTeam.GREEN
            16 -> GameTeam.YELLOW
            else -> null
        } ?: return

        if (teamManager.getTeam(player) == team) {
            player.sendMessage(PREFIX + ChatColor.YELLOW + "你已经在该队伍中了。")
            return
        }

        val teamLimit = resolveTeamLimit()
        val targetCount = teamManager.getSelectedCount(team)
        if (targetCount >= teamLimit) {
            player.sendMessage(PREFIX + ChatColor.RED + "该队伍已满员，请选择其他队伍。")
            return
        }

        if (!passesBalanceRule(player, team)) {
            player.sendMessage(PREFIX + ChatColor.RED + "自动平衡已阻止本次切队。")
            return
        }

        teamManager.chooseTeam(player, team)
        player.sendMessage(PREFIX + ChatColor.GREEN + "加入成功：" + team.coloredName)
        player.closeInventory()
    }

    fun giveSelector(player: Player) {
        if (gameManager.state == GameState.PLAYING) {
            return
        }

        val selector = ItemStack(Material.COMPASS)
        val meta = selector.itemMeta
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD.toString() + "队伍选择器 " + ChatColor.GRAY + "(右键打开)")
            meta.lore = listOf(
                ChatColor.GRAY.toString() + "请在开局前选择你的队伍",
                ChatColor.YELLOW.toString() + "红队 / 蓝队 / 绿队 / 黄队"
            )
            selector.itemMeta = meta
        }

        player.inventory.setItem(4, selector)
    }

    private fun setTeamItem(inventory: Inventory, slot: Int, team: GameTeam, icon: Material, viewer: Player) {
        val item = ItemStack(icon)
        val meta = item.itemMeta
        if (meta != null) {
            val selected = teamManager.getSelectedCount(team)
            val max = resolveTeamLimit()
            val full = selected >= max

            meta.setDisplayName(team.coloredName + if (full) ChatColor.RED.toString() + " [满员]" else ChatColor.GREEN.toString() + " [可加入]")
            val lore = ArrayList<String>()
            lore.add(ChatColor.GRAY.toString() + "当前人数：" + ChatColor.AQUA + selected + ChatColor.GRAY + "/" + ChatColor.AQUA + max)
            lore.add(ChatColor.GRAY.toString() + "队伍状态：" + if (full) ChatColor.RED.toString() + "满员" else ChatColor.GREEN.toString() + "可加入")
            lore.add(ChatColor.DARK_GRAY.toString() + "已启用自动平衡")
            if (teamManager.getTeam(viewer) == team) {
                lore.add(ChatColor.GOLD.toString() + "你当前就在该队伍")
            } else {
                lore.add(ChatColor.YELLOW.toString() + "点击加入该队伍")
            }
            meta.lore = lore
            item.itemMeta = meta
        }
        inventory.setItem(slot, item)
    }

    private fun resolveTeamLimit(): Int {
        val configured = configManager.settings().getInt("settings.team-max-size", 0)
        if (configured > 0) {
            return configured
        }

        val online = maxOf(1, Bukkit.getOnlinePlayers().size)
        return maxOf(1, kotlin.math.ceil(online / 4.0).toInt())
    }

    private fun passesBalanceRule(player: Player, target: GameTeam): Boolean {
        val maxGap = maxOf(0, configManager.settings().getInt("settings.team-balance-max-gap", 1))
        val counts = teamManager.getSelectedCounts().toMutableMap()

        val current = teamManager.getTeam(player)
        if (current != null) {
            counts.computeIfPresent(current) { _, v -> maxOf(0, v - 1) }
        }
        counts.computeIfPresent(target) { _, v -> v + 1 }

        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (value in counts.values) {
            min = minOf(min, value)
            max = maxOf(max, value)
        }

        return max - min <= maxGap
    }
}