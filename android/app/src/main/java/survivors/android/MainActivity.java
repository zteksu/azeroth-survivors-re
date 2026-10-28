package survivors.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * The whole app: a launcher screen that boots straight into the game, and a setup screen behind
 * it (Back or Start + Select while booting, or the in-game menu). Booting unpacks the web build,
 * starts the bundled wenilla-host on loopback against the game data on the device, and shows
 * http://127.0.0.1:8090/ full-screen in a WebView. The page is the one `make start` serves on a
 * desktop plus android.js, the controller/touch/menu layer; the game itself knows nothing of
 * Android.
 */
public class MainActivity extends Activity {
    private static final String TAG = "survivors";
    /** Fixed, not random: the page keeps its pick screen choice in localStorage, keyed by origin. */
    private static final int PORT = 8090;
    private static final String URL = "http://127.0.0.1:" + PORT + "/";
    private static final int PICK_FOLDER = 1;

    private static final int BG = Color.rgb(0x14, 0x0e, 0x08);
    private static final int GOLD = Color.rgb(0xff, 0xd1, 0x00);
    private static final int TEXT = Color.rgb(0xe8, 0xd8, 0xb0);
    private static final int DIM = Color.rgb(0x9a, 0x8a, 0x6a);
    private static final int RED = Color.rgb(0xff, 0x6a, 0x50);

