// MercMobs.java
// Author: ChatGPT
// Spigot 1.21.10 + Citizens
package com.redchanit.mercmobs;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.*;
import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class MercMobs extends JavaPlugin implements Listener {

    // -------------------------------------------------------------------------
    // Citizens persistent data keys (stored on NPCs so they survive restarts)
    // -------------------------------------------------------------------------
    private static final String CIT_KEY_MERCMOBS = "mercmobs";
    private static final String CIT_KEY_TYPE = "mercmobs_type";          // "merc" | "green_tnt" | "jackblack"
    private static final String CIT_KEY_COLOR = "mercmobs_color";        // "red" | "blue" | "green" | "yellow" | "none"
    private static final String CIT_KEY_STATE = "mercmobs_state";        // "wild_neutral" | "wild_angry" | "tame"
    private static final String CIT_KEY_OWNER = "mercmobs_owner";        // UUID string
    private static final String CIT_KEY_ASSIST_TARGET = "mercmobs_assist_target"; // UUID string (non-persistent)
    private static final String CIT_KEY_ASSIST_UNTIL_MS = "mercmobs_assist_until_ms"; // long (non-persistent)
    private static final String CIT_KEY_MODE = "mercmobs_mode";          // "follow" | "defend" | "patrol"
    private static final String CIT_KEY_BOWMAN = "mercmobs_bowman";      // boolean
    private static final String CIT_KEY_WEAPON = "mercmobs_weapon";      // "fist" | "bow" | "iron" | "gold" | "diamond"
    private static final String CIT_KEY_ANGRY_TARGET = "mercmobs_angry_target"; // UUID string (player) for wild angry
    private static final String CIT_KEY_LAST_COMBAT = "mercmobs_last_combat";  // long millis
    private static final String CIT_KEY_FUSE_UNTIL = "mercmobs_fuse_until";    // long millis
    private static final String CIT_KEY_FUSE_TARGET = "mercmobs_fuse_target";  // UUID string (player/entity)
    private static final String CIT_KEY_PATROL_ANCHOR = "mercmobs_patrol_anchor"; // serialized "world,x,y,z" best-effort

    // -------------------------------------------------------------------------
    // PDC markers (on entity) for fast recognition
    // -------------------------------------------------------------------------
    private NamespacedKey pdcIsMercKey;
    private NamespacedKey pdcTypeKey;
    private NamespacedKey pdcColorKey;
    private NamespacedKey pdcStateKey;

    // -------------------------------------------------------------------------
    // Citizens / Dynmap
    // -------------------------------------------------------------------------
    private boolean citizensAvailable = false;

    // Dynmap reflection (optional)
    private boolean dynmapAvailable = false;
    private int dynmapInitAttempts = 0;
    private Plugin dynmapPlugin = null;
    private Object dynmapApi = null;
    private Object dynmapMarkerApi = null;

    // Cached dynmap marker spawn points
    private final List<Location> dynmapSpawnMarkers = Collections.synchronizedList(new ArrayList<>());
    // Dynmap markers.yml fallback (when API reflection is unavailable)
    private volatile boolean dynmapFileMarkersAvailable = false;
    private volatile int dynmapFileMarkerCount = 0;

    private int dynmapRefreshTaskId = -1;

    // -------------------------------------------------------------------------
    // MineSkin caching: skinKey -> SkinData(value,signature)
    // skinKey examples:
    //   merc_red_neutral, merc_red_tame, merc_red_angry
    //   merc_green_tnt, jackblack
    // -------------------------------------------------------------------------
    private final Map<String, SkinData> skinCache = new ConcurrentHashMap<>();
    private volatile boolean warnedBlankMineSkinKey = false;


    // -------------------------------------------------------------------------
    // Tracking: entity UUID -> npc id
    // -------------------------------------------------------------------------
    private final Map<UUID, Integer> npcIdByEntity = new ConcurrentHashMap<>();
    private final Map<UUID, String> typeByEntity = new ConcurrentHashMap<>();  // quick runtime hint, not authoritative

    // -------------------------------------------------------------------------
    // Tasks
    // -------------------------------------------------------------------------
    private int spawnTaskId = -1;
    private int aiTaskId = -1;
    private int enforceTaskId = -1;
    private int adoptTaskId = -1;

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------
    private enum MercType { MERC, GREEN_TNT, JACKBLACK }
    private enum MercColor { RED, BLUE, GREEN, YELLOW, NONE }
    private enum MercState { WILD_NEUTRAL, WILD_ANGRY, TAME }
    private enum MercMode { FOLLOW, DEFEND, PATROL }
    private enum WeaponKind { FIST, BOW, IRON, GOLD, DIAMOND }

    private static final class SkinData {
        final String value;
        final String signature;
        SkinData(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }

    // -------------------------------------------------------------------------
    // Enable/Disable
    // -------------------------------------------------------------------------
    @Override
    public void onEnable() {
        pdcIsMercKey = new NamespacedKey(this, "mercmobs");
        pdcTypeKey = new NamespacedKey(this, "mercmobs_type");
        pdcColorKey = new NamespacedKey(this, "mercmobs_color");
        pdcStateKey = new NamespacedKey(this, "mercmobs_state");

        ensureConfigDefaults();     // writes config.yml even with no embedded resource
        ensureSkinFolder();
        loadSkinCacheFromConfig();

        Plugin citizensPlugin = Bukkit.getPluginManager().getPlugin("Citizens");
        citizensAvailable = (citizensPlugin != null && citizensPlugin.isEnabled());
        if (!citizensAvailable) {
            getLogger().warning("Citizens not found/enabled. MercMobs requires Citizens.");
        } else {
            getLogger().info("Citizens detected. MercMobs humanoid NPCs enabled.");
        }

        initDynmap();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("skins.preloadOnEnable", true)) {
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                for (String skinKey : getAllSkinKeys()) {
                    ensureSkinLoaded(skinKey);
                }
                getLogger().info("MineSkin preload complete. Cached skins: " + skinCache.keySet());
            });
        }

        startSpawner();
        startAiLoop();
        startEnforceLoop();
        scheduleAdoptPass();
        startDynmapMarkerRefresh();
    }

    @Override
    public void onDisable() {
        stopTask(spawnTaskId); spawnTaskId = -1;
        stopTask(aiTaskId); aiTaskId = -1;
        stopTask(enforceTaskId); enforceTaskId = -1;
        stopTask(adoptTaskId); adoptTaskId = -1;
        stopTask(dynmapRefreshTaskId); dynmapRefreshTaskId = -1;

        npcIdByEntity.clear();
        typeByEntity.clear();
        dynmapSpawnMarkers.clear();
        skinCache.clear();
    }

    private void stopTask(int id) {
        if (id != -1) Bukkit.getScheduler().cancelTask(id);
    }

    // -------------------------------------------------------------------------
    // Config + folders
    // -------------------------------------------------------------------------
    private void ensureConfigDefaults() {
        FileConfiguration cfg = getConfig();

        cfg.addDefault("enabled", true);

        // Spawning general
        cfg.addDefault("spawning.enabledWorlds", new ArrayList<String>());
        cfg.addDefault("spawning.tickInterval", 200);
        cfg.addDefault("spawning.attemptsPerIntervalPerPlayer", 1);
        cfg.addDefault("spawning.spawnChancePerAttempt", 0.06);
        cfg.addDefault("spawning.spawnRadiusBlocks", 96);
        cfg.addDefault("spawning.minDistanceFromPlayer", 24.0);
        cfg.addDefault("spawning.locationTries", 12);

        cfg.addDefault("spawning.maxPerWorld", 24);
        cfg.addDefault("spawning.maxWildPerWorld", 24);
        cfg.addDefault("spawning.maxTamedPerPlayerSoftCap", 24); // safety; does not hard-block taming by default
        cfg.addDefault("spawning.groupMin", 1);
        cfg.addDefault("spawning.groupMax", 3);

        // Faction weights (normal merc colors)
        cfg.addDefault("spawning.weights.red", 1.0);
        cfg.addDefault("spawning.weights.blue", 1.0);
        cfg.addDefault("spawning.weights.green", 1.0);
        cfg.addDefault("spawning.weights.yellow", 1.0);

        // Bowman chance
        cfg.addDefault("spawning.bowmanChance", 0.15);

        // Jack Black Steve
        cfg.addDefault("jackblack.enabled", false);
        cfg.addDefault("jackblack.spawnChanceMultiplier", 0.20); // relative to normal spawn chance
        cfg.addDefault("jackblack.chat.enabled", true);
        cfg.addDefault("jackblack.chat.range", 18.0);
        cfg.addDefault("jackblack.chat.cooldownTicksMin", 80);
        cfg.addDefault("jackblack.chat.cooldownTicksMax", 160);
        cfg.addDefault("jackblack.chat.cooldownMultiplier", 10.0);
        cfg.addDefault("jackblack.chat.neutralQuotes", Arrays.asList(
                "Welcome to the overworld!",
                "I... am Steve.",
                "This place rules."
        ));
        cfg.addDefault("jackblack.chat.angryQuotes", Arrays.asList(
                "You fuckin' dick!",
                "Come on!",
                "Not cool, man!"
        ));

        // Green TNT Merc
        cfg.addDefault("greenTnt.enabled", true);
        cfg.addDefault("greenTnt.spawnChancePercentOfGreen", 0.35); // VERY rare mutation (% of green spawns)
        cfg.addDefault("greenTnt.explosiveCombat", true);
        cfg.addDefault("greenTnt.explosion.power", 3.0); // creeper-like
        cfg.addDefault("greenTnt.explosion.damageEntities", true);
        cfg.addDefault("greenTnt.explosion.griefingMode", "PLAYER_ONLY"); // PLAYER_ONLY | INCLUDE_NON_PLAYER | OFF
        cfg.addDefault("greenTnt.fuseTicks", 30);
        cfg.addDefault("greenTnt.triggerRange", 3.0);
        cfg.addDefault("greenTnt.cancelRange", 4.5);

        // Aggro rules
        cfg.addDefault("aggro.sameColorCallRadius", 16.0);
        cfg.addDefault("aggro.loseTargetDistance", 32.0);
        cfg.addDefault("aggro.resetAfterSecondsNoCombat", 10);

        // Combat ranges / cooldowns
        cfg.addDefault("combat.hostileEngageRange", 14.0);
        cfg.addDefault("combat.factionEngageRange", 14.0);
        cfg.addDefault("combat.meleeRange", 2.6);
        cfg.addDefault("combat.meleeCooldownTicks", 20);
        cfg.addDefault("combat.bowRange", 18.0);
        cfg.addDefault("combat.bowCooldownTicks", 25);

        // Taming
        cfg.addDefault("taming.ironChance", 0.35);
        cfg.addDefault("taming.goldChance", 0.45);
        cfg.addDefault("taming.diamondChance", 1.00);

        // Tamed behavior
        cfg.addDefault("tamed.followRange", 18.0);
        cfg.addDefault("tamed.teleportIfFartherThan", 28.0);
        cfg.addDefault("tamed.commandHearRange", 32.0);
        cfg.addDefault("tamed.patrolRadius", 20.0);
        cfg.addDefault("tamed.autoDefendOnOwnerMissing", true);

        // Healing
        cfg.addDefault("healing.autoHeal.enabled", true);
        cfg.addDefault("healing.autoHeal.everyTicks", 100);
        cfg.addDefault("healing.autoHeal.amount", 1.0);
        cfg.addDefault("healing.food.enabled", true);

        // Sounds (we keep entities silent and play hurt ourselves)
        cfg.addDefault("sounds.playHurtSoundOnDamage", true);
        cfg.addDefault("sounds.hurt.volume", 1.0);
        cfg.addDefault("sounds.hurt.pitch", 1.0);

        // Skins + MineSkin
        cfg.addDefault("skins.preloadOnEnable", true);
        cfg.addDefault("skins.allowMineskinRequests", true);
        cfg.addDefault("skins.mineskinApiKey", "");
        cfg.addDefault("skins.userAgent", "MercMobs/1.0");
        cfg.addDefault("skins.variant", "classic");
        cfg.addDefault("skins.visibility", "unlisted");
        cfg.addDefault("skins.localFolderName", "skins");

        // Skin files (placed under plugins/MercMobs/skins/)
        // Normal mercs (3 states)
        addSkinFileDefaults(cfg, "red", "redmerc_neutral.png", "redmerc_tame.png", "redmerc_angry.png");
        addSkinFileDefaults(cfg, "blue", "bluemer_neutral.png", "bluemer_tame.png", "bluemer_angry.png");
        addSkinFileDefaults(cfg, "green", "greenmer_neutral.png", "greenmer_tame.png", "greenmer_angry.png");
        addSkinFileDefaults(cfg, "yellow", "yellowmer_neutral.png", "yellowmer_tame.png", "yellowmer_angry.png");

        // Special
        cfg.addDefault("skins.files.green_tnt", "greenmerctnt.png");
        cfg.addDefault("skins.files.jackblack", "jackblacksteve.png");

        // Messages
        cfg.addDefault("messages.prefix.red", "<Red Mercenary> ");
        cfg.addDefault("messages.prefix.blue", "<Blue Mercenary> ");
        cfg.addDefault("messages.prefix.green", "<Green Mercenary> ");
        cfg.addDefault("messages.prefix.yellow", "<Yellow Mercenary> ");
        cfg.addDefault("messages.prefix.jackblack", "<Jack Black> ");

        cfg.addDefault("messages.follow", Arrays.asList(
                "Yes, sir!",
                "I got your six!"
        ));
        cfg.addDefault("messages.defend", Arrays.asList(
                "Awaiting orders.",
                "Holding defensive position.",
                "I will wait right here, sir."
        ));
        cfg.addDefault("messages.patrol", Arrays.asList(
                "Yes, sir!"
        ));
        cfg.addDefault("messages.outOfRangeTemplate", "*You called for your Mercs to %MODE%, but %NUM% were out of range.");

        // Dynmap marker-based spawn points (optional)
        cfg.addDefault("dynmapMarkers.enabled", true);
        cfg.addDefault("dynmapMarkers.refreshTicks", 600);
        cfg.addDefault("dynmapMarkers.requiredLabelContains", Arrays.asList("dmarker"));
        cfg.addDefault("dynmapMarkers.exceptionKeywords", Arrays.asList("nomercs", "no_mercs"));
        cfg.addDefault("dynmapMarkers.useSpawnNearMarkerChance", 0.55);
        cfg.addDefault("dynmapMarkers.spawnNearMarkerRadius", 24.0);

        cfg.options().copyDefaults(true);
        saveConfig(); // NOTE: does not require embedded config.yml
    }

    private void addSkinFileDefaults(FileConfiguration cfg, String color,
                                     String neutralFile, String tameFile, String angryFile) {
        cfg.addDefault("skins.files." + color + ".neutral", neutralFile);
        cfg.addDefault("skins.files." + color + ".tame", tameFile);
        cfg.addDefault("skins.files." + color + ".angry", angryFile);
    }

    private void ensureSkinFolder() {
        File dir = getSkinDirectory();
        if (!dir.exists()) {
            boolean ok = dir.mkdirs();
            if (ok) getLogger().info("Created skin folder: " + dir.getPath());
        }
    }

    private File getSkinDirectory() {
        String folderName = getConfig().getString("skins.localFolderName", "skins");
        if (folderName == null || folderName.trim().isEmpty()) folderName = "skins";
        return new File(getDataFolder(), folderName);
    }

    // -------------------------------------------------------------------------
    // Commands: /mercs <stop|follow|patrol|reload|status>
    // -------------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mercs")) return false;

        if (args.length == 0) {
            sender.sendMessage("Usage: /mercs <stop|follow|patrol|spawn|reload|status>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            reloadConfig();
            ensureSkinFolder();
            loadSkinCacheFromConfig();
            initDynmap();
            sender.sendMessage("MercMobs config reloaded.");
            return true;
        }

        if (sub.equals("status")) {
            sender.sendMessage("MercMobs v" + getDescription().getVersion());
            sender.sendMessage("Citizens: " + (citizensAvailable ? "OK" : "MISSING"));
            sender.sendMessage("Dynmap markers: " + ((dynmapAvailable || dynmapFileMarkersAvailable) ? ("OK (" + dynmapSpawnMarkers.size() + ")") : "MISSING/DISABLED"));
            sender.sendMessage("Tracked merc entities (alive): " + countAliveTracked());
            sender.sendMessage("Skin cache keys: " + skinCache.keySet());
            sender.sendMessage("Skins folder: " + getSkinDirectory().getPath());
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Player-only command.");
            return true;
        }
        Player player = (Player) sender;
		
			if (sub.equals("spawn")) {
				if (!citizensAvailable) {
					player.sendMessage("Citizens is not available.");
					return true;
				}
	
				if (args.length < 2) {
					player.sendMessage("Usage: /mercs spawn <red|blue|green|yellow|green_tnt|jackblack> [count] [bow|melee]");
					return true;
				}
	
				String which = args[1].toLowerCase(Locale.ROOT);
	
				MercType type;
				MercColor color;
	
				switch (which) {
					case "red" -> { type = MercType.MERC; color = MercColor.RED; }
					case "blue" -> { type = MercType.MERC; color = MercColor.BLUE; }
					case "green" -> { type = MercType.MERC; color = MercColor.GREEN; }
					case "yellow" -> { type = MercType.MERC; color = MercColor.YELLOW; }
					case "green_tnt", "tnt", "greentnt" -> { type = MercType.GREEN_TNT; color = MercColor.GREEN; }
					case "jackblack", "jack", "jackblacksteve" -> { type = MercType.JACKBLACK; color = MercColor.NONE; }
					default -> {
						player.sendMessage("Unknown type. Use: red, blue, green, yellow, green_tnt, jackblack");
						return true;
					}
				}
	
				int count = 1;
				if (args.length >= 3) {
					try {
						count = Integer.parseInt(args[2]);
					} catch (NumberFormatException ignored) { }
				}
				count = Math.max(1, Math.min(10, count));
	
				// Optional force mode: bow/melee
				// null = default behavior
				Boolean forceBowman = null;
				if (args.length >= 4) {
					String modeArg = args[3].toLowerCase(Locale.ROOT);
					if (modeArg.equals("bow") || modeArg.equals("bowman")) forceBowman = true;
					else if (modeArg.equals("melee") || modeArg.equals("fist")) forceBowman = false;
				}
	
				                // Debug spawn never blocks on skins, but always try to prepare them first.
                prepareSkinsForCommandSpawn(type, color);

Location base = snapToSurface(player.getLocation());
				if (base == null) {
					base = findSafeNear(player.getLocation(), 4);
				}
				if (base == null) {
					player.sendMessage("Could not find a safe spawn location.");
					return true;
				}
	
				int spawned = 0;
				for (int i = 0; i < count; i++) {
					Location loc = base.clone().add(randomInt(-2, 2) + 0.5, 0.0, randomInt(-2, 2) + 0.5);
					loc = snapToSurface(loc);
					if (loc == null) loc = base;
	
					boolean bowman;
					if (forceBowman != null) {
						bowman = forceBowman;
					} else {
						// Default spawn behavior: TNT only becomes "bowman" if explosiveCombat is disabled.
						bowman = (type != MercType.GREEN_TNT || !getConfig().getBoolean("greenTnt.explosiveCombat", true))
								&& (Math.random() < clamp01(getConfig().getDouble("spawning.bowmanChance", 0.15)));
					}
	
					boolean ok = spawnOne(type, color, loc, bowman);
					if (ok) spawned++;
				}
	
				player.sendMessage("Spawned " + spawned + " MercMobs of type '" + which + "'.");
				return true;
			}


        MercMode requested;
        if (sub.equals("stop")) requested = MercMode.DEFEND;
        else if (sub.equals("follow")) requested = MercMode.FOLLOW;
        else if (sub.equals("patrol")) requested = MercMode.PATROL;
        else {
            sender.sendMessage("Usage: /mercs <stop|follow|patrol|spawn|reload|status>");
            return true;
        }

        int outOfRange = setModeForOwnedMercsInRange(player, requested);

        if (outOfRange > 0) {
            String tmpl = getConfig().getString("messages.outOfRangeTemplate",
                    "*You called for your Mercs to %MODE%, but %NUM% were out of range.");
            tmpl = tmpl.replace("%MODE%", sub).replace("%NUM%", String.valueOf(outOfRange));
            player.sendMessage(tmpl);
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Interaction: taming, healing, owner click toggle defend/follow
    // -------------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!citizensAvailable) return;
        if (!getConfig().getBoolean("enabled", true)) return;

        Player player = event.getPlayer();
        Entity clicked = event.getRightClicked();
        if (clicked == null) return;

        NPC npc = getNpcForEntity(clicked);
        if (npc == null) return;
        if (!isOurNpc(npc, clicked)) return;

        // Prevent off-hand double fire
        if (event.getHand() != EquipmentSlot.HAND) return;

        MercType type = getMercType(npc);
        MercState state = getMercState(npc);
        MercColor color = getMercColor(npc);

        ItemStack hand = player.getInventory().getItemInMainHand();
        Material mat = (hand == null) ? Material.AIR : hand.getType();

        // Food heal (tamed only)
        if (state == MercState.TAME && getConfig().getBoolean("healing.food.enabled", true)) {
            UUID owner = getOwnerUuid(npc);
            if (owner != null && owner.equals(player.getUniqueId())) {
                if (mat != Material.AIR && mat != Material.ROTTEN_FLESH && mat.isEdible()) {
                    LivingEntity living = (clicked instanceof LivingEntity) ? (LivingEntity) clicked : null;
                    if (living != null) {
                        double max = getMaxHealth(living);
                        double before = living.getHealth();
                        double healAmount = computeHealAmount(mat, max);
                        double after = Math.min(max, before + healAmount);
                        if (after > before) {
                            consumeOne(player);
                            living.setHealth(after);
                            spawnHearts(living.getLocation());
                            event.setCancelled(true);
                            return;
                        }
                    }
                }
            }
        }

        // Taming attempt (wild only; TNT merc becomes normal tame green merc)
        if (state != MercState.TAME) {
            if (mat == Material.IRON_INGOT || mat == Material.GOLD_INGOT || mat == Material.DIAMOND) {
                double chance = (mat == Material.IRON_INGOT)
                        ? clamp01(getConfig().getDouble("taming.ironChance", 0.35))
                        : (mat == Material.GOLD_INGOT)
                        ? clamp01(getConfig().getDouble("taming.goldChance", 0.45))
                        : 1.0;

                consumeOne(player);
                boolean success = (Math.random() <= chance);
                if (!success) {
                    spawnTameFailSmoke(clicked.getLocation());
                    event.setCancelled(true);
                    return;
                }

                // Success
                spawnHearts(clicked.getLocation());

                setMercState(npc, MercState.TAME);
                setOwnerUuid(npc, player.getUniqueId());
                setMercMode(npc, MercMode.FOLLOW);
                clearAngryTarget(npc);

                boolean bowman = isBowman(npc);
                WeaponKind weaponKind;
                if (bowman) weaponKind = WeaponKind.BOW;
                else {
                    if (mat == Material.IRON_INGOT) weaponKind = WeaponKind.IRON;
                    else if (mat == Material.GOLD_INGOT) weaponKind = WeaponKind.GOLD;
                    else weaponKind = WeaponKind.DIAMOND;
                }
                setWeaponKind(npc, weaponKind);

                // TNT merc tamed becomes a normal green merc (cosmetic: switch to green tame skin)
                if (type == MercType.GREEN_TNT) {
                    setMercType(npc, MercType.MERC);
                    setMercColor(npc, MercColor.GREEN);
                }

                applyCurrentSkinAndEquipment(npc);

                // Prevent nameplate
                hideNameplate(npc, clicked);

                event.setCancelled(true);
                return;
            }
        }

        // Owner click toggles defend/follow (tamed only)
        if (state == MercState.TAME) {
            UUID owner = getOwnerUuid(npc);
            if (owner != null && owner.equals(player.getUniqueId())) {
                // If holding a taming item or food we already handled, otherwise toggle mode
                MercMode mode = getMercMode(npc);
                MercMode newMode = (mode == MercMode.DEFEND) ? MercMode.FOLLOW : MercMode.DEFEND;
                setMercMode(npc, newMode);

                String msg = randomFrom(getConfig().getStringList(
                        newMode == MercMode.DEFEND ? "messages.defend" : "messages.follow"
                ));
                player.sendMessage(getPrefixFor(color, type) + msg);

                event.setCancelled(true);
            }
        }
    }

    private void consumeOne(Player player) {
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || inHand.getType() == Material.AIR) return;
        int amt = inHand.getAmount();
        if (amt <= 1) player.getInventory().setItemInMainHand(null);
        else inHand.setAmount(amt - 1);
    }

