// gpt_dmcladders.sma
// Restore ladder climbing in DMC via CONTENTS_LADDER, with top-out assist.
// Requires: fakemeta, hamsandwich
//
// Author: Max + helper
// Version: 1.2

#include <amxmodx>
#include <fakemeta>
#include <hamsandwich>

#define PLUGIN  "DMC Restore Ladders"
#define VERSION "1.2"
#define AUTHOR  "max/gpt"

#define MAX_PLAYERS       32
#define CONTENTS_LADDER  (-16)  // HLSDK

// --- CVARs ---
new c_lad_enable;        // 1=on
new c_lad_up;            // up speed (ups)
new c_lad_dn;            // down speed (ups)
new c_lad_slide;         // idle slide (downs) (ups)
new c_lad_stick;         // horizontal damping 0..1
new c_lad_detach_jump;   // 1=jump detaches
new c_lad_face_dot;      // facing requirement (0..1). set 0 to disable
new c_lad_debug;         // 1=debug prints
new c_lad_probe_dist;    // how far forward to probe contents (units)
new c_lad_probe_side;    // side probe distance (units)

// Top-out assist
new c_top_time;          // seconds to keep helping after leaving ladder
new c_top_push;          // horizontal push away from wall (ups)
new c_top_up;            // vertical carry during top-out (ups)

// State
new bool:g_onLadder[MAX_PLAYERS + 1];
new bool:g_prevOnLadder[MAX_PLAYERS + 1];
new Float:g_lastNormal[MAX_PLAYERS + 1][3];
new Float:g_topoutUntil[MAX_PLAYERS + 1];

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    c_lad_enable      = register_cvar("dmc_ladder_enable", "1");
    c_lad_up          = register_cvar("dmc_ladder_upspd", "200.0");
    c_lad_dn          = register_cvar("dmc_ladder_dnspd", "200.0");
    c_lad_slide       = register_cvar("dmc_ladder_slide", "60.0");
    c_lad_stick       = register_cvar("dmc_ladder_stick", "0.2");
    c_lad_detach_jump = register_cvar("dmc_ladder_detach_jump", "1");
    c_lad_face_dot    = register_cvar("dmc_ladder_facedot", "0.20");
    c_lad_debug       = register_cvar("dmc_ladder_debug", "0");
    c_lad_probe_dist  = register_cvar("dmc_ladder_probedist", "24.0");
    c_lad_probe_side  = register_cvar("dmc_ladder_probeside", "12.0");

    // Top-out assist defaults (feel free to tweak)
    c_top_time        = register_cvar("dmc_ladder_topout_time", "0.18");
    c_top_push        = register_cvar("dmc_ladder_topout_push", "120.0");
    c_top_up          = register_cvar("dmc_ladder_topout_up",   "180.0");

    RegisterHam(Ham_Player_PreThink, "player", "OnPlayerPreThink");
}

public client_putinserver(id) { resetp(id); }
public client_disconnect(id)  { resetp(id); }

public OnPlayerPreThink(id)
{
    if (!get_pcvar_num(c_lad_enable)) return HAM_IGNORED;
    if (!is_user_alive(id))           return HAM_IGNORED;

    // Detect ladder and get wall normal (if any)
    new Float:nrm[3]; nrm[0]=nrm[1]=0.0; nrm[2]=1.0;
    new bool:onLadderNow = IsAtLadder(id, nrm);

    // Track edges for top-out assist
    new Float:now = get_gametime();
    if (onLadderNow)
    {
        g_onLadder[id] = true;
        g_lastNormal[id][0]=nrm[0];
        g_lastNormal[id][1]=nrm[1];
        g_lastNormal[id][2]=nrm[2];
        // While we are on the ladder, clear any prior top-out window
        g_topoutUntil[id] = 0.0;
    }
    else
    {
        // If we just left the ladder, start the top-out window
        if (g_prevOnLadder[id])
        {
            g_topoutUntil[id] = now + get_pcvar_float(c_top_time);
        }
        g_onLadder[id] = false;
    }
    g_prevOnLadder[id] = onLadderNow;

    // Decide if we should apply ladder/top-out control this frame
    new bool:assist = onLadderNow || (g_topoutUntil[id] > now);

    if (!assist)
        return HAM_IGNORED;

    // Movement shaping
    new buttons = pev(id, pev_button);
    new Float:vel[3]; pev(id, pev_velocity, vel);

    // Detach by jump
    if (get_pcvar_num(c_lad_detach_jump) && (buttons & IN_JUMP))
    {
        // push away only if we have a sensible normal
        if (g_lastNormal[id][2] < 0.7)
        {
            vel[0] += g_lastNormal[id][0] * 120.0;
            vel[1] += g_lastNormal[id][1] * 120.0;
        }
        set_pev(id, pev_velocity, vel);
        g_topoutUntil[id] = 0.0;
        return HAM_IGNORED;
    }

    // Choose which vertical regime to apply
    if (onLadderNow)
    {
        // Normal ladder behavior
        new Float:upspd  = get_pcvar_float(c_lad_up);
        new Float:dnspd  = get_pcvar_float(c_lad_dn);
        new Float:slides = get_pcvar_float(c_lad_slide);

        if (buttons & IN_FORWARD)      vel[2] = upspd;
        else if (buttons & IN_BACK)    vel[2] = -dnspd;
        else                           vel[2] = -slides;

        // Stickiness
        new Float:stick = get_pcvar_float(c_lad_stick);
        if (stick < 0.0) stick = 0.0;
        if (stick > 1.0) stick = 1.0;
        vel[0] *= stick; vel[1] *= stick;

        set_pev(id, pev_velocity, vel);
    }
    else
    {
        // Top-out assist: give a small vertical carry and a horizontal nudge
        new Float:carryUp = get_pcvar_float(c_top_up);
        new Float:push    = get_pcvar_float(c_top_push);

        // Only help if player is pressing forward (i.e., intends to climb)
        if (buttons & IN_FORWARD)
        {
            // vertical carry
            if (vel[2] < carryUp) vel[2] = carryUp;

            // horizontal nudge away from wall normal (requires a valid normal)
            if (g_lastNormal[id][2] < 0.7)
            {
                vel[0] += g_lastNormal[id][0] * push;
                vel[1] += g_lastNormal[id][1] * push;
            }

            set_pev(id, pev_velocity, vel);
        }

        // End assist early once the player is grounded on top
        new flags = pev(id, pev_flags);
        if (flags & FL_ONGROUND)
            g_topoutUntil[id] = 0.0;
    }

    return HAM_IGNORED;
}

