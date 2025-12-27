package com.redchanit.nocooldown;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;

public class NoCooldown extends JavaPlugin implements Listener {

    // Config values
    private double attackSpeedValue = 1024.0;
    private boolean maintainEnabled = true;
    private long maintainIntervalTicks = 200L;

    private BukkitTask maintainTask = null;

    @Override
    public void onEnable() {
        ensureConfigFileExists();
        reloadPluginConfig();

        Bukkit.getPluginManager().registerEvents(this, this);

        // Apply to already-online players (e.g., /reload)
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            applyAttackSpeedNextTick(onlinePlayer);
        }

        startOrStopMaintainTask();
        getLogger().info("NoCooldown enabled. AttackSpeed=" + attackSpeedValue);
    }

    @Override
    public void onDisable() {
        if (maintainTask != null) {
            maintainTask.cancel();
            maintainTask = null;
        }
    }

    private void ensureConfigFileExists() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("attackSpeed", 1024.0);
            cfg.set("maintain.enabled", true);
            cfg.set("maintain.intervalTicks", 200);

            try {
                cfg.save(configFile);
            } catch (IOException e) {
                getLogger().severe("Failed to create config.yml: " + e.getMessage());
            }
        }
    }

    private void reloadPluginConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);

        attackSpeedValue = cfg.getDouble("attackSpeed", 1024.0);
        maintainEnabled = cfg.getBoolean("maintain.enabled", true);
        maintainIntervalTicks = cfg.getLong("maintain.intervalTicks", 200L);

        if (attackSpeedValue < 0.0) attackSpeedValue = 0.0;
        if (maintainIntervalTicks < 1L) maintainIntervalTicks = 1L;
    }

    private void startOrStopMaintainTask() {
        if (maintainTask != null) {
            maintainTask.cancel();
            maintainTask = null;
        }

        if (!maintainEnabled) return;

        maintainTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                        applyAttackSpeedNow(onlinePlayer, false);
                    }
                },
                maintainIntervalTicks,
                maintainIntervalTicks
        );
    }

    private void applyAttackSpeedNextTick(Player player) {
        if (player == null) return;
        Bukkit.getScheduler().runTask(this, () -> applyAttackSpeedNow(player, true));
    }

    private void applyAttackSpeedNow(Player player, boolean logIfMissingAttribute) {
        AttributeInstance attr = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) {
            if (logIfMissingAttribute) {
                getLogger().warning("Player has no GENERIC_ATTACK_SPEED attribute: " + player.getName());
            }
            return;
        }

        double currentBase = attr.getBaseValue();
        if (Double.compare(currentBase, attackSpeedValue) != 0) {
            attr.setBaseValue(attackSpeedValue);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        applyAttackSpeedNextTick(event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Ensure it applies after respawn is finalized
        applyAttackSpeedNextTick(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        applyAttackSpeedNextTick(event.getPlayer());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("nocooldown")) return false;

        if (!sender.hasPermission("nocooldown.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("Usage:");
            sender.sendMessage("/nocooldown reload");
            sender.sendMessage("/nocooldown set <attackSpeed>");
            sender.sendMessage("/nocooldown apply");
            sender.sendMessage("Current attackSpeed=" + attackSpeedValue);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload")) {
            reloadPluginConfig();
            startOrStopMaintainTask();

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                applyAttackSpeedNextTick(onlinePlayer);
            }

            sender.sendMessage("NoCooldown reloaded. attackSpeed=" + attackSpeedValue
                    + ", maintain.enabled=" + maintainEnabled
                    + ", maintain.intervalTicks=" + maintainIntervalTicks);
            return true;
        }

        if (sub.equals("set")) {
            if (args.length < 2) {
                sender.sendMessage("Usage: /nocooldown set <attackSpeed>");
                return true;
            }

            double newValue;
            try {
                newValue = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("Invalid number: " + args[1]);
                return true;
            }

            if (newValue < 0.0) newValue = 0.0;
            attackSpeedValue = newValue;

            // Persist immediately
            File configFile = new File(getDataFolder(), "config.yml");
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            cfg.set("attackSpeed", attackSpeedValue);
            try {
                cfg.save(configFile);
            } catch (IOException e) {
                sender.sendMessage("Failed to save config.yml: " + e.getMessage());
                return true;
            }

            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                applyAttackSpeedNextTick(onlinePlayer);
            }

            sender.sendMessage("Set attackSpeed=" + attackSpeedValue + " and applied to online players.");
            return true;
        }

        if (sub.equals("apply")) {
            if (sender instanceof Player) {
                applyAttackSpeedNextTick((Player) sender);
                sender.sendMessage("Applied attackSpeed=" + attackSpeedValue + " to you.");
            } else {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    applyAttackSpeedNextTick(onlinePlayer);
                }
                sender.sendMessage("Applied attackSpeed=" + attackSpeedValue + " to all online players.");
            }
            return true;
        }

        sender.sendMessage("Unknown subcommand. Use /nocooldown");
        return true;
    }
}
