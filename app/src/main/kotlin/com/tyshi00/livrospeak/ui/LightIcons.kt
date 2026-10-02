package com.tyshi00.livrospeak.ui

import com.tyshi00.livrospeak.R

/**
 * Trimmed down from the full light-sdk LightIcons enum (which has ~150+
 * entries) to only what this app uses. Same structure/naming as the
 * original so it's a drop-in superset if more icons are added later --
 * just copy more drawables into res/drawable and add more objects here.
 */
sealed class LightIconConfiguration(
    val name: String,
    val darkModeResource: Int,
    val lightModeResource: Int,
)

object LightIcons {
    object DOWNLOADED_ARROW : LightIconConfiguration(
        name = "downloaded arrow",
        darkModeResource = R.drawable.ic_downloaded_arrow_white,
        lightModeResource = R.drawable.ic_downloaded_arrow_black,
    )
    object DOWNLOAD_ARROW : LightIconConfiguration(
        name = "download arrow",
        darkModeResource = R.drawable.ic_download_arrow_white,
        lightModeResource = R.drawable.ic_download_arrow_black,
    )
    object AUDIO_MESSAGE : LightIconConfiguration(
        name = "audio message",
        darkModeResource = R.drawable.ic_audio_message_white,
        lightModeResource = R.drawable.ic_audio_message_black
    )
    object PLAY : LightIconConfiguration(
        name = "play",
        darkModeResource = R.drawable.ic_play_white,
        lightModeResource = R.drawable.ic_play_black
    )
    object PAUSE : LightIconConfiguration(
        name = "pause",
        darkModeResource = R.drawable.ic_pause_white,
        lightModeResource = R.drawable.ic_pause_black
    )
    object REWIND : LightIconConfiguration(
        name = "rewind",
        darkModeResource = R.drawable.ic_rewind_white,
        lightModeResource = R.drawable.ic_rewind_black
    )
    object FAST_FORWARD : LightIconConfiguration(
        name = "fast-forward",
        darkModeResource = R.drawable.ic_fast_forward_white,
        lightModeResource = R.drawable.ic_fast_forward_black
    )
    object SKIP_BACKWARD_FIFTEEN : LightIconConfiguration(
        name = "skip-backward-fifteen",
        darkModeResource = R.drawable.ic_skip_backward_fifteen_white,
        lightModeResource = R.drawable.ic_skip_backward_fifteen_white
    )
    object SKIP_FORWARD_FIFTEEN : LightIconConfiguration(
        name = "skip-forward-fifteen",
        darkModeResource = R.drawable.ic_skip_forward_fifteen_white,
        lightModeResource = R.drawable.ic_skip_forward_fifteen_white
    )
    object BACK : LightIconConfiguration(
        name = "back",
        darkModeResource = R.drawable.ic_back_white,
        lightModeResource = R.drawable.ic_back_black
    )
    object ADD : LightIconConfiguration(
        name = "add",
        darkModeResource = R.drawable.ic_add_white,
        lightModeResource = R.drawable.ic_add_black
    )
    object CLOSE : LightIconConfiguration(
        name = "close",
        darkModeResource = R.drawable.ic_close_white,
        lightModeResource = R.drawable.ic_close_black
    )
    object SETTINGS : LightIconConfiguration(
        name = "settings",
        darkModeResource = R.drawable.ic_settings_white,
        lightModeResource = R.drawable.ic_settings_white
    )
    object LIST : LightIconConfiguration(
        name = "list",
        darkModeResource = R.drawable.ic_list_white,
        lightModeResource = R.drawable.ic_list_white
    )
    object MENU : LightIconConfiguration(
        name = "menu",
        darkModeResource = R.drawable.ic_menu_white,
        lightModeResource = R.drawable.ic_menu_white
    )
    object RENEW : LightIconConfiguration(
        name = "renew",
        darkModeResource = R.drawable.ic_renew_white,
        lightModeResource = R.drawable.ic_renew_white
    )
    object RETURN : LightIconConfiguration(
        name = "return",
        darkModeResource = R.drawable.ic_return_white,
        lightModeResource = R.drawable.ic_return_white
    )
    object SEARCH : LightIconConfiguration(
        name = "search",
        darkModeResource = R.drawable.ic_search_white,
        lightModeResource = R.drawable.ic_search_black
    )
    object TOGGLE_STATE_ON : LightIconConfiguration(
        name = "toggle state on",
        darkModeResource = R.drawable.ic_toggle_state_on_white,
        lightModeResource = R.drawable.ic_toggle_state_on_black
    )
    object TOGGLE_STATE_OFF : LightIconConfiguration(
        name = "toggle state off",
        darkModeResource = R.drawable.ic_toggle_state_off_white,
        lightModeResource = R.drawable.ic_toggle_state_off_black
    )
    object REFRESH : LightIconConfiguration(
        name = "refresh",
        darkModeResource = R.drawable.ic_refresh_white,
        lightModeResource = R.drawable.ic_refresh_black
    )
    /** Invisible spacer -- used by LightTopBar/LightBarButton to keep
     *  layout balanced when a button slot is empty. */
    object SPACER : LightIconConfiguration(
        name = "spacer",
        darkModeResource = R.drawable.ic_spacer,
        lightModeResource = R.drawable.ic_spacer
    )
}
