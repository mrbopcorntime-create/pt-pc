package com.ptport.android;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import org.libsdl.app.SDLActivity;

import java.io.File;

/**
 * The game: SDL's activity running SDL_main from libmain.so (the port's src/main.cpp). The launcher has unpacked the
 * runtime files and the game archives; the choices it offers reach the game as environment variables, read by
 * src/engine/core/resource_path.cpp (PT_ANDROID_ROOT) and src/engine/render/android_vulkan.cpp and vk_context.cpp
 * (the driver and PT_ANDROID_MAX_HEIGHT).
 */
public class PtActivity extends SDLActivity {
    private static final String TAG = "PtActivity";

    static void setenv(String name, String value) {
        try {
            Os.setenv(name, value, true);
        } catch (ErrnoException e) {
            Log.e(TAG, "setenv " + name, e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Settings settings = new Settings(this);
        File root = GameFiles.root(this);
        setenv("PT_ANDROID_ROOT", root.getAbsolutePath());
        /* 0 is the screen's own resolution: a cap no screen reaches */
        setenv("PT_ANDROID_MAX_HEIGHT", Integer.toString(settings.maxHeight() == 0 ? 100000 : settings.maxHeight()));
        boolean turnip = settings.driver().equals("turnip") && GameFiles.haveTurnip(this);
        setenv("PT_DRIVER", turnip ? "turnip" : "system");
        if (turnip) {
            setenv("PT_TURNIP_DIR", GameFiles.turnipDir(this).getAbsolutePath() + "/");
            setenv("PT_TURNIP_NAME", GameFiles.TURNIP_NAME);
            setenv("PT_ADRENOTOOLS_HOOKS", getApplicationInfo().nativeLibraryDir + "/");
        }
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"SDL3", "main"};
    }

    @Override
    protected String[] getArguments() {
        File game = GameFiles.gameDir(this);
        return new String[] {"--game", game.getAbsolutePath()};
    }
}
