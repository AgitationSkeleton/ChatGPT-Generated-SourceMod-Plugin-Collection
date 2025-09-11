// dmc_impulse100_flashlight.sma
// DMC "flashlight" using TE_DLIGHT, toggled by impulse 100
// HamSandwich + Fakemeta; auto-scale option to keep the spot small.
// Requires: fakemeta, hamsandwich

#include <amxmodx>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN  "DMC Impulse100 Flashlight"
#define VERSION "1.3"
#define AUTHOR  "gpt"

new bool:g_on[33];

// Visual cvars
new c_fl_mode;                  // 0=fixed, 1=scale with distance
new c_fl_radius;                // base radius at ref distance
new c_fl_radius_min, c_fl_radius_max;
new c_fl_refdist;               // units where fl_radius is “correct”
new c_fl_maxdist;               // max trace distance
new c_fl_life, c_fl_decay;
new c_fl_color_r, c_fl_color_g, c_fl_color_b;

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    // Tuning — made smaller by default
    c_fl_mode       = register_cvar("fl_mode",       "1");
    c_fl_radius     = register_cvar("fl_radius",     "10");
    c_fl_radius_min = register_cvar("fl_radius_min", "6");
    c_fl_radius_max = register_cvar("fl_radius_max", "22");
    c_fl_refdist    = register_cvar("fl_refdist",    "200");
    c_fl_maxdist    = register_cvar("fl_maxdist",    "800");

    c_fl_life    = register_cvar("fl_life",   "2");   // 1..255 (10 ≈ 1s)
    c_fl_decay   = register_cvar("fl_decay",  "1");   // 0..255
    c_fl_color_r = register_cvar("fl_color_r","255");
    c_fl_color_g = register_cvar("fl_color_g","255");
    c_fl_color_b = register_cvar("fl_color_b","200");

    register_forward(FM_CmdStart, "OnCmdStart");

    RegisterHam(Ham_Player_PreThink, "player", "OnPlayerPreThink");
    RegisterHam(Ham_Spawn,            "player", "OnPlayerSpawnPost", 1);
    RegisterHam(Ham_Killed,           "player", "OnPlayerKilledPost", 1);
}

public plugin_precache()
{
    precache_sound("items/flashlight1.wav");
}

public client_putinserver(id) { g_on[id] = false; }
public client_disconnect(id)  { g_on[id] = false; }
public OnPlayerSpawnPost(id)  { if (is_user_connected(id)) g_on[id] = false; }
public OnPlayerKilledPost(victim, killer, shouldgib) { if (is_user_connected(victim)) g_on[victim] = false; }

public OnCmdStart(id, uc_handle, seed)
{
    if (!is_user_alive(id)) return FMRES_IGNORED;

    new impulse = get_uc(uc_handle, UC_Impulse);
    if (impulse != 100) return FMRES_IGNORED;

    set_uc(uc_handle, UC_Impulse, 0);

    if (!g_on[id]) Flashlight_On(id);
    else           Flashlight_Off(id);

    return FMRES_HANDLED;
}

public OnPlayerPreThink(id)
{
    if (!g_on[id] || !is_user_alive(id)) return HAM_IGNORED;

    new aim[3], eye[3];
    get_user_origin(id, aim, 3);  // end of view trace
    get_user_origin(id, eye, 1);  // eye position

    // Clamp to max distance by lerping if needed
    new Float:dist = vector_distance_3i(eye, aim);
    new Float:maxd = get_pcvar_float(c_fl_maxdist);
    if (dist > maxd && maxd > 0.0)
    {
        lerp_point_3i(eye, aim, maxd / dist, aim);
        dist = maxd;
    }

    Send_DLight_Scaled(aim, dist);
    return HAM_IGNORED;
}

stock Flashlight_On(id)
{
    if (g_on[id]) return;
    g_on[id] = true;
    emit_sound(id, CHAN_ITEM, "items/flashlight1.wav", VOL_NORM, ATTN_NORM, 0, PITCH_NORM);
}

stock Flashlight_Off(id)
{
    if (!g_on[id]) return;
    g_on[id] = false;
    emit_sound(id, CHAN_ITEM, "items/flashlight1.wav", VOL_NORM, ATTN_NORM, 0, PITCH_NORM);
}

stock Send_DLight_Scaled(const pos[3], Float:dist)
{
    // Color
    new r = clamp(get_pcvar_num(c_fl_color_r), 0, 255);
    new g = clamp(get_pcvar_num(c_fl_color_g), 0, 255);
    new b = clamp(get_pcvar_num(c_fl_color_b), 0, 255);

    // Life/decay
    new life  = clamp(get_pcvar_num(c_fl_life),  1, 255);
    new decay = clamp(get_pcvar_num(c_fl_decay), 0, 255);

    // Radius
    new mode = get_pcvar_num(c_fl_mode);
    new Float:rad = float(get_pcvar_num(c_fl_radius));

    if (mode == 1)
    {
        new Float:refd = get_pcvar_float(c_fl_refdist);
        if (refd <= 1.0) refd = 200.0;

        // scale inversely with distance: closer = smaller spot
        // at dist=refd => rad unchanged
        if (dist < 1.0) dist = 1.0;
        rad = rad * (dist / refd);
        // clamp
        new Float:rmin = get_pcvar_float(c_fl_radius_min);
        new Float:rmax = get_pcvar_float(c_fl_radius_max);
        if (rad < rmin) rad = rmin;
        if (rad > rmax) rad = rmax;
    }

    message_begin(MSG_PAS, SVC_TEMPENTITY, pos);
    write_byte(TE_DLIGHT);         // 27
    write_coord(pos[0]);
    write_coord(pos[1]);
    write_coord(pos[2]);
    write_byte(clamp(floatround(rad), 1, 255));
    write_byte(r); write_byte(g); write_byte(b);
    write_byte(life);
    write_byte(decay);
    message_end();
}

// ---- small helpers ----
stock Float:vector_distance_3i(const a[3], const b[3])
{
    new Float:ax = float(a[0]), Float:ay = float(a[1]), Float:az = float(a[2]);
    new Float:bx = float(b[0]), Float:by = float(b[1]), Float:bz = float(b[2]);
    new Float:dx = ax - bx, Float:dy = ay - by, Float:dz = az - bz;
    return floatsqroot(dx*dx + dy*dy + dz*dz);
}

stock lerp_point_3i(const a[3], const b[3], Float:t, out[3])
{
    out[0] = floatround(float(a[0]) + (float(b[0]) - float(a[0])) * t);
    out[1] = floatround(float(a[1]) + (float(b[1]) - float(a[1])) * t);
    out[2] = floatround(float(a[2]) + (float(b[2]) - float(a[2])) * t);
}
