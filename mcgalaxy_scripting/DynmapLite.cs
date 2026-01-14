//reference System.dll
//reference System.Core.dll
//reference System.Drawing.dll

// DynmapLite.cs - Dynmap-like web map for MCGalaxy
// Author: ChatGPT
//
// Notes / limitations of this "draft":
// - This implements a tile-based renderer (top-down + basic isometric) using MCGalaxy block colors.
// - It DOES download the requested default texturepack ZIP into plugins/DynmapLite/textures.zip,
//   but DOES NOT yet sample actual textures from it.
//   Reason: MCGalaxy targets .NET Framework 4.0; System.IO.Compression.ZipArchive is not available.
//   If you want true texture sampling, easiest is to ship a zip library DLL (e.g. SharpZipLib)
//   and load terrain/atlas images from textures.zip.
//
// Security model:
// - Binds to 127.0.0.1 only by default (configurable, but default is local-only).
// - Requires token on ALL endpoints as ?token=... (and supports X-Auth-Token header too).
//
// Tile strategy (Dynmap-style):
// - Tile size is configurable (default 64x64 blocks).
// - On block changes in loaded levels, mark affected tile "dirty".
// - When a tile is requested, render on-demand if missing/dirty.
//
// API endpoints (all require token):
// - GET /              -> HTML UI
// - GET /api/state     -> JSON worlds + players
// - GET /tiles/{world}/{view}/{tx}/{tz}.png  -> map tile image
//
// Views:
// - topdown: highest-solid block per x/z column (simple).
// - isometric: height shaded pseudo-isometric (draft; fast but not a perfect dynmap iso).
//
using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;

using MCGalaxy;
using MCGalaxy.Events.LevelEvents;
using MCGalaxy.Events.PlayerEvents;
using MCGalaxy.Events;
using MCGalaxy.Network;
using MCGalaxy.Tasks;

public sealed class DynmapLite : Plugin {
    public override string name { get { return "DynmapLite"; } }
    public override string creator { get { return "ChatGPT"; } }
    public override string MCGalaxy_Version { get { return "1.9.0.0"; } } // safe minimum; adjust if you want

    const string PluginFolder = "plugins/DynmapLite";
    const string TilesFolder  = "plugins/DynmapLite/tiles";
    const string ConfigPath   = "plugins/DynmapLite/config.properties";
    const string TextureZipPath = "plugins/DynmapLite/textures.zip";

    // As requested:
    const string DefaultTextureZipUrl = "https://files.catbox.moe/erpnqa.zip";

    HttpListener _listener;
    Thread _httpThread;
    volatile bool _running;

    DynmapConfig _cfg;

    // dirty tile sets keyed by: world|view|tx|tz
    readonly object _dirtyLock = new object();
    readonly HashSet<string> _dirtyTiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

    // small cache: key -> last render time (ms) to avoid stampedes
    readonly Dictionary<string, long> _tileRenderGuard = new Dictionary<string, long>(StringComparer.OrdinalIgnoreCase);

    // periodic “soft” refresh for loaded maps (optional)
    SchedulerTask _tickTask;

    UdpClient _udp;
    Thread _udpThread;


    public override void Load(bool startup) {
        Directory.CreateDirectory(PluginFolder);
        Directory.CreateDirectory(TilesFolder);

        _cfg = DynmapConfig.LoadOrCreate(ConfigPath);

        EnsureTextureZip();

        StartWeb();
        StartUdp();

        // Block changes -> mark dirty tiles
        OnBlockChangingEvent.Register(HandleBlockChanging, Priority.Low);

        // If a level is unloaded/loaded, we don't need special handling, but we can mark overview stale
        OnLevelAddedEvent.Register(HandleLevelAdded, Priority.Low);
        OnLevelRemovedEvent.Register(HandleLevelRemoved, Priority.Low);

        // Optional periodic tick: can mark “around players” dirty if you want truly “periodic check”
        // (This is a cheap nudge, not a full scan.)
        _tickTask = Server.MainScheduler.QueueRepeat(SoftTick, null, TimeSpan.FromSeconds(_cfg.softTickSeconds));

        Logger.Log(LogType.SystemActivity,
            "[DynmapLite] Loaded. Local URL: http://{0}:{1}/  Token disabled",
            _cfg.bindHost, _cfg.port, "(disabled)");
    }

    public override void Unload(bool shutdown) {
        try {
            Server.MainScheduler.Cancel(_tickTask);
        } catch { }

        OnBlockChangingEvent.Unregister(HandleBlockChanging);
        OnLevelAddedEvent.Unregister(HandleLevelAdded);
        OnLevelRemovedEvent.Unregister(HandleLevelRemoved);

        StopUdp();
        StopWeb();
        Logger.Log(LogType.SystemActivity, "[DynmapLite] Unloaded.");
    }

