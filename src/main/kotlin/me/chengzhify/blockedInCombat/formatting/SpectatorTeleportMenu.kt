package me.chengzhify.blockedInCombat.formatting

import me.chengzhify.blockedInCombat.game.GameManager
import me.chengzhify.blockedInCombat.game.GameState
import me.chengzhify.blockedInCombat.team.GameTeam
import me.chengzhify.blockedInCombat.team.TeamManager
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.Locale
import java.util.UUID

class SpectatorTeleportMenu(
    private val gameManager: GameManager,
    private val teamManager: TeamManager
) : Listener {
    companion object {
        private val PREFIX: String = ChatColor.GOLD.toString() + "✦ " + ChatColor.YELLOW + "BIC" + ChatColor.DARK_GRAY + " » " + ChatColor.RESET
        private val TEAM_TITLE: String = ChatColor.AQUA.toString() + "旁观传送 - 选择队伍"
        private val PLAYER_TITLE_PREFIX: String = ChatColor.AQUA.toString() + "旁观传送 - "
    }

    private val openedTeamMenus: MutableMap<UUID, GameTeam> = HashMap()
    private val openedPlayerMenus: MutableMap<UUID, List<UUID>> = HashMap()

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (gameManager.state != GameState.PLAYING) {
            return
        }

        val player = event.player
        if (!gameManager.isGhostSpectator(player)) {
            return
        }

        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return
        }

        val item = event.item
        if (item == null || item.type != Material.COMPASS) {
            return
        }

        event.isCancelled = true
        openTeamMenu(player)
    }

    @EventHandler
    fun onMenuClick(event: InventoryClickEvent) {
        val title = event.view.title
        if (title != TEAM_TITLE && !title.startsWith(PLAYER_TITLE_PREFIX)) {
            return
        }

        event.isCancelled = true
        val viewer = event.whoClicked as? Player ?: return

        if (gameManager.state != GameState.PLAYING || !gameManager.isGhostSpectator(viewer)) {
            viewer.closeInventory()
            return
        }

        val slot = event.rawSlot
        if (slot < 0 || slot >= event.view.topInventory.size) {
            return
        }

        if (title == TEAM_TITLE) {
            val team = when (slot) {
                10 -> GameTeam.RED
                12 -> GameTeam.BLUE
                14 -> GameTeam.GREEN
                16 -> GameTeam.YELLOW
                else -> null
            }
            if (team != null) {
                openPlayerMenu(viewer, team)
            }
            return
        }

        val targets = openedPlayerMenus[viewer.uniqueId]
        if (targets.isNullOrEmpty()) {
            return
        }

        val index = slot - 10
        if (index < 0 || index >= targets.size) {
            return
        }

        val targetId = targets[index]
        val target = Bukkit.getPlayer(targetId)
        if (target == null || !target.isOnline) {
            viewer.sendMessage(PREFIX + ChatColor.RED + "目标玩家不在线。")
            openPlayerMenu(viewer, openedTeamMenus[viewer.uniqueId] ?: GameTeam.RED)
            return
        }

        if (target.world != viewer.world) {
            viewer.sendMessage(PREFIX + ChatColor.RED + "目标不在当前对局世界。")
            return
        }

        val destination: Location = target.location.clone().add(0.0, 0.3, 0.0)
        viewer.teleport(destination)
        viewer.sendMessage(PREFIX + ChatColor.GREEN + "已传送至 " + ChatColor.AQUA + target.name)
        viewer.closeInventory()
    }

    private fun openTeamMenu(viewer: Player) {
        val inventory = Bukkit.createInventory(null, 27, TEAM_TITLE)

        setTeamItem(inventory, 10, GameTeam.RED, Material.RED_TERRACOTTA)
        setTeamItem(inventory, 12, GameTeam.BLUE, Material.BLUE_TERRACOTTA)
        setTeamItem(inventory, 14, GameTeam.GREEN, Material.GREEN_TERRACOTTA)
        setTeamItem(inventory, 16, GameTeam.YELLOW, Material.YELLOW_TERRACOTTA)

        viewer.openInventory(inventory)
    }

    private fun setTeamItem(inventory: Inventory, slot: Int, team: GameTeam, icon: Material) {
        val item = ItemStack(icon)
        val meta = item.itemMeta
        if (meta != null) {
            val alive = countOnlineMembers(team)
            meta.setDisplayName(team.coloredName)
            meta.lore = listOf(
                ChatColor.GRAY.toString() + "在线成员: " + ChatColor.AQUA + alive,
                ChatColor.YELLOW.toString() + "点击查看队员"
            )
            item.itemMeta = meta
        }
        inventory.setItem(slot, item)
    }

    private fun openPlayerMenu(viewer: Player, team: GameTeam) {
        val members = collectTeamMembers(team)
        if (members.isEmpty()) {
            viewer.sendMessage(PREFIX + ChatColor.RED + "该队伍当前没有可传送目标。")
            return
        }

        val inventory = Bukkit.createInventory(null, 54, PLAYER_TITLE_PREFIX + team.coloredName)

        val order = ArrayList<UUID>()
        var slot = 10
        for (member in members) {
            if (slot >= 54) {
                break
            }
            order.add(member.uniqueId)
            inventory.setItem(slot, buildPlayerHead(member, team))
            slot++
        }

        openedTeamMenus[viewer.uniqueId] = team
        openedPlayerMenus[viewer.uniqueId] = order
        viewer.openInventory(inventory)
    }

    private fun buildPlayerHead(target: Player, team: GameTeam): ItemStack {
        val skull = ItemStack(Material.PLAYER_HEAD)
        val meta = skull.itemMeta as? SkullMeta
        if (meta != null) {
            meta.owningPlayer = target
            meta.setDisplayName(ChatColor.AQUA.toString() + target.name)
            val health = String.format(Locale.ROOT, "%.1f", maxOf(0.0, target.health))
            meta.lore = listOf(
                ChatColor.GRAY.toString() + "队伍：" + team.coloredName,
                ChatColor.GRAY.toString() + "血量：" + ChatColor.RED + health
            )
            skull.itemMeta = meta
        }
        return skull
    }

    private fun countOnlineMembers(team: GameTeam): Int = collectTeamMembers(team).size

    private fun collectTeamMembers(team: GameTeam): List<Player> {
        val members = teamManager.getMembers()
        val uuids = members[team] ?: emptySet()
        val gameWorld = gameManager.currentGameWorld ?: return emptyList()

        val online = ArrayList<Player>()
        for (uuid in uuids) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null && player.isOnline && player.world == gameWorld) {
                online.add(player)
            }
        }
        return online
    }
}