    private Process host;
    private WebView web;
    private PadInput pad;
    private TextView status;
    /** Booting toward the game; Back or Start + Select turns it toward setup instead. */
    private volatile boolean booting;
    private volatile boolean setupRequested;
    private boolean onSetup;
    private boolean startHeld, selectHeld;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) web.onResume();
        // Back from the all-files-access settings page, or after copying the data over.
        if (onSetup) showSetup();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        stopHost();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    // ── Input ──

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (web != null && pad != null) {
            if (pad.key(e)) return true;
            // The system Back (button or gesture) opens and closes the in-game menu.
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    web.evaluateJavascript("window.survivorsAndroid&&survivorsAndroid.back()", null);
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (booting) {
            int k = e.getKeyCode();
            boolean isDown = e.getAction() == KeyEvent.ACTION_DOWN;
            if (k == KeyEvent.KEYCODE_BUTTON_START) startHeld = isDown;
            if (k == KeyEvent.KEYCODE_BUTTON_SELECT) selectHeld = isDown;
            if ((startHeld && selectHeld) || (k == KeyEvent.KEYCODE_BACK && !isDown)
                    || (k == KeyEvent.KEYCODE_BUTTON_B && !isDown)) {
                requestSetup();
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (web != null && pad != null && pad.motion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    private void requestSetup() {
        if (setupRequested) return;
        setupRequested = true;
        if (status != null) status.setText("Opening setup…");
    }

    // ── Boot ──

    private void boot() {
        booting = true;
        setupRequested = false;
        onSetup = false;
        showSplash("Starting…");
        new Thread(() -> {
            try {
                File data = DataFolder.find(this);
                if (data == null || DataFolder.check(data).error != null || setupRequested) {
                    runOnUiThread(this::showSetup);
                    return;
                }
                File www = unpackWeb();
                if (setupRequested) {
                    runOnUiThread(this::showSetup);
                    return;
                }
                startHost(www, data);
                runOnUiThread(() -> {
                    if (setupRequested) showSetup();
                    else showGame();
                });
            } catch (Exception e) {
                Log.e(TAG, "boot failed", e);
                String log = Files.tail(new File(getFilesDir(), "host.log"), 4000);
                runOnUiThread(() -> showError("Could not start the game server.\n\n" + e.getMessage()
                        + (log.isEmpty() ? "" : "\n\nhost.log:\n" + log)));
            }
        }, "boot").start();
    }

    /** Copies assets/www to files/www once per installed APK (an update re-copies it). */
    private File unpackWeb() throws Exception {
        File www = new File(getFilesDir(), "www");
        File stamp = new File(getFilesDir(), "www.stamp");
        String want = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        if (www.isDirectory() && want.equals(Files.read(stamp))) return www;
        setStatus("Unpacking the game (first launch after an install)…");
        Files.deleteTree(www);
        Files.copyAssets(getAssets(), "www", www);
        Files.write(stamp, want);
        return www;
    }

    private void startHost(File www, File data) throws Exception {
        if (host != null && host.isAlive()) return;
        killStaleHost();
        setStatus("Opening the game data…");
        File exe = new File(getApplicationInfo().nativeLibraryDir, "libwenillahost.so");
        File log = new File(getFilesDir(), "host.log");
        ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(),
                "--www", www.getAbsolutePath(),
                "--data", data.getAbsolutePath(),
                "--bind", "127.0.0.1:" + PORT);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);
        host = pb.start();
        Files.write(new File(getFilesDir(), "host.pid"), String.valueOf(pid(host)));
        Log.i(TAG, "wenilla-host started, data=" + data);

        // The chain opens every MPQ before the socket binds; give a slow card a while.
        for (int i = 0; i < 600; i++) {
            if (!host.isAlive()) throw new IOException("wenilla-host exited with code " + host.exitValue());
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 200);
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IOException("wenilla-host did not open port " + PORT + " within a minute");
    }

    /** A host left behind by a killed app process still holds the port; it runs as our uid. */
    private void killStaleHost() {
        File pidFile = new File(getFilesDir(), "host.pid");
        try {
            int pid = Integer.parseInt(Files.read(pidFile).trim());
            if (pid > 0) android.os.Process.killProcess(pid);
            Thread.sleep(200);
        } catch (Exception ignored) {
        }
        pidFile.delete();
    }

    private void stopHost() {
        if (host != null) {
            host.destroy();
            host = null;
        }
        new File(getFilesDir(), "host.pid").delete();
    }

    // ── The page's side: `window.AndroidHost` ──

    private final class Bridge {
        @JavascriptInterface
        public void exit() {
            runOnUiThread(() -> {
                stopHost();
                finishAndRemoveTask();
            });
        }

        @JavascriptInterface
        public void setup() {
            runOnUiThread(MainActivity.this::showSetup);
        }

        @JavascriptInterface
        public String version() {
            return versionLine();
        }
    }

    // ── Screens ──

    private void showGame() {
        booting = false;
        onSetup = false;
        WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect from a PC, for logs
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(), "AndroidHost");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "page: " + m.message());
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript("!!navigator.gpu", v -> {
                    if (!"true".equals(v)) noWebGpu();
                });
            }
        });
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        pad = new PadInput(web);
        setContentView(web);
        web.requestFocus();
        immersive();
        web.loadUrl(URL);
    }

    /** The world needs WebGPU; an old Android System WebView doesn't have it, Chrome does. */
    private void noWebGpu() {
        new AlertDialog.Builder(this)
                .setTitle("No WebGPU in this WebView")
                .setMessage("The game needs WebGPU. Update \"Android System WebView\" from the Play "
                        + "Store, or play in Chrome instead: keep this app open in the background, "
                        + "it is the game's server.")
                .setPositiveButton("Open in Chrome", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(URL));
                    i.setPackage("com.android.chrome");
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        i.setPackage(null);
                        startActivity(i);
                    }
                })
                .setNegativeButton("Stay here", null)
                .show();
    }

    /** The launcher: the title, one status line, and how to get to setup. */
    private void showSplash(String text) {
        LinearLayout col = column();
        col.addView(title());
        status = label(text, TEXT, 16);
        status.setGravity(Gravity.CENTER);
        col.addView(status);
        TextView hint = label("Back or Start + Select: setup", DIM, 13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(24), 0, 0);
        col.addView(hint);
        setScreen(col);
    }

    /** Game folder, what the data check found, and the ways to fix it. */
    private void showSetup() {
        booting = false;
        onSetup = true;
        stopHost();
        String picked = DataFolder.picked(this);
        File data = DataFolder.find(this);
        DataFolder.Check check = data == null ? null : DataFolder.check(data);
        boolean access = DataFolder.hasAllFilesAccess();

        LinearLayout col = column();
        col.addView(title());
        col.addView(label("Game folder", GOLD, 15));
        col.addView(label(data != null ? data.getAbsolutePath()
                : picked != null ? picked + "  (no .MPQ files found)" : "Not chosen yet", TEXT, 15));
        if (check != null && check.error == null) {
            col.addView(label("✓ World of Warcraft 1.12.1 data, " + check.archives + " archives", GOLD, 15));
        }
        if (check != null && check.error != null) col.addView(label(check.error, RED, 15));
        if (check != null && check.warning != null) col.addView(label(check.warning, TEXT, 14));
        if (data == null) {
            col.addView(label("Choose your WoW 1.12.1 folder: the one holding Data/ with the .MPQ "
                    + "files, or the Data folder itself. The app then asks for \"All files access\" "
                    + "so the game can read it.", TEXT, 15));
        } else if (!access && picked != null) {
            col.addView(label("Allow \"All files access\" so the game can read this folder.", TEXT, 15));
        }

        Button first = null;
        if (check != null && check.error == null) {
            first = button("Play", v -> boot());
            col.addView(first);
        }
        Button choose = button(data == null ? "Choose game folder" : "Choose another folder", v -> pickFolder());
        col.addView(choose);
        if (first == null) first = choose;
        if (!access) {
            col.addView(button("Grant all files access", v -> requestAllFilesAccess()));
        }
        col.addView(button("Exit", v -> finishAndRemoveTask()));

        File priv = getExternalFilesDir(null);
        col.addView(label("No permission at all: copy Data into the app's own folder from a PC with\n"
                + "adb push wow-data/Data " + (priv == null ? "…" : priv.getAbsolutePath()) + "/", DIM, 13));
        col.addView(label(versionLine(), DIM, 12));
        setScreen(col);
        first.requestFocus(); // a controller's A works without touching the screen
    }

    private void showError(String text) {
        booting = false;
        LinearLayout col = column();
        col.addView(title());
        col.addView(label(text, RED, 14));
        Button retry = button("Retry", v -> {
            stopHost();
            boot();
        });
        col.addView(retry);
        col.addView(button("Setup", v -> showSetup()));
        setScreen(col);
        retry.requestFocus();
    }

    private String versionLine() {
        String v = "?";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return "Azeroth Survivors for Android " + v + " · local wenilla-host on 127.0.0.1:" + PORT;
    }

    private void setStatus(String text) {
        runOnUiThread(() -> {
            if (status != null) status.setText(setupRequested ? "Opening setup…" : text);
        });
    }

    // ── Folder picker ──

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
        } catch (Exception e) {
            showError("This device has no folder picker. Copy the data to /sdcard/wow-data/Data "
                    + "instead.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || intent == null) return;
        String path = DataFolder.pathFromTreeUri(this, intent.getData());
        if (path == null) {
            showError("Could not turn that folder into a file path. Pick a folder on the internal "
                    + "storage or the SD card.");
            return;
        }
        DataFolder.setPicked(this, path);
        Log.i(TAG, "picked data folder " + path);
        // onResume redraws setup with the new folder, after the access page if it is needed.
        if (!DataFolder.hasAllFilesAccess()) requestAllFilesAccess();
    }

    private void requestAllFilesAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ── Views ──

    private void setScreen(LinearLayout col) {
        if (web != null) {
            web.destroy();
            web = null;
            pad = null;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.addView(col);
        setContentView(scroll);
        immersive();
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(48), dp(24), dp(48), dp(24));
        return col;
    }

    private TextView title() {
        TextView t = label("Azeroth Survivors", GOLD, 30);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setShadowLayer(6, 0, 2, Color.BLACK);
        t.setPadding(0, 0, 0, dp(16));
        return t;
    }

    private TextView label(String text, int color, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTextIsSelectable(false);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setMaxWidth(dp(720));
        return t;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(GOLD);
        b.setTextSize(17);
        b.setTypeface(Typeface.SERIF);
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(Color.rgb(0x3a, 0x12, 0x0c));
        idle.setStroke(dp(2), Color.rgb(0x8a, 0x6a, 0x2c));
        idle.setCornerRadius(dp(4));
        b.setBackground(idle);
        b.setOnFocusChangeListener((v, has) -> idle.setStroke(dp(2), has ? GOLD : Color.rgb(0x8a, 0x6a, 0x2c)));
        b.setOnClickListener(onClick);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(320), dp(52));
        lp.topMargin = dp(10);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /** java.lang.Process has no pid() before API 33; Android's implementation keeps it in a field. */
    private static int pid(Process p) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                return (int) (long) Process.class.getMethod("pid").invoke(p);
            } catch (Exception ignored) {
            }
        }
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }
}
