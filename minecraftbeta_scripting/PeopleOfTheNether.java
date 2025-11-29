import java.util.Random;
import java.util.logging.Logger;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.CreatureType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class PeopleOfTheNether extends JavaPlugin implements Listener {

    private final Logger logger = Logger.getLogger("Minecraft");
    private final Random random = new Random();

    // Track only the Monsters (mob id 49) that *we* spawn,
    // so we don't touch any other mobs.
    private final Set<Integer> trackedMonsterIds = new HashSet<Integer>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        logger.info("[PeopleOfTheNether] Enabled.");
    }

    @Override
    public void onDisable() {
        trackedMonsterIds.clear();
        logger.info("[PeopleOfTheNether] Disabled.");
    }

    /**
     * Use PigZombie spawns in the Nether as a template:
     * Whenever a PigZombie would spawn in the Nether, there is a chance
     * we replace it with a Monster (mob id 49).
     */
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        World world = entity.getWorld();

        if (world.getEnvironment() != Environment.NETHER) {
            return;
        }

        // Only react to PigZombie spawns in the Nether
        if (!(entity instanceof PigZombie)) {
            return;
        }

        // Example: 20% chance to replace a PigZombie with a Monster
        int chanceOutOfFive = 5; // denominator
        int roll = random.nextInt(chanceOutOfFive); // 0..4
        if (roll != 0) {
            return; // keep this spawn a normal PigZombie
        }

        // Cancel the PigZombie spawn and spawn a Monster instead
        Location spawnLocation = entity.getLocation();
        event.setCancelled(true);

        // Poseidon should support CreatureType.MONSTER for mob id 49 (Steve-like human)
        LivingEntity spawned = world.spawnCreature(spawnLocation, CreatureType.MONSTER);

        // Track this specific Monster so we can give it custom drops later
        if (spawned != null) {
            trackedMonsterIds.add(spawned.getEntityId());
        }
    }

    /**
     * Custom drops for the specific Monsters (mob id 49) that this plugin spawned:
     *  - 0–2 string
     *  - 0–2 feathers
     *  - 0–2 gunpowder
     *  - 0–1 flint and steel
     */
    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity rawEntity = event.getEntity();

        if (!(rawEntity instanceof LivingEntity)) {
            return;
        }

        LivingEntity entity = (LivingEntity) rawEntity;
        World world = entity.getWorld();

        if (world.getEnvironment() != Environment.NETHER) {
            return;
        }

        int entityId = entity.getEntityId();

        // Only modify drops if this is one of "our" Monsters
        if (!trackedMonsterIds.remove(entityId)) {
            return;
        }

        java.util.List<ItemStack> drops = event.getDrops();

        int stringCount = random.nextInt(3); // 0–2
        if (stringCount > 0) {
            drops.add(new ItemStack(Material.STRING, stringCount));
        }

        int featherCount = random.nextInt(3); // 0–2
        if (featherCount > 0) {
            drops.add(new ItemStack(Material.FEATHER, featherCount));
        }

        int gunpowderCount = random.nextInt(3); // 0–2
        if (gunpowderCount > 0) {
            // SULPHUR is gunpowder in Beta
            drops.add(new ItemStack(Material.SULPHUR, gunpowderCount));
        }

        int flintSteelCount = random.nextInt(2); // 0–1
        if (flintSteelCount > 0) {
            drops.add(new ItemStack(Material.FLINT_AND_STEEL, flintSteelCount));
        }
    }
}