private double computeHealAmount(Material food, double maxHealth) {
    // golden apple = full heal
    if (food == Material.GOLDEN_APPLE || food == Material.ENCHANTED_GOLDEN_APPLE) {
        return maxHealth;
    }

    // Spigot API in your compile jar does not expose Material#getFoodLevel().
    // Use a lightweight fallback table (roughly hunger points) as heal amount.
    switch (food) {
        case COOKED_BEEF:
        case COOKED_PORKCHOP:
        case COOKED_MUTTON:
        case COOKED_SALMON:
        case COOKED_COD:
            return 8.0;

        case BREAD:
        case COOKED_CHICKEN:
        case COOKED_RABBIT:
            return 6.0;

        case APPLE:
        case BAKED_POTATO:
        case CARROT:
            return 4.0;

        case POTATO:
        case BEETROOT:
        case SWEET_BERRIES:
        case GLOW_BERRIES:
        case MELON_SLICE:
            return 2.0;

        default:
            return 2.0;
    }
}

    private void spawnHearts(Location loc) {
        try {
            loc.getWorld().spawnParticle(Particle.HEART, loc.clone().add(0, 1.0, 0), 7, 0.3, 0.4, 0.3, 0.0);
        } catch (Throwable ignored) {}
    }

    private void spawnTameFailSmoke(Location loc) {
        try {
            loc.getWorld().spawnParticle(Particle.SMOKE, loc.clone().add(0, 1.0, 0), 8, 0.3, 0.4, 0.3, 0.0);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Damage rules: aggro on player damage, owner protection
    // -------------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMercDamageByEntity(EntityDamageByEntityEvent event) {
        if (!citizensAvailable) return;

        Entity victim = event.getEntity();
        if (!(victim instanceof LivingEntity)) return;

        NPC npc = getNpcForEntity(victim);
        if (npc == null) return;
        if (!isOurNpc(npc, victim)) return;

        MercState state = getMercState(npc);

        // Owner cannot damage their own tamed merc
        if (state == MercState.TAME) {
            UUID owner = getOwnerUuid(npc);
            UUID damagerPlayer = getDamagerPlayerUuid(event.getDamager());
            if (owner != null && damagerPlayer != null && owner.equals(damagerPlayer)) {
                event.setCancelled(true);
                return;
            }
        }

        // Aggro only triggers if damaged by a PLAYER and the merc is wild (neutral or angry)
        UUID damagerPlayer = getDamagerPlayerUuid(event.getDamager());
        if (damagerPlayer != null && state != MercState.TAME) {
            Player player = Bukkit.getPlayer(damagerPlayer);
            if (player != null && player.isOnline()) {
                // Transition to angry only once: neutral -> angry. Do NOT re-apply skins/equipment on every hit.
                if (state == MercState.WILD_NEUTRAL) {
                    setMercState(npc, MercState.WILD_ANGRY);

                    // Call nearby same-color mercs once on initial provocation (wild only)
                    MercColor color = getMercColor(npc);
                    double callRadius = Math.max(4.0, getConfig().getDouble("aggro.sameColorCallRadius", 16.0));
                    callSameColorMercs(victim.getLocation(), color, callRadius, player.getUniqueId());

                    // Apply angry skin/equipment once (TNT + JackBlack keep static skin)
                    applyCurrentSkinAndEquipment(npc);
                }

                // Always refresh target + combat timestamp while angry
                setAngryTarget(npc, player.getUniqueId());
                setLastCombatNow(npc);

                // If the player has tamed mercs nearby, they should assist against the wild/angry merc the owner attacked.
                try { callOwnedMercsAssist(player, victim.getUniqueId()); } catch (Throwable ignored) {}
}
        } else {
            // any damage updates combat timestamp for timeout logic
            setLastCombatNow(npc);
        }
    }

    
    private boolean isOwnerPlayer(NPC npc, Player player) {
        if (npc == null || player == null) return false;
        UUID owner = getOwnerUuid(npc);
        return owner != null && owner.equals(player.getUniqueId());
    }

private UUID getDamagerPlayerUuid(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Player) return damager.getUniqueId();
        if (damager instanceof Projectile proj) {
            Object shooter = proj.getShooter();
            if (shooter instanceof Player) return ((Player) shooter).getUniqueId();
        }
        return null;
    }

    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMercDamagesOwner(EntityDamageByEntityEvent event) {
        if (!citizensAvailable) return;

        Entity victim = event.getEntity();
        if (!(victim instanceof Player victimPlayer)) return;

        // Damager could be the NPC entity or a projectile shot by it
        Entity damager = event.getDamager();
        NPC npc = null;

        if (damager != null) {
            npc = getNpcForEntity(damager);
            if (npc == null && damager instanceof Projectile proj) {
                Object shooter = proj.getShooter();
                if (shooter instanceof Entity shooterEnt) {
                    npc = getNpcForEntity(shooterEnt);
                }
            }
        }

        if (npc == null) return;
        if (!isOurNpc(npc, npc.getEntity())) return;

        if (getMercState(npc) != MercState.TAME) return;

        if (isOwnerPlayer(npc, victimPlayer)) {
            event.setCancelled(true);
        }
    }

private void callSameColorMercs(Location center, MercColor color, double radius, UUID targetPlayer) {
        if (center == null || center.getWorld() == null) return;
        double r2 = radius * radius;

        for (UUID uuid : new ArrayList<>(npcIdByEntity.keySet())) {
            Entity e = Bukkit.getEntity(uuid);
            if (!(e instanceof LivingEntity)) continue;
            if (!e.getWorld().equals(center.getWorld())) continue;
            if (e.getLocation().distanceSquared(center) > r2) continue;

            NPC other = getNpcByEntityUuid(uuid);
            if (other == null) continue;
            if (!isOurNpc(other, e)) continue;

            if (getMercState(other) == MercState.TAME) continue;
            if (getMercColor(other) != color) continue;

            setMercState(other, MercState.WILD_ANGRY);
            setAngryTarget(other, targetPlayer);
            setLastCombatNow(other);

            applyCurrentSkinAndEquipment(other);
        }
    }

    
