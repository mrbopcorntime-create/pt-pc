package com.ptport.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Where the game's files live on the phone (docs/android.md):
 *
 *   root()      the app's external files folder: the runtime files from the APK's assets/runtime (shaders, fonts,
 *               extras.pak, licences) and data/ (pt.ini, saves, pt.log). The game takes it as its program folder.
 *   gameDir()   root()/game: the three archives extracted from the player's fake PKG.
 *   turnipDir() the app's private files folder: Mesa's Turnip driver, which adrenotools may only load from there.
 */
final class GameFiles {
    static final String TURNIP_NAME = "vulkan.ad07XX.so";

    private GameFiles() {}

    static File root(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir != null ? dir : context.getFilesDir();
    }

    static File gameDir(Context context) {
        return new File(root(context), "game");
    }

    static File dataDir(Context context) {
        return new File(root(context), "data");
    }

    static File logFile(Context context) {
        return new File(dataDir(context), "pt.log");
    }

    static File turnipDir(Context context) {
        return new File(context.getFilesDir(), "turnip");
    }

    static boolean haveGame(Context context) {
        for (String name : new String[] {"chunk1.psarc", "texture.qar"}) {
            if (!new File(gameDir(context), name).isFile()) return false;
        }
        return true;
    }

    static boolean haveTurnip(Context context) {
        return new File(turnipDir(context), TURNIP_NAME).isFile();
    }

    static boolean bundledTurnip(Context context) {
        try (InputStream in = context.getAssets().open("turnip/" + TURNIP_NAME)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static long installStamp(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Unpacks assets/runtime into root() and the bundled Turnip into turnipDir() once per installed APK. */
    static void unpackRuntime(Context context) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE);
        long stamp = installStamp(context);
        if (prefs.getLong("unpacked", -1) == stamp && new File(root(context), "shaders").isDirectory()) return;
        AssetManager assets = context.getAssets();
        copyTree(assets, "runtime", root(context));
        File turnip = turnipDir(context);
        if (bundledTurnip(context)) {
            if (!turnip.isDirectory() && !turnip.mkdirs()) throw new IOException("cannot create " + turnip);
            copy(assets, "turnip/" + TURNIP_NAME, new File(turnip, TURNIP_NAME));
        }
        prefs.edit().putLong("unpacked", stamp).apply();
    }

    private static void copyTree(AssetManager assets, String path, File target) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            copy(assets, path, target);
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
        for (String child : children) copyTree(assets, path + "/" + child, new File(target, child));
    }

    private static void copy(AssetManager assets, String path, File target) throws IOException {
        File part = new File(target.getPath() + ".part");
        try (InputStream in = assets.open(path); OutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
        if (target.exists() && !target.delete()) throw new IOException("cannot replace " + target);
        if (!part.renameTo(target)) throw new IOException("cannot rename " + part);
    }
}
