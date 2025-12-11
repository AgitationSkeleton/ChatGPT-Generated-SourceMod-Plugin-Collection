import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.ArrayList;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

public class ZombieGiants extends JavaPlugin implements Listener {

    private static final Logger log = Logger.getLogger("Minecraft");

    private final Random random = new Random();
    private final Set<String> blockedWorlds = new HashSet<String>();

    private int spawnRateDenominator; // 1 in N chance
    private Material dropMaterial;
    private int dropAmount;

    private Configuration config;

    @Override
    public void onEnable() {
        // Prepare config
        setupConfig();

        // Load values from config
        reloadSettings();

        // Register events
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(this, this);

        log.info("[ZombieGiants] Enabled. 1 in " + spawnRateDenominator + " zombies may become Giants.");
    }

    @Override
    public void onDisable() {
        log.info("[ZombieGiants] Disabled.");
    }

    private void setupConfig() {
        // Ensure plugin data folder exists
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        config = new Configuration(configFile);
        config.load();

        // Set defaults if not present
        if (config.getProperty("SpawnRate") == null) {
            config.setProperty("SpawnRate", 100); // 1/100 chance
        }

        if (config.getProperty("Drops.Material") == null) {
            config.setProperty("Drops.Material", "SPONGE");
        }

        if (config.getProperty("Drops.Amount") == null) {
            config.setProperty("Drops.Amount", 16);
        }

        if (config.getProperty("BlockedWorlds") == null) {
            List<String> defaults = new ArrayList<String>();
            defaults.add("ZombieSiegeWorld");
            defaults.add("myskyblock");
            defaults.add("skylands");
            config.setProperty("BlockedWorlds", defaults);
        }

        config.save();
    }

    private void reloadSettings() {
        // Spawn rate: 1 in N zombies become Giants
        spawnRateDenominator = config.getInt("SpawnRate", 100);
        if (spawnRateDenominator < 1) {
            spawnRateDenominator = 1;
            log.warning("[ZombieGiants] SpawnRate must be >= 1; forcing to 1.");
        }

        // Drops
        String materialName = config.getString("Drops.Material", "SPONGE");
        int amount = config.getInt("Drops.Amount", 16);
        if (amount < 1) {
            amount = 1;
        }

        Material mat = Material.SPONGE; // default
        try {
            mat = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warning("[ZombieGiants] Invalid Drops.Material in config.yml: '" + materialName +
                        "', defaulting to SPONGE.");
        }

        this.dropMaterial = mat;
        this.dropAmount = amount;

        // Blocked worlds (store in lowercase for case-insensitive compare)
        blockedWorlds.clear();
        List<String> worldsList = config.getStringList("BlockedWorlds", null);
        if (worldsList != null) {
            for (String w : worldsList) {
                if (w != null) {
                    String trimmed = w.trim();
                    if (!trimmed.isEmpty()) {
                        blockedWorlds.add(trimmed.toLowerCase());
                    }
                }
            }
        }

        log.info("[ZombieGiants] Config reloaded: SpawnRate=1/" + spawnRateDenominator +
                 ", Drops=" + dropAmount + "x " + dropMaterial.name() +
                 ", BlockedWorlds=" + blockedWorlds);
    }

    @EventHandler(priority = Priority.Normal, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof Zombie)) {
            return;
        }

        // Only affect natural spawns (not spawners, eggs, plugins, etc.)
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        World world = entity.getWorld();
        if (isWorldBlocked(world)) {
            return;
        }

        // Roll the dice: 1 in spawnRateDenominator chance
        if (random.nextInt(spawnRateDenominator) != 0) {
            return;
        }

        // Turn this zombie into a Giant: cancel original spawn and spawn a Giant
        event.setCancelled(true);

        world.spawnCreature(entity.getLocation(), CreatureType.GIANT);
    }

    @EventHandler(priority = Priority.Normal, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof Giant)) {
            return;
        }

        World world = entity.getWorld();
        if (isWorldBlocked(world)) {
            // In blocked worlds, leave drops alone
            return;
        }

        // Override drops to configured item
        List<ItemStack> drops = event.getDrops();
        drops.clear();

        if (dropMaterial != Material.AIR && dropAmount > 0) {
            drops.add(new ItemStack(dropMaterial, dropAmount));
        }
    }

    private boolean isWorldBlocked(World world) {
        if (world == null) {
            return false;
        }
        String name = world.getName();
        if (name == null) {
            return false;
        }
        return blockedWorlds.contains(name.toLowerCase());
    }
}