// When an owner attacks a wild/angry merc, their tamed mercs in hearing range should assist.
private void callOwnedMercsAssist(Player owner, UUID targetEntityUuid) {
    if (owner == null || targetEntityUuid == null) return;

    double hear = Math.max(8.0, getConfig().getDouble("tamed.commandHearRange", 32.0));
    double hear2 = hear * hear;
    String ownerBase = worldBaseName(owner.getWorld().getName());

    long untilMs = System.currentTimeMillis() + 10_000L; // 10s assist window

    for (UUID entId : new ArrayList<>(npcIdByEntity.keySet())) {
        Entity ent = Bukkit.getEntity(entId);
        if (!(ent instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;

        NPC npc = getNpcByEntityUuid(entId);
        if (npc == null) continue;
        if (!isOurNpc(npc, ent)) continue;

        if (getMercState(npc) != MercState.TAME) continue;
        UUID o = getOwnerUuid(npc);
        if (o == null || !o.equals(owner.getUniqueId())) continue;

        // only same world-group
        String mercBase = worldBaseName(ent.getWorld().getName());
        if (!Objects.equals(mercBase, ownerBase)) continue;

        // must be close enough to "hear" combat call
        if (!ent.getWorld().equals(owner.getWorld())) continue;
        if (ent.getLocation().distanceSquared(owner.getLocation()) > hear2) continue;

        npc.data().set(CIT_KEY_ASSIST_TARGET, targetEntityUuid.toString());
        npc.data().set(CIT_KEY_ASSIST_UNTIL_MS, untilMs);
        setLastCombatNow(npc);

        // If following/patrolling, pursue immediately
        MercMode mode = getMercMode(npc);
        if (mode != MercMode.DEFEND) {
            Entity targetEnt = Bukkit.getEntity(targetEntityUuid);
            if (targetEnt instanceof LivingEntity targetLe && targetLe.isValid() && !targetLe.isDead()
                    && targetLe.getWorld().equals(ent.getWorld())) {
                try {
                    Navigator nav = npc.getNavigator();
                    if (nav != null) nav.setTarget(targetLe, true);
                } catch (Throwable ignored) {}
            }
        }
    }
}

private LivingEntity getAssistTargetIfAny(NPC npc, LivingEntity attacker) {
    if (npc == null || attacker == null) return null;
    Object untilObj = npc.data().get(CIT_KEY_ASSIST_UNTIL_MS);
    Object targetObj = npc.data().get(CIT_KEY_ASSIST_TARGET);
    if (untilObj == null || targetObj == null) return null;

    long untilMs;
    try { untilMs = Long.parseLong(untilObj.toString()); } catch (Throwable t) { return null; }
    if (System.currentTimeMillis() > untilMs) return null;

    UUID targetUuid;
    try { targetUuid = UUID.fromString(targetObj.toString()); } catch (Throwable t) { return null; }

    Entity e = Bukkit.getEntity(targetUuid);
    if (!(e instanceof LivingEntity le)) return null;
    if (le.isDead() || !le.isValid()) return null;
    if (!le.getWorld().equals(attacker.getWorld())) return null;

    // Do not ever treat the owner as a valid assist target.
    if (le instanceof Player p && getMercState(npc) == MercState.TAME && isOwnerPlayer(npc, p)) return null;

    return le;
}

// -------------------------------------------------------------------------
    // Sounds: only player hurt sound on damage/death (we keep them silent)
    // -------------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMercDamaged(EntityDamageEvent event) {
        if (!citizensAvailable) return;
        if (!getConfig().getBoolean("sounds.playHurtSoundOnDamage", true)) return;

        Entity victim = event.getEntity();
        NPC npc = getNpcForEntity(victim);
        if (npc == null) return;
        if (!isOurNpc(npc, victim)) return;

        if (!(victim instanceof LivingEntity living)) return;

        // Only play if real damage occurred (avoid "click" sounds when damage is cancelled/0)
        try {
            if (event.getFinalDamage() <= 0.0) return;
        } catch (Throwable ignored) {}

        // Cooldown to prevent spam (multiple hits per tick, cancelled edge cases, etc.)
        long nowMs = System.currentTimeMillis();
        long lastMs = getLong(npc, "mercmobs_last_hurt_sound_ms", 0L);
        if (nowMs - lastMs < 350L) return;
        setLong(npc, "mercmobs_last_hurt_sound_ms", nowMs);

        float volume = (float) getConfig().getDouble("sounds.hurt.volume", 1.0);
        float pitch = (float) getConfig().getDouble("sounds.hurt.pitch", 1.0);
        try {
            living.getWorld().playSound(living.getLocation(), Sound.ENTITY_PLAYER_HURT, volume, pitch);
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Death: drops + no obituary
    // -------------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMercNpcPlayerDeath(PlayerDeathEvent event) {
        if (!citizensAvailable) return;

        Player dead = event.getEntity();
        NPC npc = getNpcForEntity(dead);
        if (npc == null) return;
        if (!isOurNpc(npc, dead)) return;

        // suppress death message
        try { event.setDeathMessage(null); } catch (Throwable ignored) {}
        trySuppressAdventureDeathMessage(event);

        // Override drops exactly per spec
        try { event.getDrops().clear(); } catch (Throwable ignored) {}

        MercState state = getMercState(npc);
        WeaponKind weaponKind = getWeaponKind(npc);

        if (state == MercState.TAME) {
            // drop weapon only, random durability, no exp
            ItemStack weapon = weaponItemFor(weaponKind);
            if (weapon != null) event.getDrops().add(withRandomDurability(weapon));
            event.setDroppedExp(0);
        } else {
            // wild: exp + items
            int exp = 5;
            event.setDroppedExp(exp);

            boolean bowman = isBowman(npc);
            if (bowman) {
                event.getDrops().add(withRandomDurability(new ItemStack(Material.BOW, 1)));
                addRandomDrop(event.getDrops(), Material.ARROW, 0, 2);
            } else {
                addRandomDrop(event.getDrops(), Material.STRING, 0, 2);
                addRandomDrop(event.getDrops(), Material.FEATHER, 0, 2);

                // "0-2 gunpowder OR 0-1 flint and steel"
                if (Math.random() < 0.80) addRandomDrop(event.getDrops(), Material.GUNPOWDER, 0, 2);
                else addRandomDrop(event.getDrops(), Material.FLINT_AND_STEEL, 0, 1);
            }
        }

        // play "hurt" on death too (per spec: hurt sound upon dying)
        try {
            dead.getWorld().playSound(dead.getLocation(), Sound.ENTITY_PLAYER_HURT,
                    (float) getConfig().getDouble("sounds.hurt.volume", 1.0),
                    (float) getConfig().getDouble("sounds.hurt.pitch", 1.0));
        } catch (Throwable ignored) {}
    
        // Ensure this NPC is fully removed from Citizens registry so it doesn't linger in /npc list
        Bukkit.getScheduler().runTask(this, () -> destroyOurNpc(npc, dead.getUniqueId()));

    }

@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onMercNpcEntityDeath(EntityDeathEvent event) {
    if (!citizensAvailable) return;

    Entity ent = event.getEntity();
    NPC npc = getNpcForEntity(ent);
    if (npc == null) return;
    if (!isOurNpc(npc, ent)) return;

    // If PlayerDeathEvent didn't fire (Citizens variations), ensure we still deregister.
    Bukkit.getScheduler().runTask(this, () -> destroyOurNpc(npc, ent.getUniqueId()));
}


    private void trySuppressAdventureDeathMessage(PlayerDeathEvent event) {
        // Paper/Adventure compatibility (safe on Spigot; method will not exist)
        try {
            Method m = event.getClass().getMethod("deathMessage", Class.forName("net.kyori.adventure.text.Component"));
            m.invoke(event, new Object[]{null});
        } catch (Throwable ignored) {}
    }

    private void addRandomDrop(List<ItemStack> drops, Material material, int min, int max) {
        if (drops == null || material == null) return;
        if (max < min) max = min;
        int amount = (min == max) ? min : (min + (int) Math.floor(Math.random() * (max - min + 1)));
        if (amount <= 0) return;
        drops.add(new ItemStack(material, amount));
    }

    private ItemStack weaponItemFor(WeaponKind kind) {
        return switch (kind) {
            case BOW -> new ItemStack(Material.BOW, 1);
            case IRON -> new ItemStack(Material.IRON_SWORD, 1);
            case GOLD -> new ItemStack(Material.GOLDEN_SWORD, 1);
            case DIAMOND -> new ItemStack(Material.DIAMOND_SWORD, 1);
            default -> null;
        };
    }

    private ItemStack withRandomDurability(ItemStack item) {
        try {
            int max = item.getType().getMaxDurability();
            if (max <= 0) return item;
            ItemStack clone = item.clone();
            if (clone.getItemMeta() instanceof Damageable dmg) {
                int damage = (int) Math.floor(Math.random() * Math.max(1, max));
                dmg.setDamage(damage);
                clone.setItemMeta(dmg);
            }
            return clone;
        } catch (Throwable ignored) {
            return item;
        }
    }

    // -------------------------------------------------------------------------
    // Owner disconnect/world switch -> auto DEFEND without chat
    // -------------------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onOwnerQuit(PlayerQuitEvent event) {
        Player owner = event.getPlayer();
        if (owner == null) return;
        if (!getConfig().getBoolean("tamed.autoDefendOnOwnerMissing", true)) return;
        autoDefendOwnedMercs(owner.getUniqueId(), null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onOwnerWorldChange(PlayerChangedWorldEvent event) {
        Player owner = event.getPlayer();
        if (owner == null) return;

        // If moved to unrelated world group -> auto DEFEND
        String oldBase = worldBaseName(event.getFrom().getName());
        String newBase = worldBaseName(owner.getWorld().getName());

        if (!Objects.equals(oldBase, newBase)) {
            if (getConfig().getBoolean("tamed.autoDefendOnOwnerMissing", true)) {
                autoDefendOwnedMercs(owner.getUniqueId(), newBase);
            }
        } else {
            // same group (overworld/nether/end) -> best-effort teleport followers soon
            Bukkit.getScheduler().runTaskLater(this, () -> {
                teleportFollowersToOwnerIfNeeded(owner);
            }, 40L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onOwnerTeleport(PlayerTeleportEvent event) {
        Player owner = event.getPlayer();
        if (owner == null) return;

        // Treat cross-world teleports similarly to world change behavior.
        String fromBase = worldBaseName(event.getFrom().getWorld() != null ? event.getFrom().getWorld().getName() : owner.getWorld().getName());
        String toBase = worldBaseName(owner.getWorld().getName());

        if (!Objects.equals(fromBase, toBase)) {
            if (getConfig().getBoolean("tamed.autoDefendOnOwnerMissing", true)) {
                autoDefendOwnedMercs(owner.getUniqueId(), toBase);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(this, () -> teleportFollowersToOwnerIfNeeded(owner), 40L);
        }
    }


    private void autoDefendOwnedMercs(UUID ownerUuid, String allowedBaseGroupOrNull) {
        for (UUID entId : new ArrayList<>(npcIdByEntity.keySet())) {
            Entity e = Bukkit.getEntity(entId);
            NPC npc = getNpcByEntityUuid(entId);
            if (npc == null || e == null) continue;
            if (!isOurNpc(npc, e)) continue;

            if (getMercState(npc) != MercState.TAME) continue;

            UUID owner = getOwnerUuid(npc);
            if (owner == null || !owner.equals(ownerUuid)) continue;

            // if allowed group provided and merc already in that group, we can keep it (but spec says defensive if unrelated)
            if (allowedBaseGroupOrNull != null) {
                String mercBase = worldBaseName(e.getWorld().getName());
                if (Objects.equals(allowedBaseGroupOrNull, mercBase)) {
                    // still place in DEFEND (spec: owner switches to unrelated -> defensive; here is unrelated already handled)
                }
            }

            setMercMode(npc, MercMode.DEFEND);
        }
    }

    // -------------------------------------------------------------------------
    // Chunk adoption / maintenance
    // -------------------------------------------------------------------------
    @EventHandler(ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!citizensAvailable) return;
        Chunk chunk = event.getChunk();
        Bukkit.getScheduler().runTaskLater(this, () -> adoptNpcsInChunk(chunk), 1L);
    }

    private void scheduleAdoptPass() {
        if (!citizensAvailable) return;
        stopTask(adoptTaskId);
        adoptTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(this, this::adoptAllSpawnedNpcs, 40L);
        Bukkit.getScheduler().runTaskLater(this, this::adoptAllSpawnedNpcs, 220L);
    }

    private void adoptAllSpawnedNpcs() {
        if (!citizensAvailable) return;
        NPCRegistry reg;
        try { reg = CitizensAPI.getNPCRegistry(); } catch (Throwable t) { return; }
        if (reg == null) return;

        for (NPC npc : reg) {
            if (npc == null) continue;
            if (!npc.isSpawned()) continue;
            Entity ent = npc.getEntity();
            if (ent == null) continue;

            if (!isOurNpc(npc, ent)) continue;
            adoptNpc(npc, ent);
        }
    }

    private void adoptNpcsInChunk(Chunk chunk) {
        if (!citizensAvailable) return;
        try {
            for (Entity ent : chunk.getEntities()) {
                if (ent == null) continue;
                NPC npc = getNpcForEntity(ent);
                if (npc == null) continue;
                if (!npc.isSpawned()) continue;
                if (!isOurNpc(npc, ent)) continue;
                adoptNpc(npc, ent);
            }
        } catch (Throwable ignored) {}
    }

    private void adoptNpc(NPC npc, Entity ent) {
        try { npc.data().setPersistent(CIT_KEY_MERCMOBS, true); } catch (Throwable ignored) {}

        // Ensure tags exist (defaults if missing)
        if (npc.data().get(CIT_KEY_TYPE) == null) npc.data().setPersistent(CIT_KEY_TYPE, "merc");
        if (npc.data().get(CIT_KEY_COLOR) == null) npc.data().setPersistent(CIT_KEY_COLOR, "red");
        if (npc.data().get(CIT_KEY_STATE) == null) npc.data().setPersistent(CIT_KEY_STATE, "wild_neutral");
        if (npc.data().get(CIT_KEY_MODE) == null) npc.data().setPersistent(CIT_KEY_MODE, "follow");
        if (npc.data().get(CIT_KEY_BOWMAN) == null) npc.data().setPersistent(CIT_KEY_BOWMAN, false);
        if (npc.data().get(CIT_KEY_WEAPON) == null) npc.data().setPersistent(CIT_KEY_WEAPON, "fist");

        // Entity tags
        tagEntity(ent, npc);

        // Track
        npcIdByEntity.put(ent.getUniqueId(), npc.getId());
        typeByEntity.put(ent.getUniqueId(), npc.data().get(CIT_KEY_TYPE).toString());

        // Hide nameplate
        hideNameplate(npc, ent);

        // Collisions + silence
        enforceEntityFlags(ent);


        ensureNpcVulnerable(npc);
        // Ensure skin + equipment applied
        applyCurrentSkinAndEquipment(npc);
    }

    // -------------------------------------------------------------------------
    // Spawning
    // -------------------------------------------------------------------------
    private void startSpawner() {
        if (!citizensAvailable) return;
        stopTask(spawnTaskId);

        int intervalTicks = Math.max(20, getConfig().getInt("spawning.tickInterval", 200));
        spawnTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("enabled", true)) return;

            for (World world : Bukkit.getWorlds()) {
                List<String> enabledWorlds = getConfig().getStringList("spawning.enabledWorlds");
                if (enabledWorlds != null && !enabledWorlds.isEmpty() && !enabledWorlds.contains(world.getName())) continue;

                List<Player> players = world.getPlayers();
                if (players.isEmpty()) continue;

                int maxPerWorld = Math.max(0, getConfig().getInt("spawning.maxPerWorld", 24));
                int current = countAliveInWorld(world);
                if (current >= maxPerWorld) continue;

                int attemptsPerPlayer = Math.max(0, getConfig().getInt("spawning.attemptsPerIntervalPerPlayer", 1));
                int radius = Math.max(16, getConfig().getInt("spawning.spawnRadiusBlocks", 96));
                double spawnChance = clamp01(getConfig().getDouble("spawning.spawnChancePerAttempt", 0.06));
                double minDistance = Math.max(8.0, getConfig().getDouble("spawning.minDistanceFromPlayer", 24.0));

                for (Player p : players) {
                    for (int a = 0; a < attemptsPerPlayer; a++) {
                        if (current >= maxPerWorld) break;
                        if (Math.random() > spawnChance) continue;

                        // Jack Black Steve (rare)
                        boolean spawnJack = getConfig().getBoolean("jackblack.enabled", false)
                                && (Math.random() < getConfig().getDouble("jackblack.spawnChanceMultiplier", 0.20));
                        SpawnPlan plan;
                        if (spawnJack) {
                            plan = new SpawnPlan(MercType.JACKBLACK, MercColor.NONE, 1);
                        } else {
                            MercColor color = pickColorByWeight();
                            int minG = Math.max(1, getConfig().getInt("spawning.groupMin", 1));
                            int maxG = Math.max(minG, getConfig().getInt("spawning.groupMax", 3));
                            int group = randomInt(minG, maxG);

                            // Green TNT mutation
                            if (color == MercColor.GREEN && getConfig().getBoolean("greenTnt.enabled", true)) {
                                double pct = Math.max(0.0, getConfig().getDouble("greenTnt.spawnChancePercentOfGreen", 0.35));
                                // interpret as percent chance of green spawns (so 0.35% by default)
                                if (Math.random() < (pct / 100.0)) {
                                    plan = new SpawnPlan(MercType.GREEN_TNT, MercColor.GREEN, 1);
                                } else {
                                    plan = new SpawnPlan(MercType.MERC, color, group);
                                }
                            } else {
                                plan = new SpawnPlan(MercType.MERC, color, group);
                            }
                        }

                        Location origin = pickSpawnOrigin(world, p.getLocation(), radius);
                        if (origin == null) continue;
                        if (origin.distanceSquared(p.getLocation()) < (minDistance * minDistance)) continue;

                        boolean ok = spawnGroup(plan, origin);
                        if (ok) current++;
                    }
                }
            }
        }, intervalTicks, intervalTicks);
    }

    private static final class SpawnPlan {
        final MercType type;
        final MercColor color;
        final int count;
        SpawnPlan(MercType type, MercColor color, int count) {
            this.type = type; this.color = color; this.count = count;
        }
    }

    private String skinKeyMerc(MercColor color, String state) {
        return "merc_" + color.name().toLowerCase(Locale.ROOT) + "_" + state;
    }

    private List<String> requiredSkinKeysFor(MercType type, MercColor color) {
        ArrayList<String> keys = new ArrayList<>();

        if (type == MercType.MERC) {
            keys.add(skinKeyMerc(color, "neutral"));
            keys.add(skinKeyMerc(color, "tame"));
            keys.add(skinKeyMerc(color, "angry"));
            return keys;
        }

        if (type == MercType.GREEN_TNT) {
            // TNT skin is static; also require green's normal set so taming/aggro never shows default skins.
            keys.add("merc_green_tnt");
            keys.add(skinKeyMerc(MercColor.GREEN, "neutral"));
            keys.add(skinKeyMerc(MercColor.GREEN, "tame"));
            keys.add(skinKeyMerc(MercColor.GREEN, "angry"));
            return keys;
        }

        if (type == MercType.JACKBLACK) {
            keys.add("jackblack");
            return keys;
        }

        return keys;
    }
	
    // Natural spawning: hard requirement. If any required skin is missing/unavailable, skip spawning.
    private boolean skinsReadyForNaturalSpawn(MercType type, MercColor color) {
		boolean allReady = true;
		for (String key : requiredSkinKeysFor(type, color)) {
			String k = key.toLowerCase(Locale.ROOT);
			if (!skinCache.containsKey(k)) {
				allReady = false;
			}
		}
		if (!allReady) {
			prepareSkinsForCommandSpawn(type, color); // schedules async ensureSkinLoaded calls
		}
		return allReady;
	}


    // /mercs spawn: never block, but always try to prepare the needed skins asynchronously.
    private void prepareSkinsForCommandSpawn(MercType type, MercColor color) {
        for (String key : requiredSkinKeysFor(type, color)) {
            String k = key.toLowerCase(Locale.ROOT);
            if (skinCache.containsKey(k)) continue;

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    ensureSkinLoaded(key);
                } catch (Throwable ignored) { }
            });
        }
    }

