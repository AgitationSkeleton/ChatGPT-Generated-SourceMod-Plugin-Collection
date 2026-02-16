import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class BetaMultiverseWorldLoader extends JavaPlugin {

    private static class WorldSpec {
        String worldName;
        Environment environment;
        String generatorNameOrPlugin; // null if none

        WorldSpec(String worldName, Environment environment, String generatorNameOrPlugin) {
            this.worldName = worldName;
            this.environment = environment;
            this.generatorNameOrPlugin = generatorNameOrPlugin;
        }
    }

    @Override
    public void onEnable() {
        log("Enabling. Enforcing load + generator for existing worlds (unloads/reloads if needed).");

        List<WorldSpec> specs = new ArrayList<WorldSpec>();
        specs.add(new WorldSpec("thebetawasbetter", Environment.NORMAL, null));
        specs.add(new WorldSpec("thebetawasbetter_nether", Environment.NETHER, null));
        specs.add(new WorldSpec("skylands", Environment.NORMAL, null)); // generator enforcement not requested here
        specs.add(new WorldSpec("zombie", Environment.NORMAL, null));
        specs.add(new WorldSpec("redchanit1", Environment.NORMAL, null));
        specs.add(new WorldSpec("redchanit2", Environment.NORMAL, null));
        specs.add(new WorldSpec("ZombieSiegeWorld", Environment.NORMAL, "ZombieSiegeGen"));
        specs.add(new WorldSpec("Redmurk", Environment.NORMAL, null));
        specs.add(new WorldSpec("myskyblock", Environment.NORMAL, "MySkyBlock"));
        specs.add(new WorldSpec("IndevHell", Environment.NETHER, "IndevHellGen"));

        Plugin multiverse = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        boolean hasMultiverse = multiverse != null && multiverse.isEnabled();

        int ok = 0;
        int skipMissing = 0;
        int fail = 0;

        for (int i = 0; i < specs.size(); i++) {
            WorldSpec spec = specs.get(i);

            if (!worldFolderExists(spec.worldName)) {
                log("World folder missing on disk (skipping, will NOT create): " + spec.worldName);
                skipMissing++;
                continue;
            }

            boolean success = ensureWorldLoadedWithGenerator(spec, hasMultiverse);
            if (success) ok++; else fail++;
        }

        log("Done. ok=" + ok + " missingFolders=" + skipMissing + " failed=" + fail);
    }

    @Override
    public void onDisable() {
        log("Disabled.");
    }

    private boolean ensureWorldLoadedWithGenerator(WorldSpec spec, boolean hasMultiverse) {
        World existing = Bukkit.getWorld(spec.worldName);

        // If generator is required and world is already loaded, unload first so we can actually enforce.
        if (existing != null && spec.generatorNameOrPlugin != null && spec.generatorNameOrPlugin.length() > 0) {
            log("World is already loaded but generator must be enforced; unloading: " + spec.worldName);
            boolean unloaded = tryUnloadWorld(spec.worldName, true);
            if (!unloaded) {
                log("FAILED to unload world (cannot enforce generator while loaded): " + spec.worldName);
                return false;
            }
        } else if (existing != null) {
            log("Already loaded (no generator enforcement needed): " + spec.worldName);
            return true;
        }

        // If we got here, it’s not loaded (or was unloaded). Load it, enforcing generator if requested.
        ChunkGenerator genObj = null;
        if (spec.generatorNameOrPlugin != null && spec.generatorNameOrPlugin.length() > 0) {
            genObj = resolveGenerator(spec.worldName, spec.generatorNameOrPlugin);
            if (genObj == null) {
                log("Generator '" + spec.generatorNameOrPlugin + "' could not be resolved as a ChunkGenerator object.");
            }
        }

        // Attempt to load via server methods that accept generator objects/names.
        boolean loaded = false;

        if (genObj != null) {
            loaded = loadWorldViaServerWithGeneratorObject(spec.worldName, spec.environment, genObj);
            if (!loaded) {
                log("Server did not expose a compatible createWorld/loadWorld signature with ChunkGenerator for: " + spec.worldName);
            }
        }

        // If still not loaded, try generator by string (some forks accept it)
        if (!loaded && spec.generatorNameOrPlugin != null && spec.generatorNameOrPlugin.length() > 0) {
            loaded = loadWorldViaServerWithGeneratorString(spec.worldName, spec.environment, spec.generatorNameOrPlugin);
        }

        // If still not loaded, fall back to loading without generator only if none required.
        if (!loaded && (spec.generatorNameOrPlugin == null || spec.generatorNameOrPlugin.length() == 0)) {
            loaded = loadWorldLegacyNoGenerator(spec.worldName, spec.environment);
        }

        // If generator is required and above failed, try Multiverse command fallback
        if (!loaded && spec.generatorNameOrPlugin != null && spec.generatorNameOrPlugin.length() > 0 && hasMultiverse) {
            loaded = tryMultiverseImportLoad(spec.worldName, spec.environment, spec.generatorNameOrPlugin);
        }

        if (!loaded) {
            log("FAILED to load world with enforcement: " + spec.worldName);
            return false;
        }

        // Confirm it is actually loaded
        World now = Bukkit.getWorld(spec.worldName);
        if (now == null) {
            log("Load attempt returned success but Bukkit.getWorld is still null: " + spec.worldName);
            return false;
        }

        log("Loaded: " + spec.worldName + (spec.generatorNameOrPlugin != null ? (" (generator enforced: " + spec.generatorNameOrPlugin + ")") : ""));
        return true;
    }

    private boolean worldFolderExists(String worldName) {
        File worldDir = new File(worldName);
        return worldDir.exists() && worldDir.isDirectory();
    }

    private boolean tryUnloadWorld(String worldName, boolean save) {
        try {
            World w = Bukkit.getWorld(worldName);
            if (w == null) return true;

            Object server = Bukkit.getServer();
            if (server == null) return false;

            // Try unloadWorld(World, boolean)
            try {
                Method m1 = server.getClass().getMethod("unloadWorld", World.class, boolean.class);
                Object res = m1.invoke(server, w, save);
                return (res instanceof Boolean) ? ((Boolean) res).booleanValue() : true;
            } catch (Throwable ignored) { }

            // Try unloadWorld(String, boolean)
            try {
                Method m2 = server.getClass().getMethod("unloadWorld", String.class, boolean.class);
                Object res = m2.invoke(server, worldName, save);
                return (res instanceof Boolean) ? ((Boolean) res).booleanValue() : true;
            } catch (Throwable ignored) { }

            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private ChunkGenerator resolveGenerator(String worldName, String generatorNameOrPlugin) {
        PluginManager pm = Bukkit.getPluginManager();
        if (pm == null) return null;

        Plugin p = pm.getPlugin(generatorNameOrPlugin);
        if (p == null) {
            // Not a plugin name. Some systems use "PluginName:GenId" or a bare string; we handle string fallback elsewhere.
            return null;
        }

        // Use reflection for getDefaultWorldGenerator(String, String) since old Plugin interface may differ.
        try {
            Method m = p.getClass().getMethod("getDefaultWorldGenerator", String.class, String.class);
            Object obj = m.invoke(p, worldName, "");
            if (obj instanceof ChunkGenerator) {
                return (ChunkGenerator) obj;
            }
        } catch (Throwable ignored) { }

        return null;
    }

    private boolean loadWorldViaServerWithGeneratorObject(String worldName, Environment env, ChunkGenerator genObj) {
        Object server = Bukkit.getServer();
        if (server == null) return false;

        // Try common signatures:
        // createWorld(String, Environment, ChunkGenerator)
        // createWorld(String, Environment, long, ChunkGenerator)
        // loadWorld(String, Environment, ChunkGenerator)
        // loadWorld(String, Environment, long, ChunkGenerator)

        try {
            Method m1 = server.getClass().getMethod("createWorld", String.class, Environment.class, ChunkGenerator.class);
            Object res = m1.invoke(server, worldName, env, genObj);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m2 = server.getClass().getMethod("createWorld", String.class, Environment.class, long.class, ChunkGenerator.class);
            Object res = m2.invoke(server, worldName, env, 0L, genObj);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m3 = server.getClass().getMethod("loadWorld", String.class, Environment.class, ChunkGenerator.class);
            Object res = m3.invoke(server, worldName, env, genObj);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m4 = server.getClass().getMethod("loadWorld", String.class, Environment.class, long.class, ChunkGenerator.class);
            Object res = m4.invoke(server, worldName, env, 0L, genObj);
            return res != null;
        } catch (Throwable ignored) { }

        return false;
    }

    private boolean loadWorldViaServerWithGeneratorString(String worldName, Environment env, String generatorString) {
        Object server = Bukkit.getServer();
        if (server == null) return false;

        // Some forks accept a generator as a String:
        // createWorld(String, Environment, String)
        // createWorld(String, Environment, long, String)
        // loadWorld(String, Environment, String)
        // loadWorld(String, Environment, long, String)

        try {
            Method m1 = server.getClass().getMethod("createWorld", String.class, Environment.class, String.class);
            Object res = m1.invoke(server, worldName, env, generatorString);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m2 = server.getClass().getMethod("createWorld", String.class, Environment.class, long.class, String.class);
            Object res = m2.invoke(server, worldName, env, 0L, generatorString);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m3 = server.getClass().getMethod("loadWorld", String.class, Environment.class, String.class);
            Object res = m3.invoke(server, worldName, env, generatorString);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m4 = server.getClass().getMethod("loadWorld", String.class, Environment.class, long.class, String.class);
            Object res = m4.invoke(server, worldName, env, 0L, generatorString);
            return res != null;
        } catch (Throwable ignored) { }

        return false;
    }

    private boolean loadWorldLegacyNoGenerator(String worldName, Environment env) {
        Object server = Bukkit.getServer();
        if (server == null) return false;

        try {
            Method m1 = server.getClass().getMethod("createWorld", String.class);
            Object res = m1.invoke(server, worldName);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m2 = server.getClass().getMethod("createWorld", String.class, Environment.class);
            Object res = m2.invoke(server, worldName, env);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m3 = server.getClass().getMethod("loadWorld", String.class);
            Object res = m3.invoke(server, worldName);
            return res != null;
        } catch (Throwable ignored) { }

        try {
            Method m4 = server.getClass().getMethod("loadWorld", String.class, Environment.class);
            Object res = m4.invoke(server, worldName, env);
            return res != null;
        } catch (Throwable ignored) { }

        return false;
    }

    private boolean tryMultiverseImportLoad(String worldName, Environment env, String generatorName) {
        Object sender = getConsoleSenderBestEffort();
        if (sender == null) {
            log("Multiverse fallback needed, but no console sender available on this server build.");
            return false;
        }

        String envToken = (env == Environment.NETHER) ? "nether" : "normal";

        // Try "mv load" first (some builds have it)
        dispatchCommand(sender, "mv load " + worldName);

        // Then try import with generator. Import should register an existing folder.
        // Common syntax: mv import <world> <env> -g <generator>
        dispatchCommand(sender, "mv import " + worldName + " " + envToken + " -g " + generatorName);

        // If MV uses "create" but you already have folders, import is the right one; still,
        // some forks ignore -g on import, so also attempt create as a last-ditch (may fail safely).
        dispatchCommand(sender, "mv create " + worldName + " " + envToken + " -g " + generatorName);

        // We can only judge success by whether Bukkit now sees the world.
        return Bukkit.getWorld(worldName) != null;
    }

    private Object getConsoleSenderBestEffort() {
        Object server = Bukkit.getServer();
        if (server == null) return null;

        // Try getConsoleSender()
        try {
            Method m = server.getClass().getMethod("getConsoleSender");
            return m.invoke(server);
        } catch (Throwable ignored) { }

        // Some very old builds might allow the Server itself as a sender for dispatchCommand
        return server;
    }

    private void dispatchCommand(Object sender, String command) {
        try {
            Method m = Bukkit.class.getMethod("dispatchCommand", Class.forName("org.bukkit.command.CommandSender"), String.class);
            m.invoke(null, sender, command);
            log("Dispatched: " + command);
        } catch (Throwable t) {
            // If dispatchCommand signature differs, just log it.
            log("Could not dispatch command on this server build: " + command);
        }
    }

    private void log(String msg) {
        System.out.println("[BetaMultiverseWorldLoader] " + msg);
    }
}
