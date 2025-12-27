package com.redchanit.irongolemregen;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Snowman;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class IronGolemRegen extends JavaPlugin implements Listener {

    private final Map<UUID, Long> lastDamageMillisByEntity = new ConcurrentHashMap<>();

    private boolean enabled;

    private boolean healIronGolems;
    private boolean healSnowGolems;
    private boolean healVillagers;
    private boolean healWanderingTraders;

    private double healAmount;
    private long healIntervalTicks;

    private boolean requireOutOfCombat;
    private long outOfCombatMillis;

    private BukkitTask healTask;

    @Override
    public void onEnable() {
        ensureDefaultConfigExists();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);

        startHealTask();
        getLogger().info("IronGolemRegen enabled.");
    }

    @Override
    public void onDisable() {
        if (healTask != null) {
            healTask.cancel();
            healTask = null;
        }
        lastDamageMillisByEntity.clear();
        getLogger().info("IronGolemRegen disabled.");
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        LivingEntity living = (event.getEntity() instanceof LivingEntity) ? (LivingEntity) event.getEntity() : null;
        if (living == null) return;

        if (!(living instanceof IronGolem)
                && !(living instanceof Snowman)
                && !(living instanceof Villager)
                && !(living instanceof WanderingTrader)) {
            return;
        }

        lastDamageMillisByEntity.put(living.getUniqueId(), System.currentTimeMillis());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("irongolemregen")) return false;

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            startHealTask();
            sender.sendMessage("[IronGolemRegen] Reloaded config.");
            return true;
        }

        sender.sendMessage("[IronGolemRegen] Usage: /irongolemregen reload");
        return true;
    }

    private void startHealTask() {
        if (healTask != null) {
            healTask.cancel();
            healTask = null;
        }

        if (!enabled) {
            getLogger().info("Healing task not started (enabled=false).");
            return;
        }

        healTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();

            for (World world : Bukkit.getWorlds()) {

                if (healIronGolems) {
                    for (IronGolem entity : world.getEntitiesByClass(IronGolem.class)) {
                        healIfEligible(entity, now);
                    }
                }

                if (healSnowGolems) {
                    for (Snowman entity : world.getEntitiesByClass(Snowman.class)) {
                        healIfEligible(entity, now);
                    }
                }

                if (healVillagers) {
                    for (Villager entity : world.getEntitiesByClass(Villager.class)) {
                        healIfEligible(entity, now);
                    }
                }

                if (healWanderingTraders) {
                    for (WanderingTrader entity : world.getEntitiesByClass(WanderingTrader.class)) {
                        healIfEligible(entity, now);
                    }
                }
            }
        }, healIntervalTicks, healIntervalTicks);

        getLogger().info("Healing task started. IntervalTicks=" + healIntervalTicks
                + ", healAmount=" + healAmount
                + ", iron=" + healIronGolems
                + ", snow=" + healSnowGolems
                + ", villagers=" + healVillagers
                + ", traders=" + healWanderingTraders);
    }

    private void healIfEligible(LivingEntity living, long nowMillis) {
        if (living == null || living.isDead() || !living.isValid()) return;

        double maxHealth = getMaxHealth(living);
        if (maxHealth <= 0.0) return;

        double currentHealth = living.getHealth();
        if (currentHealth >= maxHealth) return;

        if (requireOutOfCombat) {
            Long lastHit = lastDamageMillisByEntity.get(living.getUniqueId());
            if (lastHit != null) {
                long elapsed = nowMillis - lastHit;
                if (elapsed < outOfCombatMillis) return;
            }
        }

        double newHealth = Math.min(maxHealth, currentHealth + healAmount);
        living.setHealth(newHealth);
    }

    private double getMaxHealth(LivingEntity living) {
        AttributeInstance attributeInstance = living.getAttribute(Attribute.MAX_HEALTH);
        if (attributeInstance != null) {
            return attributeInstance.getValue();
        }
        return living.getMaxHealth();
    }

    private void loadSettings() {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        this.enabled = config.getBoolean("enabled", true);

        this.healIronGolems = config.getBoolean("healIronGolems", true);
        this.healSnowGolems = config.getBoolean("healSnowGolems", true);
        this.healVillagers = config.getBoolean("healVillagers", true);
        this.healWanderingTraders = config.getBoolean("healWanderingTraders", false);

        this.healAmount = config.getDouble("healAmount", 1.0);
        if (this.healAmount <= 0.0) this.healAmount = 1.0;

        long seconds = config.getLong("healIntervalSeconds", 10L);
        if (seconds < 1L) seconds = 1L;
        this.healIntervalTicks = seconds * 20L;

        this.requireOutOfCombat = config.getBoolean("requireOutOfCombat", true);

        long outOfCombatSeconds = config.getLong("outOfCombatSeconds", 20L);
        if (outOfCombatSeconds < 0L) outOfCombatSeconds = 0L;
        this.outOfCombatMillis = outOfCombatSeconds * 1000L;
    }

    private void ensureDefaultConfigExists() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Failed to create plugin data folder: " + getDataFolder().getAbsolutePath());
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) return;

        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", true);

        config.set("healIronGolems", true);
        config.set("healSnowGolems", true);
        config.set("healVillagers", true);
        config.set("healWanderingTraders", false);

        // Heal amount in health points: 1.0 = half heart, 2.0 = full heart.
        config.set("healAmount", 1.0);

        // How often to attempt healing (seconds).
        config.set("healIntervalSeconds", 10);

        // If true, entity must not have taken damage recently to heal.
        config.set("requireOutOfCombat", true);

        // Seconds since last damage before healing is allowed.
        config.set("outOfCombatSeconds", 20);

        try {
            config.save(configFile);
        } catch (IOException e) {
            getLogger().severe("Failed to write default config.yml: " + e.getMessage());
        }
    }
}