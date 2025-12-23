// Author: ChatGPT
// DiscordBridge (Spigot 1.21.10)
// - Relays Minecraft chat/events -> Discord via webhook (player avatar via Minotar isometric cube)
// - Relays Discord channel messages -> Minecraft via JDA bot
//
// No config changes required.

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

    private boolean relayJoinQuit;
    private boolean relayChat;
    private boolean relayDeaths;
    private boolean relayCommands;
    private boolean stripColorCodesInWebhook;

    // Minotar "Isometric Head" (cube) PNG
    private static final int AVATAR_SIZE = 64;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        if (!isDiscordConfigured()) {
            getLogger().warning("Discord is not configured (bot-token/webhook-url/relay-channel-id). Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);

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

        // Server lifecycle messages: keep server name, no avatar override
        sendToDiscordWebhookAsync(serverName, null, "Server has started.");
        getLogger().info("Enabled DiscordBridge.");
    }

    @Override
    public void onDisable() {
        try {
            if (isDiscordConfigured()) {
                sendToDiscordWebhook(serverName, null, "Server is stopping...");
                sendToDiscordWebhook(serverName, null, "Server has stopped.");
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
            if (event.getAuthor().isBot()) return;
            if (event.getChannel().getIdLong() != relayChannelId) return;

            final String authorName = event.getAuthor().getName();
            final String content = event.getMessage().getContentDisplay();
            if (content == null || content.trim().isEmpty()) return;

            final String mcMessage = ChatColor.BLUE + "[Discord] " + ChatColor.AQUA + authorName + ChatColor.GRAY + ": "
                    + ChatColor.WHITE + content;

            Bukkit.getScheduler().runTask(DiscordBridge.this, () -> Bukkit.broadcastMessage(mcMessage));
        }
    }

    // -------------------------------------------------------------------------
    // Minecraft -> Discord (Webhook)
    // -------------------------------------------------------------------------

    private void sendToDiscordWebhookAsync(final String username, final String avatarUrl, final String message) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                sendToDiscordWebhook(username, avatarUrl, message);
            } catch (Exception e) {
                getLogger().warning("Failed to send webhook message:");
                e.printStackTrace();
            }
        });
    }

    private void sendToDiscordWebhook(String username, String avatarUrl, String message) throws Exception {
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

            String payload = buildWebhookPayload(finalUsername, avatarUrl, finalMessage);

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
            if (connection != null) connection.disconnect();
        }
    }

    private String buildWebhookPayload(String username, String avatarUrl, String message) {
        String safeUsername = escapeJson(username);
        String safeMessage = escapeJson(message);

        StringBuilder payload = new StringBuilder(256);
        payload.append("{");
        payload.append("\"username\":\"").append(safeUsername).append("\",");

        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            payload.append("\"avatar_url\":\"").append(escapeJson(avatarUrl.trim())).append("\",");
        }

        payload.append("\"content\":\"").append(safeMessage).append("\"");
        payload.append("}");
        return payload.toString();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int index = 0; index < text.length(); index++) {
            char currentChar = text.charAt(index);
            switch (currentChar) {
                case '\\': builder.append("\\\\"); break;
                case '"': builder.append("\\\""); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (currentChar < 0x20) builder.append(String.format("\\u%04x", (int) currentChar));
                    else builder.append(currentChar);
                    break;
            }
        }
        return builder.toString();
    }

    // -------------------------------------------------------------------------
    // Avatar URL (Minotar isometric cube)
    // -------------------------------------------------------------------------

    private String getIsometricAvatarUrlForName(String playerName) {
        if (playerName == null) return null;
        String name = playerName.trim();
        if (name.isEmpty()) return null;

        // Minotar docs: https://minotar.net/cube/user/100.png :contentReference[oaicite:3]{index=3}
        return "https://minotar.net/cube/" + urlEncodeLoose(name) + "/" + AVATAR_SIZE + ".png";
    }

    private String urlEncodeLoose(String text) {
        // MC usernames are safe; this is only defensive
        return text.replace(" ", "%20");
    }

    // -------------------------------------------------------------------------
    // Spigot events (Minecraft side)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!relayJoinQuit) return;
        Player player = event.getPlayer();
        sendToDiscordWebhookAsync(player.getName(), getIsometricAvatarUrlForName(player.getName()), player.getName() + " joined the game.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!relayJoinQuit) return;
        Player player = event.getPlayer();
        sendToDiscordWebhookAsync(player.getName(), getIsometricAvatarUrlForName(player.getName()), player.getName() + " left the game.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!relayChat) return;
        Player player = event.getPlayer();
        sendToDiscordWebhookAsync(player.getName(), getIsometricAvatarUrlForName(player.getName()), event.getMessage());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!relayCommands) return;
        Player player = event.getPlayer();
        String message = event.getMessage();
        if (message != null && message.startsWith("/")) {
            sendToDiscordWebhookAsync(player.getName(), getIsometricAvatarUrlForName(player.getName()), "used command: " + message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!relayDeaths) return;

        Player victim = event.getEntity();
        String deathMessage = event.getDeathMessage();
        if (deathMessage == null || deathMessage.trim().isEmpty()) {
            deathMessage = victim.getName() + " died.";
        }

        sendToDiscordWebhookAsync(victim.getName(), getIsometricAvatarUrlForName(victim.getName()), deathMessage);
    }
}