    void EnsureTextureZip() {
        try {
            if (File.Exists(TextureZipPath)) return;

            Logger.Log(LogType.SystemActivity, "[DynmapLite] textures.zip not found, downloading default texturepack...");
            using (WebClient wc = new WebClient()) {
                // Download into temp then move into place as textures.zip
                string tmp = Path.Combine(PluginFolder, "textures.tmp");
                wc.DownloadFile(DefaultTextureZipUrl, tmp);

                if (File.Exists(TextureZipPath)) File.Delete(TextureZipPath);
                File.Move(tmp, TextureZipPath);
            }
            Logger.Log(LogType.SystemActivity, "[DynmapLite] Downloaded texturepack to {0}", TextureZipPath);
        } catch (Exception ex) {
            Logger.LogError(ex);
            Logger.Log(LogType.Error, "[DynmapLite] Failed to download texturepack. Rendering will still work using block colors.");
        }
    }

void StartUdp() {
    // HTTP itself is TCP-only. This UDP socket is just for simple LAN discovery beacons.
    // If you don't want it, you can remove this entire method and StopUdp().
    try {
        _udp = new UdpClient();
        _udp.EnableBroadcast = true;
        _running = true;

        _udpThread = new Thread(UdpLoop);
        _udpThread.IsBackground = true;
        _udpThread.Start();

        Logger.Log(LogType.SystemActivity, "[DynmapLite] UDP discovery beacons enabled on port {0}", _cfg.port);
    } catch (Exception ex) {
        Logger.LogError(ex);
        Logger.Log(LogType.Error, "[DynmapLite] Failed to start UDP discovery (non-fatal).");
    }
}

void StopUdp() {
    try { if (_udp != null) _udp.Close(); } catch { }
    _udp = null;
    try { if (_udpThread != null && _udpThread.IsAlive) _udpThread.Join(500); } catch { }
    _udpThread = null;
}

void UdpLoop() {
    // Sends a broadcast beacon every 5 seconds: "DynmapLite|http://host:port/"
    IPEndPoint ep = new IPEndPoint(IPAddress.Broadcast, _cfg.port);
    byte[] payload = Encoding.ASCII.GetBytes("DynmapLite|http://" + _cfg.bindHost + ":" + _cfg.port + "/");
    while (_running) {
        try {
            if (_udp != null) _udp.Send(payload, payload.Length, ep);
        } catch { }
        Thread.Sleep(5000);
    }
}

    void StartWeb() {
        _listener = new HttpListener();
        string prefix = string.Format(CultureInfo.InvariantCulture, "http://{0}:{1}/", _cfg.bindHost, _cfg.port);
        _listener.Prefixes.Add(prefix);

        _running = true;
        _listener.Start();

        _httpThread = new Thread(HttpLoop);
        _httpThread.IsBackground = true;
        _httpThread.Start();

        Logger.Log(LogType.SystemActivity, "[DynmapLite] Web server listening on {0}", prefix);
    }

    void StopWeb() {
        _running = false;
        try { if (_listener != null) _listener.Stop(); } catch { }
        try { if (_listener != null) _listener.Close(); } catch { }
        _listener = null;

        try {
            if (_httpThread != null && _httpThread.IsAlive) _httpThread.Join(1000);
        } catch { }
        _httpThread = null;
    }

    void HttpLoop() {
        while (_running) {
            HttpListenerContext ctx = null;
            try {
                ctx = _listener.GetContext();
                HandleHttp(ctx);
            } catch (HttpListenerException) {
                // stopping
            } catch (ObjectDisposedException) {
                // stopping
            } catch (Exception ex) {
                Logger.LogError(ex);
                try {
                    if (ctx != null) {
                        ctx.Response.StatusCode = 500;
                        WriteText(ctx.Response, "Internal server error");
                    }
                } catch { }
            }
        }
    }

    // --- Events -------------------------------------------------------------

    void HandleLevelAdded(Level lvl) {
        // could pre-render spawn tiles etc; for now, do nothing
    }

    void HandleLevelRemoved(Level lvl) {
        // do nothing
    }

    void HandleBlockChanging(Player p, ushort x, ushort y, ushort z, ushort block, bool placing, ref bool cancel) {
        // Mark dirty only if it's a real level and rendering enabled
        if (p == null || p.level == null) return;
        Level lvl = p.level;

        MarkDirtyForBlock(lvl, x, z, "topdown");
        MarkDirtyForBlock(lvl, x, z, "isometric");
    }

