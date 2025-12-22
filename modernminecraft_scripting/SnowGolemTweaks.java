package com.redchanit.snowgolemtweaks;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Snowman;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

public class SnowGolemTweaks extends JavaPlugin implements Listener {

    private File configFile;
    private FileConfiguration config;

    // Config values (cached)
    private boolean preventPlayerDamageToSnowGolems;
    private boolean preventPrecipitationDamage;
    private boolean preventClimateDamage;
    private boolean buffSnowGolemSnowballs;
    private double snowballDamageToHostiles;
    private boolean disableSnowTrail;

    // Damage causes we treat as precipitation/water-related or climate-related.
    // (Minecraft internals can vary; these cover the typical cases seen in Bukkit/Spigot events.)
    private static final Set<EntityDamageEvent.DamageCause> PRECIPITATION_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.CONTACT
    );

    private static final Set<EntityDamageEvent.DamageCause> CLIMATE_CAUSES = EnumSet.of(
            EntityDamageEvent.DamageCause.FIRE,
            EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.LAVA
    );

    @Override
    public void onEnable() {
        ensureConfigExists();
        loadConfigValues();

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("SnowGolemTweaks enabled.");
    }

    private void ensureConfigExists() {
        if (!getDataFolder().exists()) {
            boolean madeDir = getDataFolder().mkdirs();
            if (!madeDir) {
                getLogger().warning("Could not create plugin data folder: " + getDataFolder().getAbsolutePath());
            }
        }

        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            YamlConfiguration defaultConfig = new YamlConfiguration();

            defaultConfig.set("snowGolems.preventPlayerDamage", true);
            defaultConfig.set("snowGolems.preventPrecipitationDamage", true);
            defaultConfig.set("snowGolems.preventClimateDamage", true);

            defaultConfig.set("snowGolems.snowballs.buffEnabled", true);
            defaultConfig.set("snowGolems.snowballs.damageToHostileMobs", 1.0); // 1.0 = half-heart

            defaultConfig.set("snowGolems.disableSnowTrail", true);

            defaultConfig.set("debug.logCancellations", false);

            try {
                defaultConfig.save(configFile);
            } catch (IOException ioException) {
                getLogger().severe("Failed to create default config.yml: " + ioException.getMessage());
            }
        }

        config = YamlConfiguration.loadConfiguration(configFile);
    }

    private void loadConfigValues() {
        config = YamlConfiguration.loadConfiguration(configFile);

        preventPlayerDamageToSnowGolems = config.getBoolean("snowGolems.preventPlayerDamage", true);
        preventPrecipitationDamage = config.getBoolean("snowGolems.preventPrecipitationDamage", true);
        preventClimateDamage = config.getBoolean("snowGolems.preventClimateDamage", true);

        buffSnowGolemSnowballs = config.getBoolean("snowGolems.snowballs.buffEnabled", true);
        snowballDamageToHostiles = config.getDouble("snowGolems.snowballs.damageToHostileMobs", 1.0);

        disableSnowTrail = config.getBoolean("snowGolems.disableSnowTrail", true);
    }

    private boolean isDebugEnabled() {
        return config.getBoolean("debug.logCancellations", false);
    }

    @EventHandler
    public void onSnowGolemDamagedByPlayerOrPlayerProjectile(EntityDamageByEntityEvent event) {
        if (!preventPlayerDamageToSnowGolems) {
            return;
        }

        Entity victim = event.getEntity();
        if (victim.getType() != EntityType.SNOW_GOLEM) {
            return;
        }

        Entity damager = event.getDamager();

        // Direct melee damage
        if (damager instanceof Player) {
            event.setCancelled(true);
            if (isDebugEnabled()) {
                getLogger().info("Cancelled player melee damage to Snow Golem.");
            }
            return;
        }

        // Ranged/indirect: projectile shot by player (arrow, trident, snowball, etc.)
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player) {
                event.setCancelled(true);
                if (isDebugEnabled()) {
                    getLogger().info("Cancelled player projectile damage to Snow Golem. Projectile=" + damager.getType());
                }
            }
        }
    }

    @EventHandler
    public void onSnowGolemEnvironmentalDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (victim.getType() != EntityType.SNOW_GOLEM) {
            return;
        }

        EntityDamageEvent.DamageCause cause = event.getCause();

        // Precipitation / water-related
        if (preventPrecipitationDamage && PRECIPITATION_CAUSES.contains(cause)) {
            // If it's raining/storming at the golem's location (common case: rain damage),
            // cancel; otherwise allow (could be actual drowning / other contact).
            if (isLikelyPrecipitationDamage((Snowman) victim, cause)) {
                event.setCancelled(true);
                if (isDebugEnabled()) {
                    getLogger().info("Cancelled precipitation-related damage to Snow Golem. Cause=" + cause);
                }
                return;
            }
        }

        // Climate / heat-related (best-effort via causes)
        if (preventClimateDamage && CLIMATE_CAUSES.contains(cause)) {
            event.setCancelled(true);
            if (isDebugEnabled()) {
                getLogger().info("Cancelled climate-related damage to Snow Golem. Cause=" + cause);
            }
        }
    }

    private boolean isLikelyPrecipitationDamage(Snowman snowman, EntityDamageEvent.DamageCause cause) {
        World world = snowman.getWorld();
        if (!world.hasStorm()) {
            // Not raining; probably not precipitation damage.
            // If you want to *always* cancel DROWNING/CONTACT for snow golems, you can,
            // but this keeps it closer to "rain/precipitation".
            return false;
        }

        // If the golem is exposed to sky (raining directly on it), treat as precipitation.
        // This doesn't cover every edge case but aligns with "rain/precipitation".
        Block at = snowman.getLocation().getBlock();
        int highestY = world.getHighestBlockYAt(at.getX(), at.getZ());
        boolean hasSkyAccess = snowman.getLocation().getY() >= highestY;

        // Some servers use thunder storms; treat both the same.
        return hasSkyAccess && (cause == EntityDamageEvent.DamageCause.DROWNING || cause == EntityDamageEvent.DamageCause.CONTACT);
    }

    @EventHandler
    public void onSnowballHitHostile(EntityDamageByEntityEvent event) {
        if (!buffSnowGolemSnowballs) {
            return;
        }

        if (!(event.getDamager() instanceof Snowball snowball)) {
            return;
        }

        ProjectileSource shooter = snowball.getShooter();
        if (!(shooter instanceof Snowman)) {
            return;
        }

        Entity victim = event.getEntity();
        if (!(victim instanceof Monster)) {
            return; // Only hostile mobs
        }

        // Apply configured damage
        if (snowballDamageToHostiles < 0.0) {
            snowballDamageToHostiles = 0.0;
        }

        event.setDamage(snowballDamageToHostiles);
    }

    @EventHandler
    public void onSnowGolemSnowTrail(EntityBlockFormEvent event) {
        if (!disableSnowTrail) {
            return;
        }

        Entity entity = event.getEntity();
        if (entity.getType() != EntityType.SNOW_GOLEM) {
            return;
        }

        // Snow golems typically form SNOW (snow layer) blocks.
        // Cancel any block forming they do to avoid trails.
        Material newType = event.getNewState().getType();
        if (newType == Material.SNOW) {
            event.setCancelled(true);
            if (isDebugEnabled()) {
                getLogger().info("Cancelled Snow Golem snow trail block form.");
            }
        }
    }

    // Optional: simple /sgt reload
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sgt")) {
            return false;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("snowgolemtweaks.reload")) {
                sender.sendMessage("You do not have permission to do that.");
                return true;
            }
            loadConfigValues();
            sender.sendMessage("SnowGolemTweaks config reloaded.");
            return true;
        }

        sender.sendMessage("Usage: /sgt reload");
        return true;
    }
}
