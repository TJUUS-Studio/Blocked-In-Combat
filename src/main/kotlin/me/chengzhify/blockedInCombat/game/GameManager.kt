package me.chengzhify.blockedInCombat.game

import me.chengzhify.blockedInCombat.config.ConfigManager
import me.chengzhify.blockedInCombat.formatting.BossBarService
import me.chengzhify.blockedInCombat.formatting.ScoreboardService
import me.chengzhify.blockedInCombat.map.MapGenerator
import me.chengzhify.blockedInCombat.map.VoidChunkGenerator
import me.chengzhify.blockedInCombat.team.GameTeam
import me.chengzhify.blockedInCombat.team.TeamManager
import me.chengzhify.blockedInCombat.util.TextUtil
import org.bukkit.Bukkit
import org.bukkit.Bukkit.broadcast
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
import java.util.logging.Level
import java.util.stream.Stream
import kotlin.collections.set
import kotlin.compareTo
import kotlin.text.contains
import kotlin.text.toFloat
import kotlin.use

class GameManager(
    private val plugin: JavaPlugin,
    private val configManager: ConfigManager,
    val mapGenerator: MapGenerator,
    val teamManager: TeamManager,
)
{
    private val eliminatedPlayersMutable: MutableSet<UUID> = HashSet()
    private val pendingRespawns: MutableSet<UUID> = HashSet()
    private val respawnTickets: MutableMap<UUID, Int> = HashMap()
    private val killCount: MutableMap<UUID, Int> = HashMap()
    private val survivalStartMs: MutableMap<UUID, Long> = HashMap()
    private val eliminatedAtMs: MutableMap<UUID, Long> = HashMap()
    private val resourceRegenTasks: MutableMap<String, BukkitTask> = HashMap()

    var state: GameState = GameState.LOBBY
        private set

    private lateinit var bossBarService: BossBarService
    private lateinit var scoreboardService: ScoreboardService

    private var gameTimerTask: BukkitTask? = null
    private var pvpEnableTask: BukkitTask? = null
    private var lobbyCountdownTask: BukkitTask? = null
    private var settlementTask: BukkitTask? = null
    private var startTitleCountdownTask: BukkitTask? = null

    var lobbyCountdownSeconds: Int = 0
        private set
    var lobbyCountdownLeft: Int = 0
        private set
    var gameDurationSeconds: Int = 0
        private set
    var timeLeftSeconds: Int = 0
        private set

    private var currentGameWorldName: String? = null

    val eliminatedPlayers: Set<UUID>
        get() = eliminatedPlayersMutable

    val currentGameWorld: World?
        get() = currentGameWorldName?.let { Bukkit.getWorld(it) }

    fun initializeUI() {
        //TODO: 计分板和 Boss Bar
    }

    /*
    * 大厅倒计时处理流程。
    *
    *
    * */
    fun startGame(executor: CommandSender) {
        startGame(executor, false)
    }

    fun startGame(executor: CommandSender, force: Boolean) {
        if (state == GameState.PLAYING) {
            executor.sendMessage(TextUtil.transC("&c游戏正在进行中!"))
        }
        if (state == GameState.STARTING) {
            if (force) {
                cancelTask(lobbyCountdownTask)
                lobbyCountdownTask = null
                launchGame()
                broadcast(TextUtil.transC("&7游戏已强制开启!"))
            }
            executor.sendMessage(TextUtil.transC("&c倒计时正在进行! 如果需要强制开启, 请使用 /bic forcestart"))
        }

        val minPlayers = configManager.settings().getInt("settings.min-players", 2)

        if (!force && Bukkit.getOnlinePlayers().size <= minPlayers) {
            executor.sendMessage(TextUtil.transC("&c当前在线玩家不足 $minPlayers 人, 无法开始游戏!"))
        }

        if (force) {
            launchGame()
            broadcast(TextUtil.transC("&7游戏已强制开启!"))
        }

        startLobbyCountdown()

        executor.sendMessage(TextUtil.transC("&c倒计时已开启。"))
        if (executor is Player) {
            broadcast(TextUtil.transC("&c${executor.player?.name} &7开启了游戏倒计时!"))
        } else {
            broadcast(TextUtil.transC("&7管理员开启了游戏倒计时!"))
        }
    }

    private fun startLobbyCountdown() {
        cancelTask(lobbyCountdownTask)
        lobbyCountdownTask = null

        val settings = configManager.settings()
        val minPlayers = configManager.settings().getInt("settings.min-players", 2)
        lobbyCountdownLeft = settings.getInt("settings.lobby-countdown-seconds", 30)
        lobbyCountdownSeconds = lobbyCountdownLeft

        state = GameState.STARTING
        // TODO: 计分板和 BossBar 状态修改。

        broadcast("&e游戏将在 &a$lobbyCountdownSeconds &e秒后开始!")
        lobbyCountdownTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (state != GameState.STARTING) {
                return@Runnable
            }

            if (Bukkit.getOnlinePlayers().size < minPlayers) {
                stopCountdown()
                return@Runnable
            }

            if (lobbyCountdownLeft <= 0) {
                cancelTask(lobbyCountdownTask)
                lobbyCountdownTask = null
                launchGame()
                return@Runnable
            }

            if (lobbyCountdownLeft <= 5 || lobbyCountdownLeft % 10 == 0) {
                broadcast("&e游戏将在 &a$lobbyCountdownLeft &e秒后开始!")
            }

            lobbyCountdownLeft--
        }, 0L, 20L)
    }

    private fun stopCountdown() {
        cancelTask(lobbyCountdownTask)
        lobbyCountdownTask = null
        state = GameState.LOBBY
        // TODO: 计分板和 BossBar 状态修改。
        broadcast("&c倒计时已取消!")
    }

    private fun launchGame() {
        val settings = configManager.settings()
        val world = createWorld(settings)

        if (world == null) {
            stopCountdown()
            plugin.logger.log(Level.INFO, "创建世界失败, 停止倒计时。")
            return
        }
        // 清除之前的缓存数据
        state = GameState.PLAYING
        eliminatedPlayersMutable.clear()
        pendingRespawns.clear()
        respawnTickets.clear()
        killCount.clear()
        survivalStartMs.clear()
        eliminatedAtMs.clear()
        cancelTask(startTitleCountdownTask)
        startTitleCountdownTask = null

        // 给在线玩家分配队伍
        val players = Bukkit.getOnlinePlayers();
        teamManager.prepareTeams(players)

        // 地图生成
        mapGenerator.generateArena(world)

        val mapWidth = mapGenerator.arenaWidth
        val mapLength = mapGenerator.arenaLength
        val mapHeight = mapGenerator.arenaHeight
        val y = configManager.map().getInt("map.y", 64)
        val baseSize = configManager.map().getInt("map.team-base-size", 6)
        val maxRespawns = settings.getInt("settings.respawn.max-respawns-per-player", 1)

        for (player in players) {
            resetPlayer(player)
            val team = teamManager.getTeam(player)
            if (team != null) {
                player.teleport(teamManager.getTeamSpawn(world, team, mapWidth, mapLength, mapHeight, y, baseSize))
            }
            respawnTickets[player.uniqueId] = maxOf(maxRespawns, 0)
            killCount[player.uniqueId] = 0
            survivalStartMs[player.uniqueId] = System.currentTimeMillis()
        }

        world.pvp = false
        schedulePvpEnable(world, settings.getInt("settings.pvp-delay-seconds", 120))

        gameDurationSeconds = settings.getInt("settings.game-duration-seconds", 300)
        timeLeftSeconds = gameDurationSeconds
        scheduleGameTimer()
        runStartTitleCountdown(players)

        //TODO: 计分板和 BossBar 状态修改。



    }

    fun stopGame(reason: String) {
        if (state == GameState.LOBBY) {
            return
        }

        if (state == GameState.STARTING) {
            cancelTask(lobbyCountdownTask)
            lobbyCountdownTask = null
            bossBarService.stop()
            scoreboardService.start()
            teleportAllToLobby()
            state = GameState.LOBBY
            broadcast(reason)
            return
        }

        state = GameState.ENDING

        cancelTask(gameTimerTask)
        gameTimerTask = null
        cancelTask(pvpEnableTask)
        pvpEnableTask = null
        cancelTask(lobbyCountdownTask)
        lobbyCountdownTask = null
        cancelTask(settlementTask)
        settlementTask = null
        cancelTask(startTitleCountdownTask)
        startTitleCountdownTask = null

        bossBarService.stop()
        scoreboardService.stop()

        for (player in Bukkit.getOnlinePlayers()) {
            player.gameMode = GameMode.SPECTATOR
        }

        broadcast(reason)

        val delay = maxOf(1, configManager.settings().getInt("settings.settlement-return-seconds", 8))
        broadcast("&e结算中，将在 {seconds}秒 后返回大厅。")

        settlementTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable { finishMatchCleanup() }, delay * 20L)
    }

    fun setLobbySpawn(location: Location) {
        val settings = configManager.settings()
        settings.set("settings.lobby-world", location.world?.name ?: "world")
        settings.set("settings.lobby-spawn.x", location.x)
        settings.set("settings.lobby-spawn.y", location.y)
        settings.set("settings.lobby-spawn.z", location.z)
        settings.set("settings.lobby-spawn.yaw", location.yaw)
        settings.set("settings.lobby-spawn.pitch", location.pitch)
        configManager.save("settings.yml")
    }

    private fun resolveLobbySpawn(): Location? {
        val settings = configManager.settings()
        val lobby = resolveLobbyWorld(settings) ?: return null

        if (!settings.contains("settings.lobby-spawn.x")) {
            return lobby.spawnLocation
        }

        val spawn = lobby.spawnLocation
        val x = settings.getDouble("settings.lobby-spawn.x", spawn.x)
        val y = settings.getDouble("settings.lobby-spawn.y", spawn.y)
        val z = settings.getDouble("settings.lobby-spawn.z", spawn.z)
        val yaw = settings.getDouble("settings.lobby-spawn.yaw", spawn.yaw.toDouble()).toFloat()
        val pitch = settings.getDouble("settings.lobby-spawn.pitch", spawn.pitch.toDouble()).toFloat()
        return Location(lobby, x, y, z, yaw, pitch)
    }

    private fun resolveLobbyWorld(settings: FileConfiguration): World? {
        val worldName = settings.getString("settings.lobby-world", "world")
        val world = Bukkit.getWorld(worldName!!)
        if (world != null) {
            return world
        }
        return Bukkit.getWorlds().firstOrNull()
    }

    private fun teleportAllToLobby() {
        val lobbySpawn = resolveLobbySpawn() ?: return
        for (player in Bukkit.getOnlinePlayers()) {
            player.teleport(lobbySpawn)
        }
    }

    private fun finishMatchCleanup() {
        val gameWorld = currentGameWorld
        gameWorld?.pvp = true

        teleportAllToLobby()
        for (player in Bukkit.getOnlinePlayers()) {
            player.closeInventory()
            resetLobbyPlayer(player)
        }

        if (gameWorld != null) {
            deleteMatchWorld(gameWorld)
        }

        currentGameWorldName = null
        teamManager.clearGameData()
        eliminatedPlayersMutable.clear()
        pendingRespawns.clear()
        respawnTickets.clear()
        killCount.clear()
        survivalStartMs.clear()
        eliminatedAtMs.clear()
        state = GameState.LOBBY
        settlementTask = null
        scoreboardService.start()
    }

    private fun resetLobbyPlayer(player: Player) {
        clearSpectatorEffects(player)
        player.gameMode = GameMode.ADVENTURE
        player.health = player.maxHealth
        player.foodLevel = 20
        player.saturation = 20f
        player.exp = 0f
        player.level = 0
        player.inventory.clear()
        giveTeamSelectorItem(player)
    }

    private fun giveTeamSelectorItem(player: Player) {
        val selector = ItemStack(Material.COMPASS)
        val meta = selector.itemMeta
        if (meta != null) {
            meta.setDisplayName("&6队伍选择器 &7(右键打开)")
            meta.lore = listOf("&7选择 红/蓝/绿/黄 队伍", "&e请在开局前完成选择")
            selector.itemMeta = meta
        }
        player.inventory.setItem(4, selector)
    }


    private fun deleteMatchWorld(world: World) {
        val worldName = world.name
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.logger.warning("Could not unload match world $worldName")
            return
        }

        val worldPath = Path.of(Bukkit.getWorldContainer().absolutePath, worldName)
        if (!Files.exists(worldPath)) {
            return
        }

        try {
            Files.walk(worldPath).use { stream: Stream<Path> ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (ex: IOException) {
                        plugin.logger.warning("Failed to delete path $path: ${ex.message}")
                    }
                }
            }
        } catch (ex: IOException) {
            plugin.logger.warning("Failed to delete world folder: ${ex.message}")
        }
    }

    private fun runStartTitleCountdown(players: Collection<Player>) {
        cancelTask(startTitleCountdownTask)
        startTitleCountdownTask = null

        val left = intArrayOf(5)
        startTitleCountdownTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (state != GameState.PLAYING) {
                cancelTask(startTitleCountdownTask)
                startTitleCountdownTask = null
                return@Runnable
            }

            if (left[0] <= 0) {
                for (player in players) {
                    if (!player.isOnline) {
                        continue
                    }
                    player.sendTitle("&e&l开始游戏!", "&7祝你好运", 0, 20, 10)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                }
                broadcast("&a战斗开始!")
                cancelTask(startTitleCountdownTask)
                startTitleCountdownTask = null
                return@Runnable
            }

            for (player in players) {
                if (!player.isOnline) {
                    continue
                }
                player.sendTitle("&e&l${left[0]}", "&7地图已生成, 请做好准备", 0, 20, 0)
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 1.0f)
            }

            left[0]--
        }, 0L, 20L)
    }

    private fun decideWinnerByAliveCount() {
        var best: GameTeam? = null
        var bestCount = -1

        for (team in GameTeam.entries) {
            val alive = teamManager.getAliveCount(team, eliminatedPlayersMutable)
            if (alive > bestCount) {
                best = team
                bestCount = alive
            }
        }

        if (best == null || bestCount <= 0) {
            stopGame("&e本局平局: 没有队伍存活.")
            return
        }

        stopGame("")
    }

    private fun scheduleGameTimer() {
        cancelTask(gameTimerTask)

        gameTimerTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (state != GameState.PLAYING) {
                return@Runnable
            }

            timeLeftSeconds--
            if (timeLeftSeconds <= 0) {
                decideWinnerByAliveCount()
            }
        }, 20L, 20L)
    }

    private fun schedulePvpEnable(world: World, delaySeconds: Int) {
        cancelTask(pvpEnableTask)

        pvpEnableTask = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (state != GameState.PLAYING) {
                return@Runnable
            }
            world.pvp = true
            broadcast( "&cPVP 已开启!")
        }, delaySeconds * 20L)
    }

    private fun resetPlayer(player: Player) {
        clearSpectatorEffects(player)
        player.gameMode = GameMode.SURVIVAL
        player.health = player.maxHealth
        player.foodLevel = 20
        player.saturation = 20f
        player.exp = 0f
        player.level = 0
        player.inventory.clear()
    }

    private fun clearSpectatorEffects(player: Player) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY)
        player.isInvulnerable = false
        player.isCollidable = true
        player.allowFlight = false
        player.isFlying = false
    }

    private fun createWorld(settings: FileConfiguration): World? {
        val prefix = settings.getString("settings.match-world-prefix", "bic_")
        val name = "$prefix${UUID.randomUUID()}"

        val creator = WorldCreator(name)
        creator.generator(VoidChunkGenerator())
        val world = creator.createWorld() ?: return null

        val y = configManager.map().getInt("map.y", 64)
        world.setSpawnLocation(Location(world, 0.5, y + 2.0, 0.5))
        world.isAutoSave = false

        currentGameWorldName = name
        return world
    }

    private fun cancelTask(task: BukkitTask?) {
        task?.cancel()
        plugin.logger.log(Level.INFO, "Blocked In Combat 任务取消: ${task?.taskId}");
    }

    private fun broadcast(text: String) {
        Bukkit.getOnlinePlayers().forEach {player ->
            run {
                player.sendMessage(TextUtil.transC(text))
            }
        }
    }

    fun isGhostSpectator(player: Player?): Boolean {
        return player != null && eliminatedPlayersMutable.contains(player.uniqueId)
    }

    fun getMinPlayers(): Int {
        return configManager.settings().getInt("settings.min-players", 2)
    }
}