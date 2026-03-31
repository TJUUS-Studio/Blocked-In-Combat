package me.chengzhify.blockedInCombat.team

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.EnumMap
import java.util.UUID

class TeamManager {
    private val members: MutableMap<GameTeam, MutableSet<UUID>> = EnumMap(GameTeam::class.java)
    private val playerTeams: MutableMap<UUID, GameTeam> = HashMap()

    init {
        for (team in GameTeam.entries) {
            members[team] = HashSet()
        }
    }

    fun chooseTeam(player: Player, team: GameTeam) {
        removePlayer(player.uniqueId)
        playerTeams[player.uniqueId] = team
    }

    fun removePlayer(uuid: UUID) {
        val oldTeam = playerTeams.remove(uuid)
        if (oldTeam != null) {
            members[oldTeam]?.remove(uuid)
        }
    }

    fun prepareTeams(players: Collection<Player>) {
        for (teamMembers in members.values) {
            teamMembers.clear()
        }

        val unassigned = ArrayList<Player>()
        for (player in players) {
            val selected = playerTeams[player.uniqueId]
            if (selected == null) {
                unassigned.add(player)
                continue
            }
            members[selected]?.add(player.uniqueId)
        }

        for (player in unassigned.shuffled()) {
            val smallest = members.entries.minByOrNull { it.value.size }?.key ?: GameTeam.RED // 给未选择队伍的玩家进行平衡分配，先获取所有队伍中人数最少的。取 key，无则取红队。
            playerTeams[player.uniqueId] = smallest
            members[smallest]?.add(player.uniqueId)
        }
    }

    fun getTeam(uuid: UUID): GameTeam? = playerTeams[uuid]

    fun getTeam(player: Player): GameTeam? = getTeam(player.uniqueId)

    fun getAliveTeams(eliminatedPlayers: Set<UUID>): Set<GameTeam> {
        val alive: MutableSet<GameTeam> = HashSet()
        for ((team, teamMembers) in members) {
            if (teamMembers.any { it !in eliminatedPlayers }) {
                alive.add(team)
            }
        }
        return alive
    }

    fun getAliveCount(team: GameTeam, eliminatedPlayers: Set<UUID>): Int {
        val teamMembers = members[team] ?: return 0
        return teamMembers.count { it !in eliminatedPlayers }
    }

    fun getMembers(): Map<GameTeam, Set<UUID>> = members

    fun getSelectedCount(team: GameTeam): Int = playerTeams.values.count { it == team }

    fun getSelectedCounts(): Map<GameTeam, Int> {
        val counts: MutableMap<GameTeam, Int> = EnumMap(GameTeam::class.java)
        for (team in GameTeam.entries) {
            counts[team] = 0
        }
        for (selected in playerTeams.values) {
            counts.computeIfPresent(selected) { _, v -> v + 1 }
        }
        return counts
    }

    fun getTeamSpawn(world: World, team: GameTeam, mapWidth: Int, mapLength: Int, mapHeight: Int, y: Int, baseSize: Int): Location {
        val roomHalf = 2
        val offset = maxOf(roomHalf + 2, 3)

        val minX = -mapWidth / 2
        val minY = y
        val minZ = -mapLength / 2
        val maxX = minX + mapWidth - 1
        val maxY = minY + mapHeight - 1
        val maxZ = minZ + mapLength - 1

        val (rawX, rawZ) = when (team) {
            GameTeam.RED -> 0 to (minZ + offset)
            GameTeam.BLUE -> (maxX - offset) to 0
            GameTeam.GREEN -> 0 to (maxZ - offset)
            GameTeam.YELLOW -> (minX + offset) to 0
        }

        val x = rawX.coerceIn(minX + roomHalf + 1, maxX - roomHalf - 1)
        val z = rawZ.coerceIn(minZ + roomHalf + 1, maxZ - roomHalf - 1)
        val roomMinY = minY + maxOf(1, (mapHeight - 4) / 2)
        val spawnY = minOf(maxY - 1, roomMinY + 1)

        return Location(world, x + 0.5, spawnY + 0.0, z + 0.5)
    }

    fun clearGameData() {
        for (teamMembers in members.values) {
            teamMembers.clear()
        }
    }

    fun clearAllData() {
        clearGameData()
        playerTeams.clear()
    }
}
