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
import org.bukkit.potion.PotionEffect
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
        bossBarService = BossBarService(plugin, this)
        scoreboardService = ScoreboardService(plugin, this, teamManager)
        scoreboardService.start()
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
                return
            }
            executor.sendMessage(TextUtil.transC("&c倒计时正在进行! 如果需要强制开启, 请使用 /bic forcestart"))
        }

        val minPlayers = configManager.settings().getInt("settings.min-players", 2)

        if (!force && Bukkit.getOnlinePlayers().size < minPlayers) {
            executor.sendMessage(TextUtil.transC("&c当前在线玩家不足 $minPlayers 人, 无法开始游戏!"))
            return
        }

        if (force) {
            if (Bukkit.getOnlinePlayers().size < minPlayers) {
                executor.sendMessage(TextUtil.transC("&c当前在线玩家不足 $minPlayers 人, 无法开始游戏!"))
                return
            }
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

        bossBarService.start()
        scoreboardService.start()

        broadcast("&e游戏将在 &a$lobbyCountdownSeconds &e秒后开始!")
        bossBarService.refreshNow()
        scoreboardService.refreshNow()
        lobbyCountdownTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (state != GameState.STARTING) {
                return@Runnable
            }

            if (Bukkit.getOnlinePlayers().size < minPlayers) {
                stopCountdown()
                return@Runnable
            }

            lobbyCountdownLeft--
            bossBarService.refreshNow()
            scoreboardService.refreshNow()

            if (lobbyCountdownLeft <= 0) {
                lobbyCountdownLeft = 0
                broadcast("&e游戏将在 &a0 &e秒后开始!")
                bossBarService.refreshNow()
                scoreboardService.refreshNow()
                cancelTask(lobbyCountdownTask)
                lobbyCountdownTask = null
                launchGame()
                return@Runnable
            }

            if ((lobbyCountdownLeft in 1..5) || lobbyCountdownLeft % 10 == 0) {
                broadcast("&e游戏将在 &a$lobbyCountdownLeft &e秒后开始!")
            }
        }, 20L, 20L)
    }

    private fun stopCountdown() {
        cancelTask(lobbyCountdownTask)
        lobbyCountdownTask = null
        state = GameState.LOBBY

        bossBarService.stop()
        scoreboardService.start()

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

        processPotions(players)


        world.pvp = false
        schedulePvpEnable(world, settings.getInt("settings.pvp-delay-seconds", 120))

        gameDurationSeconds = settings.getInt("settings.game-duration-seconds", 300)
        timeLeftSeconds = gameDurationSeconds
        runStartTitleCountdown(players)
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
        broadcast("&e结算中，将在 ${delay.toString()} 秒后返回大厅.")

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
            meta.setDisplayName(TextUtil.transC("&6队伍选择器 &7(右键打开)"))
            meta.lore = listOf(TextUtil.transC("&7选择 红/蓝/绿/黄 队伍"), TextUtil.transC("&e请在开局前完成选择"))
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
                scheduleGameTimer()
                bossBarService.start()
                scoreboardService.start()

                for (player in players) {
                    if (!player.isOnline) {
                        continue
                    }
                    player.sendTitle(TextUtil.transC("&e&l开始游戏!"), TextUtil.transC("&7祝你好运"), 0, 20, 10)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                }
                bossBarService.refreshNow()
                scoreboardService.refreshNow()
                broadcast(TextUtil.transC("&a战斗开始!"))
                cancelTask(startTitleCountdownTask)
                startTitleCountdownTask = null
                return@Runnable
            }

            for (player in players) {
                if (!player.isOnline) {
                    continue
                }
                player.sendTitle(TextUtil.transC("&e&l${left[0]}"), TextUtil.transC("&7地图已生成, 请做好准备"), 0, 20, 0)
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
            stopGame(TextUtil.transC("&e本局平局: 没有队伍存活."))
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
            bossBarService.refreshNow()
            scoreboardService.refreshNow()
            if (timeLeftSeconds < 0) {
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

    fun handleLethalHit(victim: Player, killer: Player?) {
        if (state != GameState.PLAYING) {
            return
        }

        val victimId = victim.uniqueId
        if (eliminatedPlayersMutable.contains(victimId) || pendingRespawns.contains(victimId)) {
            return
        }

        if (killer != null && killer.uniqueId != victimId) {
            killCount.merge(killer.uniqueId, 1, Int::plus)
        }

        eliminatedPlayersMutable.add(victimId)
        pendingRespawns.remove(victimId)
        eliminatedAtMs.putIfAbsent(victimId, System.currentTimeMillis())
        applyGhostSpectatorState(victim)

        val deadTeam = teamManager.getTeam(victim)
        if (deadTeam != null && teamManager.getAliveCount(deadTeam, eliminatedPlayersMutable) == 0) {
            broadcast("&e队伍 ${deadTeam.coloredName} &e已被淘汰!")
        }

        checkVictoryAfterElimination()
    }

    private fun checkVictoryAfterElimination() {
        val aliveTeams = teamManager.getAliveTeams(eliminatedPlayersMutable)
        if (aliveTeams.size <= 1) {
            if (aliveTeams.isEmpty()) {
                stopGame("&e本局平局: 没有队伍存活.")
            } else {
                val winner = aliveTeams.first()
                stopGame("&6胜利队伍: ${winner.coloredName}")
            }
        }
    }
    fun handlePlayerDeath(player: Player, killer: Player?) {
        if (state != GameState.PLAYING) {
            return
        }

        if (eliminatedPlayersMutable.contains(player.uniqueId)) {
            return
        }

        if (killer != null && killer.uniqueId != player.uniqueId) {
            killCount.merge(killer.uniqueId, 1, Int::plus)
        }

        val delay = maxOf(1, configManager.settings().getInt("settings.respawn.delay-seconds", 3))
        val left = respawnTickets[player.uniqueId] ?: 0

        if (left > 0) {
            respawnTickets[player.uniqueId] = left - 1
            pendingRespawns.add(player.uniqueId)
            player.sendMessage("&e你将在 ${delay.toString()} 秒后重生.")

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                val online = Bukkit.getPlayer(player.uniqueId)
                if (online != null && online.isOnline && online.isDead && pendingRespawns.contains(player.uniqueId)) {
                    online.spigot().respawn()
                }
            }, delay * 20L)

            return
        }

        eliminatedPlayersMutable.add(player.uniqueId)
        pendingRespawns.remove(player.uniqueId)
        eliminatedAtMs.putIfAbsent(player.uniqueId, System.currentTimeMillis())
        applyGhostSpectatorState(player)

        val deadTeam = teamManager.getTeam(player)
        if (deadTeam != null && teamManager.getAliveCount(deadTeam, eliminatedPlayersMutable) == 0) {
            broadcast("&e队伍 ${deadTeam.coloredName} &e已被淘汰!")
        }

        val aliveTeams = teamManager.getAliveTeams(eliminatedPlayersMutable)
        if (aliveTeams.size <= 1) {
            if (aliveTeams.isEmpty()) {
                stopGame("&e本局平局: 没有队伍存活.")
            } else {
                val winner = aliveTeams.first()
                stopGame("&6胜利队伍：${winner.coloredName}")
            }
        }
    }

    private fun processPotions(players: Collection<Player>) {
        for (player in players) {
            if (state == GameState.PLAYING) {
                player.addPotionEffect(PotionEffect(PotionEffectType.NIGHT_VISION, Int.MAX_VALUE, 0, false, false, true))
                player.addPotionEffect(PotionEffect(PotionEffectType.SATURATION, Int.MAX_VALUE, 0, false, false, true))
            }
            if (state == GameState.ENDING) {
                player.removePotionEffect(PotionEffectType.NIGHT_VISION)
                player.removePotionEffect(PotionEffectType.SATURATION)
            }
        }
    }

    private fun applyGhostSpectatorState(player: Player) {
        clearSpectatorEffects(player)
        player.gameMode = GameMode.SURVIVAL
        player.health = player.maxHealth
        player.foodLevel = 20
        player.saturation = 20f
        player.inventory.clear()
        player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, Int.MAX_VALUE, 0, false, false, false))
        player.isInvulnerable = true
        player.isCollidable = false
        player.allowFlight = true
        player.isFlying = true
        giveSpectatorTeleportItem(player)
        player.sendMessage("&7你已进入旁观状态.")
    }

    private fun giveSpectatorTeleportItem(player: Player) {
        val teleporter = ItemStack(Material.COMPASS)
        val meta = teleporter.itemMeta
        if (meta != null) {
            meta.setDisplayName("&b旁观传送器 &7(右键打开)")
            meta.lore = listOf("&7一级菜单选择队伍", "&7二级菜单选择玩家传送")
            teleporter.itemMeta = meta
        }
        player.inventory.setItem(0, teleporter)
    }

    fun handlePlayerJoin(player: Player) {
        if (state == GameState.STARTING || state == GameState.PLAYING) {
            bossBarService.addPlayer(player)
        }

        if (state == GameState.PLAYING) {
            currentGameWorld?.spawnLocation?.let { player.teleport(it) }
            clearSpectatorEffects(player)
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage("&e游戏进行中, 你已以旁观者身份加入.")
            return
        }

        resolveLobbySpawn()?.let { player.teleport(it) }
        resetLobbyPlayer(player)
        tryAutoStartLobbyCountdown()
    }

    fun resolveRespawnLocation(player: Player): Location {
        val gameWorld = currentGameWorld
        if (gameWorld == null) {
            val lobby = resolveLobbyWorld(configManager.settings())
            return lobby?.spawnLocation ?: player.location
        }

        val mapWidth = mapGenerator.arenaWidth
        val mapLength = mapGenerator.arenaLength
        val mapHeight = mapGenerator.arenaHeight
        val y = configManager.map().getInt("map.y", 64)
        val baseSize = configManager.map().getInt("map.team-base-size", 6)
        val team = teamManager.getTeam(player)
        if (team == null) {
            return gameWorld.spawnLocation
        }
        return teamManager.getTeamSpawn(gameWorld, team, mapWidth, mapLength, mapHeight, y, baseSize)
    }

    fun isPendingRespawn(uuid: UUID): Boolean = pendingRespawns.contains(uuid)

    fun finalizeRespawn(player: Player) {
        pendingRespawns.remove(player.uniqueId)
        clearSpectatorEffects(player)
        player.gameMode = GameMode.SURVIVAL
        player.health = player.maxHealth
        player.foodLevel = 20
        player.saturation = 20f
        player.inventory.clear()
    }

    fun forceCleanupOnDisable() {
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
        if (this::bossBarService.isInitialized) {
            bossBarService.stop()
        }
        if (this::scoreboardService.isInitialized) {
            scoreboardService.stop()
        }

        val gameWorld = currentGameWorld
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
    }

    fun enforceGhostSpectatorState(player: Player?) {
        if (player == null) {
            return
        }
        applyGhostSpectatorState(player)
    }

    fun clampSpectatorToArena(to: Location): Location {
        val width = mapGenerator.arenaWidth
        val length = mapGenerator.arenaLength
        val height = mapGenerator.arenaHeight
        val minY = configManager.map().getInt("map.y", 64)

        val minX = -width / 2
        val minZ = -length / 2
        val maxX = minX + width - 1
        val maxZ = minZ + length - 1
        val maxY = minY + height - 1

        val x = to.x.coerceIn(minX + 0.2, maxX + 0.8)
        val y = to.y.coerceIn(minY + 0.2, maxY + 0.8)
        val z = to.z.coerceIn(minZ + 0.2, maxZ + 0.8)
        return Location(to.world, x, y, z, to.yaw, to.pitch)
    }

    private fun tryAutoStartLobbyCountdown() {
        if (state != GameState.LOBBY) {
            return
        }

        val minPlayers = configManager.settings().getInt("settings.min-players", 2)
        if (Bukkit.getOnlinePlayers().size < minPlayers) {
            return
        }

        startLobbyCountdown()
    }

    fun handlePlayerQuit(player: Player) {
        if (state == GameState.PLAYING) {
            if (pendingRespawns.contains(player.uniqueId) || !eliminatedPlayersMutable.contains(player.uniqueId)) {
                eliminatedPlayersMutable.add(player.uniqueId)
                pendingRespawns.remove(player.uniqueId)
                eliminatedAtMs.putIfAbsent(player.uniqueId, System.currentTimeMillis())
                checkVictoryAfterElimination()
            }
            return
        }
        teamManager.removePlayer(player.uniqueId)

        val minPlayers = configManager.settings().getInt("settings.min-players", 2)
        if (state == GameState.STARTING && Bukkit.getOnlinePlayers().size < minPlayers) {
            stopCountdown()
        }
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