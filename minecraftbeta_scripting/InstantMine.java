import java.util.HashSet;
import java.util.Set;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class InstantMine extends JavaPlugin implements Listener {

    private final Set<String> instantMinePlayers = new HashSet<String>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        System.out.println("[InstantMine] Enabled.");
    }

    @Override
    public void onDisable() {
        instantMinePlayers.clear();
        System.out.println("[InstantMine] Disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("instantmine")) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }

        Player player = (Player) sender;

        // Ops only
        if (!player.isOp()) {
            player.sendMessage(ChatColor.RED + "Only operators can use /instantmine.");
            return true;
        }

        String playerName = player.getName().toLowerCase();

        if (instantMinePlayers.contains(playerName)) {
            instantMinePlayers.remove(playerName);
            player.sendMessage(ChatColor.YELLOW + "InstantMine disabled.");
        } else {
            instantMinePlayers.add(playerName);
            player.sendMessage(ChatColor.GREEN + "InstantMine enabled. All blocks you hit will break instantly.");
        }

        return true;
    }

    @EventHandler
    public void onBlockDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();

        // Only affect ops who have it toggled on
        if (!player.isOp()) {
            return;
        }

        String playerName = player.getName().toLowerCase();

        if (instantMinePlayers.contains(playerName)) {
            event.setInstaBreak(true);
        }
    }
}
