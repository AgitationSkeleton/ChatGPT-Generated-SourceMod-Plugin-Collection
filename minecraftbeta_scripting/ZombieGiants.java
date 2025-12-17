import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.ArrayList;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Chicken;
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

    // Giant conversion: 1 in N chance
    private int spawnRateDenominator;

    // Giant drops
    private Material dropMaterial;
    private int dropAmount;

    // Chicken jockey conversion
    private boolean chickenJockeyEnabled;
    private double chickenJockeyChancePercent; // e.g. 4.75 means 4.75%

    private Configuration config;

    @Override
    public void onEnable() {
        setupConfig();
        reloadSettings();

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(this, this);

        log.info("[ZombieGiants] Enabled. GiantChance=1/" + spawnRateDenominator +
                ", ChickenJockey=" + (chickenJockeyEnabled ? (chickenJockeyChancePercent + "%") : "disabled"));
    }

    @Override
    public void onDisable() {
        log.info("[ZombieGiants] Disabled.");
    }

    private void setupConfig() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        config = new Configuration(configFile);
        config.load();

        // Defaults (only set if missing)
        if (config.getProperty("SpawnRate") == null) {
            config.setProperty("SpawnRate", 100); // 1/100
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

        // Chicken jockey settings
        if (config.getProperty("ChickenJockey.Enabled") == null) {
            config.setProperty("ChickenJockey.Enabled", true);
        }
        if (config.getProperty("ChickenJockey.ChancePercent") == null) {
            config.setProperty("ChickenJockey.ChancePercent", 4.75);
        }

        config.save();
    }

    private void reloadSettings() {
        // Giant chance: 1 in N
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

        Material mat = Material.SPONGE;
        try {
            mat = Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warning("[ZombieGiants] Invalid Drops.Material: '" + materialName + "', defaulting to SPONGE.");
        }

        dropMaterial = mat;
        dropAmount = amount;

        // Blocked worlds
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

        // Chicken jockey config (old Configuration can store numbers as Integer/Double/etc)
        chickenJockeyEnabled = getBooleanCompat("ChickenJockey.Enabled", true);
        chickenJockeyChancePercent = getDoubleCompat("ChickenJockey.ChancePercent", 4.75);
        if (chickenJockeyChancePercent < 0.0) chickenJockeyChancePercent = 0.0;
        if (chickenJockeyChancePercent > 100.0) chickenJockeyChancePercent = 100.0;

        log.info("[ZombieGiants] Config: SpawnRate=1/" + spawnRateDenominator +
                ", Drops=" + dropAmount + "x " + dropMaterial.name() +
                ", ChickenJockey=" + (chickenJockeyEnabled ? (chickenJockeyChancePercent + "%") : "disabled") +
                ", BlockedWorlds=" + blockedWorlds);
    }

    @EventHandler(priority = Priority.Normal, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof Zombie)) {
            return;
        }

        // Only natural spawns
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        World world = entity.getWorld();
        if (isWorldBlocked(world)) {
            return;
        }

        Location spawnLoc = entity.getLocation();

        // 1) Giant conversion (takes precedence)
        if (random.nextInt(spawnRateDenominator) == 0) {
            // Require enough space for a giant, otherwise leave zombie alone
            if (!hasGiantSpace(world, spawnLoc.getBlockX(), spawnLoc.getBlockY(), spawnLoc.getBlockZ())) {
                return;
            }

            event.setCancelled(true);
            world.spawnCreature(spawnLoc, CreatureType.GIANT);
            return;
        }

        // 2) Chicken jockey conversion (only if not turned into giant)
        if (chickenJockeyEnabled && rollPercent(chickenJockeyChancePercent)) {
            // Chicken jockey doesn't need huge clearance; basic 2-block vertical + 1x1 is fine.
            // If it's cramped, just leave zombie alone.
            if (!hasSmallMobSpace(world, spawnLoc.getBlockX(), spawnLoc.getBlockY(), spawnLoc.getBlockZ())) {
                return;
            }

            event.setCancelled(true);

            // Spawn chicken, then zombie, then mount zombie on chicken
            Entity chickenEnt = world.spawnCreature(spawnLoc, CreatureType.CHICKEN);
            Entity zombieEnt = world.spawnCreature(spawnLoc, CreatureType.ZOMBIE);

            if (chickenEnt instanceof Chicken) {
                // In old Bukkit, setPassenger exists on Entity
                chickenEnt.setPassenger(zombieEnt);
            }
        }
    }

    @EventHandler(priority = Priority.Normal, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof Giant)) {
            return;
        }

        World world = entity.getWorld();
        if (isWorldBlocked(world)) {
            return;
        }

        List<ItemStack> drops = event.getDrops();
        drops.clear();

        if (dropMaterial != Material.AIR && dropAmount > 0) {
            drops.add(new ItemStack(dropMaterial, dropAmount));
        }
    }

    private boolean isWorldBlocked(World world) {
        if (world == null) return false;
        String name = world.getName();
        if (name == null) return false;
        return blockedWorlds.contains(name.toLowerCase());
    }

    private boolean rollPercent(double chancePercent) {
        // random [0.0, 100.0)
        double roll = random.nextDouble() * 100.0;
        return roll < chancePercent;
    }

    private boolean hasSmallMobSpace(World world, int baseX, int baseY, int baseZ) {
        // Require two air blocks (feet + head space) at the exact column
        // (typeId 0 = air)
        if (world.getBlockTypeIdAt(baseX, baseY, baseZ) != 0) return false;
        if (world.getBlockTypeIdAt(baseX, baseY + 1, baseZ) != 0) return false;
        return true;
    }

    private boolean hasGiantSpace(World world, int baseX, int baseY, int baseZ) {
        // Require 12 blocks of vertical clearance and a 3x3 footprint
        int requiredHeight = 12;

        for (int yOffset = 0; yOffset < requiredHeight; yOffset++) {
            int y = baseY + yOffset;

            for (int xOffset = -1; xOffset <= 1; xOffset++) {
                for (int zOffset = -1; zOffset <= 1; zOffset++) {
                    int typeId = world.getBlockTypeIdAt(baseX + xOffset, y, baseZ + zOffset);
                    if (typeId != 0) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private boolean getBooleanCompat(String path, boolean defaultValue) {
        Object value = config.getProperty(path);
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return Boolean.valueOf(String.valueOf(value)).booleanValue();
    }

    private double getDoubleCompat(String path, double defaultValue) {
        Object value = config.getProperty(path);
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
