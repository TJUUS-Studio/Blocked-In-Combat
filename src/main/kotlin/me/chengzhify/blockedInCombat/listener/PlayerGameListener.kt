package me.chengzhify.blockedInCombat.listener

import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.game.GameState
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

class PlayerGameListener(private val gameManager: GameManager) : Listener {
    private val prefix = ChatColor.GOLD.toString() + "✦ " + ChatColor.YELLOW + "BIC" + ChatColor.DARK_GRAY + " » " + ChatColor.RESET

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        gameManager.handlePlayerJoin(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        gameManager.handlePlayerQuit(event.player)
    }

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (event.itemDrop.itemStack.type != Material.COMPASS) {
            return
        }

        val player = event.player
        val inLobby = gameManager.state == GameState.LOBBY || gameManager.state == GameState.STARTING
        val isSpectator = player.gameMode == GameMode.SPECTATOR || gameManager.isGhostSpectator(player)
        if (!inLobby && !isSpectator) {
            return
        }

        event.isCancelled = true
        player.sendMessage(prefix + ChatColor.RED + "当前状态下不能丢弃指南针。")
    }

    @EventHandler
    fun onLeavesUpdate(event: LeavesDecayEvent) {
        event.isCancelled = true
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        if (gameManager.state != GameState.PLAYING) {
            event.isCancelled = true
            return
        }

        if (event.block.type == Material.BEDROCK) {
            event.isCancelled = true
            event.player.sendMessage(prefix + ChatColor.RED + "基岩不可破坏。")
            return
        }

        if (gameManager.isGhostSpectator(event.player)) {
            event.isCancelled = true
            return
        }

        if (!gameManager.mapGenerator.isInsideGameArea(event.block.location)) {
            event.isCancelled = true
            return
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (gameManager.state != GameState.PLAYING) {
            event.isCancelled = true
            return
        }

        if (gameManager.isGhostSpectator(event.player)) {
            event.isCancelled = true
            return
        }

        if (!gameManager.mapGenerator.isInsideGameArea(event.block.location)) {
            event.isCancelled = true
            return
        }
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        if (gameManager.state == GameState.PLAYING && gameManager.isGhostSpectator(event.entity)) {
            event.deathMessage = null
            event.drops.clear()
            event.keepInventory = true
            return
        }

        event.deathMessage = null
        event.keepInventory = true
        event.drops.clear()
        gameManager.handlePlayerDeath(event.entity, event.entity.killer)
    }

    @EventHandler
    fun onAnyDamage(event: EntityDamageEvent) {
        if (gameManager.state != GameState.PLAYING) {
            return
        }

        val player = event.entity as? Player ?: return
        if (event is EntityDamageByEntityEvent) {
            return
        }

        if (gameManager.isGhostSpectator(player)) {
            event.isCancelled = true
            return
        }

        if (event.finalDamage > 0 && player.health <= event.finalDamage) {
            event.isCancelled = true
            gameManager.handleLethalHit(player, null)
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        if (gameManager.eliminatedPlayers.contains(player.uniqueId)) {
            event.respawnLocation = gameManager.resolveRespawnLocation(player)
            gameManager.enforceGhostSpectatorState(player)
            return
        }

        if (gameManager.isPendingRespawn(player.uniqueId)) {
            event.respawnLocation = gameManager.resolveRespawnLocation(player)
            gameManager.finalizeRespawn(player)
        }
    }

    @EventHandler
    fun onMove(event: PlayerMoveEvent) {
        if (gameManager.state != GameState.PLAYING) {
            return
        }

        val player = event.player
        if (player.gameMode != GameMode.SPECTATOR && !gameManager.isGhostSpectator(player)) {
            return
        }

        val gameWorld = gameManager.currentGameWorld ?: return
        if (player.world.name != gameWorld.name) {
            return
        }

        val to: Location = event.to ?: return
        if (!gameManager.mapGenerator.isInsideGameArea(to)) {
            event.setTo(gameManager.clampSpectatorToArena(to))
        }
    }
}
