package me.chengzhify.blockedInCombat.formatting

import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.game.GameState
import me.chengzhify.blockedInCombat.team.GameTeam
import me.chengzhify.blockedInCombat.team.TeamManager
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.DisplaySlot
import sun.awt.windows.awtLocalization
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ScoreboardService(
    private val plugin: JavaPlugin,
    private val gameManager: GameManager,
    private val teamManager: TeamManager
) {
    companion object {
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")
    }

    private var task: BukkitTask? = null

    fun start() {
        if (task != null) {
            return
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { updateAll() }, 0L, 20L)
    }

    fun stop() {
        task?.cancel()
        task = null

        val main = Bukkit.getScoreboardManager()?.mainScoreboard ?: return
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = main
        }
    }

    fun refreshNow() {
        updateAll()
    }

    private fun updateAll() {
        if (gameManager.state == GameState.ENDING) {
            return
        }
        for (player in Bukkit.getOnlinePlayers()) {
            applyBoard(player)
        }
    }

    private fun applyBoard(player: Player) {
        val manager = Bukkit.getScoreboardManager() ?: return

        val board = manager.newScoreboard
        val objective = board.registerNewObjective("bic", "dummy", ChatColor.GOLD.toString() + "" + ChatColor.YELLOW + "惊天矿工团")
        objective.displaySlot = DisplaySlot.SIDEBAR

        var score = 14
        objective.getScore(ChatColor.GRAY.toString() + LocalDate.now().format(DATE_FORMATTER) + ChatColor.DARK_GRAY + " TJUUS").score = score--
        objective.getScore(ChatColor.DARK_GRAY.toString() + " ").score = score--

        if (gameManager.state == GameState.LOBBY || gameManager.state == GameState.STARTING) {
            val waiting = Bukkit.getOnlinePlayers().size
            val min = gameManager.getMinPlayers()
            objective.getScore(ChatColor.GRAY.toString() + "等待人数: " + ChatColor.GREEN + waiting + ChatColor.GRAY + "/" + ChatColor.AQUA + min).score = score--

            if (gameManager.state == GameState.STARTING) {
                val left = maxOf(gameManager.lobbyCountdownLeft, 0)
                objective.getScore(ChatColor.GRAY.toString() + "开局倒计时: " + ChatColor.YELLOW + left + "秒").score = score--
            } else {
                objective.getScore(ChatColor.GRAY.toString() + "状态: " + ChatColor.YELLOW + "等待玩家加入").score = score--
            }
            val current = teamManager.getTeam(player)
            val name = current?.coloredName ?: (ChatColor.GRAY.toString() + "未选择")
            objective.getScore(ChatColor.GRAY.toString() + "我的队伍: " + name).score = score--
            objective.getScore(ChatColor.DARK_GRAY.toString() + "  ").score = score--
            objective.getScore(ChatColor.YELLOW.toString() + "tju.edu.cn").score = score
            player.scoreboard = board
            return
        }

        objective.getScore(ChatColor.GRAY.toString() + "剩余时间: " + ChatColor.YELLOW + gameManager.timeLeftSeconds + "秒").score = score--
        objective.getScore(ChatColor.DARK_GRAY.toString() + "   ").score = score--

        for (team in GameTeam.entries) {
            val current = teamManager.getTeam(player)
            val locator = if (current == team) ChatColor.GRAY.toString() + "   我" else ""
            val alive = teamManager.getAliveCount(team, gameManager.eliminatedPlayers)
            val status = if (alive > 0) ChatColor.GREEN.toString() + alive else ChatColor.RED.toString() + "❌"
            objective.getScore(team.color.toString() + team.coloredName + ChatColor.GRAY + ": " + status + locator).score = score--
        }
        objective.getScore(ChatColor.DARK_GRAY.toString() + "  ").score = score--
        objective.getScore(ChatColor.YELLOW.toString() + "tju.edu.cn").score = score

        player.scoreboard = board
    }
}
