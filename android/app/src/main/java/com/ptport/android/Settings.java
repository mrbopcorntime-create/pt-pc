package com.ptport.android;

import android.content.Context;
import android.content.SharedPreferences;

/** The launcher's own choices; the game's settings stay in data/pt.ini (its PC settings page, Back/Share on a controller). */
final class Settings {
    static final int[] HEIGHTS = {720, 900, 1080, 1440, 0};

    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    /** "turnip" (Mesa, bundled) or "system" (the phone's own driver, BC textures patched in where adrenotools can) */
    String driver() {
        return prefs.getString("driver", "turnip");
    }

    void setDriver(String driver) {
        prefs.edit().putString("driver", driver).apply();
    }

    /** the most lines the game renders before FSR and the compositor scale it to the screen; 0 is the screen's own */
    int maxHeight() {
        return prefs.getInt("max_height", 1080);
    }

    void setMaxHeight(int height) {
        prefs.edit().putInt("max_height", height).apply();
    }

    static String heightName(int height) {
        return height == 0 ? "Screen resolution" : height + "p";
    }
}