private Location pickSpawnOrigin(World world, Location nearPlayer, int radius) {
        // Prefer dynmap marker proximity sometimes
        boolean useMarkers = dynmapAvailable
                && getConfig().getBoolean("dynmapMarkers.enabled", true)
                && (Math.random() < clamp01(getConfig().getDouble("dynmapMarkers.useSpawnNearMarkerChance", 0.55)))
                && !dynmapSpawnMarkers.isEmpty();

        if (useMarkers) {
            Location marker = pickMarkerInWorld(world);
            if (marker != null) {
                double mr = Math.max(8.0, getConfig().getDouble("dynmapMarkers.spawnNearMarkerRadius", 24.0));
                Location candidate = pickLocationNear(marker, (int) Math.round(mr));
                if (candidate != null) return candidate;
            }
        }

        // Wild spawn near player
        return pickLocationNear(nearPlayer, radius);
    }

    private Location pickMarkerInWorld(World world) {
        synchronized (dynmapSpawnMarkers) {
            List<Location> candidates = new ArrayList<>();
            for (Location loc : dynmapSpawnMarkers) {
                if (loc != null && loc.getWorld() != null && loc.getWorld().getName().equals(world.getName())) {
                    candidates.add(loc);
                }
            }
            if (candidates.isEmpty()) return null;
            return candidates.get(randomInt(0, candidates.size() - 1)).clone();
        }
    }

    private boolean spawnGroup(SpawnPlan plan, Location origin) {
        if (!citizensAvailable) return false;
        if (origin == null || origin.getWorld() == null) return false;


        // Natural spawning requires skins; if not ready, skip this spawn group.
        if (!skinsReadyForNaturalSpawn(plan.type, plan.color)) return false;

        int maxWild = Math.max(0, getConfig().getInt("spawning.maxWildPerWorld", 24));
        if (countWildInWorld(origin.getWorld()) >= maxWild) return false;

        int spawned = 0;
        for (int i = 0; i < plan.count; i++) {
            Location loc = origin.clone().add(randomInt(-3, 3) + 0.5, 0.0, randomInt(-3, 3) + 0.5);
            loc = snapToSurface(loc);
            if (loc == null) continue;

            boolean bowman = (plan.type != MercType.GREEN_TNT || !getConfig().getBoolean("greenTnt.explosiveCombat", true))
                    && (Math.random() < clamp01(getConfig().getDouble("spawning.bowmanChance", 0.15)));

            boolean ok = spawnOne(plan.type, plan.color, loc, bowman);
            if (ok) spawned++;
        }
        return spawned > 0;
    }

    private Location snapToSurface(Location base) {
        World w = base.getWorld();
        if (w == null) return null;

        int x = base.getBlockX();
        int z = base.getBlockZ();
        int y = w.getHighestBlockYAt(x, z);
        if (y <= w.getMinHeight() + 1) return null;
        if (y >= w.getMaxHeight() - 3) return null;

        Location feet = new Location(w, x + 0.5, y, z + 0.5);
        if (!feet.getBlock().getType().isAir()) return null;
        if (!feet.clone().add(0, 1, 0).getBlock().getType().isAir()) return null;
        if (feet.clone().add(0, -1, 0).getBlock().isLiquid()) return null;
        return feet;
    }

    private Location pickLocationNear(Location origin, int radius) {
        if (origin == null || origin.getWorld() == null) return null;

        World w = origin.getWorld();
        int tries = Math.max(5, getConfig().getInt("spawning.locationTries", 12));
        for (int i = 0; i < tries; i++) {
            int dx = randomInt(-radius, radius);
            int dz = randomInt(-radius, radius);

            int x = origin.getBlockX() + dx;
            int z = origin.getBlockZ() + dz;

            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (!isChunkGeneratedSafe(w, chunkX, chunkZ)) continue;

            Chunk chunk = w.getChunkAt(chunkX, chunkZ);
            if (!chunk.isLoaded()) chunk.load();

            int y = w.getHighestBlockYAt(x, z) + 1;
            Location loc = new Location(w, x + 0.5, y, z + 0.5);
            // need two blocks of headroom and solid-ish ground
            if (!loc.getBlock().getType().isAir()) continue;
            if (!loc.clone().add(0, 1, 0).getBlock().getType().isAir()) continue;
            if (loc.clone().add(0, -1, 0).getBlock().isLiquid()) continue;
            if (!loc.clone().add(0, -1, 0).getBlock().getType().isSolid()) continue;

            return loc;
        }
        return null;
    }

    private boolean isChunkGeneratedSafe(World world, int chunkX, int chunkZ) {
        try {
            return world.isChunkGenerated(chunkX, chunkZ);
        } catch (Throwable ignored) {
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            return chunk.isLoaded();
        }
    }

    private int countAliveInWorld(World world) {
        int count = 0;
        Iterator<Map.Entry<UUID, Integer>> it = npcIdByEntity.entrySet().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next().getKey();
            Entity ent = Bukkit.getEntity(uuid);
            if (ent == null || !ent.isValid() || ent.isDead()) {
                it.remove();
                typeByEntity.remove(uuid);
                continue;
            }
            if (ent.getWorld().equals(world)) count++;
        }
        return count;
    }

    private int countWildInWorld(World world) {
        int count = 0;
        for (UUID uuid : new ArrayList<>(npcIdByEntity.keySet())) {
            Entity ent = Bukkit.getEntity(uuid);
            if (ent == null || !ent.isValid() || ent.isDead()) continue;
            if (!ent.getWorld().equals(world)) continue;
            NPC npc = getNpcByEntityUuid(uuid);
            if (npc == null) continue;
            if (!isOurNpc(npc, ent)) continue;
            if (getMercState(npc) != MercState.TAME) count++;
        }
        return count;
    }

    private boolean spawnOne(MercType type, MercColor color, Location spawnLoc, boolean bowman) {
        if (!citizensAvailable) return false;

        try {
            NPCRegistry registry = CitizensAPI.getNPCRegistry();
            if (registry == null) return false;

            String profileName = generateProfileName(type, color);
            NPC npc = registry.createNPC(EntityType.PLAYER, profileName);

            // Make it damageable/mob-like
            npc.setProtected(false);


            ensureNpcVulnerable(npc);
            // Persist ownership markers
            npc.data().setPersistent(CIT_KEY_MERCMOBS, true);
            npc.data().setPersistent(CIT_KEY_TYPE, typeToString(type));
            npc.data().setPersistent(CIT_KEY_COLOR, colorToString(color));
            npc.data().setPersistent(CIT_KEY_STATE, "wild_neutral");
            npc.data().setPersistent(CIT_KEY_MODE, "follow");
            npc.data().setPersistent(CIT_KEY_BOWMAN, bowman);
            npc.data().setPersistent(CIT_KEY_WEAPON, bowman ? "bow" : "fist");

            // Hide nameplate
            npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false);

            // Spawn
            boolean ok = npc.spawn(spawnLoc);
            if (!ok) return false;

            Entity ent = npc.getEntity();
            if (ent == null) return false;

            // Tag entity + track
            tagEntity(ent, npc);
            npcIdByEntity.put(ent.getUniqueId(), npc.getId());
            typeByEntity.put(ent.getUniqueId(), typeToString(type));

            // Health 20
            if (ent instanceof LivingEntity living) {
                setMaxHealth(living, 20.0);
                living.setHealth(Math.min(living.getHealth(), 20.0));
            }

            // Silence + collisions + no name
            hideNameplate(npc, ent);
            enforceEntityFlags(ent);

            // Skin + equipment
            applyCurrentSkinAndEquipment(npc);

            // Re-apply after a short delay for client reliability
            Bukkit.getScheduler().runTaskLater(this, () -> {
                NPC again = getNpcByEntityUuid(ent.getUniqueId());
                if (again != null && again.isSpawned()) {
                    try { again.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false); } catch (Throwable ignored) {}
                    applyCurrentSkinAndEquipment(again);
                }
            }, 10L);

            return true;
        } catch (Throwable ex) {
            getLogger().warning("Spawn failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    private String generateProfileName(MercType type, MercColor color) {
        // 16 chars max; keep them short but unique
        String base = switch (type) {
            case JACKBLACK -> "JackBlack";
            case GREEN_TNT -> "GreenTNT";
            default -> switch (color) {
                case RED -> "RedMerc";
                case BLUE -> "BlueMerc";
                case GREEN -> "GreenMerc";
                case YELLOW -> "YelMerc";
                default -> "Merc";
            };
        };
        int r = randomInt(1000, 9999);
        String name = base + r;
        if (name.length() > 16) name = name.substring(0, 16);
        return toValidUsername(name);
    }

    // -------------------------------------------------------------------------
    // AI loop: wander/follow/patrol/defend/combat + TNT fuse + JackBlack chat
    // -------------------------------------------------------------------------
    private void startAiLoop() {
        if (!citizensAvailable) return;
        stopTask(aiTaskId);

        int period = 10; // tick rate for lightweight logic
        aiTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!getConfig().getBoolean("enabled", true)) return;

            int meleeCooldown = Math.max(5, getConfig().getInt("combat.meleeCooldownTicks", 20));
            int bowCooldown = Math.max(5, getConfig().getInt("combat.bowCooldownTicks", 25));

            for (UUID entId : new ArrayList<>(npcIdByEntity.keySet())) {
                try {
                Entity ent = Bukkit.getEntity(entId);
                if (!(ent instanceof LivingEntity living) || ent.isDead() || !ent.isValid()) {
                    npcIdByEntity.remove(entId);
                    typeByEntity.remove(entId);
                    continue;
                }

                NPC npc = getNpcByEntityUuid(entId);
                if (npc == null || !npc.isSpawned()) continue;
                if (!isOurNpc(npc, ent)) continue;

                // Keep flags enforced
                enforceEntityFlags(ent);
                hideNameplate(npc, ent);

                MercType type = getMercType(npc);
                MercState state = getMercState(npc);
                MercMode mode = getMercMode(npc);

                // Auto-heal tamed
                maybeAutoHeal(npc, living);

                // Angry timeout (wild)
                if (state == MercState.WILD_ANGRY) {
                    handleWildAngryTimeout(npc, living);
                }

                // JackBlack chat
                if (type == MercType.JACKBLACK && getConfig().getBoolean("jackblack.chat.enabled", true)) {
                    handleJackBlackChat(npc, living);
                }

                // Determine target + behavior
                if (state == MercState.TAME) {
                    handleTamedBehavior(npc, living, mode);
                } else {
                    handleWildBehavior(npc, living);
                }

                // Attacks (melee/bow/tnt)
                handleAttacks(npc, living, meleeCooldown, bowCooldown);
                } catch (Throwable t) {
                    // Keep loop alive even if one NPC tick errors
                }
            }
        }, period, period);
    }

    private void handleWildAngryTimeout(NPC npc, LivingEntity living) {
        UUID target = getAngryTarget(npc);
        double loseDist = Math.max(8.0, getConfig().getDouble("aggro.loseTargetDistance", 32.0));
        double loseDist2 = loseDist * loseDist;

        Player p = (target == null) ? null : Bukkit.getPlayer(target);
        if (p == null || !p.isOnline() || !p.getWorld().equals(living.getWorld())
                || p.getLocation().distanceSquared(living.getLocation()) > loseDist2) {

            int resetSecs = Math.max(2, getConfig().getInt("aggro.resetAfterSecondsNoCombat", 10));
            long last = getLastCombatMillis(npc);
            if (last > 0 && (System.currentTimeMillis() - last) > (resetSecs * 1000L)) {
                // revert to neutral
                setMercState(npc, MercState.WILD_NEUTRAL);
                clearAngryTarget(npc);
                applyCurrentSkinAndEquipment(npc);
            }
        }
    }

    private void handleTamedBehavior(NPC npc, LivingEntity living, MercMode mode) {
        UUID ownerUuid = getOwnerUuid(npc);
        if (ownerUuid == null) {
            // no owner -> defensive idle
            setMercMode(npc, MercMode.DEFEND);
            return;
        }

        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner == null || !owner.isOnline()) {
            if (getConfig().getBoolean("tamed.autoDefendOnOwnerMissing", true)) {
                setMercMode(npc, MercMode.DEFEND);
            }
            return;
        }

        // World-group follow rule: only follow across overworld/nether/end if base matches
        String mercBase = worldBaseName(living.getWorld().getName());
        String ownerBase = worldBaseName(owner.getWorld().getName());
        if (!Objects.equals(mercBase, ownerBase)) {
            // unrelated -> defensive
            setMercMode(npc, MercMode.DEFEND);
            return;
        }

        // FOLLOW: path to owner + teleport if far (safe across world transitions)
                if (mode == MercMode.FOLLOW) {
                    // followRange = distance at which the merc starts moving toward the owner
                    double followRange = Math.max(6.0, getConfig().getDouble("tamed.followRange", 14.0));
                    // followStopDistance = "personal space" distance; within this, the merc should stop navigating
                    double followStop = Math.max(2.0, getConfig().getDouble("tamed.followStopDistance", 4.0));
                    double teleportDist = Math.max(followRange + 6.0, getConfig().getDouble("tamed.teleportIfFartherThan", 28.0));

                    boolean differentWorld = !owner.getWorld().equals(living.getWorld());
                    if (differentWorld) {
                        // Same world-group but different actual world (overworld/nether/end). Teleport near owner.
                        Location tp = findSafeNear(owner.getLocation(), 3);
                        if (tp != null) {
                            try { living.teleport(tp); } catch (Throwable ignored) {}
                        }
                    } else {
                        Location ownerLoc = owner.getLocation();
                        Location mercLoc = living.getLocation();

                        double d2 = ownerLoc.distanceSquared(mercLoc);

                        if (d2 > teleportDist * teleportDist) {
                            Location tp = findSafeNear(ownerLoc, 3);
                            if (tp != null) {
                                try { living.teleport(tp); } catch (Throwable ignored) {}
                            }
                        } else if (d2 <= followStop * followStop) {
                            // Too close: stop navigating so we don't crowd the owner
                            Navigator nav = npc.getNavigator();
                            if (nav != null && nav.isNavigating()) {
                                try { nav.cancelNavigation(); } catch (Throwable ignored) {}
                            }
                        } else if (d2 > followRange * followRange) {
                            Navigator nav = npc.getNavigator();
                            if (nav != null && !nav.isNavigating()) {
                                try { nav.getLocalParameters().speedModifier(1.05f); } catch (Throwable ignored) {}
                                // Citizens Navigator in your build does not accept an Entity target; use a Location target.
                                nav.setTarget(ownerLoc);
                            }
                        }
                    }
                }

        // PATROL: wander around owner within patrol radius
        if (mode == MercMode.PATROL) {
            double pr = Math.max(8.0, getConfig().getDouble("tamed.patrolRadius", 20.0));
            Navigator nav = npc.getNavigator();
            if (nav != null && !nav.isNavigating()) {
                Location anchor = owner.getLocation().clone();
                Location target = pickWanderTarget(anchor, (int) Math.round(pr));
                if (target != null) {
                    try { nav.getLocalParameters().speedModifier(1.0f); } catch (Throwable ignored) {}
                    nav.setTarget(target);
                }
            }
        }

        // DEFEND: do not navigate (stand still); attacks handled elsewhere
        if (mode == MercMode.DEFEND) {
            Navigator nav = npc.getNavigator();
            if (nav != null && nav.isNavigating()) {
                try { nav.cancelNavigation(); } catch (Throwable ignored) {}
            }
        }
    }

    private void teleportFollowersToOwnerIfNeeded(Player owner) {
        UUID ownerUuid = owner.getUniqueId();
        String ownerBase = worldBaseName(owner.getWorld().getName());

        for (UUID entId : new ArrayList<>(npcIdByEntity.keySet())) {
            Entity ent = Bukkit.getEntity(entId);
            if (!(ent instanceof LivingEntity living) || ent.isDead() || !ent.isValid()) continue;

            NPC npc = getNpcByEntityUuid(entId);
            if (npc == null) continue;
            if (!isOurNpc(npc, ent)) continue;

            if (getMercState(npc) != MercState.TAME) continue;
            UUID o = getOwnerUuid(npc);
            if (o == null || !o.equals(ownerUuid)) continue;

            if (getMercMode(npc) != MercMode.FOLLOW) continue;

            String mercBase = worldBaseName(living.getWorld().getName());
            if (!Objects.equals(ownerBase, mercBase)) continue;

            double teleportDist = Math.max(18.0, getConfig().getDouble("tamed.teleportIfFartherThan", 28.0));
            if (!living.getWorld().equals(owner.getWorld())
                    || living.getLocation().distanceSquared(owner.getLocation()) > teleportDist * teleportDist) {
                Location tp = findSafeNear(owner.getLocation(), 3);
                if (tp != null) living.teleport(tp);
            }
        }
    }

    private void handleWildBehavior(NPC npc, LivingEntity living) {
        MercType type = getMercType(npc);
        MercColor color = getMercColor(npc);
        MercState state = getMercState(npc);

        // Priority 1: if angry, chase target player
        if (state == MercState.WILD_ANGRY) {
            UUID targetUuid = getAngryTarget(npc);
            Player target = (targetUuid == null) ? null : Bukkit.getPlayer(targetUuid);
            if (target != null && target.isOnline() && target.getWorld().equals(living.getWorld())) {
                Navigator nav = npc.getNavigator();
                if (nav != null) {
                    try { nav.getLocalParameters().speedModifier(1.15f); } catch (Throwable ignored) {}
                    nav.setTarget(target, true);
                }
                return;
            }
        }

        // Otherwise: wander idle (passive-mob style) unless a hostile or enemy faction is nearby
        LivingEntity hostile = findNearestHostile(living, Math.max(6.0, getConfig().getDouble("combat.hostileEngageRange", 14.0)));
        if (hostile != null) {
            Navigator nav = npc.getNavigator();
            if (nav != null) {
                try { nav.getLocalParameters().speedModifier(1.10f); } catch (Throwable ignored) {}
                nav.setTarget(hostile, true);
            }
            return;
        }

        // Cross-faction combat (wild merc colors only; JackBlack exempt; TNT merc participates if enabled)
        if (type == MercType.MERC || type == MercType.GREEN_TNT) {
            if (color != MercColor.NONE) {
                LivingEntity enemyMerc = findNearestEnemyFactionMerc(living, color,
                        Math.max(6.0, getConfig().getDouble("combat.factionEngageRange", 14.0)));
                if (enemyMerc != null) {
                    Navigator nav = npc.getNavigator();
                    if (nav != null) {
                        try { nav.getLocalParameters().speedModifier(1.05f); } catch (Throwable ignored) {}
                        nav.setTarget(enemyMerc, true);
                    }
                    return;
                }
            }
        }

        // Idle wander
        Navigator nav = npc.getNavigator();
        if (nav != null && !nav.isNavigating()) {
            Location target = pickWanderTarget(living.getLocation(), 14);
            if (target != null) {
                try { nav.getLocalParameters().speedModifier(0.95f); } catch (Throwable ignored) {}
                nav.setTarget(target);
            }
        }
    }

    private Location pickWanderTarget(Location from, int radius) {
        World w = from.getWorld();
        if (w == null) return null;

        // Try a handful of random points; we want solid ground with headroom.
        for (int attempt = 0; attempt < 12; attempt++) {
            int dx = randomInt(-radius, radius);
            int dz = randomInt(-radius, radius);

            int x = from.getBlockX() + dx;
            int z = from.getBlockZ() + dz;

            int topY = w.getHighestBlockYAt(x, z); // returns top solid block Y
            int y = topY + 1; // stand in the air block above it

            Location feet = new Location(w, x + 0.5, y, z + 0.5);

            Material ground = feet.clone().add(0, -1, 0).getBlock().getType();
            if (ground == Material.AIR || ground == Material.WATER || ground == Material.LAVA) continue;

            Material feetType = feet.getBlock().getType();
            Material headType = feet.clone().add(0, 1, 0).getBlock().getType();

            if (!feetType.isAir()) continue;
            if (!headType.isAir()) continue;

            return feet;
        }

        return null;
    }

    private void handleAttacks(NPC npc, LivingEntity attacker, int meleeCooldownTicks, int bowCooldownTicks) {
    if (!(attacker instanceof Player)) return; // Citizens NPC player-entity

    MercType type = getMercType(npc);
    MercState state = getMercState(npc);
    MercMode mode = getMercMode(npc);

    LivingEntity target = null;

    // Wild angry: explicit player target
    if (state == MercState.WILD_ANGRY) {
        UUID tu = getAngryTarget(npc);
        Player p = (tu == null) ? null : Bukkit.getPlayer(tu);
        if (p != null && p.isOnline() && p.getWorld().equals(attacker.getWorld())) {
            target = p;
        }
    }

    // Tamed assist target (owner attacked something)
    if (target == null && state == MercState.TAME) {
        target = getAssistTargetIfAny(npc, attacker);
    }

    // Contextual nearest target
    if (target == null) {
        double hostileRange = Math.max(6.0, getConfig().getDouble("combat.hostileEngageRange", 14.0));
        double factionRange = Math.max(6.0, getConfig().getDouble("combat.factionEngageRange", 14.0));

        if (state == MercState.TAME) {
            // Tamed: engage nearby hostiles (monsters) automatically
            target = findNearestHostile(attacker, hostileRange);
        } else {
            // Wild: hostiles and (for colored mercs) enemy factions
            target = findNearestHostile(attacker, hostileRange);

            if (target == null && (type == MercType.MERC || type == MercType.GREEN_TNT)) {
                MercColor color = getMercColor(npc);
                if (color != MercColor.NONE) {
                    target = findNearestEnemyFactionMerc(attacker, color, factionRange);
                }
            }
        }
    }

    // Never target owner (hard safety)
    if (state == MercState.TAME && target instanceof Player p && isOwnerPlayer(npc, p)) {
        target = null;
    }

    if (target == null) return;

    // TNT merc: creeper-like explosion attack (if enabled)
    if (type == MercType.GREEN_TNT && getConfig().getBoolean("greenTnt.explosiveCombat", true)) {
        handleTntFuse(npc, attacker, target);
        return;
    }

    // If tamed and allowed to move (FOLLOW/PATROL), pursue target while fighting.
    if (state == MercState.TAME && mode != MercMode.DEFEND) {
        try {
            Navigator nav = npc.getNavigator();
            if (nav != null) {
                // do not "aggressively" target owner (we already filtered)
                nav.setTarget(target, true);
            }
        } catch (Throwable ignored) {}
    }

    WeaponKind weapon = getWeaponKind(npc);
    boolean bowman = isBowman(npc);
    if (bowman) weapon = WeaponKind.BOW;

    if (weapon == WeaponKind.BOW) {
        doBowAttack(npc, attacker, target, bowCooldownTicks);
    } else {
        doMeleeAttack(npc, attacker, target, meleeCooldownTicks);
    }
}

