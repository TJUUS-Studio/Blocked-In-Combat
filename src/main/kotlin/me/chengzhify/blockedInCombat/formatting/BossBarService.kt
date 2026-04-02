package me.chengzhify.blockedInCombat.formatting

import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.game.GameState
import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

class BossBarService(
    private val plugin: JavaPlugin,
    private val gameManager: GameManager
) {
    private var bossBar: BossBar? = null
    private var task: BukkitTask? = null

    fun start() {
        if (task != null) {
            return
        }

        if (bossBar == null) {
            bossBar = Bukkit.createBossBar("惊天矿工团", BarColor.WHITE, BarStyle.SOLID)
        }

        val bar = bossBar ?: return
        for (player in Bukkit.getOnlinePlayers()) {
            bar.addPlayer(player)
        }
        bar.isVisible = true

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { update() }, 0L, 20L)
    }

    fun stop() {
        task?.cancel()
        task = null

        bossBar?.let {
            it.removeAll()
            it.isVisible = false
        }
    }

    fun addPlayer(player: Player) {
        bossBar?.addPlayer(player)
    }

    private fun update() {
        val bar = bossBar ?: return

        if (gameManager.state == GameState.STARTING) {
            val left = maxOf(gameManager.lobbyCountdownLeft, 0)
            val total = maxOf(gameManager.lobbyCountdownSeconds, 1)
            bar.progress = (left.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            bar.color = BarColor.BLUE
            bar.setTitle("惊天矿工团 | 距离开局 $left 秒")
            return
        }

        if (gameManager.state != GameState.PLAYING) {
            return
        }

        val total = maxOf(gameManager.gameDurationSeconds, 1)
        val left = maxOf(gameManager.timeLeftSeconds, 0)
        val progress = (left.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)

        bar.progress = progress
        bar.setTitle("惊天矿工团 | 剩余时间 $left 秒")

        bar.color = when {
            left <= 60 -> BarColor.RED
            left <= 180 -> BarColor.YELLOW
            else -> BarColor.GREEN
        }
    }
}
