package me.chengzhify.blockedInCombat.listener

import me.chengzhify.blockedInCombat.config.ConfigManager
import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.game.GameState
import me.chengzhify.blockedInCombat.team.GameTeam
import me.chengzhify.blockedInCombat.team.TeamManager
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent

class CombatListener(
    private val gameManager: GameManager,
    private val teamManager: TeamManager,
    private val configManager: ConfigManager
) : Listener {
    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (gameManager.state != GameState.PLAYING) {
            event.isCancelled = true
            return
        }

        val victim = event.entity as? Player ?: return
        val attacker = resolveAttacker(event.damager) ?: return

        if (gameManager.isGhostSpectator(victim) || gameManager.isGhostSpectator(attacker)) {
            event.isCancelled = true
            return
        }

        val friendlyFire = configManager.settings().getBoolean("settings.friendly-fire", false)
        if (!friendlyFire) {
            val victimTeam: GameTeam? = teamManager.getTeam(victim)
            val attackerTeam: GameTeam? = teamManager.getTeam(attacker)
            if (victimTeam != null && victimTeam == attackerTeam) {
                event.isCancelled = true
                return
            }
        }

        if (event.isCancelled || event.finalDamage <= 0) {
            return
        }

        if (victim.health <= event.finalDamage) {
            event.isCancelled = true
            gameManager.handleLethalHit(victim, attacker)
            return
        }

    }

    private fun resolveAttacker(damager: Entity): Player? {
        if (damager is Player) {
            return damager
        }
        if (damager is Projectile && damager.shooter is Player) {
            return damager.shooter as Player
        }
        return null
    }
}