// ---------- Ladder detection via CONTENTS_LADDER + (optional) facing check ----------

stock bool:IsAtLadder(id, Float:outNormal[3])
{
    // Build sample points around the player's eye level.
    new Float:org[3]; pev(id, pev_origin, org);
    new Float:viewofs[3]; pev(id, pev_view_ofs, viewofs);
    new Float:eye[3]; eye[0]=org[0]+viewofs[0]; eye[1]=org[1]+viewofs[1]; eye[2]=org[2]+viewofs[2];

    new Float:angles[3]; pev(id, pev_v_angle, angles);
    new Float:fwd[3], right[3];
    angle_vector(angles, ANGLEVECTOR_FORWARD, fwd);
    angle_vector(angles, ANGLEVECTOR_RIGHT,   right);
    normalize(fwd); normalize(right);

    new Float:fd = get_pcvar_float(c_lad_probe_dist);
    new Float:sd = get_pcvar_float(c_lad_probe_side);

    // Sample 5 points: forward, forward+left, forward+right, forward + small up, forward + small down
    new Float:samples[5][3];
    BuildSample(samples[0], eye, fwd, right, fd,      0.0,  0.0);
    BuildSample(samples[1], eye, fwd, right, fd,      sd,   0.0);
    BuildSample(samples[2], eye, fwd, right, fd,     -sd,   0.0);
    BuildSample(samples[3], eye, fwd, right, fd,      0.0,  8.0);
    BuildSample(samples[4], eye, fwd, right, fd,      0.0, -8.0);

    // Check contents at each sample
    new bool:hit = false;
    for (new i=0; i<5; i++)
    {
        if (engfunc(EngFunc_PointContents, samples[i]) == CONTENTS_LADDER)
        {
            hit = true;
            break;
        }
    }
    if (!hit) return false;

    // Optional facing requirement and normal capture
    new Float:faceth = get_pcvar_float(c_lad_face_dot);

    // Trace a short line to get a surface normal if possible
    new Float:end[3]; end[0]=eye[0]+fwd[0]*16.0; end[1]=eye[1]+fwd[1]*16.0; end[2]=eye[2]+fwd[2]*16.0;
    new tr = 0;
    engfunc(EngFunc_TraceLine, eye, end, IGNORE_MONSTERS, id, tr);

    if (get_tr2(tr, TR_flFraction) < 1.0)
    {
        new Float:nrm[3]; get_tr2(tr, TR_vecPlaneNormal, nrm);
        // Prefer vertical surfaces
        if (floatabs(nrm[2]) <= 0.7)
        {
            // Always output a normal (used for top-out assist)
            outNormal[0]=nrm[0]; outNormal[1]=nrm[1]; outNormal[2]=nrm[2];

            if (faceth > 0.0)
            {
                new Float:dot = fwd[0]*nrm[0] + fwd[1]*nrm[1] + fwd[2]*nrm[2];
                if (dot > -faceth) return false;
            }
        }
        else
        {
            // If we hit floor/ceiling, just clear normal Z so we don't push upward oddly
            outNormal[0]=0.0; outNormal[1]=0.0; outNormal[2]=1.0;
        }
    }
    else
    {
        // No hit; keep a safe default normal
        outNormal[0]=0.0; outNormal[1]=0.0; outNormal[2]=1.0;
    }

    return true;
}

stock BuildSample(Float:out[3], const Float:eye[3], const Float:fwd[3], const Float:right[3], Float:fd, Float:side, Float:up)
{
    out[0] = eye[0] + fwd[0]*fd + right[0]*side;
    out[1] = eye[1] + fwd[1]*fd + right[1]*side;
    out[2] = eye[2] + fwd[2]*fd + up;
}

stock normalize(Float:v[3])
{
    new Float:l = floatsqroot(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
    if (l > 0.0) { v[0]/=l; v[1]/=l; v[2]/=l; }
}

stock resetp(id)
{
    g_onLadder[id] = false;
    g_prevOnLadder[id] = false;
    g_lastNormal[id][0]=0.0; g_lastNormal[id][1]=0.0; g_lastNormal[id][2]=1.0;
    g_topoutUntil[id] = 0.0;
}

// Debug helper (only used if you turn cvar on)
stock DebugMsg(id, const fmt[], any:...)
{
    if (!get_pcvar_num(c_lad_debug)) return;
    static msg[192];
    vformat(msg, charsmax(msg), fmt, 3);
    client_print(id, print_chat, "%s", msg);
}
