// TFC Long Jump Module Fix (AMX Mod X)
// Makes item_longjump in TFC actually give a long jump ability until death.
// Behavior:
//  - Touch item_longjump -> gain longjump until you die
//  - On pickup: FVOX line plays
//  - On longjump: CTF longjump sound plays (ctf/pow_big_jump.wav)
//  - Longjump is: running forward + duck + fresh jump press
//  - Only horizontal speed is boosted; jump height is unchanged.

#include <amxmodx>
#include <engine>
#include <fakemeta>
#include <hamsandwich>
#include <xs>

// Plugin info
#define PLUGIN_NAME    "TFC LongJump Module Fix"
#define PLUGIN_VERSION "1.1"
#define PLUGIN_AUTHOR  "ChatGPT"

// Button flags (from HLSDK)
#define IN_ATTACK      (1<<0)
#define IN_JUMP        (1<<1)
#define IN_DUCK        (1<<2)
#define IN_FORWARD     (1<<3)

// Player state
new bool:gHasLongJump[33];

// Cvar handles
new gPcvarEnabled;
new gPcvarForwardSpeed;
new gPcvarMinSpeed;

// Sound paths (relative to sound/)
new const gLongJumpSound[] = "ctf/pow_big_jump.wav";
// Keep your FVOX line here (adjust if your exact filename differs)
new const gPowerOnSound[]  = "fvox/powermove_on.wav";

public plugin_precache()
{
    precache_sound(gLongJumpSound);
    precache_sound(gPowerOnSound);
}

public plugin_init()
{
    register_plugin(PLUGIN_NAME, PLUGIN_VERSION, PLUGIN_AUTHOR);

    // Cvars for tuning
    // Forward boost speed (units/sec)
    gPcvarForwardSpeed = register_cvar("tfc_lj_forward",  "560.0");
    // Minimum run speed before a longjump is allowed
    gPcvarMinSpeed     = register_cvar("tfc_lj_minspeed", "170.0");
    // Master enable
    gPcvarEnabled      = register_cvar("tfc_lj_enabled",  "1");

    // Hook touch between item_longjump and player
    register_touch("item_longjump", "player", "onLongJumpPickup");

    // Movement logic
    register_forward(FM_PlayerPreThink, "fw_PlayerPreThink");

    // Clear ability on death + spawn
    register_event("DeathMsg", "onDeathMsg", "a");
    register_event("ResetHUD", "onResetHUD", "b");
}

public client_connect(id)
{
    if (1 <= id <= 32)
        gHasLongJump[id] = false;
}

public client_disconnect(id)
{
    if (1 <= id <= 32)
        gHasLongJump[id] = false;
}

// ----------------------------------------------------
// item_longjump touch handler
// ----------------------------------------------------
public onLongJumpPickup(ent, id)
{
    if (!get_pcvar_num(gPcvarEnabled))
        return PLUGIN_CONTINUE;

    if (!is_user_connected(id) || !is_user_alive(id))
        return PLUGIN_CONTINUE;

    // Already has module for this life
    if (gHasLongJump[id])
        return PLUGIN_CONTINUE;

    gHasLongJump[id] = true;

    // Move info text to console (no chat spam)
    client_print(id, print_console,
        "[LongJump] Module installed. Run forward, hold duck, then tap jump.");

    // FVOX power-on line
    emit_sound(id, CHAN_VOICE, gPowerOnSound, 1.0, ATTN_NORM, 0, PITCH_NORM);

    // Do NOT remove the entity; TFC handles respawn
    return PLUGIN_CONTINUE;
}

// ----------------------------------------------------
// Clear longjump on death + respawn
// ----------------------------------------------------
public onDeathMsg()
{
    new victim = read_data(2);
    if (1 <= victim <= 32)
    {
        gHasLongJump[victim] = false;
    }
}

public onResetHUD(id)
{
    if (1 <= id <= 32)
    {
        // New life; require re-pickup
        gHasLongJump[id] = false;
    }
}

// ----------------------------------------------------
// Movement logic: detect long jump input and apply boost
// ----------------------------------------------------
public fw_PlayerPreThink(id)
{
    if (!get_pcvar_num(gPcvarEnabled))
        return FMRES_IGNORED;

    if (!is_user_alive(id))
        return FMRES_IGNORED;

    if (!gHasLongJump[id])
        return FMRES_IGNORED;

    // Must be on ground
    new flags = pev(id, pev_flags);
    if (!(flags & FL_ONGROUND))
        return FMRES_IGNORED;

    // Avoid doing this in deep water
    new waterlevel = pev(id, pev_waterlevel);
    if (waterlevel >= 2)
        return FMRES_IGNORED;

    // Check buttons
    new buttons    = pev(id, pev_button);
    new oldbuttons = pev(id, pev_oldbuttons);

    // Require a *fresh* jump press (no pogo)
    if (!(buttons & IN_JUMP) || (oldbuttons & IN_JUMP))
        return FMRES_IGNORED;

    // Require duck
    if (!(buttons & IN_DUCK))
        return FMRES_IGNORED;

    // Require forward key held
    if (!(buttons & IN_FORWARD))
        return FMRES_IGNORED;

    // Require some existing forward speed
    new Float:velocity[3];
    pev(id, pev_velocity, velocity);

    new Float:horizSpeed = floatsqroot(
        velocity[0] * velocity[0] +
        velocity[1] * velocity[1]
    );

    new Float:minSpeed = get_pcvar_float(gPcvarMinSpeed);
    if (horizSpeed < minSpeed)
        return FMRES_IGNORED;

    // Avoid division by zero / tiny speeds
    if (horizSpeed <= 1.0)
        return FMRES_IGNORED;

    // Scale current horizontal velocity up to desired longjump speed,
    // preserving direction and leaving vertical component alone.
    new Float:targetSpeed = get_pcvar_float(gPcvarForwardSpeed);
    new Float:scale = targetSpeed / horizSpeed;

    velocity[0] *= scale;
    velocity[1] *= scale;
    // Do NOT touch velocity[2]; this keeps jump height unchanged.
    set_pev(id, pev_velocity, velocity);

    // Play longjump sound
    emit_sound(id, CHAN_BODY, gLongJumpSound, 1.0, ATTN_NORM, 0, PITCH_NORM);

    return FMRES_IGNORED;
}
