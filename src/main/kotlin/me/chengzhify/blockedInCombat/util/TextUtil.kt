package me.chengzhify.blockedInCombat.util

import org.bukkit.ChatColor

class TextUtil {
    companion object {
        fun transC(string: String): String {
            return ChatColor.translateAlternateColorCodes('&', string)
        }
    }
}