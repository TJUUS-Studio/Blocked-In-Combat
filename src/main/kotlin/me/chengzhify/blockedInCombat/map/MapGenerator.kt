package me.chengzhify.blockedInCombat.map

import me.chengzhify.blockedInCombat.config.ConfigManager
import me.chengzhify.blockedInCombat.team.GameTeam
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.FileConfiguration
import java.util.Locale
import java.util.Random

class MapGenerator(private val configManager: ConfigManager) {
    private val random = Random()
    private val blockList: MutableSet<Material> = HashSet()

    val arenaWidth: Int
        get() = readMapDimensions(configManager.map())[0]
    val arenaLength: Int
        get() = readMapDimensions(configManager.map())[1]
    val arenaHeight: Int
        get() = readMapDimensions(configManager.map())[2]

    fun generateArena(world: World) {
        val map: FileConfiguration = configManager.map()
        checkBlockList(map)

        val width: Int = arenaWidth
        val length: Int = arenaLength
        val height: Int = arenaHeight
        val minY: Int = map.getInt("map.y")
        val fallbackBlock = searchMaterial(map.getString("map.fallback_block"), Material.STONE)

        val minX = -width / 2
        val minZ = -length / 2
        val maxXExclusive = minX + width
        val maxYExclusive = minY + height
        val maxZExclusive = minZ + length

        for (x in minX until maxXExclusive) {
            for (y in minY until maxYExclusive) {
                for (z in minZ until maxZExclusive) {
                    world.getBlockAt(x, y, z).setType(selectBlock(map, fallbackBlock), false)
                }
            }
        }
        buildBedrockCage(world, minX, maxXExclusive - 1, minY, maxYExclusive - 1, minZ, maxZExclusive - 1)

        createTeamRoom(world, GameTeam.RED, map, width, length, height)
        createTeamRoom(world, GameTeam.BLUE, map, width, length, height)
        createTeamRoom(world, GameTeam.GREEN, map, width, length, height)
        createTeamRoom(world, GameTeam.YELLOW, map, width, length, height)
    }

    fun isInsideArena(location: Location): Boolean {
        val width = arenaWidth
        val height = arenaHeight
        val length = arenaLength

        val minX = -width / 2
        val minZ = -length / 2
        val minY = configManager.map().getInt("min_y")
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ
        return x >= minX && x <= minX + width && z >= minZ && z <= minZ + length && y >= minY && y <= minY + height && z >= minZ + length
    }

    private fun buildBedrockCage(world: World, minX: Int, maxX: Int, minY: Int, maxY: Int, minZ: Int, maxZ: Int) {
        val wallMinX = minX - 1
        val wallMaxX = maxX + 1
        val wallMinY = minY - 1
        val wallMaxY = maxY + 1
        val wallMinZ = minZ - 1
        val wallMaxZ = maxZ + 1

        for (x in wallMinX..wallMaxX) {
            for (y in wallMinY..wallMaxY) {
                world.getBlockAt(x, y, wallMinZ).setType(Material.BEDROCK, false)
                world.getBlockAt(x, y, wallMaxZ).setType(Material.BEDROCK, false)
            }
            for (z in wallMinZ..wallMaxZ) {
                world.getBlockAt(x, wallMinY, z).setType(Material.BEDROCK, false)
            }
        }

        for (y in wallMinY..wallMaxY) {
            for (z in wallMinZ..wallMaxZ) {
                world.getBlockAt(wallMinX, y, z).setType(Material.BEDROCK, false)
                world.getBlockAt(wallMaxX, y, z).setType(Material.BEDROCK, false)
            }
        }
    }

