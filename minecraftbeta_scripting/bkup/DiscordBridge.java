package com.example.discordbridge;

import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;

import javax.security.auth.login.LoginException;

import org.bukkit.ChatColor;
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

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent; // Beta 1.7.3
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import org.bukkit.plugin.java.JavaPlugin;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class DiscordBridge extends JavaPlugin implements Listener {

    // ============================================================
    // CONFIG – EDIT THESE AND RECOMPILE
    // ============================================================

    // Your Discord bot token (NOT a webhook)
    private static final String BOT_TOKEN = "ALVINBLASTER";

    // Webhook URL for the channel where Minecraft events should appear
    private static final String WEBHOOK_URL = "ALVINBLASTER";

    // Numeric ID of the channel the bot will read messages from (same channel as webhook is typical)
    private static final long RELAY_CHANNEL_ID = ALVINBLASTERL;

    // ============================================================

    private JDA jda;

    @Override
    public void onEnable() {
        System.out.println("[DiscordBridge] Enabling...");

        if (BOT_TOKEN == null || BOT_TOKEN.trim().isEmpty()
                || WEBHOOK_URL == null || WEBHOOK_URL.trim().isEmpty()
                || RELAY_CHANNEL_ID == 0L) {

            System.out.println("[DiscordBridge] BOT_TOKEN / WEBHOOK_URL / RELAY_CHANNEL_ID not set. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            // JDA 4.x: enable GUILD_MESSAGES intent
            jda = JDABuilder.createDefault(BOT_TOKEN)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES)
                    .addEventListeners(new DiscordListener())
                    .setAutoReconnect(true)
                    .build();

            System.out.println("[DiscordBridge] JDA started.");
        } catch (LoginException e) {
            System.out.println("[DiscordBridge] Failed to login to Discord: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        } catch (Exception e) {
            System.out.println("[DiscordBridge] Failed to start JDA: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
        System.out.println("[DiscordBridge] Enabled.");
    }

    @Override
    public void onDisable() {
        System.out.println("[DiscordBridge] Disabling...");
        if (jda != null) {
            try {
                jda.shutdownNow();
            } catch (Exception ignored) {
            }
        }
        System.out.println("[DiscordBridge] Disabled.");
    }

    // ============================================================
    // Bukkit -> Discord relay (joins / quits / chat / commands)
    // ============================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String msg = player.getName() + " joined the game.";
        sendToDiscordWebhookAsync(player.getName(), msg);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String msg = player.getName() + " left the game.";
        sendToDiscordWebhookAsync(player.getName(), msg);
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String msg = "<" + player.getName() + "> " + event.getMessage();
        sendToDiscordWebhookAsync(player.getName(), msg);
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String full = event.getMessage(); // e.g. "/login mypassword"
        String lower = full.toLowerCase();

        // xAuth-style sensitive commands – log only the command name, not args
        if (lower.startsWith("/login") || lower.startsWith("/register")) {
            String[] parts = lower.split(" ");
            String cmdOnly = parts.length > 0 ? parts[0] : "/login";

            String msg = player.getName() + " used " + cmdOnly + " (arguments hidden)";
            sendToDiscordWebhookAsync(player.getName(), msg);
            return;
        }

        // Normal commands: log full message
        String formatted = player.getName() + " used command: " + full;
        sendToDiscordWebhookAsync(player.getName(), formatted);
    }

    // ============================================================
    // Death messages -> Discord (SimpleDeathMessages-style)
    // ============================================================

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity dead = event.getEntity();
        if (!(dead instanceof Player)) {
            return;
        }

        Player player = (Player) dead;
        String playerName = player.getName();

        // Use last damage cause to reconstruct a nicer death message.
        EntityDamageEvent lastDamage = player.getLastDamageCause();
        String deathMessage;

        if (lastDamage != null) {
            deathMessage = getDeathMessage(player, lastDamage);
        } else {
            deathMessage = playerName + " died";
        }

        if (deathMessage != null && deathMessage.length() > 0) {
            sendToDiscordWebhookAsync(playerName, deathMessage);
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

            default:
                // Catch-all
                return playerName + " died";
        }
    }

    /**
     * Determine a nice display name for a mob.
     * Generic Monster becomes "Herobrine".
     */
    private String getMobDisplayName(LivingEntity entity) {
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

    // ============================================================
    // Async webhook sender
    // ============================================================

    private void sendToDiscordWebhookAsync(final String playerName, final String text) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                sendToDiscordWebhook(playerName, text);
            }
        }, "DiscordBridge-Webhook-" + playerName).start();
    }

    private void sendToDiscordWebhook(String playerName, String text) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(WEBHOOK_URL);

            // Explicitly avoid any system proxy that might be set
            Proxy proxy = Proxy.NO_PROXY;
            connection = (HttpURLConnection) url.openConnection(proxy);

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) DiscordBridge/1.0"
            );

            String avatarUrl = getAvatarUrl(playerName);

            String payload = "{"
                    + "\"username\":\"" + escapeJson(playerName) + "\","
                    + "\"avatar_url\":\"" + escapeJson(avatarUrl) + "\","
                    + "\"content\":\"" + escapeJson(text) + "\""
                    + "}";

            byte[] payloadBytes = payload.getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(payloadBytes.length));

            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(payloadBytes);
            outputStream.flush();
            outputStream.close();

            int responseCode = connection.getResponseCode();
            if (responseCode != 204 && responseCode != 200) {
                System.out.println("[DiscordBridge] Webhook HTTP " + responseCode + " " + connection.getResponseMessage());

                try {
                    InputStream errorStream = connection.getErrorStream();
                    if (errorStream != null) {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(errorStream, "UTF-8"));
                        String line;
                        StringBuilder body = new StringBuilder();
                        while ((line = reader.readLine()) != null) {
                            body.append(line);
                        }
                        reader.close();
                        System.out.println("[DiscordBridge] Webhook error body: " + body.toString());
                    }
                } catch (Exception inner) {
                    System.out.println("[DiscordBridge] Failed to read webhook error body: " + inner.getMessage());
                }
            }

        } catch (Exception e) {
            System.out.println("[DiscordBridge] Failed to send webhook: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ============================================================
    // Discord -> Bukkit relay
    // ============================================================

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            // Wrong channel → ignore
            if (event.getChannel().getIdLong() != RELAY_CHANNEL_ID) {
                return;
            }

            // Ignore bots and webhooks (to avoid loops with our own webhook posts)
            if (event.getAuthor().isBot() || event.isWebhookMessage()) {
                return;
            }

            final String authorName = event.getAuthor().getName();
            final String content = event.getMessage().getContentDisplay();

            System.out.println("[DiscordBridge] Discord message in relay channel from "
                    + authorName + ": " + content);

            if (content == null || content.trim().isEmpty()) {
                return;
            }

            final String mcMessage = ChatColor.DARK_AQUA + "[Discord] "
                    + authorName + ": " + content;

            getServer().getScheduler().scheduleSyncDelayedTask(DiscordBridge.this, new Runnable() {
                @Override
                public void run() {
                    getServer().broadcastMessage(mcMessage);
                }
            });
        }
    }

    // ============================================================
    // Skin / avatar API
    // ============================================================

    // Minotar face (works fine for offline/beta names)
    private String getAvatarUrl(String playerName) {
        try {
            String encodedName = URLEncoder.encode(playerName, "UTF-8");
            return "https://minotar.net/avatar/" + encodedName + "/64.png";
        } catch (Exception e) {
            return "https://minotar.net/avatar/Steve/64.png";
        }
    }

    // ============================================================
    // Simple JSON escaping
    // ============================================================

    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 32) {
                        String hex = Integer.toHexString(c);
                        builder.append("\\u");
                        for (int j = hex.length(); j < 4; j++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(c);
                    }
                    break;
            }
        }
        return builder.toString();
    }
}
