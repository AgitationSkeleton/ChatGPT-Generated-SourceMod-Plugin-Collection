// Author: ChatGPT
// Plugin: StatusWebhook - periodic Discord status webhook for Minecraft Beta 1.7.3

import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class StatusWebhook extends JavaPlugin {

    // Put your real webhook URL here.
    // Example: "https://discord.com/api/webhooks/123456789012345678/yourlongtoken..."
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/REPLACE_ME";

    // Icon to show at the top-left of the embed next to the bold text
    private static final String ICON_URL = "https://resources.redchanit.xyz/games/minecraftbeta.png";

    // Light green embed color (0x57F287)
    private static final int EMBED_COLOR = 0x57F287;

    // Used to avoid firing multiple times within the same 15-minute slot
    private int lastTriggerHour = -1;
    private int lastTriggerQuarter = -1;

    @Override
    public void onEnable() {
        System.out.println("[StatusWebhook] Enabled.");

        if (WEBHOOK_URL == null || WEBHOOK_URL.trim().length() == 0 || WEBHOOK_URL.contains("REPLACE_ME")) {
            System.out.println("[StatusWebhook] WARNING: WEBHOOK_URL is not set. Edit the source and recompile.");
        }

        // Run every 60 seconds (1200 ticks), check if we are at a 15-minute boundary.
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                checkAndSendPeriodicWebhook();
            }
        }, 20L, 1200L); // initial delay 1 second, period 60 seconds
    }

    @Override
    public void onDisable() {
        System.out.println("[StatusWebhook] Disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("testwebhook")) {
            // Ops only
            if (!sender.isOp()) {
                sender.sendMessage("You must be an operator to use this command.");
                return true;
            }

            sender.sendMessage("Triggering status webhook (will only send if players are online)...");
            sendStatusWebhook();
            return true;
        }

        return false;
    }

    private void checkAndSendPeriodicWebhook() {
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.MINUTE);
        int hour = now.get(Calendar.HOUR_OF_DAY);

        if (minute % 15 != 0) {
            return;
        }

        int currentQuarter = minute / 15; // 0 = :00, 1 = :15, 2 = :30, 3 = :45

        // Check if we already triggered this hour/quarter
        if (hour == lastTriggerHour && currentQuarter == lastTriggerQuarter) {
            return;
        }

        lastTriggerHour = hour;
        lastTriggerQuarter = currentQuarter;

        sendStatusWebhook();
    }

    private void sendStatusWebhook() {
        Player[] players = getServer().getOnlinePlayers();
        int onlineCount = players.length;
        int maxPlayers = getServer().getMaxPlayers();

        if (onlineCount <= 0) {
            // No players online; do nothing.
            return;
        }

        // Build the embed description text
        StringBuilder description = new StringBuilder();

        // Top line is now handled by the embed author (with icon).
        // So we start directly with the counts.
        description.append("There are ")
                   .append(onlineCount)
                   .append("/")
                   .append(maxPlayers)
                   .append(" online.\n");
        description.append("play.redchanit.xyz:24565\n\n");

        description.append("__Online Players__\n\n");

        for (int i = 0; i < onlineCount; i++) {
            Player player = players[i];
            description.append("**Name**\n");
            description.append(player.getName()).append("\n\n");
        }

        description.append("[Dynmap](http://play.redchanit.xyz:8123/)\n");

        // Timestamp: format like "1/16/2025 9:00 AM" in America/Los_Angeles
        SimpleDateFormat sdf = new SimpleDateFormat("M/d/yyyy h:mm a");
        sdf.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
        String timestamp = sdf.format(Calendar.getInstance().getTime());
        description.append(timestamp);

        final String descriptionText = description.toString();

        // Fire the HTTP request off-thread so we do not block the main server thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                postDiscordWebhook(descriptionText);
            }
        }, "StatusWebhook-Poster").start();
    }

    private void postDiscordWebhook(String descriptionText) {
        if (WEBHOOK_URL == null || WEBHOOK_URL.trim().length() == 0 || WEBHOOK_URL.contains("REPLACE_ME")) {
            System.out.println("[StatusWebhook] WEBHOOK_URL is not set. Cannot send webhook.");
            return;
        }

        try {
            String authorName = "Minecraft Beta 1.7.3";

            String escapedDescription = escapeJson(descriptionText);
            String escapedAuthorName = escapeJson(authorName);
            String escapedIconUrl = escapeJson(ICON_URL);

            // Construct JSON payload with author (for icon + bold text at top)
            // {
            //   "username": "REDchanit.xyz | Minecraft Beta 1.7.3",
            //   "embeds": [
            //     {
            //       "author": {
            //         "name": "Minecraft Beta 1.7.3",
            //         "icon_url": "https://resources.redchanit.xyz/games/minecraftbeta.png"
            //       },
            //       "description": "...",
            //       "color": 5763719
            //     }
            //   ]
            // }
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{");
            jsonBuilder.append("\"username\":\"REDchanit.xyz | Minecraft Beta 1.7.3\",");
            jsonBuilder.append("\"embeds\":[{");
            jsonBuilder.append("\"author\":{");
            jsonBuilder.append("\"name\":\"").append(escapedAuthorName).append("\",");
            jsonBuilder.append("\"icon_url\":\"").append(escapedIconUrl).append("\"");
            jsonBuilder.append("},");
            jsonBuilder.append("\"description\":\"").append(escapedDescription).append("\",");
            jsonBuilder.append("\"color\":").append(EMBED_COLOR);
            jsonBuilder.append("}]}");

            String jsonPayload = jsonBuilder.toString();

            URL url = new URL(WEBHOOK_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "StatusWebhookPlugin/1.0 (Java)");

            byte[] payloadBytes = jsonPayload.getBytes("UTF-8");
            connection.setRequestProperty("Content-Length", String.valueOf(payloadBytes.length));

            OutputStream outputStream = null;
            try {
                outputStream = connection.getOutputStream();
                outputStream.write(payloadBytes);
                outputStream.flush();
            } finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException ignored) {
                    }
                }
            }

            int responseCode = connection.getResponseCode();

            if (responseCode < 200 || responseCode >= 300) {
                System.out.println("[StatusWebhook] Webhook POST returned HTTP " + responseCode);

                String errorBody = readStream(connection.getErrorStream());
                if (errorBody == null || errorBody.length() == 0) {
                    errorBody = readStream(connection.getInputStream());
                }

                if (errorBody != null && errorBody.length() > 0) {
                    System.out.println("[StatusWebhook] Error body: " + errorBody);
                }
            }

            connection.disconnect();
        } catch (Exception e) {
            System.out.println("[StatusWebhook] Error sending Discord webhook: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }

        String escaped = text;

        escaped = escaped.replace("\\", "\\\\");
        escaped = escaped.replace("\"", "\\\"");
        escaped = escaped.replace("\n", "\\n");
        escaped = escaped.replace("\r", "");

        return escaped;
    }

    private String readStream(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
            return body.toString();
        } catch (IOException ignored) {
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
                inputStream.close();
            } catch (IOException ignored2) {
            }
        }
    }
}
