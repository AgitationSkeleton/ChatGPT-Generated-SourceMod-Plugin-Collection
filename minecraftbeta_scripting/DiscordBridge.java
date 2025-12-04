// Author: ChatGPT
// DiscordBridge - bridges Minecraft chat/events with Discord via JDA and webhooks
//
// NOTE: This version is written for Minecraft Beta 1.7.3 Bukkit/Poseidon,
// without using org.bukkit.configuration.file.* APIs.

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Monster;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Spider;
import org.bukkit.entity.Zombie;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerChatEvent; // Beta 1.7.3
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.plugin.java.JavaPlugin;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class DiscordBridge extends JavaPlugin implements Listener {

    // ============================================================
    // CONFIG – loaded from config.yml in the plugin folder
    // ============================================================

    private final Map<String, String> configValues = new HashMap<String, String>();

    private String botToken;
    private String webhookUrl;
    private long relayChannelId;
    private String serverName;

    private File configFile;

    // ============================================================

    private JDA jda;

    @Override
    public void onLoad() {
        System.out.println("[DiscordBridge] onLoad...");
        initConfigFile();
        loadConfigValues();
        if (isDiscordConfigured()) {
            sendToDiscordWebhookAsync(serverName, "Server is starting...");
        }
    }

    @Override
    public void onEnable() {
        System.out.println("[DiscordBridge] Enabling DiscordBridge...");

        initConfigFile();
        loadConfigValues();

        if (!isDiscordConfigured()) {
            System.out.println("[DiscordBridge] bot-token, webhook-url, or relay-channel-id not set in config.yml. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            jda = JDABuilder.createDefault(
                        botToken,
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT
                    )
                    .addEventListeners(new DiscordMessageListener())
                    .build();

            System.out.println("[DiscordBridge] JDA bot logged in.");
        } catch (Exception e) {
            System.out.println("[DiscordBridge] Failed to log in bot:");
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        System.out.println("[DiscordBridge] Enabled.");
        sendToDiscordWebhookAsync(serverName, "Server has started.");
    }

    @Override
    public void onDisable() {
        System.out.println("[DiscordBridge] Disabling DiscordBridge...");
        if (jda != null) {
            try {
                sendToDiscordWebhook(serverName, "Server is stopping...");
                sendToDiscordWebhook(serverName, "Server has stopped.");
            } catch (Exception e) {
                e.printStackTrace();
            }
            jda.shutdownNow();
        }
        System.out.println("[DiscordBridge] Disabled.");
    }

    // ============================================================
    // Simple config.yml handling (no Bukkit FileConfiguration)
    // ============================================================

    private void initConfigFile() {
        File folder = getDataFolder();
        if (!folder.exists()) {
            folder.mkdirs();
        }
        configFile = new File(folder, "config.yml");
        if (!configFile.exists()) {
            writeDefaultConfig();
        }
    }

    private void writeDefaultConfig() {
        System.out.println("[DiscordBridge] Creating default config.yml...");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(configFile));
            writer.write("# DiscordBridge configuration\n");
            writer.write("# bot-token: Your Discord bot token (NOT a webhook)\n");
            writer.write("# webhook-url: Discord webhook URL for MC -> Discord events\n");
            writer.write("# relay-channel-id: Numeric ID of the channel the bot should read from\n");
            writer.write("# server-name: Name used in lifecycle messages\n");
            writer.write("\n");
            writer.write("bot-token: \"REPLACE_ME_BOT_TOKEN\"\n");
            writer.write("webhook-url: \"https://discord.com/api/webhooks/ID/TOKEN\"\n");
            writer.write("relay-channel-id: \"123456789012345678\"\n");
            writer.write("server-name: \"Server\"\n");
        } catch (Exception e) {
            System.out.println("[DiscordBridge] Failed to write default config.yml:");
            e.printStackTrace();
        } finally {
            try {
                if (writer != null) writer.close();
            } catch (Exception ignored) {}
        }
    }

    private void loadConfigValues() {
        configValues.clear();

        if (configFile == null) {
            initConfigFile();
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(configFile));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                int colonIndex = line.indexOf(':');
                if (colonIndex == -1) {
                    continue;
                }
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                // Strip surrounding quotes if present
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }

                configValues.put(key, value);
            }
        } catch (Exception e) {
            System.out.println("[DiscordBridge] Failed to read config.yml:");
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
        }

        this.botToken = safeGetString("bot-token");
        this.webhookUrl = safeGetString("webhook-url");
        this.serverName = safeGetString("server-name");
        if (this.serverName.length() == 0) {
            this.serverName = "Server";
        }

        this.relayChannelId = 0L;
        String channelIdString = safeGetString("relay-channel-id");
        if (channelIdString.length() > 0) {
            try {
                this.relayChannelId = Long.parseLong(channelIdString);
            } catch (NumberFormatException ex) {
                System.out.println("[DiscordBridge] Invalid relay-channel-id in config.yml; must be a numeric Discord channel ID.");
                this.relayChannelId = 0L;
            }
        }
    }

    private String safeGetString(String key) {
        String value = configValues.get(key);
        return value == null ? "" : value.trim();
    }

    private boolean isDiscordConfigured() {
        return botToken != null && botToken.length() > 0
                && webhookUrl != null && webhookUrl.length() > 0
                && relayChannelId != 0L;
    }

    // ============================================================
    // DISCORD -> MINECRAFT
    // ============================================================

    private class DiscordMessageListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) {
                return;
            }

            long channelId = event.getChannel().getIdLong();
            // Only relay from the configured channel, and do not log anything else
            if (channelId != relayChannelId) {
                return;
            }

            System.out.println("[DiscordBridge] MessageReceivedEvent from configured channel.");

            final String authorName = event.getAuthor().getName();
            final String content = event.getMessage().getContentDisplay();

            final String mcMessage = ChatColor.BLUE + "[Discord] " + authorName + ": " + ChatColor.WHITE + content;
            getServer().getScheduler().scheduleSyncDelayedTask(DiscordBridge.this, new Runnable() {
                public void run() {
                    getServer().broadcastMessage(mcMessage);
                }
            });
        }
    }

    // ============================================================
    // MINECRAFT -> DISCORD (WEBHOOK)
    // ============================================================

    private void sendToDiscordWebhookAsync(final String username, final String message) {
        // Use a plain Java thread instead of Bukkit async scheduler (more compatible with old Bukkit)
        new Thread(new Runnable() {
            public void run() {
                try {
                    sendToDiscordWebhook(username, message);
                } catch (Exception e) {
                    System.out.println("[DiscordBridge] Failed to send webhook message:");
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void sendToDiscordWebhook(String username, String message) throws Exception {
        if (webhookUrl == null || webhookUrl.trim().length() == 0) {
            System.out.println("[DiscordBridge] Webhook URL not configured; skipping send.");
            return;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(webhookUrl);
            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            String payload = buildWebhookPayload(username, message);

            OutputStream os = connection.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                System.out.println("[DiscordBridge] Webhook sent successfully. Response code: " + responseCode);
            } else {
                System.out.println("[DiscordBridge] Webhook failed. Response code: " + responseCode);
                System.out.println("[DiscordBridge] Response message: " + connection.getResponseMessage());
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(errorStream));
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.out.println("[DiscordBridge] " + line);
                    }
                    br.close();
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildWebhookPayload(String username, String message) {
        String safeUsername = escapeJson(username);
        String safeMessage = escapeJson(message);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"username\":\"").append(safeUsername).append("\",");
        sb.append("\"content\":\"").append(safeMessage).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
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
                    if (c < 0x20 || c > 0x7E) {
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

    // ============================================================
    // BUKKIT EVENTS (MINECRAFT SIDE)
    // ============================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String msg = name + " joined the game.";
        sendToDiscordWebhookAsync("Join/Quit", msg);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String msg = name + " left the game.";
        sendToDiscordWebhookAsync("Join/Quit", msg);
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String msg = event.getMessage();
        String formatted = name + ": " + msg;
        sendToDiscordWebhookAsync("Chat", formatted);
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String message = event.getMessage();

        if (message.startsWith("/")) {
            sendToDiscordWebhookAsync("Commands", name + " used command: " + message);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        EntityDamageEvent lastDamage = entity.getLastDamageCause();

        if (!(entity instanceof Player)) {
            return;
        }
        Player victim = (Player) entity;
        String victimName = victim.getName();
        String deathMessage;

        if (lastDamage == null) {
            deathMessage = victimName + " died.";
        } else {
            DamageCause cause = lastDamage.getCause();
            if (cause == DamageCause.ENTITY_ATTACK && lastDamage instanceof EntityDamageByEntityEvent) {
                EntityDamageByEntityEvent byEntity = (EntityDamageByEntityEvent) lastDamage;
                Entity damager = byEntity.getDamager();

                if (damager instanceof Player) {
                    Player killer = (Player) damager;
                    deathMessage = victimName + " was killed by " + killer.getName() + ".";
                } else if (damager instanceof Creeper) {
                    deathMessage = victimName + " was blown up by a Creeper.";
                } else if (damager instanceof Skeleton) {
                    deathMessage = victimName + " was shot by a Skeleton.";
                } else if (damager instanceof Spider) {
                    deathMessage = victimName + " was slain by a Spider.";
                } else if (damager instanceof Zombie) {
                    deathMessage = victimName + " was slain by a Zombie.";
                } else if (damager instanceof PigZombie) {
                    deathMessage = victimName + " was slain by a Zombie Pigman.";
                } else if (damager instanceof Slime) {
                    deathMessage = victimName + " was slain by a Slime.";
                } else if (damager instanceof Ghast) {
                    deathMessage = victimName + " was fireballed by a Ghast.";
                } else if (damager instanceof Monster) {
                    deathMessage = victimName + " was slain by a monster.";
                } else {
                    deathMessage = victimName + " was slain by an unknown entity.";
                }
            } else {
                switch (cause) {
                    case VOID:
                        deathMessage = victimName + " fell into the void.";
                        break;
                    case FIRE_TICK:
                        deathMessage = victimName + " burned to death.";
                        break;
                    case FIRE:
                        deathMessage = victimName + " went up in flames.";
                        break;
                    case LAVA:
                        deathMessage = victimName + " tried to swim in lava.";
                        break;
                    case FALL:
                        deathMessage = victimName + " fell from a high place.";
                        break;
                    case SUFFOCATION:
                        deathMessage = victimName + " suffocated in a wall.";
                        break;
                    case DROWNING:
                        deathMessage = victimName + " drowned.";
                        break;
                    case BLOCK_EXPLOSION:
                    case ENTITY_EXPLOSION:
                        deathMessage = victimName + " blew up.";
                        break;
                    case CONTACT:
                        deathMessage = victimName + " was pricked to death.";
                        break;
                    case LIGHTNING:
                        deathMessage = victimName + " was struck by lightning.";
                        break;
                    default:
                        deathMessage = victimName + " died.";
                        break;
                }
            }
        }

        sendToDiscordWebhookAsync("Deaths", deathMessage);
    }
}