private void handleTntFuse(NPC npc, LivingEntity attacker, LivingEntity target) {
    try {
        if (npc == null || attacker == null || target == null) return;
        MercType type = getMercType(npc);
        if (type != MercType.GREEN_TNT) return;

        // If it somehow becomes tame without converting, do not use explosive behavior.
        if (getMercState(npc) == MercState.TAME) return;

        if (!getConfig().getBoolean("greenTnt.explosiveCombat", true)) return;

        Entity ent = npc.getEntity();
        if (!(ent instanceof LivingEntity living)) return;

        // If target is in a different world, cancel fuse.
        if (target.getWorld() != living.getWorld()) {
            npc.data().remove("mercmobs_tnt_fuse_start_ms");
            return;
        }

        double triggerRange = getConfig().getDouble("greenTnt.triggerRange", 3.0);
        double cancelRange = getConfig().getDouble("greenTnt.cancelRange", 4.5);
        int fuseTicks = Math.max(10, getConfig().getInt("greenTnt.fuseTicks", 30));

        double distSq = living.getLocation().distanceSquared(target.getLocation());

        Long fuseStart = (Long) npc.data().get("mercmobs_tnt_fuse_start_ms");
        if (fuseStart == null) {
            // Not currently fusing: start if within trigger range
            if (distSq <= (triggerRange * triggerRange)) {
                long now = System.currentTimeMillis();
                npc.data().set("mercmobs_tnt_fuse_start_ms", now);

                // Fuse sound once
                try {
                    living.getWorld().playSound(living.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.0f, 1.0f);
                } catch (Throwable ignored) { }
            }
            return;
        }

        // Currently fusing: cancel if target moved away
        if (distSq > (cancelRange * cancelRange)) {
            npc.data().remove("mercmobs_tnt_fuse_start_ms");
            return;
        }

        long now = System.currentTimeMillis();
        long elapsedMs = now - fuseStart;
        long fuseMs = fuseTicks * 50L;
        if (elapsedMs < fuseMs) return;

        // Detonate
        npc.data().remove("mercmobs_tnt_fuse_start_ms");

        double powerD = getConfig().getDouble("greenTnt.explosion.power", 3.0);
        float power = (float) Math.max(0.0, Math.min(12.0, powerD));

        boolean damageEntities = getConfig().getBoolean("greenTnt.explosion.damageEntities", true);
        String mode = String.valueOf(getConfig().getString("greenTnt.explosion.griefingMode", "PLAYER_ONLY")).trim().toUpperCase(Locale.ROOT);

        boolean breakBlocks = false;
        if (!mode.equals("OFF")) {
            if (mode.equals("INCLUDE_NON_PLAYER")) {
                breakBlocks = true;
            } else if (mode.equals("PLAYER_ONLY")) {
                breakBlocks = (target instanceof Player);
            }
        }

        Location boomLoc = living.getLocation();

        // If entity damage is off, we still want the visual/sound without damage:
        // create an explosion with power 0 but optionally break blocks (if enabled).
        float effectivePower = damageEntities ? power : 0.0f;

        try {
            living.getWorld().createExplosion(boomLoc, effectivePower, false, breakBlocks, living);
        } catch (Throwable t) {
            // Fallback signature
            try {
                living.getWorld().createExplosion(boomLoc, effectivePower, false, breakBlocks);
            } catch (Throwable ignored) { }
        }

        // Remove the TNT merc after detonation if it isn't already dead
        try {
            living.setHealth(0.0);
        } catch (Throwable ignored) { }
    } catch (Throwable ignored) { }
}


private void doMeleeAttack(NPC npc, LivingEntity attacker, LivingEntity target, int cooldownTicks) {
    if (target instanceof Player p && getMercState(npc) == MercState.TAME && isOwnerPlayer(npc, p)) return;

    double range = Math.max(2.0, getConfig().getDouble("combat.meleeRange", 2.6));
    double r2 = range * range;

    long nowMs = System.currentTimeMillis();
    long lastMs = getLong(npc, "mercmobs_last_melee_ms", -999999999L);
    if ((nowMs - lastMs) < (cooldownTicks * 50L)) return;

    if (!attacker.getWorld().equals(target.getWorld())) return;
    if (attacker.getLocation().distanceSquared(target.getLocation()) > r2) return;

    try {
        attacker.swingMainHand();
        target.damage(1.0, attacker);
        setLong(npc, "mercmobs_last_melee_ms", nowMs);
        setLastCombatNow(npc);
    } catch (Throwable ignored) {}
}

