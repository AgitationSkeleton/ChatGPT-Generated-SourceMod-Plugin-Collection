import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RetroQuery
 *
 * A lightweight query bridge for old Bukkit/Poseidon servers (e.g. Beta 1.7.3).
 * - Exposes a TCP text protocol ("QUERY", "QUERY_JSON") for simple checks.
 * - Emulates the GameSpy4 UDP query used by modern Minecraft servers so
 *   tools like GameTracker can read hostname/map/players/plugins/etc.
 */
public final class RetroQuery extends JavaPlugin {

    private static final String SERVER_PROPERTIES = "server.properties";
    private static final Charset ASCII = Charset.forName("UTF-8");

    private final Logger log = Logger.getLogger("Minecraft");

    private String serverIp;
    private int serverPort;
    private int queryPort;
    private int maxPlayers;

    private String serverName;
    private String serverMotd;

    private boolean onlineMode;
    private boolean whitelistEnabled;
    private boolean allowNether;
    private boolean pvpEnabled;

    private TcpQueryListener tcpListener;
    private UdpQueryListener udpListener;

    public RetroQuery() {
        try {
            Properties props = new Properties();
            props.load(new FileReader(SERVER_PROPERTIES));

            serverIp = props.getProperty("server-ip", "ANY");
            if (serverIp != null && serverIp.length() == 0) {
                serverIp = "ANY";
            }

            serverPort = parseInt(props.getProperty("server-port"), 25565);
            maxPlayers = parseInt(props.getProperty("max-players"), 32);

            // Prefer retroquery-port, fall back to minequery-port, then default.
            String portText = props.getProperty("retroquery-port",
                                props.getProperty("minequery-port", "25566"));
            queryPort = parseInt(portText, 25566);

            serverMotd = trim(props.getProperty("motd"));
            String nameFromProps = trim(props.getProperty("server-name"));

            if (nameFromProps != null) {
                serverName = nameFromProps;
            } else if (serverMotd != null) {
                serverName = serverMotd;
            } else {
                serverName = null; // resolved later via config
            }

            onlineMode = parseBoolean(props.getProperty("online-mode"), true);

            String whitelistRaw = props.getProperty("white-list",
                                    props.getProperty("whitelist", "false"));
            whitelistEnabled = parseBoolean(whitelistRaw, false);

            allowNether = parseBoolean(props.getProperty("allow-nether"), true);
            pvpEnabled  = parseBoolean(props.getProperty("pvp"), true);

        } catch (IOException ex) {
            log.log(Level.SEVERE, "RetroQuery: unable to read " + SERVER_PROPERTIES, ex);
        }
    }

    @Override
    public void onEnable() {
        resolveServerNameFromConfig();

        if (serverIp == null) {
            serverIp = "ANY";
        }

        try {
            tcpListener = new TcpQueryListener(this, serverIp, queryPort);
            tcpListener.start();
        } catch (IOException ex) {
            log.log(Level.SEVERE, "RetroQuery: failed to start TCP listener on port " + queryPort, ex);
        }

        try {
            udpListener = new UdpQueryListener(this, queryPort);
            udpListener.start();
        } catch (IOException ex) {
            log.log(Level.SEVERE, "RetroQuery: failed to start UDP listener on port " + queryPort, ex);
        }

        log.info("[RetroQuery] Enabled on TCP/UDP port " + queryPort);
    }

    @Override
    public void onDisable() {
        if (tcpListener != null) {
            try {
                tcpListener.shutdown();
            } catch (IOException ex) {
                log.log(Level.WARNING, "RetroQuery: error while closing TCP listener", ex);
            }
        }

        if (udpListener != null) {
            udpListener.shutdown();
        }

        log.info("[RetroQuery] Disabled");
    }

    // -----------------------
    // Configuration helpers
    // -----------------------

    private void resolveServerNameFromConfig() {
        String finalName = trim(serverName);

        if (finalName == null) {
            if (serverMotd != null && serverMotd.length() > 0) {
                finalName = serverMotd;
            } else {
                finalName = "Minecraft Server";
            }

            File folder = getDataFolder();
            if (!folder.exists()) {
                folder.mkdirs();
            }

            File configFile = new File(folder, "config.yml");

            String nameFromConfig = readNameFromConfig(configFile);
            if (nameFromConfig != null) {
                finalName = nameFromConfig;
            } else {
                writeDefaultConfig(configFile, finalName);
            }
        } else {
            File folder = getDataFolder();
            if (!folder.exists()) {
                folder.mkdirs();
            }

            File configFile = new File(folder, "config.yml");
            if (!configFile.exists()) {
                writeDefaultConfig(configFile, finalName);
            }
        }

        serverName = finalName;
    }

