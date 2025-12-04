// Author: ChatGPT
// StatusWebhook - periodic Discord status webhook for Minecraft Beta 1.7.3
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
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class StatusWebhook extends JavaPlugin {

    // Default icon (can be overridden in config.yml)
    private static final String DEFAULT_ICON_URL = "https://resources.redchanit.xyz/games/minecraftbeta.png";

    // Light green embed color (0x57F287)
    private static final int EMBED_COLOR = 0x57F287;

    // Config-driven fields
    private final Map<String, String> configValues = new HashMap<String, String>();

    private String webhookUrl;
    private String serverName;
    private String serverAddress;
    private String dynmapUrl;
    private String iconUrl;

    private File configFile;

    // Used to avoid firing multiple times within the same 15-minute slot
    private int lastTriggerHour = -1;
    private int lastTriggerQuarter = -1;

    @Override
    public void onEnable() {
        System.out.println("[StatusWebhook] Enabled.");

        initConfigFile();
        loadConfigValues();

        if (webhookUrl == null || webhookUrl.length() == 0 || webhookUrl.contains("REPLACE_ME")) {
            System.out.println("[StatusWebhook] WARNING: webhook-url is not set in config.yml.");
        }

        // Schedule a repeating task every minute (20 ticks * 60 = 1200)
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() {
                try {
                    checkAndSendPeriodicStatus();
                } catch (Exception e) {
                    System.out.println("[StatusWebhook] Error in periodic task:");
                    e.printStackTrace();
                }
            }
        }, 20L, 1200L); // initial delay 1 second, period 60 seconds
    }

    @Override
    public void onDisable() {
        System.out.println("[StatusWebhook] Disabled.");
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
        System.out.println("[StatusWebhook] Creating default config.yml...");
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(configFile));
            writer.write("# StatusWebhook configuration\n");
            writer.write("# webhook-url: Discord webhook URL for periodic status embeds\n");
            writer.write("# server-name: Display name for this server in the embed and webhook username\n");
            writer.write("# server-address: IP / DNS + port line to show in the status message\n");
            writer.write("# dynmap-url: Optional Dynmap URL to display under the address\n");
            writer.write("# icon-url: Optional icon URL for the embed author avatar\n");
            writer.write("\n");
            writer.write("webhook-url: \"https://discord.com/api/webhooks/ID/TOKEN\"\n");
            writer.write("server-name: \"My Cool Beta Server\"\n");
            writer.write("server-address: \"play.example.com:25565\"\n");
            writer.write("dynmap-url: \"https://map.example.com\"\n");
            writer.write("icon-url: \"\"\n");
        } catch (Exception e) {
            System.out.println("[StatusWebhook] Failed to write default config.yml:");
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
            System.out.println("[StatusWebhook] Failed to read config.yml:");
            e.printStackTrace();
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Exception ignored) {}
        }

        this.webhookUrl = safeGetString("webhook-url");

        this.serverName = safeGetString("server-name");
        if (this.serverName.length() == 0) {
            this.serverName = "Minecraft Server";
        }

        this.serverAddress = safeGetString("server-address");
        if (this.serverAddress.length() == 0) {
            this.serverAddress = "play.example.com:25565";
        }

        this.dynmapUrl = safeGetString("dynmap-url");

        this.iconUrl = safeGetString("icon-url");
        if (this.iconUrl.length() == 0) {
            this.iconUrl = DEFAULT_ICON_URL;
        }
    }

    private String safeGetString(String key) {
        String value = configValues.get(key);
        return value == null ? "" : value.trim();
    }

    // ============================================================
    // Command handling
    // ============================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("testwebhook")) {
            if (!sender.isOp()) {
                sender.sendMessage("You must be an operator to use this command.");
                return true;
            }

            Player[] players = Bukkit.getOnlinePlayers();
            int onlineCount = (players != null) ? players.length : 0;

            StringBuilder description = new StringBuilder();
            description.append("This is a test of the StatusWebhook plugin.\n\n");
            description.append("Online players right now: ").append(onlineCount).append("\n");
            description.append("Max players: ").append(Bukkit.getMaxPlayers()).append("\n");
            description.append("\n");
            description.append(serverAddress).append("\n");
            if (dynmapUrl.length() > 0) {
                description.append(dynmapUrl).append("\n");
            }

            try {
                postDiscordWebhook(description.toString());
                sender.sendMessage("Test webhook sent (if configured correctly).");
            } catch (IOException e) {
                sender.sendMessage("Failed to send test webhook. See console.");
                e.printStackTrace();
            }
            return true;
        }
        return false;
    }

    // ============================================================
    // Periodic status sending
    // ============================================================

    private void checkAndSendPeriodicStatus() throws IOException {
        if (webhookUrl == null || webhookUrl.trim().length() == 0 || webhookUrl.contains("REPLACE_ME")) {
            return;
        }

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));

        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int quarter = minute / 15;  // 0,1,2,3 for the 15-minute blocks

        if (minute % 15 != 0) {
            return;
        }
        if (hour == lastTriggerHour && quarter == lastTriggerQuarter) {
            return;
        }

        lastTriggerHour = hour;
        lastTriggerQuarter = quarter;

        int onlineCount = 0;
        Player[] players = Bukkit.getOnlinePlayers();
        if (players != null) {
            onlineCount = players.length;
        }

        int maxPlayers = Bukkit.getMaxPlayers();

        StringBuilder description = new StringBuilder();

        description.append("There are ")
                   .append(onlineCount)
                   .append("/")
                   .append(maxPlayers)
                   .append(" online.\n");

        description.append(serverAddress).append("\n");

        if (dynmapUrl.length() > 0) {
            description.append(dynmapUrl).append("\n");
        }

        description.append("\n");

        if (onlineCount > 0) {
            description.append("Online players:\n");
            for (int i = 0; i < players.length; i++) {
                Player p = players[i];
                if (p != null) {
                    description.append(" • ").append(p.getName()).append("\n");
                }
            }
        } else {
            description.append("No players are currently online.\n");
        }

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        fmt.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
        String timeString = fmt.format(cal.getTime());

        description.append("\nLast updated: ").append(timeString);

        postDiscordWebhook(description.toString());
    }

    // ============================================================
    // Webhook POST
    // ============================================================

    private void postDiscordWebhook(String descriptionText) throws IOException {
        if (webhookUrl == null || webhookUrl.trim().length() == 0 || webhookUrl.contains("REPLACE_ME")) {
            System.out.println("[StatusWebhook] webhook-url is not set. Cannot send webhook.");
            return;
        }

        try {
            String authorName = serverName;
            String escapedDescription = escapeJson(descriptionText);
            String escapedAuthorName = escapeJson(authorName);
            String escapedIconUrl = escapeJson(iconUrl);

            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{");

            String username = serverName;
            String escapedUsername = escapeJson(username);
            jsonBuilder.append("\"username\":\"").append(escapedUsername).append("\",");

            jsonBuilder.append("\"embeds\":[{");

            jsonBuilder.append("\"author\":{");
            jsonBuilder.append("\"name\":\"").append(escapedAuthorName).append("\",");
            jsonBuilder.append("\"icon_url\":\"").append(escapedIconUrl).append("\"");
            jsonBuilder.append("},");

            jsonBuilder.append("\"description\":\"").append(escapedDescription).append("\",");

            jsonBuilder.append("\"color\":").append(EMBED_COLOR);

            jsonBuilder.append("}]}");

            String payload = jsonBuilder.toString();

            URL url = new URL(webhookUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            OutputStream os = connection.getOutputStream();
            os.write(payload.getBytes("UTF-8"));
            os.flush();
            os.close();

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                System.out.println("[StatusWebhook] Webhook post returned non-success code: " + responseCode);
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(errorStream));
                    String line;
                    while ((line = br.readLine()) != null) {
                        System.out.println("[StatusWebhook] " + line);
                    }
                    br.close();
                }
            }

            connection.disconnect();
        } catch (IOException e) {
            System.out.println("[StatusWebhook] Exception sending webhook:");
            e.printStackTrace();
            throw e;
        }
    }

    // ============================================================
    // JSON escaping helper
    // ============================================================

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        String hex = Integer.toHexString(c);
                        out.append("\\u");
                        for (int j = hex.length(); j < 4; j++) {
                            out.append('0');
                        }
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
