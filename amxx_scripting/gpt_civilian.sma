#include <amxmodx>
#include <amxmisc>
#include <engine>

#define PC_CIVILIAN 11

public plugin_init() {
    register_plugin("TFC Civilian Class Changer", "1.0", "ChatGPT");
    register_clcmd("say !civ", "change_to_civilian");
    register_clcmd("say !civilian", "change_to_civilian");
    register_clcmd("amxx_civilian", "change_to_civilian");
}

public change_to_civilian(id) {
    if (!is_user_alive(id)) {
        client_print(id, print_chat, "You must be alive to change class.");
        return PLUGIN_HANDLED;
    }
    
    set_user_class(id, PC_CIVILIAN);
    user_kill(id);
    client_print(id, print_chat, "You are now a Civilian.");
    
    return PLUGIN_HANDLED;
}

stock set_user_class(id, class) {
    entity_set_int(id, EV_INT_playerclass, class);
}
