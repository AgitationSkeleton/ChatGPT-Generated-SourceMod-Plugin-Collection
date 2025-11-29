import java.util.List;
import java.util.Random;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.PigZombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class RareDrops extends JavaPlugin implements Listener {

    private final Random random = new Random();

    // 2.5% rare drop chance, similar to modern Minecraft
    private static final double RARE_DROP_CHANCE = 0.025D;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        System.out.println("[RareDrops] Enabled. Backporting rare mob drops.");
    }

    @Override
    public void onDisable() {
        System.out.println("[RareDrops] Disabled.");
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        if (!(entity instanceof LivingEntity)) {
            return;
        }

        LivingEntity deadEntity = (LivingEntity) entity;

        // Old API: no getKiller(), so we check the last damage cause
        if (!wasKilledByPlayer(deadEntity)) {
            return;
        }

        List<ItemStack> drops = event.getDrops();

        if (entity instanceof Skeleton) {
            handleSkeletonDrop(drops);
        } else if (entity instanceof Zombie) {
            handleZombieDrop(drops);
        } else if (entity instanceof PigZombie) {
            handlePigZombieDrop(drops);
        }
    }

    private boolean wasKilledByPlayer(LivingEntity deadEntity) {
        EntityDamageEvent lastDamage = deadEntity.getLastDamageCause();
        if (!(lastDamage instanceof EntityDamageByEntityEvent)) {
            return false;
        }

        EntityDamageByEntityEvent damageEvent = (EntityDamageByEntityEvent) lastDamage;
        Entity damager = damageEvent.getDamager();

        return (damager instanceof Player);
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

        // Pool of modern-style rare drops using items that existed in Beta:
        // - Iron ingot
        // - Iron shovel
        // - Iron sword
        // - Full set of chainmail armor pieces (so they're obtainable)
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
