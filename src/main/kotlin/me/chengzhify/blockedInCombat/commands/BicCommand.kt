package me.chengzhify.blockedInCombat.commands

import me.chengzhify.blockedInCombat.config.ConfigManager
import me.chengzhify.blockedInCombat.formatting.AdminSettingsMenu
import me.chengzhify.blockedInCombat.formatting.TeamSelectMenu
import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.game.GameState
import me.chengzhify.blockedInCombat.team.GameTeam
import me.chengzhify.blockedInCombat.team.TeamManager
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.Locale

class BicCommand(
    private val gameManager: GameManager,
    private val teamManager: TeamManager,
    private val adminSettingsMenu: AdminSettingsMenu,
    private val teamSelectMenu: TeamSelectMenu,
    private val configManager: ConfigManager
) : CommandExecutor, TabCompleter {
    private val prefix = ChatColor.GOLD.toString() + "✦ " + ChatColor.YELLOW + "BIC" + ChatColor.DARK_GRAY + " » " + ChatColor.RESET

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(prefix + ChatColor.GOLD + "命令列表：")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic start")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic forcestart")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic stop")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic team <red|blue|green|yellow> &7(选择队伍)")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic status")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic menu")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic setlobby")
            sender.sendMessage(ChatColor.GRAY.toString() + " - " + ChatColor.GOLD + "/bic reload")
            return true
        }

        when (args[0].lowercase(Locale.ROOT)) {
            "start" -> {
                if (!sender.hasPermission("bic.admin")) {
                    sender.sendMessage(prefix + ChatColor.RED + "你没有权限执行此命令。")
                    return true
                }
                gameManager.startGame(sender)
                return true
            }
            "stop" -> {
                if (!sender.hasPermission("bic.admin")) {
                    sender.sendMessage(prefix + ChatColor.RED + "你没有权限执行此命令。")
                    return true
                }
                gameManager.stopGame("&c管理员已强制结束对局。")
                return true
            }
            "forcestart" -> {
                if (!sender.hasPermission("bic.admin")) {
                    sender.sendMessage(prefix + ChatColor.RED + "你没有权限执行此命令。")
                    return true
                }
                gameManager.startGame(sender, true)
                return true
            }
            "team" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage(prefix + ChatColor.RED + "只有玩家可以选择队伍。")
                    return true
                }
                if (gameManager.state == GameState.PLAYING) {
                    sender.sendMessage(prefix + ChatColor.RED + "对局进行中，无法切换队伍。")
                    return true
                }
                if (args.size < 2) {
                    teamSelectMenu.open(player)
                    return true
                }

                val team = GameTeam.fromInput(args[1])
                if (team == null) {
                    sender.sendMessage(prefix + ChatColor.RED + "未知队伍，请输入 red/blue/green/yellow。")
                    return true
                }

                teamManager.chooseTeam(player, team)
                sender.sendMessage(prefix + ChatColor.AQUA + "你已加入队伍：" + team.coloredName)
                return true
            }
            "status" -> {
                sender.sendMessage(prefix + ChatColor.GOLD + "状态：" + ChatColor.YELLOW + gameManager.state.name)
                sender.sendMessage(prefix + ChatColor.GOLD + "大厅倒计时：" + ChatColor.AQUA + gameManager.lobbyCountdownLeft + "秒")
                sender.sendMessage(prefix + ChatColor.GOLD + "剩余时间：" + ChatColor.AQUA + gameManager.timeLeftSeconds + "秒")
                sender.sendMessage(prefix + ChatColor.GOLD + "对局世界：" + ChatColor.YELLOW + (gameManager.currentGameWorld?.name ?: "无"))
                return true
            }
            "menu" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage(prefix + ChatColor.RED + "只有玩家可以打开管理菜单。")
                    return true
                }
                if (!sender.hasPermission("bic.admin")) {
                    sender.sendMessage(prefix + ChatColor.RED + "你没有权限执行此命令。")
                    return true
                }
                adminSettingsMenu.open(player)
                return true
            }
            "setlobby" -> {
                val player = sender as? Player
                if (player == null) {
                    sender.sendMessage(prefix + ChatColor.RED + "只有玩家可以使用该命令。")
                    return true
                }
                if (!sender.hasPermission("bic.admin")) {
                    sender.sendMessage(prefix + ChatColor.RED + "你没有权限执行此命令。")
                    return true
                }
                gameManager.setLobbySpawn(player.location)
                sender.sendMessage(prefix + ChatColor.GREEN + "大厅出生点已更新并保存。")
                return true
            }
            "reload" -> {
                if (!sender.hasPermission("bic.admin")) {
                    sender.sendMessage(prefix + ChatColor.RED + "你没有权限执行此命令。")
                    return true
                }
                configManager.reloadAll()
                sender.sendMessage(prefix + ChatColor.GREEN + "配置文件已重载。")
                return true
            }
            else -> {
                sender.sendMessage(prefix + ChatColor.RED + "未知子命令，请使用 /bic 查看帮助。")
                return true
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val result = ArrayList<String>()
        if (args.size == 1) {
            result.addAll(listOf("start", "forcestart", "stop", "team", "status", "menu", "setlobby", "reload"))
            return filter(result, args[0])
        }

        if (args.size == 2 && args[0].equals("team", ignoreCase = true)) {
            result.addAll(listOf("red", "blue", "green", "yellow"))
            return filter(result, args[1])
        }

        return emptyList()
    }

    private fun filter(source: List<String>, current: String): List<String> {
        val lower = current.lowercase(Locale.ROOT)
        return source.filter { it.startsWith(lower) }
    }
}
