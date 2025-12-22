// Author: ChatGPT
// DiscordBridge (Spigot 1.21.10)
// - Relays Minecraft chat/events -> Discord via webhook
// - Relays Discord channel messages -> Minecraft via JDA bot
//
// Notes:
// - Uses modern hooks (AsyncPlayerChatEvent, PlayerDeathEvent, etc.)
// - Requires JDA on the runtime classpath (or shaded into the plugin JAR)

package com.example.discordbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class DiscordBridge extends JavaPlugin implements Listener {

    private JDA jda;

    private String botToken;
    private String webhookUrl;
    private long relayChannelId;
    private String serverName;

    // Optional behavior toggles
    private boolean relayJoinQuit;
    private boolean relayChat;
    private boolean relayDeaths;
    private boolean relayCommands;
    private boolean stripColorCodesInWebhook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        if (!isDiscordConfigured()) {
            getLogger().warning("Discord is not configured (bot-token/webhook-url/relay-channel-id). Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Register Spigot listeners
        Bukkit.getPluginManager().registerEvents(this, this);

        // Start JDA
        try {
            jda = JDABuilder.createDefault(
                            botToken,
                            GatewayIntent.GUILD_MESSAGES,
                            GatewayIntent.MESSAGE_CONTENT
                    )
                    .addEventListeners(new DiscordMessageListener())
                    .build();
        } catch (Exception e) {
            getLogger().severe("Failed to start JDA bot:");
            e.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        sendToDiscordWebhookAsync(serverName, "Server has started.");
        getLogger().info("Enabled DiscordBridge.");
    }

    @Override
    public void onDisable() {
        try {
            if (isDiscordConfigured()) {
                sendToDiscordWebhook(serverName, "Server is stopping...");
                sendToDiscordWebhook(serverName, "Server has stopped.");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (jda != null) {
            try {
                jda.shutdownNow();
            } catch (Exception ignored) {
            }
            jda = null;
        }

        getLogger().info("Disabled DiscordBridge.");
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    private void loadSettings() {
        FileConfiguration cfg = getConfig();

        this.botToken = cfg.getString("bot-token", "").trim();
        this.webhookUrl = cfg.getString("webhook-url", "").trim();
        this.relayChannelId = parseLongSafe(cfg.getString("relay-channel-id", "0").trim());
        this.serverName = cfg.getString("server-name", "Server").trim();
        if (this.serverName.isEmpty()) {
            this.serverName = "Server";
        }

        this.relayJoinQuit = cfg.getBoolean("relay.join-quit", true);
        this.relayChat = cfg.getBoolean("relay.chat", true);
        this.relayDeaths = cfg.getBoolean("relay.deaths", true);
        this.relayCommands = cfg.getBoolean("relay.commands", true);

        this.stripColorCodesInWebhook = cfg.getBoolean("webhook.strip-color-codes", true);
    }

    private boolean isDiscordConfigured() {
        return botToken != null && !botToken.isEmpty()
                && webhookUrl != null && !webhookUrl.isEmpty()
                && relayChannelId != 0L;
    }

    private long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    // -------------------------------------------------------------------------
    // Discord -> Minecraft
    // -------------------------------------------------------------------------

    private final class DiscordMessageListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (event.getAuthor().isBot()) {
                return;
            }
            if (event.getChannel().getIdLong() != relayChannelId) {
                return;
            }

            final String authorName = event.getAuthor().getName();
            final String content = event.getMessage().getContentDisplay();

            if (content == null || content.trim().isEmpty()) {
                return;
            }

            final String mcMessage = ChatColor.BLUE + "[Discord] " + ChatColor.AQUA + authorName + ChatColor.GRAY + ": "
                    + ChatColor.WHITE + content;

            Bukkit.getScheduler().runTask(DiscordBridge.this, () -> Bukkit.broadcastMessage(mcMessage));
        }
    }

    // -------------------------------------------------------------------------
    // Minecraft -> Discord (Webhook)
    // -------------------------------------------------------------------------

    private void sendToDiscordWebhookAsync(final String username, final String message) {
        // Use Bukkit async scheduler (modern + clean shutdown behavior)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                sendToDiscordWebhook(username, message);
            } catch (Exception e) {
                getLogger().warning("Failed to send webhook message:");
                e.printStackTrace();
            }
        });
    }

    private void sendToDiscordWebhook(String username, String message) throws Exception {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return;
        }

        String finalUsername = username == null ? "Server" : username;
        String finalMessage = message == null ? "" : message;

        if (stripColorCodesInWebhook) {
            finalUsername = ChatColor.stripColor(finalUsername);
            finalMessage = ChatColor.stripColor(finalMessage);
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(webhookUrl);
            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Content-Type", "application/json");

            String payload = buildWebhookPayload(finalUsername, finalMessage);

            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(bytes);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                getLogger().warning("Webhook failed. Response code: " + responseCode + " (" + connection.getResponseMessage() + ")");
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            getLogger().warning(line);
                        }
                    }
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

        return "{"
                + "\"username\":\"" + safeUsername + "\","
                + "\"content\":\"" + safeMessage + "\""
                + "}";
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char currentChar = text.charAt(index);
            switch (currentChar) {
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
                    if (currentChar < 0x20) {
                        builder.append(String.format("\\u%04x", (int) currentChar));
                    } else {
                        builder.append(currentChar);
                    }
                    break;
            }
        }
        return builder.toString();
    }

    // -------------------------------------------------------------------------
    // Spigot events (Minecraft side)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!relayJoinQuit) return;

        Player player = event.getPlayer();
        sendToDiscordWebhookAsync("Join/Quit", player.getName() + " joined the game.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!relayJoinQuit) return;

        Player player = event.getPlayer();
        sendToDiscordWebhookAsync("Join/Quit", player.getName() + " left the game.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!relayChat) return;

        Player player = event.getPlayer();
        String message = event.getMessage();
        sendToDiscordWebhookAsync("Chat", player.getName() + ": " + message);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!relayCommands) return;

        Player player = event.getPlayer();
        String message = event.getMessage();
        if (message != null && message.startsWith("/")) {
            sendToDiscordWebhookAsync("Commands", player.getName() + " used command: " + message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!relayDeaths) return;

        // Modern: use the actual computed death message from the server (no spoofing)
        String deathMessage = event.getDeathMessage();
        if (deathMessage == null || deathMessage.trim().isEmpty()) {
            Player victim = event.getEntity();
            deathMessage = victim.getName() + " died.";
        }

        sendToDiscordWebhookAsync("Deaths", deathMessage);
    }
}
