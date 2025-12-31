/*
 * GridPrograms.java (Spigot 1.21.10)
 * Tron: Legacy / Uprising simulator for The_Grid using Citizens NPCs.
 *
 * Author: ChatGPT
 *
 * Notes:
 * - Single-file implementation scaffold (fits your drag-drop builder workflow).
 * - Focuses on: world gating, POI loading, MineSkin skin caching+upload, basic POI-driven spawning,
 *   VTF-style click-to-talk (stop, face player, resume), hunger disable, user Identity Disc issuance,
 *   disc throw/recall for players, and strict damage filtering (players only harm Orange).
 *
 * Required plugins:
 * - Citizens
 * - TronLegacyGen (for POI export file)
 *
 * World:
 * - Only runs in world named "The_Grid"
 */

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.ThreadLocalRandom;

public class GridPrograms extends JavaPlugin implements Listener {

    // ------------------------------------------------------------------------
    // Constants / Keys
    // ------------------------------------------------------------------------

    private static final String GRID_WORLD_NAME = "The_Grid";

    private static final String SKIN_BLUE_FILE = "blue.png";
    private static final String SKIN_WHITE_FILE = "white.png";
    private static final String SKIN_ORANGE_FILE = "orange.png";

    // Music discs (Identity Discs)
    private static final Material DISC_WHITE_USERS = Material.MUSIC_DISC_STRAD; // Users + White
    private static final Material DISC_BLUE = Material.MUSIC_DISC_WAIT;
    private static final Material DISC_ORANGE = Material.MUSIC_DISC_CHIRP;

    private static final String DISC_ISSUE_MESSAGE =
            "%s, you will receive an Identity Disc. Everything you do or learn will be imprinted on this disc. " +
            "If you lose your disc, or fail to follow commands, you will be subject to immediate deresolution.";

    private NamespacedKey KEY_MANAGED_PROGRAM;
    private NamespacedKey KEY_FACTION;
    private NamespacedKey KEY_POI_ID;
    private NamespacedKey KEY_PROGRAM_NAME;

    private NamespacedKey KEY_IDENTITY_DISC;
    private NamespacedKey KEY_DISC_OWNER;

    // ------------------------------------------------------------------------
    // Config / Files
    // ------------------------------------------------------------------------

    private File configFile;
    private YamlConfiguration config;

    private File skinsFolder;
    private File poiFile;
    private YamlConfiguration poiConfig;

    // ------------------------------------------------------------------------
    // Citizens
    // ------------------------------------------------------------------------

    private boolean citizensAvailable = false;
    private NPCRegistry npcRegistry;

    // ------------------------------------------------------------------------
    // Skin cache + MineSkin upload queue (permanent unless missing)
    // ------------------------------------------------------------------------

    private static class SkinData {
        final String value;
        final String signature;

        SkinData(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }

    private final Map<String, SkinData> skinCache = new ConcurrentHashMap<>();
    private final Object mineSkinRateLock = new Object();
    private volatile long nextMineSkinRequestAtMs = 0L;

    private final Map<String, RetryState> skinRetryState = new ConcurrentHashMap<>();

    private static class RetryState {
        long nextAttemptAtMs = 0L;
        int attempts = 0;
    }

    // ------------------------------------------------------------------------
    // POIs and population
    // ------------------------------------------------------------------------

    private enum Faction {
        BLUE,
        WHITE,
        ORANGE;

        public static Faction fromString(String s) {
            if (s == null) return BLUE;
            String t = s.trim().toUpperCase(Locale.ROOT);
            try {
                return Faction.valueOf(t);
            } catch (IllegalArgumentException ex) {
                return BLUE;
            }
        }
    }

    private static class Poi {
        final String id;
        final String world;
        final String type;
        final String district;
        final int x, y, z;
        final int radius;
        final Faction factionBias;

        Poi(String id, String world, String type, String district, int x, int y, int z, int radius, Faction factionBias) {
            this.id = id;
            this.world = world;
            this.type = type;
            this.district = district;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.factionBias = factionBias;
        }

    }

    private final List<Poi> pois = new ArrayList<>();
    private final Map<String, Set<Integer>> poiNpcIds = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------------
    // Dialogue / interaction (VTF-style)
    // ------------------------------------------------------------------------

    private static class ConversationState {
        long untilMs;
        Location resumeTarget; // optional
    }

    private final Map<Integer, ConversationState> npcConversation = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------------
    // Identity Disc flights (players)
    // ------------------------------------------------------------------------

    private static class DiscFlight {
        final UUID owner;
        final UUID displayEntityId;
        final long startedAtMs;
        boolean returning = false;
        boolean expediteRequested = false;
        int ticksLived = 0;

        // paramized flight
        final Vector origin;
        Vector currentPos;
        final Vector outboundDir;
        final double speedPerTick;
        final int maxTicks;
        final int outboundTicks;
        final double curveStrength;

        DiscFlight(UUID owner, UUID displayEntityId, Vector origin, Vector outboundDir,
                   double speedPerTick, int maxTicks, int outboundTicks, double curveStrength) {
            this.owner = owner;
            this.displayEntityId = displayEntityId;
            this.startedAtMs = System.currentTimeMillis();
            this.origin = origin.clone();
            this.currentPos = origin.clone();
            this.outboundDir = outboundDir.clone().normalize();
            this.speedPerTick = speedPerTick;
            this.maxTicks = maxTicks;
            this.outboundTicks = outboundTicks;
            this.curveStrength = curveStrength;
        }
    }

    private final Map<UUID, DiscFlight> activeDiscByOwner = new ConcurrentHashMap<>();
    private BukkitTask mainTickTask;

    // ------------------------------------------------------------------------
    // Enable / Disable
    // ------------------------------------------------------------------------

    @Override
    public void onEnable() {
        KEY_MANAGED_PROGRAM = new NamespacedKey(this, "grid_managed");
        KEY_FACTION = new NamespacedKey(this, "grid_faction");
        KEY_POI_ID = new NamespacedKey(this, "grid_poi_id");
        KEY_PROGRAM_NAME = new NamespacedKey(this, "grid_program_name");

        KEY_IDENTITY_DISC = new NamespacedKey(this, "identity_disc");
        KEY_DISC_OWNER = new NamespacedKey(this, "identity_disc_owner");

        ensureDataFolders();
        ensureConfigExists();
        loadConfigFile();

        // Citizens availability
        citizensAvailable = isCitizensPresent();
        if (!citizensAvailable) {
            getLogger().severe("Citizens not found. GridPrograms requires Citizens to run.");
        } else {
            npcRegistry = CitizensAPI.getNPCRegistry();
        }

        // POI file path (from TronLegacyGen)
        resolvePoiFile();
        loadPois();

        // Load cached skins (permanent unless missing)
        loadSkinCacheFromConfig();

        // Ensure required skin PNGs exist
        ensureSkinPngFiles();

        // Attempt skin uploads if cache missing
        scheduleSkinUploadIfNeeded("blue", new File(skinsFolder, SKIN_BLUE_FILE));
        scheduleSkinUploadIfNeeded("white", new File(skinsFolder, SKIN_WHITE_FILE));
        scheduleSkinUploadIfNeeded("orange", new File(skinsFolder, SKIN_ORANGE_FILE));

        Bukkit.getPluginManager().registerEvents(this, this);

        // Startup wake for already-existing Citizens NPCs (mirrors ViridianTownsFolk behavior).
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int woke = wakeAllPrograms("startup");
            getLogger().info("Startup wake: " + woke);
        }, 40L);

