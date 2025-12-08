import java.io.File;
import java.util.List;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;

public class RareDrops extends JavaPlugin implements Listener {

    private final Random random = new Random();

    // 2.5% rare drop chance, similar to modern Minecraft
    private static final double RARE_DROP_CHANCE = 0.025D;

    // Config
    private Configuration config;
    private boolean requirePlayerKill = true;

    @Override
    public void onEnable() {
        loadConfiguration();
        getServer().getPluginManager().registerEvents(this, this);
        System.out.println("[RareDrops] Enabled. Backporting rare mob drops. require-player-kill = " + requirePlayerKill);
    }

    @Override
    public void onDisable() {
        System.out.println("[RareDrops] Disabled.");
    }

    private void loadConfiguration() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        config = new Configuration(configFile);
        config.load();

        // Read or set default
        requirePlayerKill = config.getBoolean("require-player-kill", true);
        config.setProperty("require-player-kill", requirePlayerKill);

        config.save();
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof LivingEntity)) {
            return;
        }

        LivingEntity deadEntity = (LivingEntity) entity;

        // If configured, only count kills directly caused by a player or their arrow
        if (requirePlayerKill && !wasKilledByPlayer(deadEntity)) {
            return;
        }

        List<ItemStack> drops = event.getDrops();

        // Order matters: PigZombie extends Zombie, so check PigZombie first.
        if (entity instanceof Skeleton) {
            handleSkeletonDrop(drops);
        } else if (entity instanceof PigZombie) {
            handlePigZombieDrop(drops);
        } else if (entity instanceof Zombie) {
            handleZombieDrop(drops);
        }
    }

    private boolean wasKilledByPlayer(LivingEntity deadEntity) {
        EntityDamageEvent lastDamage = deadEntity.getLastDamageCause();
        if (!(lastDamage instanceof EntityDamageByEntityEvent)) {
            return false;
        }

        EntityDamageByEntityEvent damageEvent = (EntityDamageByEntityEvent) lastDamage;
        Entity damager = damageEvent.getDamager();

        // Direct melee hit by player
        if (damager instanceof Player) {
            return true;
        }

        // Arrow shot by player
        if (damager instanceof Arrow) {
            Arrow arrow = (Arrow) damager;
            LivingEntity shooter = arrow.getShooter();
            if (shooter instanceof Player) {
                return true;
            }
        }

        return false;
    }

    private void handleSkeletonDrop(List<ItemStack> drops) {
        if (!rollRareDrop()) {
            return;
        }

        // Bow with random durability
        ItemStack bow = createItemWithRandomDurability(Material.BOW);
        drops.add(bow);
    }

    private void handleZombieDrop(List<ItemStack> drops) {
        if (!rollRareDrop()) {
            return;
        }

        // Pool:
        // - Iron ingot
        // - Iron shovel
        // - Iron sword
        // - Chainmail armor pieces (helmet, chestplate, leggings, boots)
        int choice = random.nextInt(7); // 0..6

        ItemStack rareDrop;

        switch (choice) {
            case 0:
                // Iron ingot (no durability)
                rareDrop = new ItemStack(Material.IRON_INGOT, 1);
                break;
            case 1:
                // Iron shovel with random durability
                rareDrop = createItemWithRandomDurability(Material.IRON_SPADE);
                break;
            case 2:
                // Iron sword with random durability
                rareDrop = createItemWithRandomDurability(Material.IRON_SWORD);
                break;
            case 3:
                rareDrop = createItemWithRandomDurability(Material.CHAINMAIL_HELMET);
                break;
            case 4:
                rareDrop = createItemWithRandomDurability(Material.CHAINMAIL_CHESTPLATE);
                break;
            case 5:
                rareDrop = createItemWithRandomDurability(Material.CHAINMAIL_LEGGINGS);
                break;
            case 6:
            default:
                rareDrop = createItemWithRandomDurability(Material.CHAINMAIL_BOOTS);
                break;
        }

        drops.add(rareDrop);
    }

    private void handlePigZombieDrop(List<ItemStack> drops) {
        if (!rollRareDrop()) {
            return;
        }

        // Pool:
        // - Gold ingot
        // - Gold sword (random durability)
        // - Gold helmet (random durability)
        int choice = random.nextInt(3); // 0..2

        ItemStack rareDrop;

        switch (choice) {
            case 0:
                // Gold ingot (no durability)
                rareDrop = new ItemStack(Material.GOLD_INGOT, 1);
                break;
            case 1:
                // Gold sword with random durability
                rareDrop = createItemWithRandomDurability(Material.GOLD_SWORD);
                break;
            case 2:
            default:
                // Gold helmet with random durability
                rareDrop = createItemWithRandomDurability(Material.GOLD_HELMET);
                break;
        }

        drops.add(rareDrop);
    }

    private boolean rollRareDrop() {
        return random.nextDouble() < RARE_DROP_CHANCE;
    }

    private ItemStack createItemWithRandomDurability(Material material) {
        short maxDurability = material.getMaxDurability();

        // If the item has no durability (e.g., ingots), just return a normal stack
        if (maxDurability <= 0) {
            return new ItemStack(material, 1);
        }

        // In Bukkit, durability is "damage taken":
        // 0 = brand new, maxDurability-1 = almost broken
        int damage = random.nextInt(maxDurability); // 0 .. maxDurability-1

        ItemStack item = new ItemStack(material, 1);
        item.setDurability((short) damage);
        return item;
    }
}
