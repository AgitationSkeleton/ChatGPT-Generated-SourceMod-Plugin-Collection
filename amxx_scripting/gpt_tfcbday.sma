#include <amxmodx>

public plugin_init()
{
    register_plugin("TFC Birthday Setter", "1.0", "ChatGPT")
    set_task(1.0, "check_birthday")
}

public check_birthday()
{
    new time_now = get_systime()

    // Adjust for Pacific Time: PDT (UTC-7) during August
    time_now -= 7 * 60 * 60

    // Format time as YYYYMMDD
    new date_str[16]
    format_time(date_str, charsmax(date_str), "%Y%m%d", time_now)

    // Extract substrings manually
    new year_str[5], month_str[3], day_str[3]
    copy(year_str, 5, date_str[0])     // first 4 chars
    copy(month_str, 3, date_str[4])    // next 2 chars
    copy(day_str, 3, date_str[6])      // final 2 chars

    // Convert to numbers
    new month = str_to_num(month_str)
    new day = str_to_num(day_str)

    if (month == 8 && day == 3)
    {
        server_cmd("tfc_birthday 1")
        server_print("[TFC Birthday Setter] tfc_birthday set to 1 for August 3 (PST/PDT).")
    }
    else
    {
        server_print("[TFC Birthday Setter] Not August 3; tfc_birthday not set.")
    }
}