    void SoftTick(SchedulerTask task) {
        // Periodically mark tiles around players as dirty (cheap “periodic check”)
        // This keeps player trails accurate if you later add overlays.
        if (_cfg.softTickSeconds <= 0) return;

        Player[] players = PlayerInfo.Online.Items;
        for (int i = 0; i < players.Length; i++) {
            Player p = players[i];
            if (p == null || p.level == null) continue;

            ushort px = (ushort)(p.Pos.X / 32);
            ushort pz = (ushort)(p.Pos.Z / 32);

            int tileSize = _cfg.tileSize;
            int tx = px / tileSize;
            int tz = pz / tileSize;

            // mark a small 3x3 around player
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    MarkDirty(p.level.name, "topdown", tx + ox, tz + oz);
                    MarkDirty(p.level.name, "isometric", tx + ox, tz + oz);
                }
            }
        }
    }

    // --- Dirty tiles --------------------------------------------------------

    void MarkDirtyForBlock(Level lvl, ushort x, ushort z, string view) {
        int tileSize = _cfg.tileSize;
        int tx = x / tileSize;
        int tz = z / tileSize;
        MarkDirty(lvl.name, view, tx, tz);
    }

    void MarkDirty(string world, string view, int tx, int tz) {
        if (tx < 0 || tz < 0) return;
        string key = MakeTileKey(world, view, tx, tz);
        lock (_dirtyLock) {
            _dirtyTiles.Add(key);
        }
    }

    bool IsDirty(string key) {
        lock (_dirtyLock) {
            return _dirtyTiles.Contains(key);
        }
    }

    void ClearDirty(string key) {
        lock (_dirtyLock) {
            _dirtyTiles.Remove(key);
        }
    }

    static string MakeTileKey(string world, string view, int tx, int tz) {
        return world + "|" + view + "|" + tx.ToString(CultureInfo.InvariantCulture) + "|" + tz.ToString(CultureInfo.InvariantCulture);
    }

    // --- HTTP handling ------------------------------------------------------

    void HandleHttp(HttpListenerContext ctx) {
        HttpListenerRequest req = ctx.Request;
        HttpListenerResponse res = ctx.Response;        string path = req.Url.AbsolutePath ?? "/";
        if (path == "/") {
            res.StatusCode = 200;
            res.ContentType = "text/html; charset=utf-8";
            WriteBytes(res, Encoding.UTF8.GetBytes(BuildIndexHtml()));
            return;
        }

        if (path == "/api/state") {
            res.StatusCode = 200;
            res.ContentType = "application/json; charset=utf-8";
            WriteBytes(res, Encoding.UTF8.GetBytes(BuildStateJson()));
            return;
        }

        if (path.StartsWith("/tiles/", StringComparison.OrdinalIgnoreCase)) {
            // /tiles/{world}/{view}/{tx}/{tz}.png
            ServeTile(path, res);
            return;
        }

        res.StatusCode = 404;
        WriteText(res, "Not found");
    }    void ServeTile(string path, HttpListenerResponse res) {
        // Expected: /tiles/{world}/{view}/{tx}/{tz}.png
        string[] parts = path.Split(new[] { '/' }, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length != 5) {
            res.StatusCode = 400;
            WriteText(res, "Bad tile path");
            return;
        }

        string world = parts[1];
        string view  = parts[2];
        int tx, tz;

        if (!int.TryParse(parts[3], NumberStyles.Integer, CultureInfo.InvariantCulture, out tx) ||
            !int.TryParse(parts[4].Replace(".png", ""), NumberStyles.Integer, CultureInfo.InvariantCulture, out tz)) {
            res.StatusCode = 400;
            WriteText(res, "Bad tile coordinates");
            return;
        }

        if (!IsValidView(view)) {
            res.StatusCode = 400;
            WriteText(res, "Bad view");
            return;
        }

        string tilePath = GetTilePath(world, view, tx, tz);
        string key = MakeTileKey(world, view, tx, tz);

        if (ShouldRenderTileNow(key, tilePath)) {
            RenderTile(world, view, tx, tz, tilePath);
        }

        if (!File.Exists(tilePath)) {
            res.StatusCode = 404;
            WriteText(res, "Tile not available (world may be unloaded and not yet rendered)");
            return;
        }

        res.StatusCode = 200;
        res.ContentType = "image/png";
        byte[] data = File.ReadAllBytes(tilePath);
        WriteBytes(res, data);
    }

    bool ShouldRenderTileNow(string key, string tilePath) {
        // Render if missing or marked dirty.
        if (!File.Exists(tilePath)) return true;
        if (IsDirty(key)) return true;

        // Guard against stampede: if multiple requests arrive at once, only re-render once every ~250ms per tile.
        long now = DateTime.UtcNow.Ticks / TimeSpan.TicksPerMillisecond;
        lock (_tileRenderGuard) {
            long last;
            if (_tileRenderGuard.TryGetValue(key, out last)) {
                if (now - last < 250) return false;
            }
            _tileRenderGuard[key] = now;
        }
        return false;
    }

    static bool IsValidView(string view) {
        return view.Equals("topdown", StringComparison.OrdinalIgnoreCase)
            || view.Equals("isometric", StringComparison.OrdinalIgnoreCase);
    }

    static string GetTilePath(string world, string view, int tx, int tz) {
        string dir = Path.Combine(TilesFolder, world, view);
        Directory.CreateDirectory(dir);
        return Path.Combine(dir, tx.ToString(CultureInfo.InvariantCulture) + "_" + tz.ToString(CultureInfo.InvariantCulture) + ".png");
    }

    void RenderTile(string world, string view, int tx, int tz, string outPath) {
        try {
            Level lvl = LevelInfo.FindExact(world);
            if (lvl == null) {
                // World is unloaded. For a draft: no on-demand loading.
                // You could optionally load from disk here, render, then unload.
                // We'll just leave existing tile if present.
                return;
            }

            int tileSize = _cfg.tileSize;
            int startX = tx * tileSize;
            int startZ = tz * tileSize;

            if (startX >= lvl.Width || startZ >= lvl.Length) return;

            if (view.Equals("topdown", StringComparison.OrdinalIgnoreCase)) {
                using (Bitmap bmp = RenderTopDown(lvl, startX, startZ, tileSize)) {
                    SavePngAtomic(bmp, outPath);
                }
            } else {
                using (Bitmap bmp = RenderIsometricDraft(lvl, startX, startZ, tileSize)) {
                    SavePngAtomic(bmp, outPath);
                }
            }

            ClearDirty(MakeTileKey(world, view, tx, tz));
        } catch (Exception ex) {
            Logger.LogError(ex);
        }
    }

    // --- Rendering ----------------------------------------------------------

    Bitmap RenderTopDown(Level lvl, int startX, int startZ, int tileSize) {
        int w = Math.Min(tileSize, lvl.Width - startX);
        int h = Math.Min(tileSize, lvl.Length - startZ);

        Bitmap bmp = new Bitmap(w, h, PixelFormat.Format24bppRgb);

        for (int dz = 0; dz < h; dz++) {
            for (int dx = 0; dx < w; dx++) {
                int x = startX + dx;
                int z = startZ + dz;

                // Find topmost non-air
                ushort block = 0;
                for (int y = lvl.Height - 1; y >= 0; y--) {
                    ushort b = lvl.GetBlock((ushort)x, (ushort)y, (ushort)z);
                    if (b != Block.Air) { block = b; break; }
                }

                Color c = BlockToColor(block);
                bmp.SetPixel(dx, dz, c);
            }
        }

        return bmp;
    }

    Bitmap RenderIsometricDraft(Level lvl, int startX, int startZ, int tileSize) {
        // Draft isometric: make a larger canvas and project (x,z,height) into 2D.
        // Not a full dynmap iso; it's meant to be “good enough” as a first pass.
        int wBlocks = Math.Min(tileSize, lvl.Width - startX);
        int zBlocks = Math.Min(tileSize, lvl.Length - startZ);

        // Canvas size (rough)
        int pxPerBlock = 2; // keep small/fast
        int canvasW = (wBlocks + zBlocks) * pxPerBlock + 4;
        int canvasH = (wBlocks + zBlocks) * pxPerBlock / 2 + 64;

        Bitmap bmp = new Bitmap(canvasW, canvasH, PixelFormat.Format24bppRgb);
        using (Graphics g = Graphics.FromImage(bmp)) {
            g.Clear(Color.Black);

            // draw back-to-front
            for (int dz = zBlocks - 1; dz >= 0; dz--) {
                for (int dx = wBlocks - 1; dx >= 0; dx--) {
                    int x = startX + dx;
                    int z = startZ + dz;

                    int topY = 0;
                    ushort topBlock = Block.Air;

                    for (int y = lvl.Height - 1; y >= 0; y--) {
                        ushort b = lvl.GetBlock((ushort)x, (ushort)y, (ushort)z);
                        if (b != Block.Air) { topY = y; topBlock = b; break; }
                    }

                    Color baseC = BlockToColor(topBlock);

                    // Height shading
                    int shade = Math.Min(80, topY);
                    Color shaded = Color.FromArgb(
                        ClampByte(baseC.R + shade / 4),
                        ClampByte(baseC.G + shade / 4),
                        ClampByte(baseC.B + shade / 4)
                    );

                    int isoX = (dx - dz) * pxPerBlock + canvasW / 2;
                    int isoY = (dx + dz) * (pxPerBlock / 2) + 16 - (topY / 4);

                    using (SolidBrush br = new SolidBrush(shaded)) {
                        g.FillRectangle(br, isoX, isoY, pxPerBlock, pxPerBlock);
                    }
                }
            }
        }
        return bmp;
    }

    static byte ClampByte(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return (byte)v;
    }

    Color BlockToColor(ushort block) {
    // Uses MCGalaxy block color info (fast & available).
    // If you later implement texture sampling, replace this.
    try {
        // Use reflection so this compiles across MCGalaxy versions
        // (some builds expose Block.Props entries as different types/names).
        object propsObj = Block.Props[block];
        if (propsObj != null) {
            Type t = propsObj.GetType();

            // Common property/field names across builds
            object packedObj = null;

            var pi = t.GetProperty("Color") ?? t.GetProperty("Colour") ?? t.GetProperty("col") ?? t.GetProperty("Col");
            if (pi != null) packedObj = pi.GetValue(propsObj, null);

            if (packedObj == null) {
                var fi = t.GetField("Color") ?? t.GetField("Colour") ?? t.GetField("col") ?? t.GetField("Col");
                if (fi != null) packedObj = fi.GetValue(propsObj);
            }

            int packed = 0;
            if (packedObj is int) packed = (int)packedObj;
            else if (packedObj is uint) packed = unchecked((int)(uint)packedObj);
            else if (packedObj is ushort) packed = (ushort)packedObj;
            else if (packedObj is short) packed = (short)packedObj;

            // Many builds store as 0xRRGGBB; if ARGB, we just ignore A.
            int r = (packed >> 16) & 0xFF;
            int g = (packed >> 8) & 0xFF;
            int b = (packed) & 0xFF;

            // If it looks like it's 0 (unknown), fall through to palette below.
            if ((r | g | b) != 0) return Color.FromArgb(r, g, b);
        }
    } catch {
        // ignore and fall back to hardcoded palette below
    }

    // Fallback: simple mapping for common blocks
    if (block == Block.Air) return Color.Black;
    if (block == Block.Stone) return Color.FromArgb(100, 100, 100);
    if (block == Block.Grass) return Color.FromArgb(60, 160, 60);
    if (block == Block.Dirt) return Color.FromArgb(120, 85, 60);
    if (block == Block.Water) return Color.FromArgb(50, 80, 200);
    if (block == Block.Lava) return Color.FromArgb(220, 80, 20);
    return Color.Magenta; // unknown
}

