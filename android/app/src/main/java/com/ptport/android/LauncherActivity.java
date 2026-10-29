package com.ptport.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * First screen: unpacks the runtime files, imports the game archives from the player's fake PKG (picked with the system
 * file picker, so the app needs no storage permission), offers the driver and resolution choices, starts the game and
 * shares pt.log for bug reports.
 */
public class LauncherActivity extends Activity {
    private static final int PICK_PKG = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button play, importPkg, driver, resolution, shareLog;
    private Settings settings;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new Settings(this);
        buildLayout();
        busy = true;
        refresh();
        status.setText("Preparing game files...");
        new Thread(() -> {
            String error = null;
            try {
                GameFiles.unpackRuntime(this);
            } catch (IOException e) {
                error = "Could not unpack the game's runtime files: " + e.getMessage();
            }
            final String message = error;
            ui.post(() -> {
                busy = false;
                refresh();
                if (message != null) status.setText(message);
            });
        }, "unpack").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null && !busy) refresh();
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 12;
        parent.addView(b, lp);
        return b;
    }

    private void buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(48, 48, 48, 48);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(column);

        TextView title = new TextView(this);
        title.setText("P.T. Port for Android");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.SERIF);
        column.addView(title);

        TextView note = new TextView(this);
        note.setText("Unofficial port of the open-source P.T. PC port (MIT). Runs on your own copy of P.T.; "
                + "no game data is included. Not affiliated with Konami or Kojima Productions.");
        note.setTextColor(0xFF888888);
        note.setTextSize(12);
        column.addView(note);

        status = new TextView(this);
        status.setTextColor(0xFFDDDDDD);
        status.setTextSize(15);
        status.setPadding(0, 32, 0, 8);
        column.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        column.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        play = button(column, "Play", v -> startActivity(new Intent(this, PtActivity.class)));
        importPkg = button(column, "Import game from fake PKG...", v -> pickPkg());
        driver = button(column, "", v -> {
            settings.setDriver(settings.driver().equals("turnip") ? "system" : "turnip");
            refresh();
        });
        resolution = button(column, "", v -> {
            int current = settings.maxHeight(), next = Settings.HEIGHTS[0];
            for (int i = 0; i < Settings.HEIGHTS.length; ++i) {
                if (Settings.HEIGHTS[i] == current) next = Settings.HEIGHTS[(i + 1) % Settings.HEIGHTS.length];
            }
            settings.setMaxHeight(next);
            refresh();
        });
        shareLog = button(column, "Share log", v -> shareLog());

        TextView help = new TextView(this);
        help.setText("Controller: Options/Start pauses. Back/Share (or View) opens the PC settings page, where FSR 1 "
                + "and graphics options live. Clicking both sticks together stands in for saying the word.\n\n"
                + "If the game crashes or looks wrong, start it once more, then come back here and use Share log.");
        help.setTextColor(0xFF888888);
        help.setTextSize(13);
        help.setPadding(0, 32, 0, 0);
        column.addView(help);
        setContentView(scroll);
    }

    private void refresh() {
        boolean game = GameFiles.haveGame(this);
        boolean turnipBundled = GameFiles.bundledTurnip(this);
        play.setEnabled(game && !busy);
        importPkg.setEnabled(!busy);
        importPkg.setText(game ? "Re-import game from fake PKG..." : "Import game from fake PKG...");
        driver.setEnabled(!busy && turnipBundled);
        String chosen = settings.driver().equals("turnip") && turnipBundled ? "Turnip (Mesa, bundled)" : "System driver";
        driver.setText("Graphics driver: " + chosen);
        resolution.setEnabled(!busy);
        resolution.setText("Render resolution cap: " + Settings.heightName(settings.maxHeight()));
        shareLog.setEnabled(!busy);
        if (!busy) {
            String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE;
            status.setText((game ? "Game files ready." : "Import your P.T. fake PKG to begin (about 1.3 GB is written).")
                    + "\nDevice: " + Build.MODEL + " (" + soc + ")");
        }
    }

    private void pickPkg() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_PKG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PKG || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importFrom(data.getData());
    }

    private void importFrom(Uri uri) {
        busy = true;
        refresh();
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("Opening the package...");
        new Thread(() -> {
            String message;
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                 FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileChannel channel = in.getChannel()) {
                PkgExtractor.Result result = PkgExtractor.extract(channel, GameFiles.gameDir(this), (step, done, total) -> ui.post(() -> {
                    status.setText(step);
                    progress.setProgress(total > 0 ? (int) (1000 * done / total) : 0);
                }));
                try (FileWriter source = new FileWriter(new File(GameFiles.gameDir(this), "source.txt"))) {
                    source.write("content_id=" + result.contentId + "\nsource=pkg\n");
                    for (String w : result.warnings) source.write("warning=" + w + "\n");
                }
                message = "Imported " + result.contentId + (result.warnings.isEmpty() ? "." : "\n" + String.join("\n", result.warnings));
            } catch (IOException | RuntimeException e) {
                message = "Import failed: " + e.getMessage();
            }
            final String text = message;
            ui.post(() -> {
                busy = false;
                progress.setVisibility(View.GONE);
                refresh();
                status.setText(text);
            });
        }, "import").start();
    }

    /** pt.log followed by the app's own Android log (startup errors before pt.log exists, Java exceptions) */
    private File bundleLog() throws IOException {
        File dir = GameFiles.dataDir(this);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File out = new File(dir, "pt-android-report.txt");
        try (FileOutputStream sink = new FileOutputStream(out)) {
            String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : Build.HARDWARE;
            sink.write(("device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + soc + "), Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")\ndriver choice: " + settings.driver() + ", resolution cap: "
                    + Settings.heightName(settings.maxHeight()) + "\n\n===== pt.log =====\n").getBytes(StandardCharsets.UTF_8));
            File log = GameFiles.logFile(this);
            if (log.isFile()) Files.copy(log.toPath(), sink);
            else sink.write("(no pt.log yet)\n".getBytes(StandardCharsets.UTF_8));
            sink.write("\n===== Android log (this app) =====\n".getBytes(StandardCharsets.UTF_8));
            try {
                Process logcat = new ProcessBuilder("logcat", "-d", "-t", "3000", "-v", "time").redirectErrorStream(true).start();
                try (InputStream in = logcat.getInputStream()) {
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) sink.write(buffer, 0, n);
                }
            } catch (IOException e) {
                sink.write(("logcat unavailable: " + e.getMessage() + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private void shareLog() {
        File log;
        try {
            log = bundleLog();
        } catch (IOException e) {
            status.setText("Could not gather the log: " + e.getMessage());
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".logs", log);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "P.T. Android log");
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share pt.log"));
    }
}
