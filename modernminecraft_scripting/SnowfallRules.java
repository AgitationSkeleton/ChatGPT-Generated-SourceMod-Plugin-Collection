import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SnowfallRules extends JavaPlugin implements Listener {

    private boolean enabled;
    private boolean onlyListedWorlds;
    private Set<String> worldNamesLower = new HashSet<>();
    private Set<Material> allowedBelow = new HashSet<>();

    @Override
    public void onEnable() {
        ensureDefaultConfig();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        getLogger().info("SnowfallRules enabled.");
    }

    private void ensureDefaultConfig() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
        }
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration config = getConfig();

        enabled = config.getBoolean("enabled", true);
        onlyListedWorlds = config.getBoolean("worldFilter.onlyListedWorlds", false);

        worldNamesLower.clear();
        List<String> worldNames = config.getStringList("worldFilter.worlds");
        if (worldNames != null) {
            for (String name : worldNames) {
                if (name != null && !name.trim().isEmpty()) {
                    worldNamesLower.add(name.trim().toLowerCase());
                }
            }
        }

        allowedBelow.clear();
        List<String> allowed = config.getStringList("allowedBelowBlocks");
        if (allowed != null) {
            for (String matName : allowed) {
                if (matName == null) continue;
                String trimmed = matName.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    Material mat = Material.valueOf(trimmed.toUpperCase());
                    allowedBelow.add(mat);
                } catch (IllegalArgumentException ex) {
                    getLogger().warning("Unknown material in allowedBelowBlocks: " + trimmed);
                }
            }
        }

        // Sensible fallback if config list is empty or invalid
        if (allowedBelow.isEmpty()) {
            allowedBelow.add(Material.GRASS_BLOCK);
            allowedBelow.add(Material.PODZOL);
            allowedBelow.add(Material.MYCELIUM);
            allowedBelow.add(Material.MOSS_BLOCK);
            allowedBelow.add(Material.DIRT_PATH);

            // Common leaf variants (Spigot has separate materials for each tree)
            allowedBelow.add(Material.OAK_LEAVES);
            allowedBelow.add(Material.SPRUCE_LEAVES);
            allowedBelow.add(Material.BIRCH_LEAVES);
            allowedBelow.add(Material.JUNGLE_LEAVES);
            allowedBelow.add(Material.ACACIA_LEAVES);
            allowedBelow.add(Material.DARK_OAK_LEAVES);
            allowedBelow.add(Material.MANGROVE_LEAVES);
            allowedBelow.add(Material.CHERRY_LEAVES);
            allowedBelow.add(Material.AZALEA_LEAVES);
            allowedBelow.add(Material.FLOWERING_AZALEA_LEAVES);
        }
    }

    private boolean shouldApplyInWorld(World world) {
        if (!onlyListedWorlds) return true;
        if (world == null) return false;
        return worldNamesLower.contains(world.getName().toLowerCase());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSnowLayerForm(BlockFormEvent event) {
        if (!enabled) return;

        // Natural snowfall creates a snow layer: new state is SNOW.
        if (event.getNewState() == null || event.getNewState().getType() != Material.SNOW) {
            return;
        }

        Block target = event.getBlock();
        if (!shouldApplyInWorld(target.getWorld())) {
            return;
        }

        Block below = target.getRelative(0, -1, 0);
        Material belowType = below.getType();

        // Allow snow only if the surface below is in the allowlist.
        if (!allowedBelow.contains(belowType)) {
            event.setCancelled(true);
        }
    }
}
