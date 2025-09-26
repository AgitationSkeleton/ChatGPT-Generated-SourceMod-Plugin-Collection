#include <amxmodx>
#include <fakemeta>
#include <hamsandwich>

#define VERSION "1.3"

// ---------------- Cvars ----------------
new c_bunny_mode;     // 1 = enable autohop logic (kept from original)
new c_bunny_factor;   // vertical velocity factor (kept from original)
new c_sound_enable;   // 1 = play jump sound on auto hops
new c_sound_world;    // 1 = emit_sound (world-audible), 0 = client_cmd spk (local only)
new c_sound_cd;       // seconds between auto-hop jump sounds per player

// ---------------- State ----------------
#define MAX_P 32
new bool:g_holdJump[MAX_P + 1];
new bool:g_prevHoldJump[MAX_P + 1];
new Float:g_nextJumpSndOk[MAX_P + 1];

// Sound
new const JUMP_SND[] = "player/plyrjmp8.wav"; // HL/TFC/DMC classic jump sound

public plugin_precache()
{
    precache_sound(JUMP_SND);
}

public plugin_init()
{
    register_plugin("Auto BunnyHop + JumpSound", VERSION, "Night Dreamer + patch");

    // Preserve original cvars
    c_bunny_mode   = register_cvar("abh_on",     "1");
    c_bunny_factor = register_cvar("abh_factor", "0");

    // New cvars
    c_sound_enable = register_cvar("abh_jsnd_enable", "1");
    c_sound_world  = register_cvar("abh_jsnd_world",  "1");   // 1 world, 0 local spk
    c_sound_cd     = register_cvar("abh_jsnd_cd",     "0.02");// small debounce

    RegisterHam(Ham_Player_Jump, "player", "bunnyhop");

    // Track +jump edge reliably
    register_forward(FM_CmdStart, "OnCmdStart");
}

public client_putinserver(id)
{
    g_holdJump[id] = false;
    g_prevHoldJump[id] = false;
    g_nextJumpSndOk[id] = 0.0;
}
public client_disconnect(id)
{
    g_holdJump[id] = false;
    g_prevHoldJump[id] = false;
    g_nextJumpSndOk[id] = 0.0;
}

// Detect +jump press edges (manual press vs. held)
public OnCmdStart(id, uc, seed)
{
    if (!is_user_connected(id)) return FMRES_IGNORED;

    new btns = get_uc(uc, UC_Buttons);
    g_prevHoldJump[id] = g_holdJump[id];
    g_holdJump[id]     = (btns & IN_JUMP) != 0;

    return FMRES_IGNORED;
}

public bunnyhop(id)
{
    if (get_pcvar_num(c_bunny_mode) == 0)
        return HAM_IGNORED;

    if (!is_user_connected(id) || !is_user_alive(id))
        return HAM_IGNORED;

    // Optional: ignore observers (common HL pattern: iuser1 != 0)
    if (pev(id, pev_iuser1) != 0)
        return HAM_IGNORED;

    // Only when we're on ground do we manipulate jump velocity (original logic)
    if (pev(id, pev_flags) & FL_ONGROUND)
    {
        new Float:Vel[3];
        pev(id, pev_velocity, Vel);

        new Float:factor = get_pcvar_float(c_bunny_factor);
        if (factor >= 1.0)
        {
            Vel[2] = factor;
        }
        else
        {
            new Float:Spd;
            pev(id, pev_maxspeed, Spd);
            Vel[2] = Spd;
        }

        set_pev(id, pev_velocity, Vel);

        // Cosmetic animation from original
        set_pev(id, pev_gaitsequence, 6);
        set_pev(id, pev_frame, 0.0);

        // ---- NEW: play jump sound on "automatic" hops only ----
        // If +jump was just *pressed* this frame, it's the manual jump; DO NOT play (engine handles it).
        // If +jump is held (no new press edge), this is likely an auto hop → play our sound.
        if (get_pcvar_num(c_sound_enable))
        {
            new bool:pressedEdge = (g_holdJump[id] && !g_prevHoldJump[id]);
            if (!pressedEdge)
            {
                // small cooldown per player to avoid double fire
                new Float:now = get_gametime();
                new Float:cd  = get_pcvar_float(c_sound_cd);
                if (cd < 0.0) cd = 0.0;

                if (now >= g_nextJumpSndOk[id])
                {
                    PlayJumpSound(id);
                    g_nextJumpSndOk[id] = now + cd;
                }
            }
        }
        // -------------------------------------------------------
    }

    return HAM_IGNORED;
}

stock PlayJumpSound(id)
{
    if (!is_user_connected(id)) return;

    if (get_pcvar_num(c_sound_world))
    {
        // World-audible (positional) so others hear it too
        emit_sound(id, CHAN_VOICE, JUMP_SND, VOL_NORM, ATTN_NORM, 0, PITCH_NORM);
    }
    else
    {
        // Local-only (client-side)
        client_cmd(id, "spk %s", JUMP_SND);
    }
}
