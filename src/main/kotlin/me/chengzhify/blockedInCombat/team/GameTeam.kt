package me.chengzhify.blockedInCombat.team

import org.bukkit.ChatColor
import org.bukkit.Material

enum class GameTeam(
    val displayName: String,
    val color: ChatColor,
    val baseBlock: Material
)
{
    RED("红队", ChatColor.RED, Material.RED_TERRACOTTA),
    BLUE("蓝队", ChatColor.BLUE, Material.BLUE_TERRACOTTA),
    GREEN("绿队", ChatColor.GREEN, Material.GREEN_TERRACOTTA),
    YELLOW("黄队", ChatColor.YELLOW, Material.YELLOW_TERRACOTTA);

    val coloredName: String
        get() = "$color$displayName"

// 匹配输入的队伍名称
    companion object {
        fun fromInput(input: String): GameTeam? {
            return entries.firstOrNull { it.name.equals(input, ignoreCase = true) || it.displayName.equals(input, ignoreCase = true) }
        }
    }
}
