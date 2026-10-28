package survivors.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the WoW 1.12.1 `Data` folder is, and whether it is the right one.
 *
 * The folder comes from the system folder picker (the way WoW Forever for Android does it: the
 * tree URI is turned back into a plain path, since wenilla-host opens files by path), else from a
 * few well-known places. {@link #check} looks at the archive names before the server starts, so a
 * wrong client gets a sentence instead of a broken start screen.
 */
final class DataFolder {
    private static final String TAG = "survivors";
    private static final String PREFS = "survivors";
    private static final String KEY = "dataPath";

    /** The 1.12.1 archives the game reads; a client without these is incomplete. */
    static final String[] REQUIRED = {
        "dbc.mpq", "interface.mpq", "model.mpq", "texture.mpq", "terrain.mpq", "wmo.mpq",
        "misc.mpq", "fonts.mpq", "patch.mpq",
    };
    /** Archives only later clients have: their art and tables are laid out differently. */
    private static final String[] LATER = {
        "common.mpq", "common-2.mpq", "expansion.mpq", "lichking.mpq",
    };
    /** Patches a stock 1.12.1 client ends with; anything after is a mod. */
    private static final String[] STOCK_PATCHES = {"patch.mpq", "patch-2.mpq"};

    private DataFolder() {}

    static String picked(Context c) {
        return prefs(c).getString(KEY, null);
    }

    static void setPicked(Context c, String path) {
        prefs(c).edit().putString(KEY, path).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The folder picked in the app first, then the first well-known place holding the MPQs. */
    static File find(Context c) {
        String picked = picked(c);
        if (picked != null) {
            File dir = dataIn(new File(picked));
            if (dir != null) return dir;
        }
        List<File> roots = new ArrayList<>();
        File priv = c.getExternalFilesDir(null);
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
    static File dataIn(File root) {
        for (File dir : new File[] {new File(root, "Data"), new File(root, "wow-data/Data"), root}) {
            if (!mpqs(dir).isEmpty()) return dir;
        }
        return null;
    }

    private static List<String> mpqs(File dir) {
        List<String> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) return out;
        for (String n : names) {
            if (n.toLowerCase(Locale.ROOT).endsWith(".mpq")) out.add(n);
        }
        return out;
    }

    /** What is wrong with a Data folder: {@code error} stops the start, {@code warning} doesn't. */
    static final class Check {
        String error;
        String warning;
        int archives;
    }

    static Check check(File dir) {
        Check r = new Check();
        List<String> names = mpqs(dir);
        r.archives = names.size();
        List<String> lower = new ArrayList<>();
        for (String n : names) lower.add(n.toLowerCase(Locale.ROOT));

        List<String> later = new ArrayList<>();
        for (String n : LATER) if (lower.contains(n)) later.add(n);
        // Later clients also keep a locale folder (enUS, deDE…) of their own archives.
        String[] kids = dir.list();
        if (kids != null) {
            for (String k : kids) {
                if (k.length() == 4 && Character.isLowerCase(k.charAt(0))
                        && Character.isUpperCase(k.charAt(2)) && new File(dir, k).isDirectory()
                        && !mpqs(new File(dir, k)).isEmpty()) {
                    later.add(k + "/");
                }
            }
        }
        if (!later.isEmpty()) {
            r.error = "This Data folder is from a later World of Warcraft client (it has "
                    + String.join(", ", later) + "). The game needs the 1.12.1 client (build 5875): "
                    + "later clients lay their art and tables out differently, which is what "
                    + "breaks the start screen.";
            return r;
        }
        List<String> missing = new ArrayList<>();
        for (String n : REQUIRED) if (!lower.contains(n)) missing.add(n);
        if (!missing.isEmpty()) {
            r.error = "This Data folder is missing " + String.join(", ", missing)
                    + ". Copy the whole Data folder of the 1.12.1 client.";
            return r;
        }
        List<String> extra = new ArrayList<>();
        for (String n : names) {
            String l = n.toLowerCase(Locale.ROOT);
            if (l.startsWith("patch") && !List.of(STOCK_PATCHES).contains(l)) extra.add(n);
        }
        if (!extra.isEmpty()) {
            r.warning = "Extra patch archives: " + String.join(", ", extra) + ". Mods such as HD "
                    + "texture packs replace the game's art; if the menu or models look wrong, "
                    + "move them out of the folder.";
        }
        return r;
    }

    /** The path a folder-picker tree URI names, SD cards included. */
    static String pathFromTreeUri(Context c, Uri uri) {
        if (uri == null) return null;
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            int colon = docId.indexOf(':');
            if (colon < 0) return docId.startsWith("/") ? docId : null;
            String volume = docId.substring(0, colon);
            String sub = docId.substring(colon + 1);
            String root = "primary".equals(volume)
                    ? Environment.getExternalStorageDirectory().getPath()
                    : volumeRoot(c, volume);
            return sub.isEmpty() ? root : root + "/" + sub;
        } catch (Exception e) {
            Log.w(TAG, "tree uri " + uri, e);
            return null;
        }
    }

    /** An SD card's mount point from its volume id (the `1234-ABCD` in its tree URI). */
    private static String volumeRoot(Context c, String volumeId) {
        String fallback = "/storage/" + volumeId;
        if (new File(fallback).exists() || Build.VERSION.SDK_INT < 30) return fallback;
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm != null) {
            for (StorageVolume v : sm.getStorageVolumes()) {
                if (volumeId.equalsIgnoreCase(v.getUuid()) && v.getDirectory() != null) {
                    return v.getDirectory().getAbsolutePath();
                }
            }
        }
        return fallback;
    }

    static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }
}