private void doBowAttack(NPC npc, LivingEntity attacker, LivingEntity target, int cooldownTicks) {
    if (target instanceof Player p && getMercState(npc) == MercState.TAME && isOwnerPlayer(npc, p)) return;

    double range = Math.max(6.0, getConfig().getDouble("combat.bowRange", 18.0));
    double r2 = range * range;

    long nowMs = System.currentTimeMillis();
    long lastMs = getLong(npc, "mercmobs_last_bow_ms", -999999999L);
    if ((nowMs - lastMs) < (cooldownTicks * 50L)) return;

    if (!attacker.getWorld().equals(target.getWorld())) return;
    if (attacker.getLocation().distanceSquared(target.getLocation()) > r2) return;

    try {
        org.bukkit.util.Vector dir = target.getEyeLocation().toVector()
                .subtract(attacker.getEyeLocation().toVector())
                .normalize();

        Arrow arrow = attacker.launchProjectile(Arrow.class);
        arrow.setVelocity(dir.multiply(2.0));
        arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);

        setLong(npc, "mercmobs_last_bow_ms", nowMs);
        setLastCombatNow(npc);
    } catch (Throwable ignored) {}
}

    private void doTntFuseAndExplode(NPC npc, LivingEntity attacker, LivingEntity target) {
        double triggerRange = Math.max(1.5, getConfig().getDouble("greenTnt.triggerRange", 3.0));
        double cancelRange = Math.max(triggerRange + 0.5, getConfig().getDouble("greenTnt.cancelRange", 4.5));
        double trig2 = triggerRange * triggerRange;
        double cancel2 = cancelRange * cancelRange;

        long fuseUntil = getFuseUntilMillis(npc);
        UUID fuseTarget = getFuseTarget(npc);

        boolean inTrigger = attacker.getLocation().distanceSquared(target.getLocation()) <= trig2;
        boolean inCancel = attacker.getLocation().distanceSquared(target.getLocation()) <= cancel2;

        if (fuseUntil <= 0) {
            if (inTrigger) {
                // Start fuse
                int fuseTicks = Math.max(10, getConfig().getInt("greenTnt.fuseTicks", 30));
                long until = System.currentTimeMillis() + (fuseTicks * 50L);
                setFuseUntilMillis(npc, until);
                setFuseTarget(npc, target.getUniqueId());

                try { attacker.getWorld().playSound(attacker.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 1.0f, 1.0f); } catch (Throwable ignored) {}
                setLastCombatNow(npc);
            }
            return;
        }

        // Fuse running
        if (fuseTarget != null && !fuseTarget.equals(target.getUniqueId())) {
            // target changed -> cancel
            clearFuse(npc);
            return;
        }

        if (!inCancel) {
            // target backed off -> cancel
            clearFuse(npc);
            return;
        }

        if (System.currentTimeMillis() >= fuseUntil) {
            // Explode
            clearFuse(npc);

            double power = Math.max(0.0, getConfig().getDouble("greenTnt.explosion.power", 3.0));
            boolean damageEntities = getConfig().getBoolean("greenTnt.explosion.damageEntities", true);

            String grief = getConfig().getString("greenTnt.explosion.griefingMode", "PLAYER_ONLY");
            boolean breakBlocks = false;
            if ("OFF".equalsIgnoreCase(grief)) breakBlocks = false;
            else if ("INCLUDE_NON_PLAYER".equalsIgnoreCase(grief)) breakBlocks = true;
            else {
                // PLAYER_ONLY
                breakBlocks = (target instanceof Player);
            }

            if (power <= 0.0) return;

            try {
                // createExplosion always damages; we cancel damage later if configured off
                attacker.getWorld().createExplosion(attacker.getLocation(), (float) power, false, breakBlocks, attacker);

                // if entity damage disabled, we mark a short-lived flag on NPC to cancel explosion damage events
                if (!damageEntities) {
                    setLong(npc, "mercmobs_expl_nodmg_until", System.currentTimeMillis() + 1500L);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void clearFuse(NPC npc) {
        try { npc.data().remove(CIT_KEY_FUSE_UNTIL); } catch (Throwable ignored) {}
        try { npc.data().remove(CIT_KEY_FUSE_TARGET); } catch (Throwable ignored) {}
    }

    // Cancel explosion entity damage if configured off
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExplosionDamage(EntityDamageByEntityEvent event) {
        if (!(event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)) return;

        Entity damager = event.getDamager();
        if (!(damager instanceof Player)) return;

        NPC npc = getNpcForEntity(damager);
        if (npc == null) return;
        if (!isOurNpc(npc, damager)) return;
        if (getMercType(npc) != MercType.GREEN_TNT) return;

        long until = getLong(npc, "mercmobs_expl_nodmg_until", 0L);
        if (until > System.currentTimeMillis()) {
            event.setCancelled(true);
        }
    }

    // -------------------------------------------------------------------------
    // Enforce loop: collisions, silence, nametag hidden
    // -------------------------------------------------------------------------
    private void startEnforceLoop() {
        if (!citizensAvailable) return;
        stopTask(enforceTaskId);

        int tickPeriod = 40;
        enforceTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            for (UUID uuid : new ArrayList<>(npcIdByEntity.keySet())) {
                Entity ent = Bukkit.getEntity(uuid);
                if (ent == null || !ent.isValid() || ent.isDead()) continue;

                NPC npc = getNpcByEntityUuid(uuid);
                if (npc == null) continue;
                if (!isOurNpc(npc, ent)) continue;

                hideNameplate(npc, ent);
                enforceEntityFlags(ent);
            }
        }, tickPeriod, tickPeriod);
    }

private void enforceEntityFlags(Entity ent) {
    try { ent.setCustomNameVisible(false); } catch (Throwable ignored) {}
    try { ent.setCustomName(null); } catch (Throwable ignored) {}
    try { ent.setSilent(true); } catch (Throwable ignored) {}

    // Collisions: Spigot API may not expose Entity#setCollidable in your jar; try reflection.
    try {
        Method m = ent.getClass().getMethod("setCollidable", boolean.class);
        m.invoke(ent, true);
    } catch (Throwable ignored) {}
}
/**
 * Citizens NPCs are often protected/invulnerable by default even if setProtected(false) is called,
 * depending on server/Citizens settings. This method tries to enable damage through the Vulnerable trait,
 * but uses reflection so the plugin still compiles against the Citizens jar you bundle.
 */
@SuppressWarnings({"unchecked","rawtypes"})
private void ensureNpcVulnerable(NPC npc) {
    if (npc == null) return;
    try {
        // Common Citizens trait class
        Class<?> vulnClass;
        try {
            vulnClass = Class.forName("net.citizensnpcs.trait.Vulnerable");
        } catch (ClassNotFoundException ex) {
            vulnClass = Class.forName("net.citizensnpcs.trait.VulnerableTrait");
        }

        Object trait = npc.getOrAddTrait((Class) vulnClass);

        // Try method names seen across Citizens versions
        try {
            Method m = trait.getClass().getMethod("setVulnerable", boolean.class);
            m.invoke(trait, true);
            return;
        } catch (NoSuchMethodException ignored) {}

        try {
            Method m = trait.getClass().getMethod("setInvulnerable", boolean.class);
            m.invoke(trait, false);
            return;
        } catch (NoSuchMethodException ignored) {}

    } catch (Throwable ignored) {}
}


    private void hideNameplate(NPC npc, Entity ent) {
        try { npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, false); } catch (Throwable ignored) {}
        try { ent.setCustomNameVisible(false); } catch (Throwable ignored) {}
        try { ent.setCustomName(null); } catch (Throwable ignored) {}
    }

    private void destroyOurNpc(NPC npc, UUID entityUuid) {
        if (entityUuid != null) {
            npcIdByEntity.remove(entityUuid);
            typeByEntity.remove(entityUuid);
        }
        if (npc == null) return;


        try {
            if (npc.isSpawned()) npc.despawn();
        } catch (Throwable ignored) {}

        // Try both destroy + deregister to ensure it's removed from /npc list
        try { npc.destroy(); } catch (Throwable ignored) {}
        try { CitizensAPI.getNPCRegistry().deregister(npc); } catch (Throwable ignored) {}
    }

private void handleJackBlackChat(NPC npc, LivingEntity jack) {
        long next = getLong(npc, "mercmobs_jb_nextchat_tick", 0L);
        long now = (System.currentTimeMillis() / 50L);
        if (now < next) return;

        double range = Math.max(6.0, getConfig().getDouble("jackblack.chat.range", 18.0));
        double r2 = range * range;

        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : jack.getWorld().getPlayers()) {
            double d2 = p.getLocation().distanceSquared(jack.getLocation());
            if (d2 <= r2 && d2 < best) {
                best = d2;
                nearest = p;
            }
        }
        if (nearest == null) return;

        MercState state = getMercState(npc);
        List<String> quotes = (state == MercState.WILD_ANGRY)
                ? getConfig().getStringList("jackblack.chat.angryQuotes")
                : getConfig().getStringList("jackblack.chat.neutralQuotes");

        String q = randomFrom(quotes);
        if (q == null || q.isEmpty()) return;

        String msg = getPrefixFor(MercColor.NONE, MercType.JACKBLACK) + q;

        // Send to players in range (not global broadcast)
        for (Player p : jack.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(jack.getLocation()) <= r2) {
                p.sendMessage(msg);
            }
        }

        int minCd = Math.max(20, getConfig().getInt("jackblack.chat.cooldownTicksMin", 80));
        int maxCd = Math.max(minCd, getConfig().getInt("jackblack.chat.cooldownTicksMax", 160));
        double mult = Math.max(1.0, getConfig().getDouble("jackblack.chat.cooldownMultiplier", 4.0));
        int minAdj = Math.max(20, (int) Math.round(minCd * mult));
        int maxAdj = Math.max(minAdj, (int) Math.round(maxCd * mult));
        setLong(npc, "mercmobs_jb_nextchat_tick", now + randomInt(minAdj, maxAdj));
    }

    // -------------------------------------------------------------------------
    // /mercs mode changes in range + owner messages
    // -------------------------------------------------------------------------
    private int setModeForOwnedMercsInRange(Player owner, MercMode mode) {
        double hear = Math.max(6.0, getConfig().getDouble("tamed.commandHearRange", 32.0));
        double hear2 = hear * hear;

        int outOfRange = 0;

        for (UUID entId : new ArrayList<>(npcIdByEntity.keySet())) {
            Entity ent = Bukkit.getEntity(entId);
            if (!(ent instanceof LivingEntity) || ent.isDead() || !ent.isValid()) continue;

            NPC npc = getNpcByEntityUuid(entId);
            if (npc == null) continue;
            if (!isOurNpc(npc, ent)) continue;

            if (getMercState(npc) != MercState.TAME) continue;
            UUID o = getOwnerUuid(npc);
            if (o == null || !o.equals(owner.getUniqueId())) continue;

            // must be in same world group and within range
            String mercBase = worldBaseName(ent.getWorld().getName());
            String ownerBase = worldBaseName(owner.getWorld().getName());
            boolean sameGroup = Objects.equals(mercBase, ownerBase);

            boolean inRange = sameGroup
                    && ent.getWorld().equals(owner.getWorld())
                    && ent.getLocation().distanceSquared(owner.getLocation()) <= hear2;

            if (!inRange) {
                outOfRange++;
                continue;
            }

            setMercMode(npc, mode);

            MercColor color = getMercColor(npc);
            MercType type = getMercType(npc);

            String key = (mode == MercMode.DEFEND) ? "messages.defend"
                    : (mode == MercMode.PATROL) ? "messages.patrol"
                    : "messages.follow";

            String msg = randomFrom(getConfig().getStringList(key));
            if (msg == null) msg = "Yes, sir!";
            owner.sendMessage(getPrefixFor(color, type) + msg);
        }

        return outOfRange;
    }

    private String getPrefixFor(MercColor color, MercType type) {
        if (type == MercType.JACKBLACK) return getConfig().getString("messages.prefix.jackblack", "<Jack Black> ");
        return switch (color) {
            case RED -> getConfig().getString("messages.prefix.red", "<Red Mercenary> ");
            case BLUE -> getConfig().getString("messages.prefix.blue", "<Blue Mercenary> ");
            case GREEN -> getConfig().getString("messages.prefix.green", "<Green Mercenary> ");
            case YELLOW -> getConfig().getString("messages.prefix.yellow", "<Yellow Mercenary> ");
            default -> "<Mercenary> ";
        };
    }

    // -------------------------------------------------------------------------
    // Combat target helpers
    // -------------------------------------------------------------------------
    private LivingEntity findNearestHostile(LivingEntity from, double range) {
        double r2 = range * range;
        LivingEntity best = null;
        double bestD2 = Double.MAX_VALUE;

        Collection<Entity> near = from.getWorld().getNearbyEntities(from.getLocation(), range, range, range);
        for (Entity e : near) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le.isDead() || !le.isValid()) continue;
            if (le instanceof Player) continue;

            // Hostile mobs = Monster (includes many), plus some special cases
            boolean hostile = (le instanceof Monster);
            if (!hostile) continue;

            double d2 = le.getLocation().distanceSquared(from.getLocation());
            if (d2 <= r2 && d2 < bestD2) {
                bestD2 = d2;
                best = le;
            }
        }
        return best;
    }

    private LivingEntity findNearestEnemyFactionMerc(LivingEntity from, MercColor myColor, double range) {
        double r2 = range * range;
        LivingEntity best = null;
        double bestD2 = Double.MAX_VALUE;

        for (UUID uuid : new ArrayList<>(npcIdByEntity.keySet())) {
            Entity e = Bukkit.getEntity(uuid);
            if (!(e instanceof LivingEntity le)) continue;
            if (!le.getWorld().equals(from.getWorld())) continue;
            if (le.isDead() || !le.isValid()) continue;
            if (le.getLocation().distanceSquared(from.getLocation()) > r2) continue;

            NPC npc = getNpcByEntityUuid(uuid);
            if (npc == null) continue;
            if (!isOurNpc(npc, e)) continue;

            // only wild mercs participate
            if (getMercState(npc) == MercState.TAME) continue;

            MercType type = getMercType(npc);
            if (type == MercType.JACKBLACK) continue; // exempt from faction rules
            MercColor otherColor = getMercColor(npc);
            if (otherColor == MercColor.NONE) continue;

            if (otherColor != myColor) {
                double d2 = le.getLocation().distanceSquared(from.getLocation());
                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = le;
                }
            }
        }
        return best;
    }

    private double damageFor(NPC npc) {
        MercState state = getMercState(npc);
        boolean bowman = isBowman(npc);
        if (bowman) return 1.0; // bow uses arrow damage anyway; melee fallback is fist-like

        if (state != MercState.TAME) {
            return 1.0; // fist
        }

        WeaponKind weapon = getWeaponKind(npc);
        return switch (weapon) {
            case IRON -> 6.0;
            case GOLD -> 4.0;
            case DIAMOND -> 7.0;
            default -> 1.0;
        };
    }

    // -------------------------------------------------------------------------
    // Healing
    // -------------------------------------------------------------------------
