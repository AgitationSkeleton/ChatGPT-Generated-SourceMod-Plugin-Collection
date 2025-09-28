/*  Ricochet Menu Key Bridge (No HamSandwich)
 *
 *  Purpose:
 *    Restores number-key menu selection by translating slot1..slot10 into
 *    "menuselect <n>" when a menu is open (e.g., deagsmapmanager vote).
 *
 *  How it works:
 *    - Hooks "slot1".."slot10" client commands.
 *    - If a menu is currently open for the player (old-style show_menu or new AMXX menu),
 *      it issues "menuselect <n>" and swallows the original command.
 *    - If no menu is open, it either lets the original command pass or swallows it,
 *      depending on a cvar (default: let it pass).
 *
 *  Cvars:
 *    rcvote_enable        "1"   // Master on/off
 *    rcvote_only_when_menu "1"  // 1 = bridge only when a menu is open (safe default)
 *                               // 0 = always translate slotX -> menuselect X
 *    rcvote_swallow_when_no_menu "0" // When only_when_menu=1 and no menu is open:
 *                                    // 0 = let slotX pass through, 1 = swallow it
 *
 *  Test Cmd (optional):
 *    rcvote_testmenu      // Opens a 5-choice test menu for the caller
 *
 *  Notes:
 *    - No HamSandwich. AMXX core only.
 *    - Works with plugins that rely on "menuselect" (e.g., deagsmapmanager).
 *    - “0” key is “menuselect 10”.
 */

#include <amxmodx>

#define PLUGIN  "Ricochet Menu Key Bridge"
#define VERSION "1.0"
#define AUTHOR  "gpt"

// Cvars
new g_pEnable;
new g_pOnlyWhenMenu;
new g_pSwallowNoMenu;

// Forward decls
public plugin_init();
public hook_slot1(id);  public hook_slot2(id);  public hook_slot3(id);  public hook_slot4(id);  public hook_slot5(id);
public hook_slot6(id);  public hook_slot7(id);  public hook_slot8(id);  public hook_slot9(id);  public hook_slot10(id);

public plugin_init()
{
    register_plugin(PLUGIN, VERSION, AUTHOR);

    g_pEnable          = register_cvar("rcvote_enable", "1");
    g_pOnlyWhenMenu    = register_cvar("rcvote_only_when_menu", "1");
    g_pSwallowNoMenu   = register_cvar("rcvote_swallow_when_no_menu", "0");

    // Hook slot commands (1..10). 10 corresponds to the "0" key in HL menus.
    register_clcmd("slot1",  "hook_slot1");
    register_clcmd("slot2",  "hook_slot2");
    register_clcmd("slot3",  "hook_slot3");
    register_clcmd("slot4",  "hook_slot4");
    register_clcmd("slot5",  "hook_slot5");
    register_clcmd("slot6",  "hook_slot6");
    register_clcmd("slot7",  "hook_slot7");
    register_clcmd("slot8",  "hook_slot8");
    register_clcmd("slot9",  "hook_slot9");
    register_clcmd("slot10", "hook_slot10");

    // Small test tool to confirm behavior in-game (type in client console):
    register_clcmd("rcvote_testmenu", "cmd_testmenu");
}

/* Helpers */

stock bool:is_enabled()
{
    return get_pcvar_num(g_pEnable) != 0;
}

// Returns true if user currently has any menu open (old-style "show_menu" or new AMXX menu)
stock bool:user_has_menu_open(id)
{
    new keys, menuId;
    if (!is_user_connected(id))
        return false;

    // get_user_menu returns 0 if no menu is open
    return (get_user_menu(id, keys, menuId) != 0);
}

// Core translator
stock handle_slot_to_menuselect(id, const selection)
{
    if (!is_enabled())
        return PLUGIN_CONTINUE;

    new onlyWhenMenu = get_pcvar_num(g_pOnlyWhenMenu);
    new swallowWhenNo = get_pcvar_num(g_pSwallowNoMenu);

    if (onlyWhenMenu)
    {
        if (user_has_menu_open(id))
        {
            // A menu is open — send menuselect and swallow original command
            client_cmd(id, "menuselect %d", selection);
            return PLUGIN_HANDLED;
        }
        // No menu is open:
        // Either swallow or let it pass (default: let pass)
        return swallowWhenNo ? PLUGIN_HANDLED : PLUGIN_CONTINUE;
    }
    else
    {
        // Always translate slotX to menuselect X
        client_cmd(id, "menuselect %d", selection);
        return PLUGIN_HANDLED;
    }
}

/* Slot handlers */

public hook_slot1(id)  { return handle_slot_to_menuselect(id, 1);  }
public hook_slot2(id)  { return handle_slot_to_menuselect(id, 2);  }
public hook_slot3(id)  { return handle_slot_to_menuselect(id, 3);  }
public hook_slot4(id)  { return handle_slot_to_menuselect(id, 4);  }
public hook_slot5(id)  { return handle_slot_to_menuselect(id, 5);  }
public hook_slot6(id)  { return handle_slot_to_menuselect(id, 6);  }
public hook_slot7(id)  { return handle_slot_to_menuselect(id, 7);  }
public hook_slot8(id)  { return handle_slot_to_menuselect(id, 8);  }
public hook_slot9(id)  { return handle_slot_to_menuselect(id, 9);  }
public hook_slot10(id) { return handle_slot_to_menuselect(id, 10); } // "0" key

/* Optional: a quick test menu you can open from client console with "rcvote_testmenu" */
public cmd_testmenu(id)
{
    if (!is_user_connected(id)) return PLUGIN_HANDLED;

    new keys = (1<<0)|(1<<1)|(1<<2)|(1<<3)|(1<<4) // 1..5
             | (1<<9);                            // 0 key as "10" (often "Exit")

    static body[256];
    formatex(body, charsmax(body),
        "\rRCVote Test Menu^n^n\
        \y1.\w Option One^n\
        \y2.\w Option Two^n\
        \y3.\w Option Three^n\
        \y4.\w Option Four^n\
        \y5.\w Option Five^n^n\
        \y0.\w Exit");

    // Show for 15 seconds; menuid label helps tools/debuggers but is not required
    show_menu(id, keys, body, 15, "RCVoteTest");

    return PLUGIN_HANDLED;
}
