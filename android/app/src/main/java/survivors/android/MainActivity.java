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
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
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
    private static final int PICK_FOLDER = 1;
    private static final String PREFS = "survivors";
    private static final String KEY_DATA = "dataPath";

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

    /** The folder picked in the app first, then the first well-known place holding the MPQs. */
    private File findData() {
        String picked = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DATA, null);
        if (picked != null) {
            File dir = dataIn(new File(picked));
            if (dir != null) return dir;
        }
        List<File> roots = new ArrayList<>();
        File priv = getExternalFilesDir(null);
        if (priv != null) roots.add(priv);
        File sd = Environment.getExternalStorageDirectory();
        roots.add(new File(sd, "wow-data"));
        roots.add(new File(sd, "WoW"));
        roots.add(new File(sd, "wow"));
        for (File root : roots) {
            File dir = dataIn(root);
            if (dir != null) return dir;
        }
        return null;
    }

    /** `root/Data`, `root/wow-data/Data` or `root` itself, whichever holds MPQs. */
    private static File dataIn(File root) {
        for (File dir : new File[] {new File(root, "Data"), new File(root, "wow-data/Data"), root}) {
            if (hasMpq(dir)) return dir;
        }
        return null;
    }

    // ── Folder picker ──
    //
    // The same way WoW Forever for Android does it: the system folder picker names the folder,
    // its tree URI is turned back into a plain path (the host opens files by path, not through
    // the content resolver), and "All files access" is what lets the host read that path.

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(i, PICK_FOLDER);
        } catch (Exception e) {
            showStatus("This device has no folder picker. Copy the data to /sdcard/wow-data/Data "
                    + "instead.", true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != PICK_FOLDER || resultCode != RESULT_OK || intent == null) return;
        String path = pathFromTreeUri(intent.getData());
        if (path == null) {
            showStatus("Could not turn that folder into a file path. Pick a folder on the "
                    + "internal storage or the SD card.", true);
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_DATA, path).apply();
        Log.i(TAG, "picked data folder " + path);
        if (!hasAllFilesAccess()) {
            // onResume boots again when the user comes back from the settings page.
            waitingForData = true;
            requestAllFilesAccess();
            return;
        }
        if (dataIn(new File(path)) == null) {
            waitingForData = true;
            showDataHelp();
            return;
        }
        boot();
    }

    private String pathFromTreeUri(Uri uri) {
        if (uri == null) return null;
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int colon = docId.indexOf(':');
            if (colon < 0) return docId.startsWith("/") ? docId : null;
            String volume = docId.substring(0, colon);
            String sub = docId.substring(colon + 1);
            String root = "primary".equals(volume)
                    ? Environment.getExternalStorageDirectory().getPath()
                    : volumeRoot(volume);
            return sub.isEmpty() ? root : root + "/" + sub;
        } catch (Exception e) {
            Log.w(TAG, "tree uri " + uri, e);
            return null;
        }
    }

    /** An SD card's mount point from its volume id (the `1234-ABCD` in its tree URI). */
    private String volumeRoot(String volumeId) {
        String fallback = "/storage/" + volumeId;
        if (new File(fallback).exists() || Build.VERSION.SDK_INT < 30) return fallback;
        StorageManager sm = getSystemService(StorageManager.class);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (volumeId.equalsIgnoreCase(v.getUuid()) && v.getDirectory() != null) {
                    return v.getDirectory().getAbsolutePath();
                }
            }
        }
        return fallback;
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void requestAllFilesAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
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
        String picked = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DATA, null);
        File priv = getExternalFilesDir(null);
        StringBuilder text = new StringBuilder();
        if (picked == null) {
            text.append("Choose your WoW 1.12.1 folder: the one holding Data/ with the .MPQ "
                    + "files (wow-data from `make fetch-data`), or the Data folder itself.\n\n");
        } else if (!hasAllFilesAccess()) {
            text.append("Game folder: ").append(picked).append("\n\nAllow \"All files access\" "
                    + "so the game can read it.\n\n");
        } else {
            text.append("No .MPQ files in ").append(picked).append(" (nor in its Data or "
                    + "wow-data/Data). Choose the folder that holds Data/.\n\n");
        }
        text.append("Or, without any permission, copy the Data folder into the app's own folder "
                + "from a PC:\n    adb push wow-data/Data ")
                .append(priv == null ? "…" : priv.getAbsolutePath()).append("/");
        LinearLayout col = column();
        col.addView(label(text.toString()));
        Button choose = button(picked == null ? "Choose game folder" : "Choose another folder",
                v -> pickFolder());
        col.addView(choose);
        if (!hasAllFilesAccess()) {
            col.addView(button("Grant all files access", v -> {
                waitingForData = true;
                requestAllFilesAccess();
            }));
        }
        col.addView(button("Retry", v -> boot()));
        setScreen(col);
        choose.requestFocus(); // so a controller's A works without touching the screen
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