private void maybeAutoHeal(NPC npc, LivingEntity living) {
    if (!getConfig().getBoolean("healing.autoHeal.enabled", true)) return;

    double max = 20.0;
    try {
        // Spigot-safe: avoid compile-time reference to Attribute.GENERIC_MAX_HEALTH (varies by API).
        max = living.getMaxHealth();
    } catch (Throwable ignored) {}
    if (max <= 0.0) max = 20.0;

    double curHp = living.getHealth();
    if (curHp >= max) return;

    long nowMs = System.currentTimeMillis();
    long everyTicks = Math.max(20L, getConfig().getLong("healing.autoHeal.everyTicks", 100L));
    long everyMs = everyTicks * 50L;

    long nextMs = getLong(npc, "mercmobs_next_autoheal_ms", 0L);
    if (nowMs < nextMs) return;

    setLong(npc, "mercmobs_next_autoheal_ms", nowMs + everyMs);

    double amt = Math.max(0.1, getConfig().getDouble("healing.autoHeal.amount", 1.0));
    double newHp = Math.min(max, curHp + amt);
    try {
        living.setHealth(newHp);
    } catch (Throwable ignored) {}
}

    // -------------------------------------------------------------------------
    // Skin + equipment application
    // -------------------------------------------------------------------------
    private void applyCurrentSkinAndEquipment(NPC npc) {
        if (npc == null) return;
        Entity ent = npc.getEntity();
        if (!(ent instanceof LivingEntity living)) return;

        MercType type = getMercType(npc);
        MercColor color = getMercColor(npc);
        MercState state = getMercState(npc);

        // Determine skinKey
        String skinKey;
        if (type == MercType.JACKBLACK) {
            skinKey = "jackblack";
        } else if (type == MercType.GREEN_TNT) {
            skinKey = "merc_green_tnt";
        } else {
            String st = (state == MercState.TAME) ? "tame" : (state == MercState.WILD_ANGRY) ? "angry" : "neutral";
            skinKey = "merc_" + colorToString(color) + "_" + st;
        }

        SkinData skin = ensureSkinLoaded(skinKey);
        if (skin != null) {
            // Use a stable skin name identifier
            String skinName = "MercMobs_" + skinKey;
            try {
                SkinTrait trait = npc.getOrAddTrait(SkinTrait.class);
                trait.setSkinPersistent(skinName, skin.signature, skin.value);
            } catch (Throwable ex) {
                getLogger().warning("Failed to apply skin for " + skinKey + ": " + ex.getMessage());
            }
        }

        // Equipment
        boolean bowman = isBowman(npc);
        WeaponKind weapon = getWeaponKind(npc);
        if (bowman) weapon = WeaponKind.BOW;

        try {
            Equipment eq = npc.getOrAddTrait(Equipment.class);
            if (weapon == WeaponKind.BOW) {
                eq.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.BOW));
            } else if (weapon == WeaponKind.IRON) {
                eq.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.IRON_SWORD));
            } else if (weapon == WeaponKind.GOLD) {
                eq.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.GOLDEN_SWORD));
            } else if (weapon == WeaponKind.DIAMOND) {
                eq.set(Equipment.EquipmentSlot.HAND, new ItemStack(Material.DIAMOND_SWORD));
            } else {
                eq.set(Equipment.EquipmentSlot.HAND, null); // fist
            }
        } catch (Throwable ignored) {}

        // Re-tag PDC state (so events remain correct even after reloads)
        tagEntity(ent, npc);
        hideNameplate(npc, ent);
        enforceEntityFlags(ent);
    }

    // -------------------------------------------------------------------------
    // Skins: local PNG -> MineSkin upload -> cache in config
    // -------------------------------------------------------------------------
    private void loadSkinCacheFromConfig() {
        skinCache.clear();
        ConfigurationSection cached = getConfig().getConfigurationSection("skins.cached");
        if (cached == null) return;

        for (String key : cached.getKeys(false)) {
            String value = cached.getString(key + ".value", "");
            String signature = cached.getString(key + ".signature", "");
            if (!value.isEmpty() && !signature.isEmpty()) {
                skinCache.put(key.toLowerCase(Locale.ROOT), new SkinData(value, signature));
            }
        }
    }

    private List<String> getAllSkinKeys() {
        List<String> keys = new ArrayList<>();

        keys.add("jackblack");
        keys.add("merc_green_tnt");

        for (String c : Arrays.asList("red", "blue", "green", "yellow")) {
            keys.add("merc_" + c + "_neutral");
            keys.add("merc_" + c + "_tame");
            keys.add("merc_" + c + "_angry");
        }
        return keys;
    }

    private SkinData ensureSkinLoaded(String skinKey) {
        String k = skinKey.toLowerCase(Locale.ROOT).trim();

        // Cache-first: if already loaded, never hit MineSkin.
        SkinData existing = skinCache.get(k);
        if (existing != null) return existing;

        if (!getConfig().getBoolean("skins.allowMineskinRequests", true)) return null;

        // Never upload if API key is blank/missing. Cached skins still work.
        String apiKey = getConfig().getString("skins.mineskinApiKey", "").trim();
        if (apiKey.isEmpty()) {
            if (!warnedBlankMineSkinKey) {
                warnedBlankMineSkinKey = true;
                getLogger().warning("MineSkin API key is blank. Skipping MineSkin uploads. Only skins present in skins.cached will be used until a key is configured.");
            }
            return null;
        }

        byte[] pngBytes = loadLocalSkinBytesForKey(k);
        if (pngBytes == null) return null;
        if (!looksLikePng(pngBytes)) {
            getLogger().warning("Skin for '" + k + "' is not a valid PNG.");
            return null;
        }

        String userAgent = getConfig().getString("skins.userAgent", "MercMobs/1.0");
        String variant = getConfig().getString("skins.variant", "classic");
        String visibility = getConfig().getString("skins.visibility", "unlisted");

        SkinData generated = requestMineSkinByUploadBytes(pngBytes, k, variant, visibility, apiKey, userAgent);
        if (generated != null) {
            skinCache.put(k, generated);
            getConfig().set("skins.cached." + k + ".value", generated.value);
            getConfig().set("skins.cached." + k + ".signature", generated.signature);
            saveConfig();
        }
        return generated;
    }

    private byte[] loadLocalSkinBytesForKey(String skinKeyLower) {
        File dir = getSkinDirectory();

        String fileName = null;
        if (skinKeyLower.equals("jackblack")) {
            fileName = getConfig().getString("skins.files.jackblack", "jackblacksteve.png");
        } else if (skinKeyLower.equals("merc_green_tnt")) {
            fileName = getConfig().getString("skins.files.green_tnt", "greenmerctnt.png");
        } else if (skinKeyLower.startsWith("merc_")) {
            // merc_<color>_<state>
            String[] parts = skinKeyLower.split("_");
            if (parts.length == 3) {
                String color = parts[1];
                String state = parts[2];
                fileName = getConfig().getString("skins.files." + color + "." + state, null);
            }
        }

        if (fileName == null || fileName.trim().isEmpty()) {
            getLogger().warning("No skin filename configured for skinKey: " + skinKeyLower);
            return null;
        }

        File f = new File(dir, fileName);
        if (!f.exists() || f.length() <= 0) {
            getLogger().warning("Missing local skin file: " + f.getPath());
            return null;
        }

        try {
            return readFileBytes(f);
        } catch (Throwable ex) {
            getLogger().warning("Failed reading skin file '" + f.getName() + "': " + ex.getMessage());
            return null;
        }
    }

    private SkinData requestMineSkinByUploadBytes(byte[] pngBytes, String name, String variant, String visibility,
                                              String apiKey, String userAgent) {

        // This method is invoked from async preload / preparation. Sleeping here will not stall the main thread.
        final int maxAttempts = Math.max(1, getConfig().getInt("skins.mineskinMaxAttempts", 5));

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://api.mineskin.org/generate/upload");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);

                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", userAgent);
                if (!apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);

                String boundary = "----MercMobsBoundary" + System.nanoTime();
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                try (OutputStream os = conn.getOutputStream()) {
                    String safeName = sanitizeName(name);
                    writeFormField(os, boundary, "name", safeName);
                    writeFormField(os, boundary, "variant", variant);
                    writeFormField(os, boundary, "visibility", visibility);
                    writeFileField(os, boundary, "file", name + ".png", "image/png", pngBytes);
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
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
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

    private long parseMineSkinBackoffMs(String body) {
        // MineSkin commonly returns JSON like:
        // {"rateLimit":{"next":{"relative":5279},"delay":{"millis":6000},...}}
        long fallback = 6000L;
        if (body == null) return fallback;

        try {
            java.util.regex.Matcher rel = java.util.regex.Pattern
                    .compile("\"relative\"\\s*:\\s*(\\d+)")
                    .matcher(body);
            if (rel.find()) {
                long v = Long.parseLong(rel.group(1));
                v = Math.max(500L, Math.min(60000L, v));
                return v + 200L; // small cushion
            }

            java.util.regex.Matcher ms = java.util.regex.Pattern
                    .compile("\"millis\"\\s*:\\s*(\\d+)")
                    .matcher(body);
            if (ms.find()) {
                long v = Long.parseLong(ms.group(1));
                v = Math.max(500L, Math.min(60000L, v));
                return v + 200L;
            }
        } catch (Throwable ignored) {}

        return fallback;
    }


    private void writeFormField(OutputStream os, String boundary, String fieldName, String value) throws Exception {
        String part =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n" +
                        "\r\n" +
                        value + "\r\n";
        os.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private void writeFileField(OutputStream os, String boundary, String fieldName, String fileName, String contentType, byte[] data) throws Exception {
        String header =
                "--" + boundary + "\r\n" +
                        "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
                        "Content-Type: " + contentType + "\r\n" +
                        "\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readFileBytes(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream in = new BufferedInputStream(fis);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toByteArray();
        }
    }

    private boolean looksLikePng(byte[] data) {
        if (data == null || data.length < 8) return false;
        return (data[0] == (byte) 0x89 &&
                data[1] == (byte) 0x50 &&
                data[2] == (byte) 0x4E &&
                data[3] == (byte) 0x47 &&
                data[4] == (byte) 0x0D &&
                data[5] == (byte) 0x0A &&
                data[6] == (byte) 0x1A &&
                data[7] == (byte) 0x0A);
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String extractJsonString(String json, String marker) {
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        idx += marker.length();
        StringBuilder out = new StringBuilder();
        boolean escaping = false;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) { out.append(c); escaping = false; continue; }
            if (c == '\\') { escaping = true; continue; }
            if (c == '"') return out.toString();
            out.append(c);
        }
        return null;
    }

    private String sanitizeName(String input) {
        if (input == null) return "MercMobs";
        String trimmed = input.trim();
        if (trimmed.length() > 20) trimmed = trimmed.substring(0, 20);
        if (trimmed.isEmpty()) trimmed = "MercMobs";
        return trimmed;
    }

    // -------------------------------------------------------------------------
    
    // -------------------------------------------------------------------------
    // Dynmap markers.yml fallback (similar to ViridianTownsFolk)
    // -------------------------------------------------------------------------
    private void refreshDynmapMarkersFromFile() {
        dynmapFileMarkersAvailable = false;
        dynmapFileMarkerCount = 0;

        try {
            File file = new File("plugins/dynmap/markers.yml");
            if (!file.exists()) return;

            org.bukkit.configuration.file.YamlConfiguration yc = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            ConfigurationSection sets = yc.getConfigurationSection("sets");
            if (sets == null) return;

            List<String> required = getConfig().getStringList("dynmapMarkers.requiredLabelContains");
            List<String> exceptions = getConfig().getStringList("dynmapMarkers.exceptionKeywords");
            if (required == null) required = Collections.emptyList();
            if (exceptions == null) exceptions = Collections.emptyList();

            List<Location> found = new ArrayList<>();

            for (String setId : sets.getKeys(false)) {
                ConfigurationSection setSec = sets.getConfigurationSection(setId);
                if (setSec == null) continue;

                ConfigurationSection markers = setSec.getConfigurationSection("markers");
                if (markers == null) continue;

                for (String markerId : markers.getKeys(false)) {
                    ConfigurationSection m = markers.getConfigurationSection(markerId);
                    if (m == null) continue;

                    String label = m.getString("label", "");
                    if (label == null) label = "";

                    String labelLower = label.toLowerCase(Locale.ROOT);

                    boolean reqOk = required.isEmpty();
                    for (String req : required) {
                        if (req == null || req.isEmpty()) continue;
                        if (labelLower.contains(req.toLowerCase(Locale.ROOT))) { reqOk = true; break; }
                    }
                    if (!reqOk) continue;

                    boolean blocked = false;
                    for (String ex : exceptions) {
                        if (ex == null || ex.isEmpty()) continue;
                        if (labelLower.contains(ex.toLowerCase(Locale.ROOT))) { blocked = true; break; }
                    }
                    if (blocked) continue;

                    String worldName = m.getString("world", null);
                    if (worldName == null || worldName.isEmpty()) continue;
                    World w = Bukkit.getWorld(worldName);
                    if (w == null) continue;

                    double x = m.getDouble("x");
                    double y = m.getDouble("y");
                    double z = m.getDouble("z");

                    found.add(new Location(w, x, y, z));
                }
            }

            synchronized (dynmapSpawnMarkers) {
                dynmapSpawnMarkers.clear();
                dynmapSpawnMarkers.addAll(found);
            }

            dynmapFileMarkerCount = found.size();
            dynmapFileMarkersAvailable = dynmapFileMarkerCount > 0;
        } catch (Throwable ignored) {
            // silence; status will show MISSING if both API and file fallback fail
        }
    }

// Dynmap marker spawnpoints (reflection)
    // -------------------------------------------------------------------------
    private void initDynmap() {
    dynmapAvailable = false;
    dynmapPlugin = null;
    dynmapApi = null;
    dynmapMarkerApi = null;

    if (!getConfig().getBoolean("dynmapMarkers.enabled", true)) return;

    // Dynmap sometimes enables after us; try a few times.
    if (dynmapInitAttempts > 6) return;
    dynmapInitAttempts++;

    Plugin found = null;
    try {
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p != null && p.getName() != null && p.getName().equalsIgnoreCase("dynmap")) {
                found = p;
                break;
            }
        }
    } catch (Throwable ignored) {}

    dynmapPlugin = found;
    if (dynmapPlugin == null || !dynmapPlugin.isEnabled()) {
        // retry shortly
        Bukkit.getScheduler().runTaskLater(this, this::initDynmap, 60L);
        return;
    }

    try {
        Method getApi = dynmapPlugin.getClass().getMethod("getAPI");
        dynmapApi = getApi.invoke(dynmapPlugin);
        if (dynmapApi == null) return;

        Method getMarkerApi = dynmapApi.getClass().getMethod("getMarkerAPI");
        dynmapMarkerApi = getMarkerApi.invoke(dynmapApi);
        if (dynmapMarkerApi == null) return;

        dynmapAvailable = true;
        getLogger().info("Dynmap detected. Marker scanning enabled.");
    } catch (Throwable t) {
        dynmapAvailable = false;
    }


    // Always try markers.yml fallback as well (helps when API reflection is blocked)
    refreshDynmapMarkersFromFile();
}

    // ------------------------------------------------------------
    // Dynmap markers fallback (parse plugins/dynmap/markers.yml)
    // ------------------------------------------------------------


    private void refreshDynmapMarkersFallbackFile() {
        dynmapFileMarkersAvailable = false;
        dynmapSpawnMarkers.clear();

        if (!getConfig().getBoolean("dynmapMarkers.enabled", true)) return;

        File pluginsDir = getDataFolder().getParentFile();
        if (pluginsDir == null) pluginsDir = new File("plugins");

        File markersYml = new File(pluginsDir, "dynmap" + File.separator + "markers.yml");
        if (!markersYml.exists()) return;

        List<String> requiredContains = getConfig().getStringList("dynmapMarkers.requiredLabelContains");
        if (requiredContains == null || requiredContains.isEmpty()) requiredContains = Arrays.asList("dmarker");
        List<String> exceptionKeywords = getConfig().getStringList("dynmapMarkers.exceptionKeywords");

        int added = parseDynmapMarkersYml(markersYml, requiredContains, exceptionKeywords);
        dynmapFileMarkersAvailable = added > 0;
    }

    private int parseDynmapMarkersYml(File f, List<String> requiredContains, List<String> exceptionKeywords) {
        int added = 0;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;

            String label = null;
            String world = null;
            Integer x = null;
            Integer z = null;

            while ((line = br.readLine()) != null) {
                String t = line.trim();

                // marker id header line like "someid:"
                if (t.endsWith(":") && !t.startsWith("-") && !t.startsWith("label:") && !t.startsWith("world:")) {
                    // flush previous
                    if (label != null && world != null && x != null && z != null) {
                        if (labelPassesFilters(label, requiredContains, exceptionKeywords)) {
                            World w = Bukkit.getWorld(world);
                            if (w != null) {
                                int y = w.getHighestBlockYAt(x, z) + 1;
                                dynmapSpawnMarkers.add(new Location(w, x + 0.5, y, z + 0.5));
                                added++;
                            }
                        }
                    }
                    label = null; world = null; x = null; z = null;
                    continue;
                }

                if (t.startsWith("label:")) {
                    label = stripQuotes(t.substring("label:".length()).trim());
                    continue;
                }
                if (t.startsWith("world:")) {
                    world = stripQuotes(t.substring("world:".length()).trim());
                    continue;
                }
                if (t.startsWith("x:")) {
                    x = tryParseInt(stripQuotes(t.substring("x:".length()).trim()));
                    continue;
                }
                if (t.startsWith("z:")) {
                    z = tryParseInt(stripQuotes(t.substring("z:".length()).trim()));
                    continue;
                }
            }

            // flush at EOF
            if (label != null && world != null && x != null && z != null) {
                if (labelPassesFilters(label, requiredContains, exceptionKeywords)) {
                    World w = Bukkit.getWorld(world);
                    if (w != null) {
                        int y = w.getHighestBlockYAt(x, z) + 1;
                        dynmapSpawnMarkers.add(new Location(w, x + 0.5, y, z + 0.5));
                        added++;
                    }
                }
            }

        } catch (Throwable ex) {
            if (getConfig().getBoolean("debug", false)) {
				getLogger().warning("Failed to parse dynmap markers.yml: " + ex.getMessage());
			}
        }

        return added;
    }

    private boolean labelPassesFilters(String label, List<String> requiredContains, List<String> exceptionKeywords) {
        String lower = label.toLowerCase(Locale.ROOT);

        boolean ok = false;
        for (String req : requiredContains) {
            if (req == null) continue;
            String r = req.toLowerCase(Locale.ROOT);
            if (r.isEmpty()) continue;
            if (lower.contains(r)) { ok = true; break; }
        }
        if (!ok) return false;

        if (exceptionKeywords != null) {
            for (String ex : exceptionKeywords) {
                if (ex == null) continue;
                String e = ex.toLowerCase(Locale.ROOT);
                if (e.isEmpty()) continue;
                if (lower.contains(e)) return false;
            }
        }
        return true;
    }

    private String stripQuotes(String s) {
        if (s == null) return null;
        s = s.trim();
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private Integer tryParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Throwable ignored) { return null; }
    }



    private void startDynmapMarkerRefresh() {
        stopTask(dynmapRefreshTaskId);
        dynmapRefreshTaskId = -1;

        if (!getConfig().getBoolean("dynmapMarkers.enabled", true)) return;

        int ticks = Math.max(100, getConfig().getInt("dynmapMarkers.refreshTicks", 600));
        dynmapRefreshTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::refreshDynmapMarkers, 40L, ticks);
    }

    @SuppressWarnings("unchecked")
    private void refreshDynmapMarkers() {
        // Always try file-based fallback so spawn-near-marker works even if Dynmap API reflection fails.
        refreshDynmapMarkersFallbackFile();
        if (!dynmapAvailable || dynmapMarkerApi == null) {
            return;
        }

        List<String> requiredContains = getConfig().getStringList("dynmapMarkers.requiredLabelContains");
        List<String> exceptionKeywords = getConfig().getStringList("dynmapMarkers.exceptionKeywords");
        if (requiredContains == null) requiredContains = Collections.singletonList("dmarker");
        if (exceptionKeywords == null) exceptionKeywords = Collections.emptyList();

        try {
            // MarkerAPI.getMarkerSets() -> Set<MarkerSet>
            Method getSets = dynmapMarkerApi.getClass().getMethod("getMarkerSets");
            Object setsObj = getSets.invoke(dynmapMarkerApi);
            if (!(setsObj instanceof Set)) return;

            List<Location> found = new ArrayList<>();
            for (Object markerSet : (Set<?>) setsObj) {
                if (markerSet == null) continue;

                // MarkerSet.getMarkers() -> Set<Marker>
                Method getMarkers = markerSet.getClass().getMethod("getMarkers");
                Object markersObj = getMarkers.invoke(markerSet);
                if (!(markersObj instanceof Set)) continue;

                for (Object marker : (Set<?>) markersObj) {
                    if (marker == null) continue;

                    String label;
                    String worldName;
                    double x, y, z;

                    try { label = String.valueOf(marker.getClass().getMethod("getLabel").invoke(marker)); }
                    catch (Throwable t) { continue; }

                    String lower = (label == null) ? "" : label.toLowerCase(Locale.ROOT);

                    boolean ok = false;
                    for (String req : requiredContains) {
                        if (req != null && !req.trim().isEmpty() && lower.contains(req.toLowerCase(Locale.ROOT))) {
                            ok = true; break;
                        }
                    }
                    if (!ok) continue;

                    boolean excepted = false;
                    for (String ex : exceptionKeywords) {
                        if (ex != null && !ex.trim().isEmpty() && lower.contains(ex.toLowerCase(Locale.ROOT))) {
                            excepted = true; break;
                        }
                    }
                    if (excepted) continue;

                    try { worldName = String.valueOf(marker.getClass().getMethod("getWorld").invoke(marker)); }
                    catch (Throwable t) { continue; }

                    try {
                        x = ((Number) marker.getClass().getMethod("getX").invoke(marker)).doubleValue();
                        y = ((Number) marker.getClass().getMethod("getY").invoke(marker)).doubleValue();
                        z = ((Number) marker.getClass().getMethod("getZ").invoke(marker)).doubleValue();
                    } catch (Throwable t) {
                        continue;
                    }

                    World w = Bukkit.getWorld(worldName);
                    if (w == null) continue;

                    found.add(new Location(w, x, y, z));
                }
            }

            synchronized (dynmapSpawnMarkers) {
                dynmapSpawnMarkers.clear();
                dynmapSpawnMarkers.addAll(found);
            }
        } catch (Throwable ignored) {
            // stay quiet; dynmap signatures vary
        }
    }

    // -------------------------------------------------------------------------
    // NPC / entity helpers
    // -------------------------------------------------------------------------
    private NPC getNpcByEntityUuid(UUID uuid) {
        Integer id = npcIdByEntity.get(uuid);
        if (id == null) return null;
        try { return CitizensAPI.getNPCRegistry().getById(id); }
        catch (Throwable ignored) { return null; }
    }

    private NPC getNpcForEntity(Entity entity) {
        try {
            NPCRegistry reg = CitizensAPI.getNPCRegistry();
            if (reg == null) return null;

            // Many Citizens builds have registry.getNPC(Entity)
            try {
                Method m = reg.getClass().getMethod("getNPC", Entity.class);
                Object result = m.invoke(reg, entity);
                return (result instanceof NPC) ? (NPC) result : null;
            } catch (NoSuchMethodException ignored) {
                // fallback via our map only
                return npcIdByEntity.containsKey(entity.getUniqueId()) ? getNpcByEntityUuid(entity.getUniqueId()) : null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isOurNpc(NPC npc, Entity ent) {
        if (npc == null || ent == null) return false;

        // Prefer Citizens persistent marker
        try {
            Object marker = npc.data().get(CIT_KEY_MERCMOBS);
            if (marker instanceof Boolean && (Boolean) marker) return true;
        } catch (Throwable ignored) {}

        // Fallback: PDC marker on entity
        try {
            Byte present = ent.getPersistentDataContainer().get(pdcIsMercKey, PersistentDataType.BYTE);
            return present != null && present == (byte) 1;
        } catch (Throwable ignored) {}

        return false;
    }

    private void tagEntity(Entity ent, NPC npc) {
        try {
            ent.getPersistentDataContainer().set(pdcIsMercKey, PersistentDataType.BYTE, (byte) 1);
            ent.getPersistentDataContainer().set(pdcTypeKey, PersistentDataType.STRING, String.valueOf(npc.data().get(CIT_KEY_TYPE)));
            ent.getPersistentDataContainer().set(pdcColorKey, PersistentDataType.STRING, String.valueOf(npc.data().get(CIT_KEY_COLOR)));
            ent.getPersistentDataContainer().set(pdcStateKey, PersistentDataType.STRING, String.valueOf(npc.data().get(CIT_KEY_STATE)));
        } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Merc data getters/setters (persistent on NPC)
    // -------------------------------------------------------------------------
    private MercType getMercType(NPC npc) {
        String s = safeLower(npc.data().get(CIT_KEY_TYPE), "merc");
        return switch (s) {
            case "green_tnt" -> MercType.GREEN_TNT;
            case "jackblack" -> MercType.JACKBLACK;
            default -> MercType.MERC;
        };
    }

    private void setMercType(NPC npc, MercType type) {
        npc.data().setPersistent(CIT_KEY_TYPE, typeToString(type));
    }

    private MercColor getMercColor(NPC npc) {
        String s = safeLower(npc.data().get(CIT_KEY_COLOR), "red");
        return switch (s) {
            case "blue" -> MercColor.BLUE;
            case "green" -> MercColor.GREEN;
            case "yellow" -> MercColor.YELLOW;
            case "none" -> MercColor.NONE;
            default -> MercColor.RED;
        };
    }

    private void setMercColor(NPC npc, MercColor color) {
        npc.data().setPersistent(CIT_KEY_COLOR, colorToString(color));
    }

    private MercState getMercState(NPC npc) {
        String s = safeLower(npc.data().get(CIT_KEY_STATE), "wild_neutral");
        return switch (s) {
            case "wild_angry" -> MercState.WILD_ANGRY;
            case "tame" -> MercState.TAME;
            default -> MercState.WILD_NEUTRAL;
        };
    }

    private void setMercState(NPC npc, MercState state) {
        npc.data().setPersistent(CIT_KEY_STATE, switch (state) {
            case WILD_ANGRY -> "wild_angry";
            case TAME -> "tame";
            default -> "wild_neutral";
        });
    }

    private MercMode getMercMode(NPC npc) {
        String s = safeLower(npc.data().get(CIT_KEY_MODE), "follow");
        return switch (s) {
            case "defend" -> MercMode.DEFEND;
            case "patrol" -> MercMode.PATROL;
            default -> MercMode.FOLLOW;
        };
    }

    private void setMercMode(NPC npc, MercMode mode) {
        npc.data().setPersistent(CIT_KEY_MODE, switch (mode) {
            case DEFEND -> "defend";
            case PATROL -> "patrol";
            default -> "follow";
        });
    }

    private boolean isBowman(NPC npc) {
        try {
            Object b = npc.data().get(CIT_KEY_BOWMAN);
            if (b instanceof Boolean) return (Boolean) b;
        } catch (Throwable ignored) {}
        return false;
    }

    private WeaponKind getWeaponKind(NPC npc) {
        String s = safeLower(npc.data().get(CIT_KEY_WEAPON), "fist");
        return switch (s) {
            case "bow" -> WeaponKind.BOW;
            case "iron" -> WeaponKind.IRON;
            case "gold" -> WeaponKind.GOLD;
            case "diamond" -> WeaponKind.DIAMOND;
            default -> WeaponKind.FIST;
        };
    }

    private void setWeaponKind(NPC npc, WeaponKind kind) {
        npc.data().setPersistent(CIT_KEY_WEAPON, switch (kind) {
            case BOW -> "bow";
            case IRON -> "iron";
            case GOLD -> "gold";
            case DIAMOND -> "diamond";
            default -> "fist";
        });
    }

    private UUID getOwnerUuid(NPC npc) {
        String s = safeString(npc.data().get(CIT_KEY_OWNER), null);
        if (s == null || s.trim().isEmpty()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (Throwable ignored) { return null; }
    }

    private void setOwnerUuid(NPC npc, UUID owner) {
        npc.data().setPersistent(CIT_KEY_OWNER, owner.toString());
    }

    private UUID getAngryTarget(NPC npc) {
        String s = safeString(npc.data().get(CIT_KEY_ANGRY_TARGET), null);
        if (s == null || s.trim().isEmpty()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (Throwable ignored) { return null; }
    }

    private void setAngryTarget(NPC npc, UUID target) {
        npc.data().setPersistent(CIT_KEY_ANGRY_TARGET, target.toString());
    }

    private void clearAngryTarget(NPC npc) {
        try { npc.data().remove(CIT_KEY_ANGRY_TARGET); } catch (Throwable ignored) {}
    }

    private void setLastCombatNow(NPC npc) {
        npc.data().setPersistent(CIT_KEY_LAST_COMBAT, System.currentTimeMillis());
    }

    private long getLastCombatMillis(NPC npc) {
        try {
            Object v = npc.data().get(CIT_KEY_LAST_COMBAT);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        return 0L;
    }

    private long getFuseUntilMillis(NPC npc) {
        try {
            Object v = npc.data().get(CIT_KEY_FUSE_UNTIL);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        return 0L;
    }

    private UUID getFuseTarget(NPC npc) {
        String s = safeString(npc.data().get(CIT_KEY_FUSE_TARGET), null);
        if (s == null || s.trim().isEmpty()) return null;
        try { return UUID.fromString(s.trim()); }
        catch (Throwable ignored) { return null; }
    }

    private void setFuseUntilMillis(NPC npc, long until) {
        npc.data().setPersistent(CIT_KEY_FUSE_UNTIL, until);
    }

    private void setFuseTarget(NPC npc, UUID target) {
        npc.data().setPersistent(CIT_KEY_FUSE_TARGET, target.toString());
    }

    // Generic long storage using Citizens data (safe)
    private long getLong(NPC npc, String key, long def) {
        try {
            Object v = npc.data().get(key);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Throwable ignored) {}
        return def;
    }

    private void setLong(NPC npc, String key, long value) {
        try { npc.data().setPersistent(key, value); } catch (Throwable ignored) {}
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------
    private int countAliveTracked() {
        int total = 0;
        Iterator<UUID> it = npcIdByEntity.keySet().iterator();
        while (it.hasNext()) {
            UUID uuid = it.next();
            Entity ent = Bukkit.getEntity(uuid);
            if (ent == null || !ent.isValid() || ent.isDead()) {
                it.remove();
                typeByEntity.remove(uuid);
                continue;
            }
            total++;
        }
        return total;
    }

    private MercColor pickColorByWeight() {
        ConfigurationSection weights = getConfig().getConfigurationSection("spawning.weights");
        double red = (weights == null) ? 1.0 : Math.max(0.0, weights.getDouble("red", 1.0));
        double blue = (weights == null) ? 1.0 : Math.max(0.0, weights.getDouble("blue", 1.0));
        double green = (weights == null) ? 1.0 : Math.max(0.0, weights.getDouble("green", 1.0));
        double yellow = (weights == null) ? 1.0 : Math.max(0.0, weights.getDouble("yellow", 1.0));
        double total = red + blue + green + yellow;
        if (total <= 0.0) return MercColor.RED;

        double roll = Math.random() * total;
        if (roll < red) return MercColor.RED;
        roll -= red;
        if (roll < blue) return MercColor.BLUE;
        roll -= blue;
        if (roll < green) return MercColor.GREEN;
        return MercColor.YELLOW;
    }

    private Location findSafeNear(Location base, int radius) {
        if (base == null || base.getWorld() == null) return null;
        World w = base.getWorld();
        for (int i = 0; i < 12; i++) {
            int dx = randomInt(-radius, radius);
            int dz = randomInt(-radius, radius);
            int x = base.getBlockX() + dx;
            int z = base.getBlockZ() + dz;
            int y = w.getHighestBlockYAt(x, z);

            Location loc = new Location(w, x + 0.5, y, z + 0.5);
            if (!loc.getBlock().getType().isAir()) continue;
            if (!loc.clone().add(0, 1, 0).getBlock().getType().isAir()) continue;
            if (loc.clone().add(0, -1, 0).getBlock().isLiquid()) continue;
            return loc;
        }
        return base.clone().add(0.5, 0.0, 0.5);
    }

    private void setMaxHealth(LivingEntity living, double max) {
        try {
            Method m = living.getClass().getMethod("setMaxHealth", double.class);
            m.invoke(living, max);
        } catch (Throwable ignored) {}
    }

    private double getMaxHealth(LivingEntity living) {
        try {
            Method m = living.getClass().getMethod("getMaxHealth");
            Object v = m.invoke(living);
            if (v instanceof Number) return ((Number) v).doubleValue();
        } catch (Throwable ignored) {}
        return 20.0;
    }

    private int randomInt(int min, int max) {
        if (max < min) max = min;
        if (min == max) return min;
        return min + (int) Math.floor(Math.random() * (max - min + 1));
    }

    private double clamp01(double v) { return clamp(v, 0.0, 1.0); }
    private double clamp(double v, double min, double max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private String safeLower(Object v, String def) {
        String s = safeString(v, def);
        return (s == null) ? def : s.toLowerCase(Locale.ROOT).trim();
    }

    private String safeString(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v);
        if (s == null) return def;
        s = s.trim();
        return s.isEmpty() ? def : s;
    }

    private String randomFrom(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(randomInt(0, list.size() - 1));
    }

    private String typeToString(MercType type) {
        return switch (type) {
            case GREEN_TNT -> "green_tnt";
            case JACKBLACK -> "jackblack";
            default -> "merc";
        };
    }

    private String colorToString(MercColor color) {
        return switch (color) {
            case BLUE -> "blue";
            case GREEN -> "green";
            case YELLOW -> "yellow";
            case NONE -> "none";
            default -> "red";
        };
    }

    private String toValidUsername(String input) {
        if (input == null) input = "Merc";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') ||
                    (c >= 'A' && c <= 'Z') ||
                    (c >= '0' && c <= '9') ||
                    (c == '_');
            out.append(ok ? c : '_');
        }
        String s = out.toString();
        while (s.startsWith("_")) s = s.substring(1);
        if (s.isEmpty()) s = "Merc";
        if (s.length() > 16) s = s.substring(0, 16);
        return s;
    }

    // World grouping: overworld <-> overworld_nether <-> overworld_the_end
    private String worldBaseName(String worldName) {
        if (worldName == null) return "";
        if (worldName.endsWith("_nether")) return worldName.substring(0, worldName.length() - "_nether".length());
        if (worldName.endsWith("_the_end")) return worldName.substring(0, worldName.length() - "_the_end".length());
        return worldName;
    }
}