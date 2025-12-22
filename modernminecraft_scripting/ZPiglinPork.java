package com.redchanit.zpiglinpork;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

public final class ZPiglinPork extends JavaPlugin implements Listener {

    private static final String CONFIG_FILE_NAME = "config.yml";

    private enum Mode {
        REPLACE,
        ADD
    }

    private Mode dropMode = Mode.REPLACE;

    @Override
    public void onEnable() {
        ensureConfigFileExists();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Enabled. Mode=" + dropMode);
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabled.");
    }

    private void ensureConfigFileExists() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        File configFile = new File(getDataFolder(), CONFIG_FILE_NAME);
        if (configFile.exists()) {
            return;
        }

        YamlConfiguration defaultConfig = new YamlConfiguration();
        defaultConfig.set("mode", "REPLACE"); // REPLACE or ADD
        defaultConfig.set("onlyZombifiedPiglin", true); // kept for future-proofing / clarity

        try {
            defaultConfig.save(configFile);
        } catch (IOException exception) {
            getLogger().severe("Failed to create default config.yml: " + exception.getMessage());
        }
    }

    private void loadSettings() {
        File configFile = new File(getDataFolder(), CONFIG_FILE_NAME);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        String modeString = config.getString("mode", "REPLACE");
        dropMode = parseMode(modeString);
    }

    private Mode parseMode(String modeString) {
        if (modeString == null) {
            return Mode.REPLACE;
        }
        try {
            return Mode.valueOf(modeString.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Invalid config value for mode: " + modeString + " (using REPLACE)");
            return Mode.REPLACE;
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntityType() != EntityType.ZOMBIFIED_PIGLIN) {
            return;
        }

        List<ItemStack> drops = event.getDrops();
        if (drops == null || drops.isEmpty()) {
            return;
        }

        int totalRottenFleshAmount = 0;

        // Collect rotten flesh amount (and optionally remove it)
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack dropStack = iterator.next();
            if (dropStack == null) {
                continue;
            }
            if (dropStack.getType() == Material.ROTTEN_FLESH) {
                totalRottenFleshAmount += Math.max(0, dropStack.getAmount());

                if (dropMode == Mode.REPLACE) {
                    iterator.remove();
                }
            }
        }

        if (totalRottenFleshAmount <= 0) {
            return;
        }

        // Add cooked porkchop matching the rotten flesh amount (same frequency -> same computed amount)
        ItemStack porkStack = new ItemStack(Material.COOKED_PORKCHOP, totalRottenFleshAmount);
        drops.add(porkStack);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("zpiglinpork")) {
            return false;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("zpiglinpork.reload")) {
                sender.sendMessage("You do not have permission to do that.");
                return true;
            }

            loadSettings();
            sender.sendMessage("ZPiglinPork reloaded. Mode=" + dropMode);
            return true;
        }

        sender.sendMessage("Usage: /zpiglinpork reload");
        return true;
    }
}
