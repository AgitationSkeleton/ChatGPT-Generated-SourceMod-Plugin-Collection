package com.redchanit.playerheaddrop;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class PlayerHeadDrop extends JavaPlugin implements Listener {

    private static final String CONFIG_IGNORE_CITIZENS_NPCS = "ignoreCitizensNPCs";

    private File configFile;
    private YamlConfiguration configYaml;

    @Override
    public void onEnable() {
        ensureConfigFileExists();
        reloadLocalConfig();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PlayerHeadDrop enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("PlayerHeadDrop disabled.");
    }

    private void ensureConfigFileExists() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }

        configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) {
            return;
        }

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set(CONFIG_IGNORE_CITIZENS_NPCS, true);

        try {
            defaults.save(configFile);
        } catch (IOException ioException) {
            getLogger().severe("Failed to create config.yml: " + ioException.getMessage());
        }
    }

    private void reloadLocalConfig() {
        if (configFile == null) {
            configFile = new File(getDataFolder(), "config.yml");
        }
        configYaml = YamlConfiguration.loadConfiguration(configFile);
    }

    private boolean shouldIgnoreCitizensNPCs() {
        return configYaml != null && configYaml.getBoolean(CONFIG_IGNORE_CITIZENS_NPCS, true);
    }

    private boolean isCitizensNpcPlayer(Player player) {
        // Only check Citizens if installed; use reflection so Citizens is an optional dependency.
        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            return false;
        }

        try {
            Class<?> citizensApiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object npcRegistry = citizensApiClass.getMethod("getNPCRegistry").invoke(null);
            Object isNpcResult = npcRegistry.getClass().getMethod("isNPC", org.bukkit.entity.Entity.class).invoke(npcRegistry, player);
            return (isNpcResult instanceof Boolean) && (Boolean) isNpcResult;
        } catch (Throwable ignored) {
            // If Citizens is present but API changed or something failed, default to not treating as NPC.
            return false;
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victimPlayer = event.getEntity();

        // If configured to ignore Citizens NPCs, only drop for connected real players (and not NPCs).
        if (shouldIgnoreCitizensNPCs()) {
            if (!victimPlayer.isOnline()) {
                return;
            }
            if (isCitizensNpcPlayer(victimPlayer)) {
                return;
            }
        }

        World world = victimPlayer.getWorld();

        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta skullMeta = (SkullMeta) headItem.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(victimPlayer);
            skullMeta.setDisplayName(victimPlayer.getName() + "'s Head");
            headItem.setItemMeta(skullMeta);
        }

        world.dropItemNaturally(victimPlayer.getLocation(), headItem);
    }
}
