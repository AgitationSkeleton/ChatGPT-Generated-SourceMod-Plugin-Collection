/* Ricochet Duck (Simulated) – smooth speed + safe stand
 * Requires: amxmodx, fakemeta, fakemeta_util
 * Version: 1.0.5
 * Author: ChatGPT
 */
#include <amxmodx>
#include <fakemeta>
#include <fakemeta_util>

#define PLUGIN_NAME "Ricochet Duck (Simulated)"
#define PLUGIN_VER  "1.0.5"
#define PLUGIN_AUTH "ChatGPT"

#define IN_DUCK (1<<2)
#define DONT_IGNORE_MONSTERS 0
#define HULL_HUMAN 1

new const Float:STAND_MINS[3] = { -16.0, -16.0, -36.0 };
new const Float:STAND_MAXS[3] = {  16.0,  16.0,  36.0 };
new const Float:DUCK_MINS[3]  = { -16.0, -16.0, -18.0 };
new const Float:DUCK_MAXS[3]  = {  16.0,  16.0,  18.0 };

new g_pEnable, g_pDuckView, g_pStandView, g_pDuckSpeed;
new bool:g_isDucked[33];
new Float:g_savedMaxSpeed[33];

public plugin_init() {
    register_plugin(PLUGIN_NAME, PLUGIN_VER, PLUGIN_AUTH);
    g_pEnable    = register_cvar("rico_duck_enable", "1");
    g_pDuckView  = register_cvar("rico_duck_view",   "12.0");
    g_pStandView = register_cvar("rico_stand_view",  "28.0");
    // Set to 0 to disable speed clamping if it jitters on your build
    g_pDuckSpeed = register_cvar("rico_duck_speed",  "140.0");

    register_forward(FM_CmdStart,         "OnCmdStart");
    register_forward(FM_SetClientMaxspeed,"OnSetClientMaxspeed");
    register_forward(FM_PlayerPostThink,  "OnPlayerPostThink");
}

public client_connect(id)      { g_isDucked[id] = false; g_savedMaxSpeed[id] = 0.0; }
public client_disconnected(id) { g_isDucked[id] = false; g_savedMaxSpeed[id] = 0.0; }

public OnCmdStart(id, uc, seed) {
    if (!get_pcvar_num(g_pEnable) || !is_user_alive(id)) return FMRES_IGNORED;

    new buttons    = get_uc(uc, UC_Buttons);
    new oldbuttons = pev(id, pev_oldbuttons);

    if ((buttons & IN_DUCK) && !(oldbuttons & IN_DUCK)) {
        if (g_savedMaxSpeed[id] <= 0.0) {
            g_savedMaxSpeed[id] = pev(id, pev_maxspeed); // store once
        }
        g_isDucked[id] = true;
        buttons &= ~IN_DUCK; set_uc(uc, UC_Buttons, buttons);
        return FMRES_HANDLED;
    }
    if (!(buttons & IN_DUCK) && (oldbuttons & IN_DUCK)) {
        if (can_stand_here(id)) {
            g_isDucked[id] = false;
        }
        return FMRES_IGNORED;
    }
    return FMRES_IGNORED;
}

// Smooth, engine-sanctioned speed control
public OnSetClientMaxspeed(id, Float:cur) {
    if (!get_pcvar_num(g_pEnable) || !is_user_alive(id)) return FMRES_IGNORED;

    new Float:duckspd = get_pcvar_float(g_pDuckSpeed);
    if (duckspd > 0.0 && g_isDucked[id]) {
        set_pev(id, pev_maxspeed, floatmax(60.0, duckspd));
        return FMRES_SUPERCEDE;
    }
    if (!g_isDucked[id] && g_savedMaxSpeed[id] > 0.0) {
        set_pev(id, pev_maxspeed, g_savedMaxSpeed[id]);
        return FMRES_SUPERCEDE;
    }
    return FMRES_IGNORED;
}

// Apply hull & view once per frame (after DLL runs)
public OnPlayerPostThink(id) {
    if (!get_pcvar_num(g_pEnable) || !is_user_alive(id)) return FMRES_IGNORED;

    if (g_isDucked[id]) {
        engfunc(EngFunc_SetSize, id, DUCK_MINS, DUCK_MAXS);
        new Float:v[3]; pev(id, pev_view_ofs, v);
        v[2] = floatmax(4.0, get_pcvar_float(g_pDuckView));
        set_pev(id, pev_view_ofs, v);
    } else {
        engfunc(EngFunc_SetSize, id, STAND_MINS, STAND_MAXS);
        new Float:v2[3]; pev(id, pev_view_ofs, v2);
        v2[2] = floatmax(20.0, get_pcvar_float(g_pStandView));
        set_pev(id, pev_view_ofs, v2);
    }
    return FMRES_IGNORED;
}

// Safer stand test: (1) stand hull fits at origin; (2) clear headroom above
bool:can_stand_here(id) {
    if (!is_user_alive(id)) return false;

    new Float:org[3]; pev(id, pev_origin, org);

    // Test 1: zero-length trace with stand hull — must not start solid
    new tr = create_tr2();
    engfunc(EngFunc_TraceHull, org, org, DONT_IGNORE_MONSTERS, HULL_HUMAN, id, tr);
    new allsolid, startsolid;
    get_tr2(tr, TR_AllSolid, allsolid);
    get_tr2(tr, TR_StartSolid, startsolid);
    free_tr2(tr);
    if (allsolid || startsolid) return false;

    // Test 2: ensure clear space above crouch (~18 units)
    new Float:end[3]; end[0]=org[0]; end[1]=org[1]; end[2]=org[2]+18.0;
    tr = create_tr2();
    engfunc(EngFunc_TraceHull, org, end, DONT_IGNORE_MONSTERS, HULL_HUMAN, id, tr);
    new Float:frac;
    get_tr2(tr, TR_flFraction, frac);
    free_tr2(tr);
    return (frac >= 1.0);
}
