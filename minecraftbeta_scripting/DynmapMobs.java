package com.redchanit.dynmapmobs;

import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Squid;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Giant;
import org.bukkit.entity.Monster;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import org.dynmap.DynmapAPI;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

public class DynmapMobs extends JavaPlugin {

    private Logger logger = Logger.getLogger("Minecraft");

    private DynmapAPI dynmapApi;
    private MarkerAPI markerApi;
    private MarkerSet mobMarkerSet;

    // Map from entityId -> Marker
    private final Map<Integer, Marker> mobMarkers = new HashMap<Integer, Marker>();

    // Map from mob key -> MarkerIcon id
    private final Map<String, String> mobIconIds = new HashMap<String, String>();

    private int taskId = -1;

    private static final String MARKER_SET_ID = "dynmap_mobs.markerset";
    private static final String MARKER_SET_LABEL = "Mobs";

    @Override
    public void onEnable() {
        PluginManager pm = getServer().getPluginManager();
        Plugin dynmapPlugin = pm.getPlugin("dynmap");

        if (dynmapPlugin == null || !(dynmapPlugin instanceof DynmapAPI)) {
            logger.log(Level.SEVERE, "[DynmapMobs] dynmap plugin not found or incompatible, disabling.");
            pm.disablePlugin(this);
            return;
        }

        dynmapApi = (DynmapAPI) dynmapPlugin;
        markerApi = dynmapApi.getMarkerAPI();

        if (markerApi == null) {
            logger.log(Level.SEVERE, "[DynmapMobs] Dynmap MarkerAPI not available, disabling.");
            pm.disablePlugin(this);
            return;
        }

        // Create (or get) the marker set
        mobMarkerSet = markerApi.getMarkerSet(MARKER_SET_ID);
        if (mobMarkerSet == null) {
            mobMarkerSet = markerApi.createMarkerSet(MARKER_SET_ID, MARKER_SET_LABEL, null, false);
        }
        if (mobMarkerSet == null) {
            logger.log(Level.SEVERE, "[DynmapMobs] Failed to create marker set, disabling.");
            pm.disablePlugin(this);
            return;
        }

        mobMarkerSet.setLayerPriority(10);
        mobMarkerSet.setHideByDefault(false);

        // Register marker icons using Minecraft Wiki sprites
        registerMobIcons();

        // Schedule periodic mob scan
        // Delay 20 ticks (1 second), repeat every 100 ticks (5 seconds)
        taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                try {
                    updateMobMarkers();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "[DynmapMobs] Exception in updateMobMarkers", t);
                }
            }
        }, 20L, 100L);

        logger.info("[DynmapMobs] Enabled.");
    }

    @Override
    public void onDisable() {
        // Cancel task
        if (taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        // Remove markers
        Iterator<Map.Entry<Integer, Marker>> it = mobMarkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Marker> entry = it.next();
            Marker m = entry.getValue();
            if (m != null) {
                m.deleteMarker();
            }
            it.remove();
        }

        logger.info("[DynmapMobs] Disabled.");
    }

    private void registerMobIcons() {
        // Standard template:
        // https://minecraft.wiki/images/EntitySprite_MOBNAMEHERE.png
        //
        // Special cases:
        // Monster -> Herobrine face:
        //   https://minecraft.wiki/images/CharacterSprite_herobrine.png
        // Giant -> use zombie face (reuse zombie icon)
        // Zombie Pigman -> zombified piglin:
        //   https://minecraft.wiki/images/EntitySprite_zombified-piglin.png

        // Hostile mobs
        registerIconForMobKey("CREEPER", "Creeper", "creeper", null);
        registerIconForMobKey("SKELETON", "Skeleton", "skeleton", null);
        registerIconForMobKey("SPIDER", "Spider", "spider", null);
        registerIconForMobKey("ZOMBIE", "Zombie", "zombie", null);
        registerIconForMobKey("SLIME", "Slime", "slime", null);
        registerIconForMobKey("GHAST", "Ghast", "ghast", null);

        // Zombie Pigman (PigZombie class in Beta)
        registerIconForMobKey("PIG_ZOMBIE", "Zombie Pigman", null,
                "https://minecraft.wiki/images/EntitySprite_zombified-piglin.png");

        // "Monster" placeholder (Herobrine face)
        registerIconOverride("MONSTER", "Monster",
                "https://minecraft.wiki/images/CharacterSprite_herobrine.png");

        // Passive mobs
        registerIconForMobKey("PIG", "Pig", "pig", null);
        registerIconForMobKey("SHEEP", "Sheep", "sheep", null);
        registerIconForMobKey("COW", "Cow", "cow", null);
        registerIconForMobKey("CHICKEN", "Chicken", "chicken", null);
        registerIconForMobKey("SQUID", "Squid", "squid", null);
        registerIconForMobKey("WOLF", "Wolf", "wolf", null);

        // Giant: reuse zombie icon
        String zombieIconId = mobIconIds.get("ZOMBIE");
        if (zombieIconId != null) {
            mobIconIds.put("GIANT", zombieIconId);
        } else {
            // If zombie icon somehow failed, try to register a separate one for GIANT
            registerIconForMobKey("GIANT", "Giant", "zombie", null);
        }
    }

    /**
     * Register an icon for a mob key, using the EntitySprite_MOBNAMEHERE.png template.
     *
     * @param mobKey      Internal key for this mob, e.g. "CREEPER"
     * @param label       Human-readable label
     * @param wikiName    Name for the wiki sprite, e.g. "creeper". If null, overrideUrl must be non-null.
     * @param overrideUrl Optional full URL, used instead of template if non-null
     */
    private void registerIconForMobKey(String mobKey, String label, String wikiName, String overrideUrl) {
        String iconId = "dynmap_mobs." + mobKey.toLowerCase();
        String url;

        if (overrideUrl != null) {
            url = overrideUrl;
        } else {
            if (wikiName == null) {
                return;
            }
            url = "https://minecraft.wiki/images/EntitySprite_" + wikiName + ".png";
        }

        MarkerIcon icon = createIconFromUrl(iconId, label, url);
        if (icon != null) {
            mobIconIds.put(mobKey, iconId);
        }
    }

    /**
     * Register an icon using a direct URL (no template).
     */
    private void registerIconOverride(String mobKey, String label, String url) {
        String iconId = "dynmap_mobs." + mobKey.toLowerCase();
        MarkerIcon icon = createIconFromUrl(iconId, label, url);
        if (icon != null) {
            mobIconIds.put(mobKey, iconId);
        }
    }

    /**
     * Create a MarkerIcon using an image fetched from a URL.
     */
    private MarkerIcon createIconFromUrl(String iconId, String label, String url) {
        if (markerApi == null) {
            return null;
        }

        // If it already exists, reuse it
        MarkerIcon existing = markerApi.getMarkerIcon(iconId);
        if (existing != null) {
            return existing;
        }

        InputStream in = null;
        try {
            URL u = new URL(url);
            in = u.openStream();
            MarkerIcon icon = markerApi.createMarkerIcon(iconId, label, in);
            if (icon != null) {
                logger.info("[DynmapMobs] Registered icon " + iconId + " from " + url);
            }
            return icon;
        } catch (Throwable t) {
            logger.log(Level.WARNING, "[DynmapMobs] Failed to load icon from " + url + " for " + iconId, t);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Periodically scan all worlds for mobs and update markers.
     */
    private void updateMobMarkers() {
        if (mobMarkerSet == null) {
            return;
        }

        Set<Integer> seenEntityIds = new HashSet<Integer>();

        for (World world : getServer().getWorlds()) {
            String worldName = world.getName();

            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof LivingEntity)) {
                    continue;
                }

                if (entity instanceof Player) {
                    continue; // do not show players
                }

                String mobKey = getMobKey(entity);
                if (mobKey == null) {
                    continue;
                }

                int entityId = entity.getEntityId();
                seenEntityIds.add(entityId);

                String iconId = getIconIdForMobKey(mobKey);

                MarkerIcon icon = null;
                if (iconId != null && markerApi != null) {
                    icon = markerApi.getMarkerIcon(iconId);
                }

                if (icon == null && markerApi != null) {
                    // Fallback: try default icon
                    icon = markerApi.getMarkerIcon("default");
                }

                Location loc = entity.getLocation();
                double x = loc.getX();
                double y = loc.getY();
                double z = loc.getZ();

                Marker marker = mobMarkers.get(entityId);
                if (marker == null) {
                    // Create new marker
                    String markerId = "dynmap_mobs.entity_" + entityId;
                    String label = getLabelForMobKey(mobKey);

                    marker = mobMarkerSet.createMarker(markerId, label, worldName, x, y, z, icon, false);
                    mobMarkers.put(entityId, marker);
                } else {
                    // Update existing marker position
                    marker.setLocation(worldName, x, y, z);
                }
            }
        }

        // Remove markers for entities no longer present
        Iterator<Map.Entry<Integer, Marker>> it = mobMarkers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Marker> entry = it.next();
            int entityId = entry.getKey();
            if (!seenEntityIds.contains(entityId)) {
                Marker marker = entry.getValue();
                if (marker != null) {
                    marker.deleteMarker();
                }
                it.remove();
            }
        }
    }

    /**
     * Map a Bukkit entity to our internal mob key string.
     */
    private String getMobKey(Entity entity) {
        if (entity instanceof Creeper) {
            return "CREEPER";
        }
        if (entity instanceof Skeleton) {
            return "SKELETON";
        }
        if (entity instanceof Spider) {
            return "SPIDER";
        }
        if (entity instanceof Zombie) {
            return "ZOMBIE";
        }
        if (entity instanceof Slime) {
            return "SLIME";
        }
        if (entity instanceof Ghast) {
            return "GHAST";
        }
        if (entity instanceof PigZombie) {
            return "PIG_ZOMBIE";
        }
        if (entity instanceof Pig) {
            return "PIG";
        }
        if (entity instanceof Sheep) {
            return "SHEEP";
        }
        if (entity instanceof Cow) {
            return "COW";
        }
        if (entity instanceof Chicken) {
            return "CHICKEN";
        }
        if (entity instanceof Squid) {
            return "SQUID";
        }
        if (entity instanceof Wolf) {
            return "WOLF";
        }
        if (entity instanceof Giant) {
            return "GIANT";
        }
        if (entity instanceof Monster) {
            // Catch-all / generic Monster type – use Herobrine sprite
            return "MONSTER";
        }

        // Not tracked
        return null;
    }

    private String getIconIdForMobKey(String mobKey) {
        if (mobKey == null) {
            return null;
        }
        String iconId = mobIconIds.get(mobKey);
        if (iconId != null) {
            return iconId;
        }

        // Fallback: try uppercase key
        return mobIconIds.get(mobKey.toUpperCase());
    }

    private String getLabelForMobKey(String mobKey) {
        if (mobKey == null) {
            return "Mob";
        }

        if ("PIG_ZOMBIE".equalsIgnoreCase(mobKey)) {
            return "Zombie Pigman";
        }
        if ("MONSTER".equalsIgnoreCase(mobKey)) {
            return "Monster";
        }

        // Default: turn "CREEPER" into "Creeper", "GIANT" into "Giant", etc.
        String lower = mobKey.toLowerCase().replace('_', ' ');
        if (lower.length() == 0) {
            return "Mob";
        }
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