    private String readNameFromConfig(File file) {
        if (!file.exists()) {
            return null;
        }

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("server-name:")) {
                    int idx = line.indexOf(':');
                    if (idx >= 0 && idx + 1 < line.length()) {
                        String raw = line.substring(idx + 1).trim();
                        if ((raw.startsWith("\"") && raw.endsWith("\"")) ||
                            (raw.startsWith("'") && raw.endsWith("'"))) {
                            raw = raw.substring(1, raw.length() - 1);
                        }
                        String trimmed = trim(raw);
                        if (trimmed != null && trimmed.length() > 0) {
                            return trimmed;
                        }
                    }
                }
            }
        } catch (IOException ex) {
            log.log(Level.WARNING, "RetroQuery: unable to read config.yml", ex);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
        }
        return null;
    }

    private void writeDefaultConfig(File file, String name) {
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(new FileWriter(file));
            writer.println("# RetroQuery configuration");
            writer.println("# server-name is used as a fallback when neither");
            writer.println("# 'server-name' nor 'motd' is set in server.properties.");
            writer.println("server-name: " + quoteYaml(name));
        } catch (IOException ex) {
            log.log(Level.WARNING, "RetroQuery: unable to write config.yml", ex);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    // -----------------------
    // General helpers
    // -----------------------

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.length() == 0) ? null : t;
    }

    private static int parseInt(String value, int def) {
        if (value == null) return def;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static boolean parseBoolean(String value, boolean def) {
        if (value == null) return def;
        value = value.trim().toLowerCase();
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        return def;
    }

    private static String quoteYaml(String text) {
        if (text == null) {
            return "\"\"";
        }
        String cleaned = text.replace('\r', ' ')
                             .replace('\n', ' ')
                             .replace('\t', ' ');
        return "\"" + cleaned.replace("\"", "\\\"") + "\"";
    }

    private static String safeString(String s) {
        return (s == null) ? "" : s;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b");  break;
                case '\f': out.append("\\f");  break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
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

    private static String stripMinecraftColorCodes(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '§' && i + 1 < chars.length) {
                i++;
                continue;
            }
            sb.append(chars[i]);
        }
        return sb.toString();
    }

    // -----------------------
    // Accessors for inner classes
    // -----------------------

    int getServerPortNumber() {
        return serverPort;
    }

    int getQueryPort() {
        return queryPort;
    }

    int getMaxPlayerSlots() {
        return maxPlayers;
    }

    String getConfiguredServerName() {
        return serverName;
    }

    String getConfiguredMotd() {
        return serverMotd;
    }

    boolean isOnlineModeEnabled() {
        return onlineMode;
    }

    boolean isWhitelistEnabledFlag() {
        return whitelistEnabled;
    }

    boolean isNetherAllowed() {
        return allowNether;
    }

    boolean isPvpEnabledFlag() {
        return pvpEnabled;
    }

    String getConfiguredServerIp() {
        return serverIp;
    }

    // ============================================================
    // TCP QUERY LISTENER
    // ============================================================

    private static final class TcpQueryListener extends Thread {

        private final RetroQuery plugin;
        private final String host;
        private final int port;
        private final ServerSocket socket;
        private final Logger log = Logger.getLogger("Minecraft");
        private volatile boolean running = true;

        TcpQueryListener(RetroQuery plugin, String host, int port) throws IOException {
            this.plugin = plugin;
            this.host = host;
            this.port = port;

            InetSocketAddress bindAddress;
            if ("ANY".equalsIgnoreCase(host)) {
                bindAddress = new InetSocketAddress(port);
                log.info("[RetroQuery] Starting TCP listener on *:" + port);
            } else {
                bindAddress = new InetSocketAddress(host, port);
                log.info("[RetroQuery] Starting TCP listener on " + host + ":" + port);
            }

            socket = new ServerSocket();
            socket.bind(bindAddress);
        }

        public void shutdown() throws IOException {
            running = false;
            socket.close();
        }

        public void run() {
            try {
                while (running) {
                    Socket client = socket.accept();
                    new TcpClientHandler(plugin, client).start();
                }
            } catch (IOException ex) {
                if (running) {
                    log.log(Level.WARNING, "[RetroQuery] TCP listener stopped unexpectedly", ex);
                } else {
                    log.info("[RetroQuery] TCP listener closed");
                }
            }
        }
    }

    private static final class TcpClientHandler extends Thread {
        private final RetroQuery plugin;
        private final Socket client;
        private final Logger log = Logger.getLogger("Minecraft");

        TcpClientHandler(RetroQuery plugin, Socket client) {
            this.plugin = plugin;
            this.client = client;
        }

        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream()));
                String line = reader.readLine();
                if (line != null) {
                    handle(line);
                }
            } catch (IOException ex) {
                log.log(Level.WARNING, "[RetroQuery] TCP client handler error", ex);
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }

        private void handle(String command) throws IOException {
            if ("QUERY".equalsIgnoreCase(command)) {
                handleTextQuery();
            } else if ("QUERY_JSON".equalsIgnoreCase(command)) {
                handleJsonQuery();
            }
        }

        private void handleTextQuery() throws IOException {
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            int online = plugin.getServer().getOnlinePlayers().length;
            Player[] players = plugin.getServer().getOnlinePlayers();
            StringBuilder playerList = new StringBuilder();
            playerList.append("[");
            for (int i = 0; i < players.length; i++) {
                if (i > 0) playerList.append(", ");
                playerList.append(players[i].getName());
            }
            playerList.append("]");

            Plugin[] plugins = plugin.getServer().getPluginManager().getPlugins();
            StringBuilder pluginList = new StringBuilder();
            pluginList.append("[");
            for (int i = 0; i < plugins.length; i++) {
                if (i > 0) pluginList.append(", ");
                String name = plugins[i].getDescription().getName();
                String ver = plugins[i].getDescription().getVersion();
                if (ver != null && ver.length() > 0) {
                    pluginList.append(name).append(" v").append(ver);
                } else {
                    pluginList.append(name);
                }
            }
            pluginList.append("]");

            String worldName = "";
            if (!plugin.getServer().getWorlds().isEmpty()) {
                worldName = plugin.getServer().getWorlds().get(0).getName();
            }

            StringBuilder resp = new StringBuilder();
            resp.append("SERVERPORT ").append(plugin.getServerPortNumber()).append("\n");
            resp.append("PLAYERCOUNT ").append(online).append("\n");
            resp.append("MAXPLAYERS ").append(plugin.getMaxPlayerSlots()).append("\n");
            resp.append("PLAYERLIST ").append(playerList.toString()).append("\n");

            resp.append("SERVERNAME ").append(safeString(plugin.getConfiguredServerName())).append("\n");

            String motd = plugin.getConfiguredMotd();
            if (motd != null) {
                resp.append("MOTD ").append(motd.replace('\n', ' ')).append("\n");
            }

            resp.append("ONLINEMODE ").append(plugin.isOnlineModeEnabled()).append("\n");
            resp.append("WHITELIST ").append(plugin.isWhitelistEnabledFlag()).append("\n");
            resp.append("ALLOWNETHER ").append(plugin.isNetherAllowed()).append("\n");
            resp.append("PVP ").append(plugin.isPvpEnabledFlag()).append("\n");
            resp.append("WORLD ").append(safeString(worldName)).append("\n");
            resp.append("MAP ").append(safeString(worldName)).append("\n");
            resp.append("PLUGINS ").append(pluginList.toString()).append("\n");

            out.writeBytes(resp.toString());
        }

        private void handleJsonQuery() throws IOException {
            DataOutputStream out = new DataOutputStream(client.getOutputStream());

            Player[] players = plugin.getServer().getOnlinePlayers();
            int online = players.length;

            Plugin[] plugins = plugin.getServer().getPluginManager().getPlugins();

            String worldName = "";
            if (!plugin.getServer().getWorlds().isEmpty()) {
                worldName = plugin.getServer().getWorlds().get(0).getName();
            }

            StringBuilder resp = new StringBuilder();
            resp.append("{");
            resp.append("\"serverPort\":").append(plugin.getServerPortNumber()).append(",");
            resp.append("\"playerCount\":").append(online).append(",");
            resp.append("\"maxPlayers\":").append(plugin.getMaxPlayerSlots()).append(",");

            resp.append("\"serverName\":\"").append(escapeJson(plugin.getConfiguredServerName())).append("\",");
            resp.append("\"motd\":\"").append(escapeJson(plugin.getConfiguredMotd())).append("\",");
            resp.append("\"onlineMode\":").append(plugin.isOnlineModeEnabled()).append(",");
            resp.append("\"whitelist\":").append(plugin.isWhitelistEnabledFlag()).append(",");
            resp.append("\"allowNether\":").append(plugin.isNetherAllowed()).append(",");
            resp.append("\"pvp\":").append(plugin.isPvpEnabledFlag()).append(",");

            resp.append("\"worldName\":\"").append(escapeJson(worldName)).append("\",");
            resp.append("\"map\":\"").append(escapeJson(worldName)).append("\",");

            resp.append("\"minecraftVersion\":\"")
                .append(escapeJson(plugin.getServer().getVersion()))
                .append("\",");

            resp.append("\"playerList\":[");
            for (int i = 0; i < players.length; i++) {
                if (i > 0) resp.append(",");
                resp.append("\"").append(escapeJson(players[i].getName())).append("\"");
            }
            resp.append("]");

            resp.append(",\"plugins\":[");
            for (int i = 0; i < plugins.length; i++) {
                if (i > 0) resp.append(",");
                String name = plugins[i].getDescription().getName();
                String ver  = plugins[i].getDescription().getVersion();
                resp.append("{\"name\":\"").append(escapeJson(name)).append("\"");
                if (ver != null && ver.length() > 0) {
                    resp.append(",\"version\":\"").append(escapeJson(ver)).append("\"");
                }
                resp.append("}");
            }
            resp.append("]");

            resp.append("}\n");

            out.writeBytes(resp.toString());
        }
    }

    // ============================================================
    // UDP GAMESPY4 COMPATIBLE QUERY LISTENER
    // ============================================================

    private static final class UdpQueryListener extends Thread {

        private final RetroQuery plugin;
        private final int port;
        private final Logger log = Logger.getLogger("Minecraft");
        private final DatagramSocket socket;
        private final Random random = new Random();
        private volatile boolean running = true;

        UdpQueryListener(RetroQuery plugin, int port) throws IOException {
            this.plugin = plugin;
            this.port = port;
            this.socket = new DatagramSocket(port);
            log.info("[RetroQuery] Starting UDP query listener on *:" + port);
        }

        void shutdown() {
            running = false;
            try { socket.close(); } catch (Exception ignored) {}
        }

        public void run() {
            byte[] buf = new byte[2048];

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    int len = packet.getLength();
                    if (len < 7) {
                        continue;
                    }

                    byte[] data = new byte[len];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, len);

                    if (data[0] != (byte)0xFE || data[1] != (byte)0xFD) {
                        continue;
                    }

                    byte type = data[2];
                    int sessionId = ((data[3] & 0xFF) << 24)
                                  | ((data[4] & 0xFF) << 16)
                                  | ((data[5] & 0xFF) << 8)
                                  |  (data[6] & 0xFF);

                    if (type == 0x09) {
                        // Handshake: respond with token as ASCII
                        int token = random.nextInt(Integer.MAX_VALUE);
                        byte[] resp = buildHandshakeReply(sessionId, token);
                        DatagramPacket reply = new DatagramPacket(resp, resp.length,
                                packet.getAddress(), packet.getPort());
                        socket.send(reply);
                    } else if (type == 0x00) {
                        // Full stat request
                        byte[] resp = buildFullStatReply(plugin, sessionId);
                        DatagramPacket reply = new DatagramPacket(resp, resp.length,
                                packet.getAddress(), packet.getPort());
                        socket.send(reply);
                    }
                } catch (IOException ex) {
                    if (running) {
                        log.log(Level.WARNING, "[RetroQuery] UDP listener error", ex);
                    } else {
                        log.info("[RetroQuery] UDP listener closed");
                    }
                }
            }
        }

        private byte[] buildHandshakeReply(int sessionId, int token) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            out.writeByte(0x09);
            out.writeInt(sessionId);
            String tokenStr = Integer.toString(token);
            out.write(tokenStr.getBytes(ASCII));
            out.writeByte(0x00);

            out.flush();
            return baos.toByteArray();
        }

        private byte[] buildFullStatReply(RetroQuery plugin, int sessionId) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            // Header
            out.writeByte(0x00);
            out.writeInt(sessionId);

            // splitnum
            writeAscii(out, "splitnum");
            out.writeByte(0x00);
            out.writeByte(0x80);
            out.writeByte(0x00);

            String hostname = stripMinecraftColorCodes(safeString(plugin.getConfiguredServerName()));
            String gametype = "SMP";
            String gameId = "MINECRAFT";

            String version = plugin.getServer().getVersion();
            if (version == null || version.length() == 0) {
                version = "Beta 1.7.3";
            }

            Plugin[] pl = plugin.getServer().getPluginManager().getPlugins();
            StringBuilder pluginSummary = new StringBuilder();
            pluginSummary.append("CraftBukkit on Bukkit ").append(version).append(": ");
            for (int i = 0; i < pl.length; i++) {
                if (i > 0) pluginSummary.append("; ");
                String pName = pl[i].getDescription().getName();
                String pVer  = pl[i].getDescription().getVersion();
                pluginSummary.append(pName);
                if (pVer != null && pVer.length() > 0) {
                    pluginSummary.append(" ").append(pVer);
                }
            }

            String mapName = "world";
            if (!plugin.getServer().getWorlds().isEmpty()) {
                mapName = plugin.getServer().getWorlds().get(0).getName();
            }

            String numPlayers = Integer.toString(plugin.getServer().getOnlinePlayers().length);
            String maxPlayers = Integer.toString(plugin.getMaxPlayerSlots());
            String hostPort   = Integer.toString(plugin.getServerPortNumber());

            String hostIp = plugin.getConfiguredServerIp();
            if (hostIp == null || hostIp.length() == 0 || "ANY".equalsIgnoreCase(hostIp)) {
                hostIp = "127.0.0.1";
            }

            // hostname
            writeAscii(out, "hostname"); out.writeByte(0x00);
            writeAscii(out, hostname);    out.writeByte(0x00);

            // gametype
            writeAscii(out, "gametype"); out.writeByte(0x00);
            writeAscii(out, gametype);   out.writeByte(0x00);

            // game_id
            writeAscii(out, "game_id"); out.writeByte(0x00);
            writeAscii(out, gameId);   out.writeByte(0x00);

            // version
            writeAscii(out, "version"); out.writeByte(0x00);
            writeAscii(out, version);   out.writeByte(0x00);

            // plugins
            writeAscii(out, "plugins"); out.writeByte(0x00);
            writeAscii(out, pluginSummary.toString()); out.writeByte(0x00);

            // map
            writeAscii(out, "map"); out.writeByte(0x00);
            writeAscii(out, mapName); out.writeByte(0x00);

            // numplayers
            writeAscii(out, "numplayers"); out.writeByte(0x00);
            writeAscii(out, numPlayers);   out.writeByte(0x00);

            // maxplayers
            writeAscii(out, "maxplayers"); out.writeByte(0x00);
            writeAscii(out, maxPlayers);   out.writeByte(0x00);

            // hostport
            writeAscii(out, "hostport"); out.writeByte(0x00);
            writeAscii(out, hostPort);   out.writeByte(0x00);

            // hostip
            writeAscii(out, "hostip"); out.writeByte(0x00);
            writeAscii(out, hostIp);   out.writeByte(0x00);

            // end of key/value section
            out.writeByte(0x00);

            // player section
            out.writeByte(0x01);
            writeAscii(out, "player_");
            out.writeByte(0x00);

            Player[] players = plugin.getServer().getOnlinePlayers();
            for (int i = 0; i < players.length; i++) {
                writeAscii(out, players[i].getName());
                out.writeByte(0x00);
            }

            // two nulls at end, matching modern servers for empty list case
            out.writeByte(0x00);
            out.writeByte(0x00);

            out.flush();
            return baos.toByteArray();
        }

        private static void writeAscii(DataOutputStream out, String s) throws IOException {
            if (s == null) return;
            out.write(s.getBytes(ASCII));
        }
    }
}