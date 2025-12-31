import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedDataWatcher.Registry;
import com.comphenix.protocol.wrappers.WrappedDataWatcher.WrappedDataWatcherObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OldBow extends JavaPlugin implements Listener {

    // Item PDC keys
    private NamespacedKey oldBowMarkerKey;
    private NamespacedKey oldBowUuidKey;

    // Projectile PDC key
    private NamespacedKey oldBowArrowKey;

    // ProtocolLib
    private ProtocolManager protocolManager;

    // Config cached
    private double arrowVelocity;
    private double inaccuracy;
    private int spamCooldownMillis;

    // Beta-ish tuning
    private double betaBaseDamage;
    private boolean betaForceNoCrit;
    private boolean betaPhysicsCompEnabled;
    private int betaPhysicsMaxTicks;
    private double betaGravityCancelPerTick;
    private double betaDragScalePerTick;
    private double betaMaxSpeed;

    private Sound fireSound;
    private float fireSoundVolume;
    private float fireSoundPitch;

    private final Map<UUID, Long> lastFireMillisByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> trackedArrowsLifeTicks = new ConcurrentHashMap<>();

    private static final String OLD_BOW_NAME = ChatColor.GOLD + "Old Bow";

    @Override
    public void onEnable() {
        oldBowMarkerKey = new NamespacedKey(this, "oldbow");
        oldBowUuidKey = new NamespacedKey(this, "oldbow_id");
        oldBowArrowKey = new NamespacedKey(this, "oldbow_arrow");

        saveDefaultConfig();
        reloadLocalConfigCache();

        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found. This plugin requires ProtocolLib.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        protocolManager = ProtocolLibrary.getProtocolManager();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickPhysicsCompensation, 1L, 1L);

        getLogger().info("OldBow enabled.");
    }

    private void reloadLocalConfigCache() {
        reloadConfig();
        FileConfiguration cfg = getConfig();

        arrowVelocity = cfg.getDouble("oldbow.arrowVelocity", 3.0);
        inaccuracy = cfg.getDouble("oldbow.inaccuracy", 0.0);
        spamCooldownMillis = cfg.getInt("oldbow.spamCooldownMillis", 35);

        betaBaseDamage = cfg.getDouble("oldbow.beta.baseDamage", 9.0);
        betaForceNoCrit = cfg.getBoolean("oldbow.beta.forceNoCrit", true);

        betaPhysicsCompEnabled = cfg.getBoolean("oldbow.beta.physicsCompensation.enabled", true);
        betaPhysicsMaxTicks = cfg.getInt("oldbow.beta.physicsCompensation.maxTicks", 80);
        betaGravityCancelPerTick = cfg.getDouble("oldbow.beta.physicsCompensation.gravityCancelPerTick", 0.012);
        betaDragScalePerTick = cfg.getDouble("oldbow.beta.physicsCompensation.dragScalePerTick", 1.012);
        betaMaxSpeed = cfg.getDouble("oldbow.beta.physicsCompensation.maxSpeed", 3.6);

        String soundName = cfg.getString("oldbow.fireSound", "ENTITY_ARROW_SHOOT");
        try {
            fireSound = Sound.valueOf(soundName);
        } catch (IllegalArgumentException ex) {
            fireSound = Sound.ENTITY_ARROW_SHOOT;
        }
        fireSoundVolume = (float) cfg.getDouble("oldbow.fireSoundVolume", 1.0);
        fireSoundPitch = (float) cfg.getDouble("oldbow.fireSoundPitch", 1.0);
    }

    // ------------------------------------------------------------
    // /oldbow conversion (slot-hard replace, no duplicates)
    // ------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("oldbow")) return false;

        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "No permission.");
                return true;
            }
            reloadLocalConfigCache();
            sender.sendMessage(ChatColor.GREEN + "OldBow config reloaded.");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Player-only.");
            return true;
        }

        Player player = (Player) sender;
        PlayerInventory inv = player.getInventory();

        int targetSlot;
        ItemStack mainHand = inv.getItemInMainHand();
        if (isConvertibleVanillaBow(mainHand)) {
            targetSlot = inv.getHeldItemSlot();
        } else {
            targetSlot = findFirstConvertibleBowSlot(inv);
        }

        if (targetSlot < 0) {
            player.sendMessage(ChatColor.RED + "You must have an unenchanted bow to convert.");
            return true;
        }

        ItemStack target = inv.getItem(targetSlot);
        if (target == null || target.getType() != Material.BOW) {
            player.sendMessage(ChatColor.RED + "No valid bow found to convert.");
            return true;
        }

        if (isOldBow(target)) {
            player.sendMessage(ChatColor.YELLOW + "That bow is already an Old Bow.");
            return true;
        }

        ItemStack converted = makeOldBow(target);
        if (converted == null) {
            player.sendMessage(ChatColor.RED + "Failed to convert bow.");
            return true;
        }

        inv.setItem(targetSlot, converted);
        player.sendMessage(ChatColor.AQUA + "Converted to " + OLD_BOW_NAME + ChatColor.AQUA + ".");
        return true;
    }

    private int findFirstConvertibleBowSlot(PlayerInventory inv) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (isConvertibleVanillaBow(item)) return slot;
        }
        return -1;
    }

    private boolean isConvertibleVanillaBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return true;
        if (!meta.getEnchants().isEmpty()) return false;
        return !isOldBow(item);
    }

    private ItemStack makeOldBow(ItemStack vanillaBow) {
        ItemStack out = vanillaBow.clone();
        out.setAmount(vanillaBow.getAmount());

        ItemMeta meta = out.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(oldBowMarkerKey, PersistentDataType.INTEGER, 1);
        pdc.set(oldBowUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString());

        meta.setDisplayName(OLD_BOW_NAME);

        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        out.setItemMeta(meta);
        return out;
    }

    // ------------------------------------------------------------
    // HARD STOP: cancel vanilla shot on release (this is mandatory)
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        ItemStack bow = event.getBow();
        if (!isOldBow(bow)) return;

        event.setCancelled(true);

        if (event.getProjectile() != null && event.getProjectile().isValid()) {
            event.getProjectile().remove();
        }
    }

    // ------------------------------------------------------------
    // Instant fire on right click
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        // Only react to the hand that actually fired the event
        if (event.getHand() == null) return;
        if (event.getHand() != EquipmentSlot.HAND) {
            // ignore offhand to avoid double-fires / weirdness
            return;
        }

        switch (event.getAction()) {
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
                break;
            default:
                return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isOldBow(item)) return;

        // Deny vanilla "use item" behavior as hard as Spigot allows
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);

        // Cooldown
        long now = System.currentTimeMillis();
        long last = lastFireMillisByPlayer.getOrDefault(player.getUniqueId(), 0L);
        if (spamCooldownMillis > 0 && (now - last) < spamCooldownMillis) return;
        lastFireMillisByPlayer.put(player.getUniqueId(), now);

        // Fight the client draw pose: clear "using item" flags for a few ticks in a row
        clearUsingItemPoseSeveralTicks(player, 4);

        // Ammo
        if (player.getGameMode() != GameMode.CREATIVE) {
            if (!consumeOneArrow(player.getInventory())) {
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.6f, 1.6f);
                return;
            }
        }

        // Spawn our arrow
        Arrow arrow = player.launchProjectile(Arrow.class);
        arrow.getPersistentDataContainer().set(oldBowArrowKey, PersistentDataType.INTEGER, 1);

        arrow.setPickupStatus(AbstractArrow.PickupStatus.ALLOWED);
        arrow.setDamage(betaBaseDamage);
        if (betaForceNoCrit) arrow.setCritical(false);

        Vector direction = player.getEyeLocation().getDirection().normalize();
        Vector velocity = direction.multiply(arrowVelocity);

        if (inaccuracy > 0.0) {
            velocity = velocity.add(new Vector(
                    (Math.random() - 0.5) * inaccuracy,
                    (Math.random() - 0.5) * inaccuracy,
                    (Math.random() - 0.5) * inaccuracy
            ));
        }

        if (velocity.length() > betaMaxSpeed) {
            velocity = velocity.normalize().multiply(betaMaxSpeed);
        }

        arrow.setVelocity(velocity);

        if (betaPhysicsCompEnabled) {
            trackedArrowsLifeTicks.put(arrow.getUniqueId(), 0);
        }

        player.getWorld().playSound(player.getLocation(), fireSound, fireSoundVolume, fireSoundPitch);
    }

    private boolean consumeOneArrow(PlayerInventory inventory) {
        int slot = inventory.first(Material.ARROW);
        if (slot < 0) return false;

        ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.getType() != Material.ARROW) return false;

        int amount = stack.getAmount();
        if (amount <= 1) inventory.setItem(slot, null);
        else {
            stack.setAmount(amount - 1);
            inventory.setItem(slot, stack);
        }
        return true;
    }

    private void clearUsingItemPoseSeveralTicks(Player player, int ticks) {
        for (int i = 0; i < ticks; i++) {
            final int delay = i;
            Bukkit.getScheduler().runTaskLater(this, () -> sendClearUsingItemMetadata(player), delay);
        }
    }

    /**
     * Best-effort: clear LivingEntity "using item" flag in metadata.
     * This does not prevent the client from starting the pose, but it forces it back down immediately.
     */
    @SuppressWarnings("deprecation")
    private void sendClearUsingItemMetadata(Player player) {
        if (protocolManager == null) return;
        if (!player.isOnline()) return;

        try {
            PacketContainer packet = protocolManager.createPacket(com.comphenix.protocol.PacketType.Play.Server.ENTITY_METADATA);
            packet.getIntegers().write(0, player.getEntityId());

            WrappedDataWatcher watcher = new WrappedDataWatcher();
            WrappedDataWatcherObject obj = new WrappedDataWatcherObject(8, Registry.get(Byte.class));

            watcher.setObject(obj, (byte) 0);

            packet.getWatchableCollectionModifier().write(0, watcher.getWatchableObjects());
            protocolManager.sendServerPacket(player, packet);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------
    // Physics compensation tick loop
    // ------------------------------------------------------------

    private void tickPhysicsCompensation() {
        if (!betaPhysicsCompEnabled) {
            trackedArrowsLifeTicks.clear();
            return;
        }
        if (trackedArrowsLifeTicks.isEmpty()) return;

        for (Map.Entry<UUID, Integer> entry : trackedArrowsLifeTicks.entrySet()) {
            UUID arrowId = entry.getKey();
            int lifeTicks = entry.getValue();

            Entity ent = Bukkit.getEntity(arrowId);
            if (!(ent instanceof Arrow)) {
                trackedArrowsLifeTicks.remove(arrowId);
                continue;
            }

            Arrow arrow = (Arrow) ent;

            if (!arrow.isValid() || arrow.isDead() || arrow.isInBlock() || lifeTicks >= betaPhysicsMaxTicks) {
                trackedArrowsLifeTicks.remove(arrowId);
                continue;
            }

            Integer mark = arrow.getPersistentDataContainer().get(oldBowArrowKey, PersistentDataType.INTEGER);
            if (mark == null || mark != 1) {
                trackedArrowsLifeTicks.remove(arrowId);
                continue;
            }

            Vector v = arrow.getVelocity();

            if (betaGravityCancelPerTick != 0.0) {
                v = v.add(new Vector(0.0, betaGravityCancelPerTick, 0.0));
            }
            if (betaDragScalePerTick != 1.0) {
                v = v.multiply(betaDragScalePerTick);
            }

            double speed = v.length();
            if (speed > betaMaxSpeed && speed > 0.0) {
                v = v.normalize().multiply(betaMaxSpeed);
            }

            arrow.setVelocity(v);
            trackedArrowsLifeTicks.put(arrowId, lifeTicks + 1);
        }
    }

    // ------------------------------------------------------------
    // OldBow identification + sanitization
    // ------------------------------------------------------------

    private boolean isOldBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer marker = pdc.get(oldBowMarkerKey, PersistentDataType.INTEGER);
        String uuid = pdc.get(oldBowUuidKey, PersistentDataType.STRING);

        return marker != null && marker == 1 && uuid != null && !uuid.isEmpty();
    }

    private void forceOldBowNameAndStripEnchants(ItemStack item) {
        if (!isOldBow(item)) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        if (!OLD_BOW_NAME.equals(meta.getDisplayName())) {
            meta.setDisplayName(OLD_BOW_NAME);
        }

        if (!meta.getEnchants().isEmpty()) {
            for (Enchantment ench : meta.getEnchants().keySet()) {
                meta.removeEnchant(ench);
            }
        }

        if (!meta.isUnbreakable()) meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        item.setItemMeta(meta);
    }

    // Prevent durability loss
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (isOldBow(event.getItem())) event.setCancelled(true);
    }

    // Prevent enchanting
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareItemEnchant(PrepareItemEnchantEvent event) {
        if (isOldBow(event.getItem())) {
            if (event.getOffers() != null) {
                for (int i = 0; i < event.getOffers().length; i++) {
                    event.getOffers()[i] = null;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        if (isOldBow(event.getItem())) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> forceOldBowNameAndStripEnchants(event.getItem()));
        }
    }

    // Block /enchant in-hand
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().trim().toLowerCase();
        if (!msg.startsWith("/enchant")) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isOldBow(hand)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Old Bow cannot be enchanted.");
            Bukkit.getScheduler().runTask(this, () -> forceOldBowNameAndStripEnchants(hand));
        }
    }

    // Prevent anvil use
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        if (isOldBow(left) || isOldBow(right)) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilClick(InventoryClickEvent event) {
        if (event.getInventory().getType() != InventoryType.ANVIL) return;

        if (event.getSlotType() == InventoryType.SlotType.RESULT) {
            AnvilInventory inv = (AnvilInventory) event.getInventory();
            ItemStack left = inv.getItem(0);
            ItemStack right = inv.getItem(1);
            if (isOldBow(left) || isOldBow(right)) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player) {
                    ((Player) event.getWhoClicked()).sendMessage(ChatColor.RED + "Old Bow cannot be used in an anvil.");
                }
            }
        }
    }

    // General sanitization
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClickSanitize(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (isOldBow(current)) Bukkit.getScheduler().runTask(this, () -> forceOldBowNameAndStripEnchants(current));

        ItemStack cursor = event.getCursor();
        if (isOldBow(cursor)) Bukkit.getScheduler().runTask(this, () -> forceOldBowNameAndStripEnchants(cursor));
    }
}