        // Citizens can spawn NPC entities later; do a couple late sweeps.
        Bukkit.getScheduler().runTaskLater(this, () -> wakeAllPrograms("startup-late"), 20L * 10L);
        Bukkit.getScheduler().runTaskLater(this, () -> wakeAllPrograms("startup-later"), 20L * 30L);

        // Main tick: population + wander + disc flights
        mainTickTask = Bukkit.getScheduler().runTaskTimer(this, this::tickMain, 20L, 2L);

        getLogger().info("GridPrograms enabled. World-locked to: " + GRID_WORLD_NAME);
    }

    @Override
    public void onDisable() {
        if (mainTickTask != null) {
            mainTickTask.cancel();
            mainTickTask = null;
        }

        // Cleanup disc display entities (visual-only)
        for (DiscFlight flight : activeDiscByOwner.values()) {
            Entity e = findEntityByUUID(flight.displayEntityId);
            if (e != null) e.remove();
        }
        activeDiscByOwner.clear();

        // IMPORTANT: Do NOT destroy Citizens NPCs on disable.
        // Match VTF/MercMobs lifecycle: cancel tasks + clear runtime state only.
        despawnAllPrograms(false);

        getLogger().info("GridPrograms disabled.");
    }

    // ------------------------------------------------------------------------
    // Config + folders
    // ------------------------------------------------------------------------

    private void ensureDataFolders() {
        if (!getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            getDataFolder().mkdirs();
        }
        skinsFolder = new File(getDataFolder(), "skins");
        if (!skinsFolder.exists()) {
            //noinspection ResultOfMethodCallIgnored
            skinsFolder.mkdirs();
        }
    }

    private void ensureConfigExists() {
        configFile = new File(getDataFolder(), "config.yml");
        if (configFile.exists()) return;

        try {
            YamlConfiguration yc = new YamlConfiguration();

            yc.set("settings.worldName", GRID_WORLD_NAME);

            yc.set("poi.filePath", "plugins/TronLegacyGen/grid_pois.yml");
            yc.set("poi.activationDistance", 160);
            yc.set("poi.maxPoisActive", 12);

            yc.set("population.maxPerPoi.blue", 8);
            yc.set("population.maxPerPoi.white", 4);
            yc.set("population.maxPerPoi.orange", 6);
            yc.set("population.spawnBatchPerTick", 1);

            yc.set("programs.maxHealth", 20.0);
            yc.set("programs.regen.enabled", true);
            yc.set("programs.regen.amount", 0.5);
            yc.set("programs.regen.intervalTicks", 80);
            yc.set("programs.regen.combatPauseSeconds", 8);

            yc.set("skins.mineskinApiKey", "");
            yc.set("skins.mineskinUserAgent", "GridPrograms/1.0");
            yc.set("skins.mineskinCooldownSeconds", 12);
            yc.set("skins.mineskinMaxAttempts", 5);

            yc.set("disc.throw.speedPerTick", 1.15);
            yc.set("disc.throw.maxTicks", 70);
            yc.set("disc.throw.outboundTicks", 28);
            yc.set("disc.throw.curveStrength", 0.04);
            yc.set("disc.throw.hitRadius", 1.2);
            yc.set("disc.throw.damage", 6.0); // roughly iron sword
            yc.set("disc.throw.expediteRecallMultiplier", 2.25);

            yc.set("disc.particles.enabled", true);
            yc.set("disc.sounds.enabled", true);

            yc.set("dialogue.cooldownSeconds", 3);

            // Skin cache placeholders (permanent unless missing)
            yc.set("skins.cache.blue.value", "");
            yc.set("skins.cache.blue.signature", "");
            yc.set("skins.cache.white.value", "");
            yc.set("skins.cache.white.signature", "");
            yc.set("skins.cache.orange.value", "");
            yc.set("skins.cache.orange.signature", "");

            yc.save(configFile);
        } catch (Exception ex) {
            getLogger().severe("Failed creating config.yml: " + ex.getMessage());
        }
    }

    private void loadConfigFile() {
        config = YamlConfiguration.loadConfiguration(configFile);
    }

    private void saveConfigFile() {
        try {
            config.save(configFile);
        } catch (Exception ex) {
            getLogger().warning("Failed saving config.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------
    // Citizens presence
    // ------------------------------------------------------------------------

    private boolean isCitizensPresent() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Citizens");
        return p != null && p.isEnabled();
    }

    // ------------------------------------------------------------------------
    // POIs
    // ------------------------------------------------------------------------

    private void resolvePoiFile() {
        String poiPath = config.getString("poi.filePath", "plugins/TronLegacyGen/grid_pois.yml");
        // If a relative path is provided, resolve from server root
        poiFile = new File(poiPath);
        if (!poiFile.isAbsolute()) {
            poiFile = new File(poiPath);
        }
    }

    private void loadPois() {
        pois.clear();

        if (poiFile == null || !poiFile.exists()) {
            getLogger().warning("POI file not found: " + (poiFile == null ? "(null)" : poiFile.getPath()));
            return;
        }

        poiConfig = YamlConfiguration.loadConfiguration(poiFile);
        List<Map<?, ?>> list = poiConfig.getMapList("pois");

        for (Map<?, ?> raw : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getKey() == null) continue;
                m.put(String.valueOf(e.getKey()), e.getValue());
            }

            String id = asString(m.get("id"));
            String world = asString(m.get("world"));
            String type = asString(m.get("type"));
            String district = asString(m.get("district"));

            int x = asInt(m.get("x"));
            int y = asInt(m.get("y"));
            int z = asInt(m.get("z"));
            int radius = asInt(m.get("radius"));

            Faction bias = Faction.fromString(asString(m.get("factionBias")));

            if (id.isEmpty() || world.isEmpty()) continue;
            if (!GRID_WORLD_NAME.equals(world)) continue; // hard lock

            pois.add(new Poi(id, world, type, district, x, y, z, radius, bias));
        }

        getLogger().info("Loaded POIs: " + pois.size() + " from " + poiFile.getPath());
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception ex) { return 0; }
    }

    // ------------------------------------------------------------------------
    // Skin cache + MineSkin
    // ------------------------------------------------------------------------

    private void loadSkinCacheFromConfig() {
        loadSkinFromConfig("blue");
        loadSkinFromConfig("white");
        loadSkinFromConfig("orange");
    }

    private void loadSkinFromConfig(String skinKey) {
        String v = config.getString("skins.cache." + skinKey + ".value", "");
        String s = config.getString("skins.cache." + skinKey + ".signature", "");
        if (v != null && s != null && !v.isEmpty() && !s.isEmpty()) {
            skinCache.put(skinKey, new SkinData(v, s));
        }
    }

    private void persistSkinToConfig(String skinKey, SkinData data) {
        config.set("skins.cache." + skinKey + ".value", data.value);
        config.set("skins.cache." + skinKey + ".signature", data.signature);
        saveConfigFile();
    }

    private void ensureSkinPngFiles() {
        File b = new File(skinsFolder, SKIN_BLUE_FILE);
        File w = new File(skinsFolder, SKIN_WHITE_FILE);
        File o = new File(skinsFolder, SKIN_ORANGE_FILE);

        if (!b.exists()) getLogger().warning("Missing skin file: " + b.getPath());
        if (!w.exists()) getLogger().warning("Missing skin file: " + w.getPath());
        if (!o.exists()) getLogger().warning("Missing skin file: " + o.getPath());
    }

    private void scheduleSkinUploadIfNeeded(String skinKey, File pngFile) {
        if (!citizensAvailable) return;

        if (skinCache.containsKey(skinKey)) {
            skinRetryState.remove(skinKey);
            return;
        }

        RetryState rs = skinRetryState.computeIfAbsent(skinKey, k -> new RetryState());
        long now = System.currentTimeMillis();
        if (rs.nextAttemptAtMs > now) return;

        // Schedule an async upload attempt
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> attemptMineSkinUpload(skinKey, pngFile));
    }

    private void attemptMineSkinUpload(String skinKey, File pngFile) {
        if (skinCache.containsKey(skinKey)) return;

        RetryState rs = skinRetryState.computeIfAbsent(skinKey, k -> new RetryState());
        long now = System.currentTimeMillis();
        if (rs.nextAttemptAtMs > now) return;

        rs.attempts++;

        if (pngFile == null || !pngFile.exists()) {
            getLogger().warning("Cannot upload skin '" + skinKey + "': missing file " + (pngFile == null ? "(null)" : pngFile.getPath()));
            backoffRetry(rs);
            return;
        }

        String apiKey = config.getString("skins.mineskinApiKey", "");
        if (apiKey == null) apiKey = "";
        // VTF-style: key may be blank; MineSkin often still works, but keep behavior deterministic.
        String userAgent = config.getString("skins.mineskinUserAgent", "GridPrograms/1.0");
        if (userAgent == null || userAgent.isBlank()) userAgent = "GridPrograms/1.0";

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(pngFile.toPath());
        } catch (Exception ex) {
            getLogger().warning("Failed reading skin file '" + skinKey + "': " + ex.getMessage());
            backoffRetry(rs);
            return;
        }

        SkinData data = requestMineSkinByUploadBytes(bytes, "grid_" + skinKey, "classic", "public", apiKey, userAgent);
        if (data == null) {
            getLogger().warning("MineSkin upload failed for '" + skinKey + "'. Will retry.");
            backoffRetry(rs);
            return;
        }

        // Persist + cache permanently
        skinCache.put(skinKey, data);
        persistSkinToConfig(skinKey, data);
        skinRetryState.remove(skinKey);

        getLogger().info("MineSkin upload OK for '" + skinKey + "'. Skin cached permanently.");
    }

    private void backoffRetry(RetryState rs) {
        long now = System.currentTimeMillis();
        // Exponential-ish backoff: 2m -> 5m -> 15m -> 30m cap
        long waitMs;
        if (rs.attempts <= 1) waitMs = 2L * 60L * 1000L;
        else if (rs.attempts == 2) waitMs = 5L * 60L * 1000L;
        else if (rs.attempts == 3) waitMs = 15L * 60L * 1000L;
        else waitMs = 30L * 60L * 1000L;

        rs.nextAttemptAtMs = now + waitMs;
    }

    // MineSkin upload (ported in spirit from ViridianTownsFolk)
    private SkinData requestMineSkinByUploadBytes(byte[] pngBytes, String name, String variant, String visibility,
                                                 String apiKey, String userAgent) {

        final int maxAttempts = Math.max(1, config.getInt("skins.mineskinMaxAttempts", 5));
        final long cooldownMs = Math.max(1000L, config.getLong("skins.mineskinCooldownSeconds", 12) * 1000L);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpURLConnection conn = null;
            try {
                synchronized (mineSkinRateLock) {
                    long now = System.currentTimeMillis();
                    long wait = nextMineSkinRequestAtMs - now;
                    if (wait > 0) {
                        try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
                    }
                    nextMineSkinRequestAtMs = System.currentTimeMillis() + cooldownMs;
                }

                String boundary = "----GridProgramsBoundary" + UUID.randomUUID();
                URL url = new URL("https://api.mineskin.org/generate/upload");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                if (!apiKey.isBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                }

                try (OutputStream os = conn.getOutputStream()) {
                    String safeName = sanitizeName(name);
                    writeFormField(os, boundary, "name", safeName);
                    writeFormField(os, boundary, "variant", variant);
                    writeFormField(os, boundary, "visibility", visibility);
                    writeFileField(os, boundary, "file", safeName + ".png", "image/png", pngBytes);
                    os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                String body = readAll((code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream());

                if (code >= 200 && code < 300) {
                    String value = extractJsonString(body, "\"value\":\"");
                    String signature = extractJsonString(body, "\"signature\":\"");
                    if (value == null || signature == null || value.isEmpty() || signature.isEmpty()) {
                        getLogger().warning("MineSkin response missing value/signature.");
                        return null;
                    }
                    return new SkinData(value, signature);
                }

                if (code == 429) {
                    long waitMs = parseMineSkinBackoffMs(body);
                    if (attempt >= maxAttempts) {
                        getLogger().warning("MineSkin upload failed (429) after " + maxAttempts + " attempts: " + body);
                        return null;
                    }
                    getLogger().warning("MineSkin rate limited (429). Backing off for " + waitMs +
                            "ms (attempt " + attempt + "/" + maxAttempts + ").");
                    try { Thread.sleep(waitMs); } catch (InterruptedException ignored) {}
                    continue;
                }

                getLogger().warning("MineSkin upload failed (" + code + "): " + body);
                return null;

            } catch (Throwable ex) {
                getLogger().warning("MineSkin upload error: " + ex.getMessage());
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        return null;
    }

    private static String sanitizeName(String s) {
        if (s == null) return "grid";
        String t = s.trim();
        if (t.length() > 32) t = t.substring(0, 32);
        t = t.replaceAll("[^A-Za-z0-9_\\- ]", "");
        if (t.isEmpty()) t = "grid";
        return t;
    }

    private static void writeFormField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFileField(OutputStream os, String boundary, String fieldName, String fileName, String contentType, byte[] bytes) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String extractJsonString(String json, String needle) {
        // Backwards-compatible helper for MineSkin responses.
        // Supports callers passing either a full needle (e.g. "\"value\":\"") or a field name ("value").
        if (json == null) return null;
        if (needle == null || needle.isEmpty()) return null;

        String field = needle;
        if (field.contains("\"")) {
            // Try to derive a field name from a needle like "\"value\":\""
            Matcher fm = Pattern.compile("\"([a-zA-Z0-9_]+)\"\\s*:\\s*\"").matcher(field);
            if (fm.find()) field = fm.group(1);
        }
        field = field.replace("\"", "").replace(":", "").trim();
        if (field.isEmpty()) return null;

        // Very small JSON field extractor (string-only), tolerant of whitespace/order.
        Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(json);
        if (!m.find()) return null;

        return unescapeJsonString(m.group(1));
    }

    private static String unescapeJsonString(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        boolean escape = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!escape) {
                if (c == '\\') {
                    escape = true;
                } else {
                    sb.append(c);
                }
                continue;
            }
            // escaped
            escape = false;
            switch (c) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    // \\uXXXX
                    if (i + 4 < raw.length()) {
                        String hex = raw.substring(i + 1, i + 5);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            sb.append('u');
                        }
                    } else {
                        sb.append('u');
                    }
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Long extractJsonNumber(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        idx += needle.length();

        StringBuilder sb = new StringBuilder();
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-') sb.append(c);
            else if (sb.length() > 0) break;
        }
        if (sb.length() == 0) return null;
        try {
            return Long.parseLong(sb.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static long parseMineSkinBackoffMs(String body) {
        if (body == null) return 10000L;
        try {
            Long n = extractJsonNumber(body, "nextRequest");
            if (n != null) {
                if (n < 1000L) return Math.max(1000L, n * 1000L);
                return Math.max(1000L, n);
            }
            Long r = extractJsonNumber(body, "retry_after");
            if (r != null) return Math.max(1000L, r * 1000L);
        } catch (Exception ignored) {}
        return 10000L;
    }

    // ------------------------------------------------------------------------
    // Main tick: skins readiness, POI activation, population, wandering, discs
    // ------------------------------------------------------------------------

    private void tickMain() {
        // Keep trying skin uploads until all are ready (permanent unless missing).
        scheduleSkinUploadIfNeeded("blue", new File(skinsFolder, SKIN_BLUE_FILE));
        scheduleSkinUploadIfNeeded("white", new File(skinsFolder, SKIN_WHITE_FILE));
        scheduleSkinUploadIfNeeded("orange", new File(skinsFolder, SKIN_ORANGE_FILE));

        boolean skinsReady = skinCache.containsKey("blue") && skinCache.containsKey("white") && skinCache.containsKey("orange");

        // Disc flights always tick (player feature)
        tickDiscFlights();

        if (!citizensAvailable || !skinsReady) {
            // Do not spawn NPCs until Citizens and skins are ready.
            return;
        }

        World gridWorld = Bukkit.getWorld(GRID_WORLD_NAME);
        if (gridWorld == null) return;

        // Activate POIs near players and enforce population
        int activationDistance = Math.max(32, config.getInt("poi.activationDistance", 160));
        int maxActivePois = Math.max(1, config.getInt("poi.maxPoisActive", 12));

        List<Poi> activePois = collectActivePois(gridWorld, activationDistance, maxActivePois);
        maintainPopulation(gridWorld, activePois);

        // Light wandering tick
        tickWandering(gridWorld);
    }

    private List<Poi> collectActivePois(World world, int activationDistance, int maxActive) {
        List<Poi> active = new ArrayList<>();
        int act2 = activationDistance * activationDistance;

        for (Poi poi : pois) {
            if (!GRID_WORLD_NAME.equals(poi.world)) continue;

            Location center = new Location(world, poi.x + 0.5, poi.y + 0.5, poi.z + 0.5);
            boolean near = false;
            for (Player p : world.getPlayers()) {
                if (!p.isOnline()) continue;
                if (p.getLocation().distanceSquared(center) <= act2) {
                    near = true;
                    break;
                }
            }
            if (near) active.add(poi);
        }

        // Simple cap: nearest-ish by player count; for now just truncate deterministically.
        if (active.size() > maxActive) {
            active = active.subList(0, maxActive);
        }

        return active;
    }

    private void maintainPopulation(World world, List<Poi> activePois) {
        int maxBlue = Math.max(0, config.getInt("population.maxPerPoi.blue", 8));
        int maxWhite = Math.max(0, config.getInt("population.maxPerPoi.white", 4));
        int maxOrange = Math.max(0, config.getInt("population.maxPerPoi.orange", 6));
        int spawnBatch = Math.max(1, config.getInt("population.spawnBatchPerTick", 1));

        Set<String> activePoiIds = new HashSet<>();
        for (Poi poi : activePois) activePoiIds.add(poi.id);

        // Despawn POIs that are no longer active
        for (String poiId : new ArrayList<>(poiNpcIds.keySet())) {
            if (!activePoiIds.contains(poiId)) {
                despawnPoiPrograms(poiId);
            }
        }

        // For each active POI, ensure population
        int spawnedThisTick = 0;

        for (Poi poi : activePois) {
            if (spawnedThisTick >= spawnBatch) break;

            Set<Integer> ids = poiNpcIds.computeIfAbsent(poi.id, k -> ConcurrentHashMap.newKeySet());

            int blueCount = 0, whiteCount = 0, orangeCount = 0;
            for (int npcId : new ArrayList<>(ids)) {
                NPC npc = npcRegistry.getById(npcId);
                if (npc == null) {
                    ids.remove(npcId);
                    continue;
                }
                if (!npc.isSpawned()) continue;

                Entity ent = npc.getEntity();
                if (ent == null || !ent.isValid()) continue;

                Faction f = getFaction(ent);
                if (f == Faction.BLUE) blueCount++;
                else if (f == Faction.WHITE) whiteCount++;
                else if (f == Faction.ORANGE) orangeCount++;
            }

            // Decide which faction to spawn next based on bias (simple first pass)
            Faction next = pickFactionToSpawn(poi.factionBias, blueCount, whiteCount, orangeCount, maxBlue, maxWhite, maxOrange);
            if (next == null) continue;

            boolean ok = spawnProgramAtPoi(world, poi, next);
            if (ok) spawnedThisTick++;
        }
    }

    private Faction pickFactionToSpawn(Faction bias, int blue, int white, int orange,
                                       int maxBlue, int maxWhite, int maxOrange) {
        // Ensure caps first
        boolean needBlue = blue < maxBlue;
        boolean needWhite = white < maxWhite;
        boolean needOrange = orange < maxOrange;

        if (!needBlue && !needWhite && !needOrange) return null;

        // Bias weighting
        List<Faction> options = new ArrayList<>();
        if (needBlue) options.add(Faction.BLUE);
        if (needWhite) options.add(Faction.WHITE);
        if (needOrange) options.add(Faction.ORANGE);

        // Favor bias by duplication
        if (bias != null && options.contains(bias)) {
            options.add(bias);
            options.add(bias);
        }

        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private boolean spawnProgramAtPoi(World world, Poi poi, Faction faction) {
        if (!citizensAvailable) return false;

        // Ensure chunk loaded
        Location center = new Location(world, poi.x + 0.5, poi.y + 1.0, poi.z + 0.5);
        center.getChunk().load();

        // Find random spawn spot within POI radius
        int r = Math.max(12, poi.radius);
        int sx = poi.x + ThreadLocalRandom.current().nextInt(-r, r + 1);
        int sz = poi.z + ThreadLocalRandom.current().nextInt(-r, r + 1);
        int sy = poi.y;

        Location spawn = new Location(world, sx + 0.5, sy, sz + 0.5);
        spawn = findSafeSpawnY(world, spawn);

        if (spawn == null) return false;

        String npcName = generateProgramName(faction);

        // Spawn Citizens NPC as player-type to support skins
        NPC npc = npcRegistry.createNPC(EntityType.PLAYER, npcName);
        if (npc == null) return false;

        // Tag and store metadata
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);

        // Skin assignment via SkinTrait (cached)
        SkinData skin = skinCache.get(factionKey(faction));
        if (skin == null) {
            npc.destroy();
            return false;
        }

        try {
            SkinTrait st = npc.getOrAddTrait(SkinTrait.class);
            st.setSkinPersistent(factionKey(faction), skin.signature, skin.value);
        } catch (Throwable ex) {
            getLogger().warning("Failed applying SkinTrait: " + ex.getMessage());
            npc.destroy();
            return false;
        }

        boolean spawned = npc.spawn(spawn);
        if (!spawned) {
            npc.destroy();
            return false;
        }

        Entity ent = npc.getEntity();
        if (ent instanceof LivingEntity le) {
            double maxHp = Math.max(1.0, config.getDouble("programs.maxHealth", 20.0));
            if (le.getAttribute(Attribute.MAX_HEALTH) != null) {
                Objects.requireNonNull(le.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(maxHp);
            }
            le.setHealth(Math.min(le.getHealth(), maxHp));
        }

        // Persist metadata at the NPC level so it survives despawn/restart (entity PDC does not).
        npc.data().setPersistent("grid_managed", true);
        npc.data().setPersistent("grid_world", world.getName());
        npc.data().setPersistent("grid_faction", faction.name());
        npc.data().setPersistent("grid_poi_id", poi.id == null ? "" : poi.id);
        npc.data().setPersistent("grid_program_name", npcName == null ? "" : npcName);

        // Also mark the live entity for fast event checks.
        markProgramEntity(ent, faction, poi.id, npcName);

        poiNpcIds.computeIfAbsent(poi.id, k -> ConcurrentHashMap.newKeySet()).add(npc.getId());
        return true;
    }

    private static String factionKey(Faction f) {
        return switch (f) {
            case BLUE -> "blue";
            case WHITE -> "white";
            case ORANGE -> "orange";
        };
    }

    private static String generateProgramName(Faction f) {
        // Simple, lore-friendly style: short designations
        String prefix = switch (f) {
            case BLUE -> "CIT";
            case WHITE -> "RES";
            case ORANGE -> "SYS";
        };
        int n = ThreadLocalRandom.current().nextInt(100, 999);
        return prefix + "-" + n;
    }

    private Location findSafeSpawnY(World world, Location near) {
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        int x = near.getBlockX();
        int z = near.getBlockZ();

        // Search up and down a bit around suggested y
        int baseY = near.getBlockY();
        for (int dy = 0; dy <= 16; dy++) {
            int y1 = baseY + dy;
            int y2 = baseY - dy;

            Location a = trySpawnAt(world, x, y1, z, minY, maxY);
            if (a != null) return a;

            Location b = trySpawnAt(world, x, y2, z, minY, maxY);
            if (b != null) return b;
        }
        return null;
    }

    private Location trySpawnAt(World world, int x, int y, int z, int minY, int maxY) {
        if (y < minY + 1 || y > maxY - 2) return null;

        Material below = world.getBlockAt(x, y - 1, z).getType();
        Material at = world.getBlockAt(x, y, z).getType();
        Material above = world.getBlockAt(x, y + 1, z).getType();

        if (below.isSolid() && at.isAir() && above.isAir()) {
            return new Location(world, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    private void despawnPoiPrograms(String poiId) {
        Set<Integer> ids = poiNpcIds.remove(poiId);
        if (ids == null) return;

        for (int npcId : ids) {
            NPC npc = npcRegistry.getById(npcId);
            if (npc == null) continue;
            if (npc.isSpawned()) npc.despawn();
            npc.destroy();
        }
    }

    private void purgeAllPrograms() {
        // Admin-only destructive purge: despawn + destroy all managed Programs (persistent removal).
        despawnAllPrograms(true);
    }

    private void despawnAllPrograms(boolean destroy) {
        if (!citizensAvailable || npcRegistry == null) {
            poiNpcIds.clear();
            return;
        }

        // Rebuild tracking by scanning registry (covers restart cases where poiNpcIds is empty).
        int count = 0;
        for (NPC npc : npcRegistry) {
            if (npc == null) continue;
            if (!isManagedProgramNpc(npc)) continue;

            try {
                if (npc.isSpawned()) npc.despawn();
            } catch (Throwable ignored) {}

            if (destroy) {
                try { npc.destroy(); } catch (Throwable ignored) {}
            }

            count++;
        }

        poiNpcIds.clear();
        getLogger().info((destroy ? "Purged" : "Despawned") + " Programs: " + count);
    }

    private boolean isManagedProgramNpc(NPC npc) {
        if (npc == null) return false;
        try {
            Object flag = npc.data().get("grid_managed");
            if (flag instanceof Boolean b) return b;
            if (flag instanceof String s) return Boolean.parseBoolean(s);
            if (flag instanceof Number n) return n.intValue() != 0;
        } catch (Throwable ignored) {}
        return false;
    }

    private int wakeAllPrograms(String reason) {
        if (!citizensAvailable || npcRegistry == null) return 0;

        int woke = 0;
        poiNpcIds.clear();

        for (NPC npc : npcRegistry) {
            if (npc == null) continue;
            if (!isManagedProgramNpc(npc)) continue;

            String storedWorld = safeString(npc.data().get("grid_world"));
            if (storedWorld != null && !storedWorld.isEmpty() && !storedWorld.equalsIgnoreCase(GRID_WORLD_NAME)) {
                continue;
            }

            if (npc.isSpawned()) {
                Entity ent = npc.getEntity();
                if (ent == null || ent.getWorld() == null || !GRID_WORLD_NAME.equals(ent.getWorld().getName())) {
                    continue;
                }

                Faction faction = readFactionForNpc(npc, ent);
                String poiId = readPoiIdForNpc(npc, ent);
                String programName = readProgramNameForNpc(npc, ent);

                // Re-apply live entity tags for fast event checks.
                markProgramEntity(ent, faction, poiId, programName);

                // Ensure max health baseline is applied (regen depends on max).
                if (ent instanceof LivingEntity le) {
                    double maxHp = Math.max(1.0, config.getDouble("programs.maxHealth", 20.0));
                    if (le.getAttribute(Attribute.MAX_HEALTH) != null) {
                        Objects.requireNonNull(le.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(maxHp);
                    }
                    le.setHealth(Math.min(le.getHealth(), maxHp));
                }
            }

            String poiId = safeString(npc.data().get("grid_poi_id"));
            if (poiId == null) poiId = "";
            poiNpcIds.computeIfAbsent(poiId, k -> ConcurrentHashMap.newKeySet()).add(npc.getId());
            woke++;
        }

        if (woke > 0) {
            getLogger().info("Wake(" + reason + "): rehooked " + woke + " Programs.");
        }
        return woke;
    }

    private String safeString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private Faction readFactionForNpc(NPC npc, Entity ent) {
        String s = safeString(npc.data().get("grid_faction"));
        if (s == null || s.isEmpty()) {
            s = getPdcString(ent, KEY_FACTION);
        }
        try {
            return (s == null || s.isEmpty()) ? Faction.BLUE : Faction.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return Faction.BLUE;
        }
    }

    private String readPoiIdForNpc(NPC npc, Entity ent) {
        String s = safeString(npc.data().get("grid_poi_id"));
        if (s == null) s = "";
        if (s.isEmpty()) s = getPdcString(ent, KEY_POI_ID);
        return (s == null) ? "" : s;
    }

    private String readProgramNameForNpc(NPC npc, Entity ent) {
        String s = safeString(npc.data().get("grid_program_name"));
        if (s == null) s = "";
        if (s.isEmpty()) s = getPdcString(ent, KEY_PROGRAM_NAME);
        return (s == null) ? "" : s;
    }

    private String getPdcString(Entity ent, NamespacedKey key) {
        if (ent == null) return null;
        try {
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            if (pdc == null) return null;
            return pdc.get(key, PersistentDataType.STRING);
        } catch (Throwable ignored) {
            return null;
        }
    }


    private void tickWandering(World world) {
        // Very lightweight wandering: occasionally give each spawned NPC a random nearby target
        // unless in conversation.
        if (!citizensAvailable) return;

        for (Set<Integer> ids : poiNpcIds.values()) {
            for (int npcId : ids) {
                NPC npc = npcRegistry.getById(npcId);
                if (npc == null || !npc.isSpawned()) continue;
                Entity ent = npc.getEntity();
                if (ent == null || !ent.isValid()) continue;
                if (!GRID_WORLD_NAME.equals(ent.getWorld().getName())) continue;

                // If in conversation, skip
                ConversationState cs = npcConversation.get(npcId);
                if (cs != null && cs.untilMs > System.currentTimeMillis()) continue;

                // Random chance to set a wander target
                if (ThreadLocalRandom.current().nextDouble() > 0.02) continue;

                String poiId = getPoiId(ent);
                Poi home = findPoiById(poiId);
                if (home == null) continue;

                int r = Math.max(10, Math.min(40, home.radius / 2));
                int tx = home.x + ThreadLocalRandom.current().nextInt(-r, r + 1);
                int tz = home.z + ThreadLocalRandom.current().nextInt(-r, r + 1);
                int ty = home.y;

                Location target = findSafeSpawnY(world, new Location(world, tx + 0.5, ty, tz + 0.5));
                if (target == null) continue;

                try {
                    npc.getNavigator().setTarget(target);
                } catch (Throwable ignored) {}
            }
        }
    }

    private Poi findPoiById(String poiId) {
        if (poiId == null || poiId.isEmpty()) return null;
        for (Poi p : pois) if (p.id.equals(poiId)) return p;
        return null;
    }

    // ------------------------------------------------------------------------
    // Program tagging
    // ------------------------------------------------------------------------

    private void markProgramEntity(Entity ent, Faction faction, String poiId, String programName) {
        if (ent == null) return;
        PersistentDataContainer pdc = ent.getPersistentDataContainer();
        pdc.set(KEY_MANAGED_PROGRAM, PersistentDataType.BYTE, (byte) 1);
        pdc.set(KEY_FACTION, PersistentDataType.STRING, faction.name());
        pdc.set(KEY_POI_ID, PersistentDataType.STRING, poiId == null ? "" : poiId);
        pdc.set(KEY_PROGRAM_NAME, PersistentDataType.STRING, programName == null ? "" : programName);
        ent.addScoreboardTag("grid_program");
    }

    private boolean isManagedProgram(Entity ent) {
        if (ent == null) return false;
        PersistentDataContainer pdc = ent.getPersistentDataContainer();
        Byte b = pdc.get(KEY_MANAGED_PROGRAM, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private Faction getFaction(Entity ent) {
        if (ent == null) return Faction.BLUE;
        String s = ent.getPersistentDataContainer().get(KEY_FACTION, PersistentDataType.STRING);
        return Faction.fromString(s);
    }

    private String getPoiId(Entity ent) {
        if (ent == null) return "";
        String s = ent.getPersistentDataContainer().get(KEY_POI_ID, PersistentDataType.STRING);
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------------
    // Identity Disc issuance + protection
    // ------------------------------------------------------------------------

    private boolean isGridWorld(World w) {
        return w != null && GRID_WORLD_NAME.equals(w.getName());
    }

    private void ensurePlayerDiscIfInGrid(Player p, boolean sendMessage) {
        if (p == null || !p.isOnline()) return;
        if (!isGridWorld(p.getWorld())) return;

        if (playerHasBoundDisc(p.getUniqueId(), p.getInventory())) return;

        ItemStack disc = createPlayerIdentityDisc(p.getUniqueId(), p.getName());
        p.getInventory().addItem(disc);

        if (sendMessage) {
            p.sendMessage(String.format(DISC_ISSUE_MESSAGE, p.getName()));
        }
    }

    private ItemStack createPlayerIdentityDisc(UUID owner, String playerName) {
        ItemStack item = new ItemStack(DISC_WHITE_USERS, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("Identity Disc");
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_IDENTITY_DISC, PersistentDataType.BYTE, (byte) 1);
            pdc.set(KEY_DISC_OWNER, PersistentDataType.STRING, owner.toString());
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isIdentityDisc(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte b = meta.getPersistentDataContainer().get(KEY_IDENTITY_DISC, PersistentDataType.BYTE);
        return b != null && b == (byte) 1;
    }

    private boolean isPlayerBoundDisc(ItemStack item, UUID owner) {
        if (!isIdentityDisc(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String s = meta.getPersistentDataContainer().get(KEY_DISC_OWNER, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return false;
        return s.equals(owner.toString());
    }

    private boolean playerHasBoundDisc(UUID owner, org.bukkit.inventory.PlayerInventory inv) {
        for (ItemStack it : inv.getContents()) {
            if (isPlayerBoundDisc(it, owner)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------
    // Disc flights (player throw + recall)
    // ------------------------------------------------------------------------

    private void tryThrowPlayerDisc(Player p, ItemStack discInHand) {
        if (p == null || !p.isOnline()) return;
        if (!isGridWorld(p.getWorld())) return;

        if (!isPlayerBoundDisc(discInHand, p.getUniqueId())) return;

        // One disc in flight per owner
        if (activeDiscByOwner.containsKey(p.getUniqueId())) return;

        double speed = config.getDouble("disc.throw.speedPerTick", 1.15);
        int maxTicks = Math.max(20, config.getInt("disc.throw.maxTicks", 70));
        int outboundTicks = Math.max(10, Math.min(maxTicks - 5, config.getInt("disc.throw.outboundTicks", 28)));
        double curveStrength = config.getDouble("disc.throw.curveStrength", 0.04);

        Location eye = p.getEyeLocation();
        Vector origin = eye.toVector().add(eye.getDirection().normalize().multiply(0.8));
        Vector dir = eye.getDirection().clone().normalize();

        // Spawn an ItemDisplay for the disc sprite
        ItemDisplay display;
        try {
            display = (ItemDisplay) p.getWorld().spawnEntity(origin.toLocation(p.getWorld()), EntityType.ITEM_DISPLAY);
        } catch (Throwable ex) {
            getLogger().warning("ItemDisplay not available/spawn failed: " + ex.getMessage());
            return;
        }

        display.setItemStack(new ItemStack(DISC_WHITE_USERS, 1));
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGlowing(false);
        display.setGravity(false);

        DiscFlight flight = new DiscFlight(
                p.getUniqueId(),
                display.getUniqueId(),
                origin,
                dir,
                speed,
                maxTicks,
                outboundTicks,
                curveStrength
        );

        activeDiscByOwner.put(p.getUniqueId(), flight);

        if (config.getBoolean("disc.sounds.enabled", true)) {
            p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.35f, 1.6f);
        }
    }

    private void expediteRecall(Player p) {
        DiscFlight flight = activeDiscByOwner.get(p.getUniqueId());
        if (flight == null) return;
        flight.returning = true;
        flight.expediteRequested = true;
    }

    private void tickDiscFlights() {
        if (activeDiscByOwner.isEmpty()) return;

        double hitRadius = config.getDouble("disc.throw.hitRadius", 1.2);
        double damage = config.getDouble("disc.throw.damage", 6.0);
        boolean particles = config.getBoolean("disc.particles.enabled", true);

        double expediteMult = config.getDouble("disc.throw.expediteRecallMultiplier", 2.25);

        for (UUID ownerId : new ArrayList<>(activeDiscByOwner.keySet())) {
            DiscFlight flight = activeDiscByOwner.get(ownerId);
            if (flight == null) continue;

            Player owner = Bukkit.getPlayer(ownerId);
            if (owner == null || !owner.isOnline() || !isGridWorld(owner.getWorld())) {
                removeFlight(ownerId, flight);
                continue;
            }

            Entity e = findEntityByUUID(flight.displayEntityId);
            if (!(e instanceof ItemDisplay display) || !display.isValid()) {
                activeDiscByOwner.remove(ownerId);
                continue;
            }

            flight.ticksLived++;
            if (flight.ticksLived > flight.maxTicks) {
                // failsafe: return or remove
                removeFlight(ownerId, flight);
                continue;
            }

            Vector ownerHand = owner.getEyeLocation().toVector().add(owner.getEyeLocation().getDirection().normalize().multiply(0.6));
            Vector pos = flight.currentPos.clone();

            // Curved outbound path: slight lateral curve based on tick progression
            Vector stepDir;

            if (!flight.returning && flight.ticksLived <= flight.outboundTicks) {
                double t = (double) flight.ticksLived / (double) flight.outboundTicks;
                Vector lateral = flight.outboundDir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
                double curve = Math.sin(t * Math.PI) * flight.curveStrength;
                stepDir = flight.outboundDir.clone().add(lateral.multiply(curve)).normalize();
            } else {
                flight.returning = true;
                stepDir = ownerHand.clone().subtract(pos).normalize();
            }

            double speed = flight.speedPerTick;

            // Expedite recall only when the player explicitly requested it (bare-hand right-click).
            if (flight.returning && flight.expediteRequested) {
                speed *= expediteMult;
            }

            Vector next = pos.add(stepDir.multiply(speed));
            flight.currentPos = next.clone();

            Location nextLoc = next.toLocation(owner.getWorld());
            nextLoc.setYaw(display.getLocation().getYaw());
            nextLoc.setPitch(display.getLocation().getPitch());
            display.teleport(nextLoc);

            if (particles) {
                // Users/White disc particles (subtle white)
                owner.getWorld().spawnParticle(Particle.END_ROD, nextLoc, 1, 0.0, 0.0, 0.0, 0.0);
            }

            // Hit detection: only damages ORANGE Programs; never Blue/White.
            // Also avoids re-hitting the same tick by requiring proximity and then a small cooldown could be added later.
            Collection<Entity> nearby = display.getWorld().getNearbyEntities(nextLoc, hitRadius, hitRadius, hitRadius);
            for (Entity target : nearby) {
                if (!(target instanceof LivingEntity le)) continue;
                if (target.getUniqueId().equals(ownerId)) continue;

                if (!isManagedProgram(target)) continue;

                Faction f = getFaction(target);
                if (f != Faction.ORANGE) continue; // Players cannot harm Blue/White ever.

                // Apply damage as player
                le.damage(damage, owner);

                if (config.getBoolean("disc.sounds.enabled", true)) {
                    owner.playSound(owner.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.25f, 1.9f);
                }
            }

            // Catch condition: close enough to owner
            if (flight.returning && flight.currentPos.distance(ownerHand) <= 1.2) {
                if (config.getBoolean("disc.sounds.enabled", true)) {
                    owner.playSound(owner.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.35f, 1.8f);
                }
                removeFlight(ownerId, flight);
            }
        }
    }

    private void removeFlight(UUID ownerId, DiscFlight flight) {
        Entity e = findEntityByUUID(flight.displayEntityId);
        if (e != null) e.remove();
        activeDiscByOwner.remove(ownerId);
    }

    private Entity findEntityByUUID(UUID uuid) {
        if (uuid == null) return null;
        for (World w : Bukkit.getWorlds()) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // VTF-style dialogue interaction (outside combat)
    // ------------------------------------------------------------------------

    private void beginConversation(Player p, NPC npc, Entity ent) {
        if (p == null || npc == null || ent == null) return;
        if (!isGridWorld(p.getWorld())) return;

        int npcId = npc.getId();
        long now = System.currentTimeMillis();
        int cdSeconds = Math.max(0, config.getInt("dialogue.cooldownSeconds", 3));
        ConversationState existing = npcConversation.get(npcId);
        if (existing != null && existing.untilMs > now) return;

        // Stop navigation and face player
        try { npc.getNavigator().cancelNavigation(); } catch (Throwable ignored) {}

        faceEntity(ent, p.getLocation());

        // Select a line
        Faction faction = getFaction(ent);
        String line = pickDialogueLine(faction);

        // Send locally (player only; later you can do local-radius)
        p.sendMessage(line);

        // Conversation state lasts briefly; NPC resumes wandering after
        ConversationState cs = new ConversationState();
        cs.untilMs = now + (cdSeconds * 1000L);
        npcConversation.put(npcId, cs);

        Bukkit.getScheduler().runTaskLater(this, () -> npcConversation.remove(npcId), 20L * cdSeconds);
    }

    private void faceEntity(Entity ent, Location lookAt) {
        if (ent == null || lookAt == null) return;
        Location loc = ent.getLocation();
        Vector dir = lookAt.toVector().subtract(loc.toVector());
        if (dir.lengthSquared() < 0.0001) return;

        loc.setDirection(dir.normalize());
        ent.teleport(loc);
    }

    private String pickDialogueLine(Faction faction) {
        // Minimal starter pools; we’ll expand into weighted contextual buckets next iteration.
        // Keep tone clipped and Tron-like.
        return switch (faction) {
            case BLUE -> {
                String[] lines = new String[]{
                        "Keep moving. Don’t draw attention.",
                        "They tightened the checkpoints this cycle.",
                        "Lower your voice.",
                        "I didn’t see anything. I don’t want trouble."
                };
                yield lines[ThreadLocalRandom.current().nextInt(lines.length)];
            }
            case WHITE -> {
                String[] lines = new String[]{
                        "Stay close to the shadows.",
                        "You’re not alone.",
                        "If they come, we’ll answer.",
                        "Survive the cycle."
                };
                yield lines[ThreadLocalRandom.current().nextInt(lines.length)];
            }
            case ORANGE -> {
                // Orange can be procedural (conversational) or hostile; this starter pool is procedural.
                String[] lines = new String[]{
                        "Halt. Identify.",
                        "You are outside authorized parameters.",
                        "Remain where you are.",
                        "Irregular presence detected."
                };
                yield lines[ThreadLocalRandom.current().nextInt(lines.length)];
            }
        };
    }

    // ------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent e) {
        // If they spawn into The_Grid, issue disc and message once per spawn.
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> ensurePlayerDiscIfInGrid(p, true), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (isGridWorld(p.getWorld())) {
            // First entry or re-entry: ensure disc; send issuance message when disc missing.
            Bukkit.getScheduler().runTaskLater(this, () -> ensurePlayerDiscIfInGrid(p, true), 10L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> ensurePlayerDiscIfInGrid(p, true), 20L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFoodLevelChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isGridWorld(p.getWorld())) return;

        // Lock hunger
        e.setCancelled(true);
        p.setFoodLevel(20);
        p.setSaturation(8.0f);
    }

    // Prevent moving disc into containers / dropping / etc (soulbound)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!isGridWorld(p.getWorld())) return;

        ItemStack it = e.getItemDrop().getItemStack();
        if (isPlayerBoundDisc(it, p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isGridWorld(p.getWorld())) return;

        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();

        if (isPlayerBoundDisc(current, p.getUniqueId()) || isPlayerBoundDisc(cursor, p.getUniqueId())) {
            // Disallow moving into non-player inventories
            InventoryHolder holder = e.getInventory().getHolder();
            boolean isPlayerInventory = holder instanceof Player;
            if (!isPlayerInventory) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!isGridWorld(p.getWorld())) return;

        ItemStack cursor = e.getOldCursor();
        if (isPlayerBoundDisc(cursor, p.getUniqueId())) {
            InventoryHolder holder = e.getInventory().getHolder();
            boolean isPlayerInventory = holder instanceof Player;
            if (!isPlayerInventory) {
                e.setCancelled(true);
            }
        }
    }

    // Player right-click disc: throw
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isGridWorld(p.getWorld())) return;

        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        ItemStack inHand = p.getInventory().getItemInMainHand();

        // Bare-hand right-click while disc in flight: expedite recall
        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && (inHand == null || inHand.getType() == Material.AIR)) {
            if (activeDiscByOwner.containsKey(p.getUniqueId())) {
                expediteRecall(p);
                e.setCancelled(true);
                return;
            }
        }

        // Disc throw
        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && isPlayerBoundDisc(inHand, p.getUniqueId())) {
            tryThrowPlayerDisc(p, inHand);
            e.setCancelled(true);
        }
    }

    // VTF-style NPC conversation on right-click entity
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!isGridWorld(p.getWorld())) return;

        Entity clicked = e.getRightClicked();
        if (clicked == null) return;
        if (!isManagedProgram(clicked)) return;

        // If Orange is in an immediate hostile posture later, we will block conversation here.
        // For now, allow conversation always (we’ll add hostility posture next iteration).
        if (!citizensAvailable) return;

        NPC npc = CitizensAPI.getNPCRegistry().getNPC(clicked);
        if (npc == null) return;

        // Only allow if not in "combat" (we haven’t implemented combat state yet).
        beginConversation(p, npc, clicked);
        e.setCancelled(true);
    }

    // Damage filtering: Players can never harm Blue/White programs by any method.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        if (!(victim instanceof LivingEntity)) return;
        if (!isManagedProgram(victim)) return;
        if (!GRID_WORLD_NAME.equals(victim.getWorld().getName())) return;

        Faction victimFaction = getFaction(victim);

        Player attackerPlayer = null;

        Entity damager = e.getDamager();
        if (damager instanceof Player p) attackerPlayer = p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) attackerPlayer = p;

        if (attackerPlayer != null) {
            if (!isGridWorld(attackerPlayer.getWorld())) return;

            // Players can only damage ORANGE programs
            if (victimFaction != Faction.ORANGE) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // Death effect placeholder: for now just drop named trophy disc for Programs.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        if (!isManagedProgram(le)) return;
        if (!GRID_WORLD_NAME.equals(le.getWorld().getName())) return;

        Faction f = getFaction(le);
        String programName = le.getPersistentDataContainer().get(KEY_PROGRAM_NAME, PersistentDataType.STRING);
        if (programName == null || programName.isEmpty()) programName = le.getName();

        ItemStack trophy = new ItemStack(discForFaction(f), 1);
        ItemMeta meta = trophy.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(programName + "'s Identity Disc");
            trophy.setItemMeta(meta);
        }

        // Drop trophy
        le.getWorld().dropItemNaturally(le.getLocation(), trophy);

        // TODO next iteration:
        // - Derezz particle "bleeding glass" effect (death only)
        // - Disc usage for NPC combat
    }

    private Material discForFaction(Faction f) {
        return switch (f) {
            case WHITE -> DISC_WHITE_USERS;
            case BLUE -> DISC_BLUE;
            case ORANGE -> DISC_ORANGE;
        };
    }

    // ------------------------------------------------------------------------
    // Commands (starter set)
    // ------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("grid")) return false;

        if (args.length == 0) {
            sender.sendMessage("Usage: /grid reload | status | rehook | purge | skins status | skins reupload");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            loadConfigFile();
            resolvePoiFile();
            loadPois();
            loadSkinCacheFromConfig();
            sender.sendMessage("GridPrograms reloaded.");
            return true;
        }

        if (args[0].equalsIgnoreCase("rehook")) {
            if (sender instanceof Player p && !p.isOp()) {
                sender.sendMessage("No.");
                return true;
            }
            int woke = wakeAllPrograms("manual");
            sender.sendMessage("Rehooked Programs: " + woke);
            return true;
        }


        if (args[0].equalsIgnoreCase("purge")) {
            if (sender instanceof Player p && !p.isOp()) {
                sender.sendMessage("No.");
                return true;
            }
            purgeAllPrograms();
            sender.sendMessage("Purged GridPrograms NPCs.");
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("World: " + GRID_WORLD_NAME);
            sender.sendMessage("Citizens: " + (citizensAvailable ? "OK" : "MISSING"));
            sender.sendMessage("POIs loaded: " + pois.size());
            sender.sendMessage("Skins cached: blue=" + skinCache.containsKey("blue") +
                    " white=" + skinCache.containsKey("white") +
                    " orange=" + skinCache.containsKey("orange"));
            sender.sendMessage("Active disc flights: " + activeDiscByOwner.size());
            sender.sendMessage("Managed POIs active: " + poiNpcIds.size());
            return true;
        }

        if (args[0].equalsIgnoreCase("skins")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
                sender.sendMessage("Skins:");
                sender.sendMessage(" - blue: " + (skinCache.containsKey("blue") ? "READY" : skinRetryLabel("blue")));
                sender.sendMessage(" - white: " + (skinCache.containsKey("white") ? "READY" : skinRetryLabel("white")));
                sender.sendMessage(" - orange: " + (skinCache.containsKey("orange") ? "READY" : skinRetryLabel("orange")));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("reupload")) {
                if (sender instanceof Player p && !p.isOp()) {
                    sender.sendMessage("No.");
                    return true;
                }
                // Clear cache and force uploads
                skinCache.remove("blue");
                skinCache.remove("white");
                skinCache.remove("orange");

                config.set("skins.cache.blue.value", "");
                config.set("skins.cache.blue.signature", "");
                config.set("skins.cache.white.value", "");
                config.set("skins.cache.white.signature", "");
                config.set("skins.cache.orange.value", "");
                config.set("skins.cache.orange.signature", "");
                saveConfigFile();

                skinRetryState.clear();

                sender.sendMessage("Cleared cached skins; will reupload shortly.");
                return true;
            }
            sender.sendMessage("Usage: /grid skins status | /grid skins reupload");
            return true;
        }

        sender.sendMessage("Unknown subcommand.");
        return true;
    }

    private String skinRetryLabel(String skinKey) {
        RetryState rs = skinRetryState.get(skinKey);
        if (rs == null) return "MISSING";
        long now = System.currentTimeMillis();
        long wait = Math.max(0L, rs.nextAttemptAtMs - now);
        long sec = wait / 1000L;
        return "RETRY_IN_" + sec + "s";
    }
}