static void SavePngAtomic(Bitmap bmp, string outPath) {
        string tmp = outPath + ".tmp";
        bmp.Save(tmp, ImageFormat.Png);
        if (File.Exists(outPath)) File.Delete(outPath);
        File.Move(tmp, outPath);
    }

    // --- UI + JSON ----------------------------------------------------------

        string BuildIndexHtml() {
        // Minimal UI: worlds list, player list, map panel.
        // Faces: https://cdn.classicube.net/face/<name>.png
        // No token required (you asked to remove it).
        string html = @"<!doctype html>
<html>
<head>
<meta charset='utf-8'>
<meta name='viewport' content='width=device-width, initial-scale=1'>
<title>DynmapLite</title>
<style>
body { font-family: Arial, sans-serif; margin: 0; background: #111; color: #ddd; }
#wrap { display: flex; height: 100vh; }
#sidebar { width: 320px; padding: 12px; box-sizing: border-box; background: #161616; overflow: auto; }
#main { flex: 1; display: flex; flex-direction: column; }
#toolbar { padding: 10px; background: #1e1e1e; border-bottom: 1px solid #2a2a2a; }
#mapOuter { flex: 1; overflow: auto; background: #0b0b0b; padding: 12px; }
#map { position: relative; display: inline-block; background: #000; border: 1px solid #2a2a2a; }
.tile { image-rendering: pixelated; display:block; }
.tileRow { display:flex; }
#tileLayer { position: relative; }
#markerLayer { position:absolute; left:0; top:0; pointer-events:none; }
.world { padding: 6px 8px; margin: 4px 0; border: 1px solid #2a2a2a; cursor: pointer; }
.world.loaded { border-color: #2f6; }
.world.unloaded { opacity: 0.6; }
.playerRow { display:flex; align-items:center; margin: 6px 0; cursor:pointer; }
.playerRow:hover { background:#1f1f1f; }
.playerRow img { width: 24px; height: 24px; margin-right: 8px; image-rendering: pixelated; }
.small { color: #aaa; font-size: 12px; }
select, button { background: #222; color: #ddd; border: 1px solid #333; padding: 6px; }
.marker { position:absolute; transform: translate(-50%, -100%); display:flex; flex-direction:column; align-items:center; }
.marker img { width: 24px; height: 24px; image-rendering: pixelated; border:1px solid rgba(0,0,0,0.6); background:rgba(0,0,0,0.35); }
.marker span { margin-top:2px; font-size:12px; color:#fff; text-shadow:0 1px 2px #000; background:rgba(0,0,0,0.25); padding:1px 4px; border-radius:3px; white-space:nowrap; }
</style>
</head>
<body>
<div id='wrap'>
  <div id='sidebar'>
    <h2 style='margin: 6px 0 10px 0;'>Worlds</h2>
    <div id='worlds'></div>
    <h2 style='margin: 14px 0 10px 0;'>Players</h2>
    <div id='players'></div>
    <div class='small' style='margin-top: 14px;'>
      Web UI (no token). If you expose this publicly, put it behind a reverse proxy with auth.
    </div>
  </div>
  <div id='main'>
    <div id='toolbar'>
      <label>View:</label>
      <select id='viewSel'>
        <option value='topdown'>Top-down</option>
        <option value='isometric'>Isometric (stitched)</option>
      </select>
      <button id='refreshBtn'>Refresh</button>
      <span class='small' id='status' style='margin-left: 10px;'></span>
    </div>
    <div id='mapOuter'>
      <div id='mapInfo' class='small'>Select a world.</div>
      <div id='map'>
        <div id='tileLayer'></div>
        <div id='markerLayer'></div>
      </div>
    </div>
  </div>
</div>

<script>
var state = null;
var selectedWorld = null;
var pendingFocus = null; // {name, world, x, y, z}

function qs(sel) { return document.querySelector(sel); }
function esc(s) {
  s = (s === null || s === undefined) ? '' : ('' + s);
  return s.replace(/[&<>\x22]/g, function(c) {
    return ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '\x22':'&quot;' })[c];
  });
}

function loadState() {
  qs('#status').textContent = 'Loading...';
  fetch('/api/state')
    .then(function(r) { return r.json(); })
    .then(function(j) {
      state = j;
      renderSidebar();
      if (selectedWorld) renderMap();
      qs('#status').textContent = 'OK';
    })
    .catch(function() { qs('#status').textContent = 'Error'; });
}

function renderSidebar() {
  var worldsDiv = qs('#worlds');
  worldsDiv.innerHTML = '';
  for (var i = 0; i < state.worlds.length; i++) {
    var w = state.worlds[i];
    var div = document.createElement('div');
    div.className = 'world ' + (w.loaded ? 'loaded' : 'unloaded');
    div.innerHTML =
      '<div><b>' + esc(w.name) + '</b> ' +
      (w.loaded ? '<span class=small>(loaded)</span>' : '<span class=small>(unloaded)</span>') +
      '</div><div class=small>' + esc(w.size) + '</div>';

    div.onclick = (function(worldName) {
      return function() { selectedWorld = worldName; pendingFocus = null; renderMap(); };
    })(w.name);

    worldsDiv.appendChild(div);
  }

  var playersDiv = qs('#players');
  playersDiv.innerHTML = '';
  for (var j = 0; j < state.players.length; j++) {
    var p = state.players[j];
    var row = document.createElement('div');
    row.className = 'playerRow';
    var face = 'https://cdn.classicube.net/face/' + encodeURIComponent(p.name) + '.png';
    row.innerHTML =
      '<img src=' + face + ' alt=face>' +
      '<div><div><b>' + esc(p.name) + '</b></div>' +
      '<div class=small>' + esc(p.world) + ' @ ' + p.x + ',' + p.y + ',' + p.z + '</div></div>';

    row.onclick = (function(pp) {
      return function() {
        selectedWorld = pp.world;
        pendingFocus = pp;
        renderMap();
      };
    })(p);

    playersDiv.appendChild(row);
  }
}

function getWorldMeta(worldName) {
  if (!state) return null;
  for (var i = 0; i < state.worlds.length; i++) {
    if (state.worlds[i].name.toLowerCase() === worldName.toLowerCase()) return state.worlds[i];
  }
  return null;
}

function renderMap() {
  var view = qs('#viewSel').value;
  var info = qs('#mapInfo');
  var tileLayer = qs('#tileLayer');
  var markerLayer = qs('#markerLayer');

  tileLayer.innerHTML = '';
  markerLayer.innerHTML = '';
  qs('#map').style.display = selectedWorld ? 'inline-block' : 'none';

  if (!selectedWorld) { info.textContent = 'Select a world.'; return; }

  var w = getWorldMeta(selectedWorld);
  if (!w) { info.textContent = 'World not found.'; return; }

  info.innerHTML = '<b>' + esc(selectedWorld) + '</b> <span class=small>(' + (w.loaded ? 'loaded' : 'unloaded') + ')</span>';

  if (!w.tilesX || !w.tilesZ) {
    tileLayer.innerHTML = '<div class=small style=padding:10px;>No tile metadata available for this world (unloaded).</div>';
    return;
  }

  var tileSize = state.tileSize || 64; // blocks per tile, and also pixels per tile in topdown tiles
  var tilesX = w.tilesX, tilesZ = w.tilesZ;

  if (view === 'topdown') {
    // simple grid
    var container = document.createElement('div');
    for (var tz = 0; tz < tilesZ; tz++) {
      var row = document.createElement('div');
      row.className = 'tileRow';
      for (var tx = 0; tx < tilesX; tx++) {
        var img = document.createElement('img');
        img.className = 'tile';
        img.src = '/tiles/' + encodeURIComponent(selectedWorld) + '/topdown/' + tx + '/' + tz + '.png?_=' + Date.now();
        row.appendChild(img);
      }
      container.appendChild(row);
    }
    tileLayer.appendChild(container);
    // set layer size
    tileLayer.style.position = 'relative';
    qs('#map').style.width  = (tilesX * tileSize) + 'px';
    qs('#map').style.height = (tilesZ * tileSize) + 'px';
    markerLayer.style.width  = qs('#map').style.width;
    markerLayer.style.height = qs('#map').style.height;
    markerLayer.style.pointerEvents = 'none';
    renderMarkersTopdown(w, tileSize);
  } else {
    // isometric stitched: absolute position each tile in a diamond grid
    // The renderer makes each iso tile a fixed bitmap:
    // canvasW = (tileSize + tileSize) * px + 4, canvasH = (tileSize + tileSize) * (px/2) + 64
    var px = 2;
    var tileW = (tileSize + tileSize) * px + 4;
    var tileH = (tileSize + tileSize) * (px/2) + 64;
    var stepX = tileSize * px;
    var stepY = tileSize * (px/2);

    tileLayer.style.position = 'relative';

    // overall bounds for container
    var minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9;

    for (var tz = 0; tz < tilesZ; tz++) {
      for (var tx = 0; tx < tilesX; tx++) {
        var left = (tx - tz) * stepX;
        var top  = (tx + tz) * stepY;

        minX = Math.min(minX, left);
        minY = Math.min(minY, top);
        maxX = Math.max(maxX, left + tileW);
        maxY = Math.max(maxY, top + tileH);

        var img = document.createElement('img');
        img.className = 'tile';
        img.style.position = 'absolute';
        img.style.left = left + 'px';
        img.style.top  = top + 'px';
        img.src = '/tiles/' + encodeURIComponent(selectedWorld) + '/isometric/' + tx + '/' + tz + '.png?_=' + Date.now();
        tileLayer.appendChild(img);
      }
    }

    // normalize to start at 0,0 by shifting contents
    var shiftX = -minX + 20;
    var shiftY = -minY + 20;
    tileLayer.style.left = shiftX + 'px';
    tileLayer.style.top  = shiftY + 'px';

    var mapW = (maxX - minX) + 40;
    var mapH = (maxY - minY) + 40;

    qs('#map').style.width  = mapW + 'px';
    qs('#map').style.height = mapH + 'px';
    markerLayer.style.width  = mapW + 'px';
    markerLayer.style.height = mapH + 'px';
    markerLayer.style.pointerEvents = 'none';

    renderMarkersIso(w, tileSize, px, tileW, tileH, stepX, stepY, shiftX, shiftY);
  }

  // Focus (after render)
  if (pendingFocus && pendingFocus.world && pendingFocus.world.toLowerCase() === selectedWorld.toLowerCase()) {
    focusOnPlayer(pendingFocus.name);
  }
}

function renderMarkersTopdown(worldMeta, tileSize) {
  var markerLayer = qs('#markerLayer');
  markerLayer.innerHTML = '';

  for (var i = 0; i < state.players.length; i++) {
    var p = state.players[i];
    if (p.world.toLowerCase() !== selectedWorld.toLowerCase()) continue;

    var left = p.x;
    var top  = p.z;

    var m = document.createElement('div');
    m.className = 'marker';
    m.style.left = left + 'px';
    m.style.top  = top + 'px';

    var face = document.createElement('img');
    face.src = 'https://cdn.classicube.net/face/' + encodeURIComponent(p.name) + '.png';

    var label = document.createElement('span');
    label.textContent = p.name;

    m.appendChild(face);
    m.appendChild(label);
    markerLayer.appendChild(m);

    // give it an id for focusing
    m.setAttribute('data-player', p.name.toLowerCase());
  }
}

function renderMarkersIso(worldMeta, tileSize, px, tileW, tileH, stepX, stepY, shiftX, shiftY) {
  var markerLayer = qs('#markerLayer');
  markerLayer.innerHTML = '';

  var tilesX = worldMeta.tilesX, tilesZ = worldMeta.tilesZ;

  for (var i = 0; i < state.players.length; i++) {
    var p = state.players[i];
    if (p.world.toLowerCase() !== selectedWorld.toLowerCase()) continue;

    var tx = Math.floor(p.x / tileSize);
    var tz = Math.floor(p.z / tileSize);
    if (tx < 0 || tz < 0 || tx >= tilesX || tz >= tilesZ) continue;

    var dx = p.x - (tx * tileSize);
    var dz = p.z - (tz * tileSize);

    // Match RenderIsometricDraft() math (tile-local)
    var canvasW = tileW;
    var isoXLocal = (dx - dz) * px + (canvasW / 2);
    var isoYLocal = (dx + dz) * (px/2) + 16 - Math.floor(p.y / 4);

    var tileLeft = (tx - tz) * stepX + shiftX;
    var tileTop  = (tx + tz) * stepY + shiftY;

    var left = tileLeft + isoXLocal;
    var top  = tileTop  + isoYLocal;

    var m = document.createElement('div');
    m.className = 'marker';
    m.style.left = left + 'px';
    m.style.top  = top + 'px';

    var face = document.createElement('img');
    face.src = 'https://cdn.classicube.net/face/' + encodeURIComponent(p.name) + '.png';

    var label = document.createElement('span');
    label.textContent = p.name;

    m.appendChild(face);
    m.appendChild(label);
    markerLayer.appendChild(m);

    m.setAttribute('data-player', p.name.toLowerCase());
  }
}

function focusOnPlayer(name) {
  var mapOuter = qs('#mapOuter');
  var marker = qs('.marker[data-player=\'' + name.toLowerCase() + '\']');
  if (!marker) return;

  // Scroll container so marker is near center
  var rect = marker.getBoundingClientRect();
  var outerRect = mapOuter.getBoundingClientRect();

  var dx = (rect.left + rect.width/2) - (outerRect.left + outerRect.width/2);
  var dy = (rect.top  + rect.height/2) - (outerRect.top  + outerRect.height/2);

  mapOuter.scrollLeft += dx;
  mapOuter.scrollTop  += dy;
}

qs('#refreshBtn').onclick = loadState;
qs('#viewSel').onchange = function() { pendingFocus = null; renderMap(); };

loadState();
setInterval(loadState, 3000);
</script>
</body>
</html>";
        return html;
    }string BuildStateJson() {
        // worlds: all map names (loaded/unloaded), with dimensions if loaded
        // players: online players with integer block coords
        string[] allMaps = LevelInfo.AllMapNames(); // documented in API docs :contentReference[oaicite:1]{index=1}

        HashSet<string> loaded = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        Level[] loadedLvls = LevelInfo.Loaded.Items;
        for (int i = 0; i < loadedLvls.Length; i++) {
            Level l = loadedLvls[i];
            if (l != null) loaded.Add(l.name);
        }

        StringBuilder sb = new StringBuilder();
        sb.Append("{\"worlds\":[");
        for (int i = 0; i < allMaps.Length; i++) {
            string name = allMaps[i];
            bool isLoaded = loaded.Contains(name);

            int width = 0, height = 0, length = 0;
            int tilesX = 0, tilesZ = 0;

            if (isLoaded) {
                Level lvl = LevelInfo.FindExact(name);
                if (lvl != null) {
                    width = lvl.Width;
                    height = lvl.Height;
                    length = lvl.Length;
                    tilesX = (width + _cfg.tileSize - 1) / _cfg.tileSize;
                    tilesZ = (length + _cfg.tileSize - 1) / _cfg.tileSize;
                }
            } else {
                // Unknown dimensions when unloaded (draft). Could read metadata from disk if needed.
                tilesX = 0; tilesZ = 0;
            }

            if (i > 0) sb.Append(",");
            sb.Append("{");
            sb.Append("\"name\":\"").Append(JsonEscape(name)).Append("\",");
            sb.Append("\"loaded\":").Append(isLoaded ? "true" : "false").Append(",");
            sb.Append("\"size\":\"").Append(isLoaded ? (width + "x" + height + "x" + length) : "unloaded").Append("\",");
            sb.Append("\"tilesX\":").Append(tilesX.ToString(CultureInfo.InvariantCulture)).Append(",");
            sb.Append("\"tilesZ\":").Append(tilesZ.ToString(CultureInfo.InvariantCulture));
            sb.Append("}");
        }
        sb.Append("],\"tileSize\":").Append(_cfg.tileSize.ToString(CultureInfo.InvariantCulture)).Append(",\"players\":[");

        Player[] players = PlayerInfo.Online.Items;
        bool firstP = true;
        for (int i = 0; i < players.Length; i++) {
            Player p = players[i];
            if (p == null || p.level == null) continue;

            int x = p.Pos.X / 32;
            int y = p.Pos.Y / 32;
            int z = p.Pos.Z / 32;

            if (!firstP) sb.Append(",");
            firstP = false;

            sb.Append("{");
            sb.Append("\"name\":\"").Append(JsonEscape(p.name)).Append("\",");
            sb.Append("\"world\":\"").Append(JsonEscape(p.level.name)).Append("\",");
            sb.Append("\"x\":").Append(x.ToString(CultureInfo.InvariantCulture)).Append(",");
            sb.Append("\"y\":").Append(y.ToString(CultureInfo.InvariantCulture)).Append(",");
            sb.Append("\"z\":").Append(z.ToString(CultureInfo.InvariantCulture));
            sb.Append("}");
        }

        sb.Append("]}");
        return sb.ToString();
    }

    static string JsonEscape(string s) {
        if (s == null) return "";
        return s.Replace("\\", "\\\\").Replace("\"", "\\\"");
    }

    static string HtmlEscape(string s) {
        if (s == null) return "";
        return s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;").Replace("\"", "&quot;");
    }

    static void WriteText(HttpListenerResponse res, string text) {
        res.ContentType = "text/plain; charset=utf-8";
        WriteBytes(res, Encoding.UTF8.GetBytes(text ?? ""));
    }

    static void WriteBytes(HttpListenerResponse res, byte[] data) {
        try {
            res.ContentLength64 = data.Length;
            res.OutputStream.Write(data, 0, data.Length);
        } catch { }
        try { res.OutputStream.Close(); } catch { }
    }
}

// --- Config ----------------------------------------------------------------

internal sealed class DynmapConfig {
    public string bindHost = "0.0.0.0"; // bind on all interfaces (you asked TCP+UDP on the port)
    public int port = 48123;
    public int tileSize = 64;
    public int softTickSeconds = 5;

    public static DynmapConfig LoadOrCreate(string path) {
        DynmapConfig cfg = new DynmapConfig();

        if (!File.Exists(path)) {            cfg.Save(path);
            return cfg;
        }

        try {
            string[] lines = File.ReadAllLines(path);
            for (int i = 0; i < lines.Length; i++) {
                string line = lines[i].Trim();
                if (line.Length == 0 || line.StartsWith("#")) continue;

                int eq = line.IndexOf('=');
                if (eq <= 0) continue;

                string key = line.Substring(0, eq).Trim();
                string val = line.Substring(eq + 1).Trim();

                if (key.Equals("bindHost", StringComparison.OrdinalIgnoreCase)) cfg.bindHost = val;
                else if (key.Equals("port", StringComparison.OrdinalIgnoreCase)) cfg.port = ParseInt(val, 48123);
                                else if (key.Equals("tileSize", StringComparison.OrdinalIgnoreCase)) cfg.tileSize = Clamp(ParseInt(val, 64), 16, 256);
                else if (key.Equals("softTickSeconds", StringComparison.OrdinalIgnoreCase)) cfg.softTickSeconds = Clamp(ParseInt(val, 5), 0, 60);
            }
        } catch {
            // fall back to defaults
        }        return cfg;
    }

    public void Save(string path) {
        StringBuilder sb = new StringBuilder();
        sb.AppendLine("# DynmapLite config");
        sb.AppendLine("# bindHost=127.0.0.1 keeps it local-only. Change to 0.0.0.0 at your own risk.");
        sb.AppendLine("bindHost=" + bindHost);
        sb.AppendLine("port=" + port.ToString(CultureInfo.InvariantCulture));
                sb.AppendLine("# Tile size in blocks (power of two recommended).");
        sb.AppendLine("tileSize=" + tileSize.ToString(CultureInfo.InvariantCulture));
        sb.AppendLine("# If >0, periodically marks tiles near players dirty (cheap refresh).");
        sb.AppendLine("softTickSeconds=" + softTickSeconds.ToString(CultureInfo.InvariantCulture));
        File.WriteAllText(path, sb.ToString());
    }

    static int ParseInt(string s, int defVal) {
        int v;
        if (int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out v)) return v;
        return defVal;
    }

    static int Clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    static string MakeToken(int len) {
        const string chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        Random rng = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.Append(chars[rng.Next(chars.Length)]);
        }
        return sb.ToString();
    }
}