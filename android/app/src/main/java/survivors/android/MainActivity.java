package survivors.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The whole app: unpack the web build, start the bundled wenilla-host on loopback against the
 * game data on the device, and show http://127.0.0.1:8090/ full-screen in a WebView. The page is
 * the same one `make start` serves on a desktop; nothing in the game knows it is on Android.
 */
public class MainActivity extends Activity {
    private static final String TAG = "survivors";
    /** Fixed, not random: the page keeps its pick screen choice in localStorage, keyed by origin. */
    private static final int PORT = 8090;
    private static final String URL = "http://127.0.0.1:" + PORT + "/";

    private Process host;
    private WebView web;
    private TextView status;
    private boolean waitingForData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showStatus("Starting…", false);
        boot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) web.onResume();
        // Back from the all-files-access settings page, or after copying the data over.
        if (waitingForData) boot();
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

    /** Back (and a controller's B, which Android turns into Back when unhandled) is the game's Esc. */
    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE));
    }

    // ── Boot ──

    private void boot() {
        waitingForData = false;
        new Thread(() -> {
            try {
                File data = findData();
                if (data == null) {
                    waitingForData = true;
                    runOnUiThread(this::showDataHelp);
                    return;
                }
                File www = unpackWeb();
                startHost(www, data);
                runOnUiThread(this::showGame);
            } catch (Exception e) {
                Log.e(TAG, "boot failed", e);
                String log = tail(new File(getFilesDir(), "host.log"), 4000);
                runOnUiThread(() -> showStatus("Could not start the game server.\n\n" + e.getMessage()
                        + (log.isEmpty() ? "" : "\n\nhost.log:\n" + log), true));
            }
        }, "boot").start();
    }

    /** The first folder holding the client's MPQs. App-private first: it needs no permission. */
    private File findData() {
        List<File> roots = new ArrayList<>();
        File priv = getExternalFilesDir(null);
        if (priv != null) roots.add(priv);
        File sd = Environment.getExternalStorageDirectory();
        roots.add(new File(sd, "wow-data"));
        roots.add(new File(sd, "WoW"));
        roots.add(new File(sd, "wow"));
        for (File root : roots) {
            for (File dir : new File[] {new File(root, "Data"), new File(root, "wow-data/Data"), root}) {
                if (hasMpq(dir)) return dir;
            }
        }
        return null;
    }

    private static boolean hasMpq(File dir) {
        String[] names = dir.list();
        if (names == null) return false;
        for (String n : names) {
            if (n.toLowerCase().endsWith(".mpq")) return true;
        }
        return false;
    }

    /** Copies assets/www to files/www once per installed APK (an update re-copies it). */
    private File unpackWeb() throws Exception {
        File www = new File(getFilesDir(), "www");
        File stamp = new File(getFilesDir(), "www.stamp");
        String want = String.valueOf(getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime);
        if (www.isDirectory() && want.equals(read(stamp))) return www;
        runOnUiThread(() -> showStatus("Unpacking the game (first launch)…", false));
        deleteTree(www);
        copyAssets(getAssets(), "www", www);
        write(stamp, want);
        return www;
    }

    private void startHost(File www, File data) throws Exception {
        killStaleHost();
        runOnUiThread(() -> showStatus("Opening the game data…", false));
        File exe = new File(getApplicationInfo().nativeLibraryDir, "libwenillahost.so");
        File log = new File(getFilesDir(), "host.log");
        ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(),
                "--www", www.getAbsolutePath(),
                "--data", data.getAbsolutePath(),
                "--bind", "127.0.0.1:" + PORT);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log);
        host = pb.start();
        write(new File(getFilesDir(), "host.pid"), String.valueOf(pid(host)));
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
            int pid = Integer.parseInt(read(pidFile).trim());
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

    // ── Screens ──

    private void showGame() {
        waitingForData = false;
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
                        + "Store, or play in Chrome instead — keep this app open in the background, "
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

    private void showDataHelp() {
        File priv = getExternalFilesDir(null);
        String privPath = priv == null ? "(unavailable)" : priv.getAbsolutePath() + "/Data";
        String text = "Game data not found.\n\n"
                + "Copy the WoW 1.12.1 client's Data folder (the one `make fetch-data` puts in "
                + "wow-data/Data, with the .MPQ files inside) to either:\n\n"
                + "  • " + privPath + "\n"
                + "    no permission needed. From a PC:\n"
                + "    adb push wow-data/Data " + (priv == null ? "…" : priv.getAbsolutePath()) + "/\n\n"
                + "  • /sdcard/wow-data/Data\n"
                + "    easy to copy with a file manager, but needs \"All files access\" below.\n\n"
                + "This screen retries every time you come back to the app.";
        LinearLayout col = column();
        col.addView(label(text));
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            col.addView(button("Grant all files access", v -> {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }));
        }
        col.addView(button("Retry", v -> boot()));
        setScreen(col);
    }

    private void showStatus(String text, boolean retry) {
        LinearLayout col = column();
        status = label(text);
        col.addView(status);
        if (retry) col.addView(button("Retry", v -> {
            stopHost();
            boot();
        }));
        setScreen(col);
    }

    private void setScreen(LinearLayout col) {
        if (web != null) {
            web.destroy();
            web = null;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(0x1b, 0x14, 0x10));
        scroll.setFillViewport(true);
        scroll.addView(col);
        setContentView(scroll);
        immersive();
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int pad = (int) (32 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);
        return col;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(0xe8, 0xd8, 0xb0));
        t.setTextSize(16);
        t.setTextIsSelectable(true);
        return t;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(onClick);
        b.setFocusable(true);
        return b;
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

    // ── Files ──

    private static void copyAssets(AssetManager am, String path, File dest) throws IOException {
        String[] kids = am.list(path);
        if (kids != null && kids.length > 0) {
            if (!dest.mkdirs() && !dest.isDirectory()) throw new IOException("mkdir " + dest);
            for (String k : kids) copyAssets(am, path + "/" + k, new File(dest, k));
            return;
        }
        try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String tail(File f, int max) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            long start = Math.max(0, r.length() - max);
            r.seek(start);
            byte[] b = new byte[(int) (r.length() - start)];
            r.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
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
