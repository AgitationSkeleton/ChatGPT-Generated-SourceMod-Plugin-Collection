import java.util.logging.Logger;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;

import org.bukkit.entity.Creeper;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Giant;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Monster;

import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityListener;

import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class SimpleDeathMessages extends JavaPlugin {

    private static final Logger log = Logger.getLogger("Minecraft");

    private final SimpleDeathMessagesEntityListener entityListener =
            new SimpleDeathMessagesEntityListener(this);

    @Override
    public void onEnable() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(Event.Type.ENTITY_DAMAGE, entityListener, Priority.Highest, this);
        log.info("[SimpleDeathMessages] Enabled.");
    }

    @Override
    public void onDisable() {
        log.info("[SimpleDeathMessages] Disabled.");
    }

    private static class SimpleDeathMessagesEntityListener extends EntityListener {

        private final SimpleDeathMessages plugin;

        public SimpleDeathMessagesEntityListener(SimpleDeathMessages plugin) {
            this.plugin = plugin;
        }

        @Override
        public void onEntityDamage(EntityDamageEvent event) {
            if (event.isCancelled()) {
                return;
            }

            if (!(event.getEntity() instanceof Player)) {
                return;
            }

            Player player = (Player) event.getEntity();

            int damage = event.getDamage();
            int health = player.getHealth();

            // Only care about lethal hits
            if (health > damage) {
                return;
            }

            String deathMessage = getDeathMessage(player, event);
            if (deathMessage != null && deathMessage.length() > 0) {
                plugin.getServer().broadcastMessage(deathMessage);
            }
        }

        private String getDeathMessage(Player player, EntityDamageEvent lastDamage) {
            String playerName = player.getName();
            DamageCause cause = lastDamage.getCause();

            // Entity attacks: players / mobs
            if (cause == DamageCause.ENTITY_ATTACK && lastDamage instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent byEntity = (EntityDamageByEntityEvent) lastDamage;
                Entity damager = byEntity.getDamager();

                if (damager instanceof Player) {
                    Player killer = (Player) damager;
                    return playerName + " was slain by " + killer.getName();
                }

                if (damager instanceof LivingEntity) {
                    LivingEntity livingDamager = (LivingEntity) damager;
                    String mobName = getMobDisplayName(livingDamager);
                    return playerName + " was slain by " + mobName;
                }

                // Fallback generic melee
                return playerName + " was slain";
            }

            // Environmental / non-entity causes
            switch (cause) {
                case VOID:
                    return playerName + " fell out of the world";

                case DROWNING:
                    return playerName + " drowned";

                case SUFFOCATION:
                    return playerName + " suffocated in a wall";

                case LAVA:
                    return playerName + " tried to swim in lava";

                case FIRE:
                case FIRE_TICK:
                    return playerName + " burned to death";

                case FALL:
                    // Slightly modern-ish text
                    return playerName + " hit the ground too hard";

                case CONTACT:
                    // Cactus etc.
                    return playerName + " was pricked to death";

                case BLOCK_EXPLOSION:
                case ENTITY_EXPLOSION:
                    return playerName + " exploded";

                case LIGHTNING:
                    return playerName + " was struck by lightning";

                case SUICIDE:
                    // /kill
                    return playerName + " committed suicide";
            }

            // Final catch-all "else" for any unhandled death cause
            return playerName + " died";
        }

        /**
         * Determine a nice display name for a mob.
         * Generic Monster becomes "Herobrine".
         */
        private String getMobDisplayName(LivingEntity entity) {
            // Explicit classes first
            if (entity instanceof Creeper) {
                return "Creeper";
            }
            if (entity instanceof Skeleton) {
                return "Skeleton";
            }
            if (entity instanceof Zombie) {
                return "Zombie";
            }
            if (entity instanceof Spider) {
                return "Spider";
            }
            if (entity instanceof Giant) {
                return "Giant";
            }
            if (entity instanceof PigZombie) {
                return "Zombie Pigman";
            }
            if (entity instanceof Wolf) {
                return "Wolf";
            }
            if (entity instanceof Slime) {
                return "Slime";
            }
            if (entity instanceof Ghast) {
                return "Ghast";
            }

            // Special-case generic Monster as "Herobrine"
            if (entity instanceof Monster) {
                return "Herobrine";
            }

            // Fallback: plain "Mob"
            return "Mob";
        }
    }
}