    private fun createTeamRoom(world: World, team: GameTeam, map: FileConfiguration, width: Int, length: Int, height: Int) {
        val roomWidth = map.getInt("map.spawn-room.width", 5)
        val roomLength = map.getInt("map.spawn-room.length", 5)
        val roomHeight = map.getInt("map.spawn-room.height", 4)
        val halfX = roomWidth / 2
        val halfZ = roomLength / 2
        val inset = maxOf(halfX + 2, 3)

        val minX = -width / 2
        val minY = map.getInt("map.y", 64)
        val minZ = -length / 2
        val maxX = minX + width - 1
        val maxY = minY + height - 1
        val maxZ = minZ + length - 1

        val roomMinY = minY + maxOf(1, (height - roomHeight) / 2)
        val roomMaxY = minOf(maxY - 1, roomMinY + roomHeight - 1)

        val raw = when (team) {
            GameTeam.RED -> 0 to (minZ + inset)
            GameTeam.BLUE -> (maxX - inset) to 0
            GameTeam.GREEN -> 0 to (maxZ - inset)
            GameTeam.YELLOW -> (minX + inset) to 0
        }

        val centerX = raw.first.coerceIn(minX + halfX + 1, maxX - halfX - 1)
        val centerZ = raw.second.coerceIn(minZ + halfZ + 1, maxZ - halfZ - 1)

        val floorY = roomMinY - 1
        for (x in (centerX - halfX)..(centerX + halfX)) {
            for (z in (centerZ - halfZ)..(centerZ + halfZ)) {
                world.getBlockAt(x, floorY, z).setType(Material.CLAY, false)
                for (y in roomMinY..roomMaxY) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false)
                }
            }
        }
    }

    private fun checkBlockList(map: FileConfiguration) {
        blockList.clear()
        val section: ConfigurationSection = map.getConfigurationSection("blocks") ?: return

        for (block in section.getKeys(false)) {
            val type = section.getString("$block.type")
            val material = searchMaterial(type, Material.AIR)
            blockList.add(material);
        }
    }

    fun isInsideGameArea(location: Location): Boolean {
        val dimensions = readMapDimensions(configManager.map())
        val width = dimensions[0]
        val depth = dimensions[1]
        val height = dimensions[2]
        val minX = -width / 2
        val minY = configManager.map().getInt("map.y", 64)
        val minZ = -depth / 2
        val x = location.blockX
        val y = location.blockY
        val z = location.blockZ
        return x >= minX && x < minX + width &&
                y >= minY && y < minY + height &&
                z >= minZ && z < minZ + depth
    }

    private fun searchMaterial(type: String?, default: Material): Material {
        if (type == null) return default;
        val material = Material.matchMaterial(type.uppercase())
        return material?: default;
    }

    private fun selectBlock(map: FileConfiguration, default: Material): Material {
        val section = map.getConfigurationSection("blocks") ?: return default

        val entries = ArrayList<ResourceEntry>()
        for (block in section.getKeys(false)) {
            val type = section.getString("$block.type")
            val chance = section.getDouble("$block.chance", 0.0)
            val material = searchMaterial(type, Material.AIR)
            if (chance > 0) {
                entries.add(ResourceEntry(material, chance))
            }
        }
        if (entries.isEmpty()) {
            return default
        }

        val random = random.nextDouble() * 100.0
        var cumulative = 0.0
        for (entry in entries) {
            cumulative += entry.chance
            if (random <= cumulative) {
                return entry.type
            }
        }
        return default
    }

    private fun readMapDimensions(map: FileConfiguration): IntArray {
        val explicitWidth = readPositiveInt(map, "map.width", -1)
        val explicitLength = readPositiveInt(map, "map.length", -1)
        val explicitHeight = readPositiveInt(map, "map.height", -1)
        if (explicitWidth > 0 && explicitLength > 0 && explicitHeight > 0) {
            return intArrayOf(explicitWidth, explicitLength, explicitHeight)
        }

        val sizeText = map.getString("map.size", "")
        if (sizeText != null) {
            var normalized = sizeText.lowercase(Locale.ROOT).replace(" ", "")
            if (!normalized.contains("x")) {
                normalized = normalized.replace("*", "x")
            }
            if (normalized.contains("x")) {
                val parts = normalized.split("x")
                if (parts.size == 3) {
                    try {
                        val width = maxOf(1, parts[0].toInt())
                        val depth = maxOf(1, parts[1].toInt())
                        val height = maxOf(1, parts[2].toInt())
                        return intArrayOf(width, depth, height)
                    } catch (_: NumberFormatException) {
                    }
                }
                if (parts.size == 2) {
                    try {
                        val width = maxOf(1, parts[0].toInt())
                        val depth = maxOf(1, parts[1].toInt())
                        val height = if (explicitHeight > 0) explicitHeight else width
                        return intArrayOf(width, depth, height)
                    } catch (_: NumberFormatException) {
                    }
                }
            }
        }

        val size = readPositiveInt(map, "map.size", 32)
        val width = if (explicitWidth > 0) explicitWidth else size
        val depth = if (explicitLength > 0) explicitLength else size
        val height = if (explicitHeight > 0) explicitHeight else size
        return intArrayOf(width, depth, height)
    }

    private fun readPositiveInt(map: FileConfiguration, path: String, fallback: Int): Int {
        val raw = map.get(path)
        if (raw is Number) {
            return maxOf(1, raw.toInt())
        }
        if (raw is String) {
            try {
                return maxOf(1, raw.trim().toInt())
            } catch (_: NumberFormatException) {
            }
        }
        return maxOf(1, fallback)
    }

    private data class ResourceEntry(val type: Material, val chance: Double)
}