package me.chengzhify.blockedInCombat.commands

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class BicCommand : CommandExecutor, TabCompleter {

    override fun onCommand(p0: CommandSender, p1: Command, p2: String, p3: Array<out String?>): Boolean {
        TODO("Not yet implemented")
    }

    override fun onTabComplete(p0: CommandSender, p1: Command, p2: String, p3: Array<out String?>): List<String?>? {
        TODO("Not yet implemented")
    }

}