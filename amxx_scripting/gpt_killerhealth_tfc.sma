#include <amxmodx>
#include <fakemeta>

public plugin_init()
{
    register_plugin("Killer HP/Armor Info", "1.1", "ChatGPT");
    register_event("DeathMsg", "event_death", "a");
}

public event_death()
{
    new killer = read_data(1);
    new victim = read_data(2);

    // Don't report if killed by world or self
    if (killer <= 0 || killer > 32 || killer == victim)
        return;

    if (!is_user_connected(killer) || !is_user_connected(victim))
        return;

    new hp = get_user_health(killer);
    new armor = get_user_armor(killer);

    client_print(victim, print_console, "[Info] Your killer had %d HP and %d armor.", hp, armor);
}
