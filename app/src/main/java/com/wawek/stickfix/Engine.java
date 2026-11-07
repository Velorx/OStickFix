package com.wawek.stickfix;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide state: profiles, the daemon connection and which profile is live.
 * The live profile is the one being edited while the settings screen is open,
 * otherwise the profile bound to the foreground app, otherwise the default.
 */
final class Engine implements DaemonClient.Listener {
    interface Listener {
        default void onConnection(boolean connected, String info) {}

        default void onSample(float[] s) {}

        default void onActiveProfile(Profile p) {}
    }

    private static Engine instance;

    static synchronized Engine get(Context ctx) {
        if (instance == null) instance = new Engine(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    final List<Profile> profiles = new ArrayList<>();
    final String token;
    final DaemonClient client;

    private String foregroundPkg = "";
    private Profile preview;
    private Profile active;
    private boolean connected;
    private String connectionInfo;

    private Engine(Context ctx) {
        this.ctx = ctx;
        prefs = ctx.getSharedPreferences("stickfix", Context.MODE_PRIVATE);
        String t = prefs.getString("token", null);
        if (t == null) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            prefs.edit().putString("token", t).apply();
        }
        token = t;
        loadProfiles();
        writeConfigFile();
        client = new DaemonClient(token, this);
        updateActive(true);
    }

    // ------------------------------------------------------------ profiles

    Profile defaultProfile() {
        return profiles.get(0);
    }

    Profile profileFor(String pkg) {
        for (Profile p : profiles) if (p.pkg.equals(pkg)) return p;
        return defaultProfile();
    }

    Profile addProfile(String pkg, String label) {
        Profile existing = profileFor(pkg);
        if (!existing.isDefault()) return existing;
        Profile p = defaultProfile().copyFor(pkg, label);
        profiles.add(p);
        saveProfiles();
        return p;
    }

    void removeProfile(Profile p) {
        if (p.isDefault()) return;
        profiles.remove(p);
        if (preview == p) preview = null;
        saveProfiles();
        updateActive(true);
    }

    /** Call after editing a profile's values. */
    void profileChanged(Profile p) {
        saveProfiles();
        if (p.isDefault()) writeConfigFile();
        if (p == active) client.applyProfile(p);
    }

    // ------------------------------------------------------------ daemon lifecycle

    private final java.util.concurrent.ExecutorService rootExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile long lastLaunch;

    /** Whether the remapper should be running (survives reboots; "Stop" turns it off). */
    boolean enabled() {
        return prefs.getBoolean("enabled", true);
    }

    long lastLaunchMs() {
        return lastLaunch;
    }

    /** Starts stickfixd as root through PServerBinder. The callback gets the launcher output or error. */
    void startDaemon(java.util.function.Consumer<String> done) {
        prefs.edit().putBoolean("enabled", true).apply();
        lastLaunch = System.currentTimeMillis();
        writeConfigFile();
        rootExec.execute(() -> {
            String result;
            long t0 = System.currentTimeMillis();
            try {
                result = PServer.run(Scripts.startCommand(ctx));
            } catch (Exception e) {
                result = "Could not start: " + e.getMessage();
            }
            android.util.Log.i("StickFix", "launcher took " + (System.currentTimeMillis() - t0) + " ms: " + result);
            String r = result;
            if (done != null) main.post(() -> done.accept(r));
        });
    }

    void stopDaemon() {
        prefs.edit().putBoolean("enabled", false).apply();
        client.send("QUIT");
        rootExec.execute(() -> {
            try {
                PServer.run(Scripts.stopCommand(ctx));
            } catch (Exception ignored) {
            }
        });
    }

    boolean hideNative() {
        return prefs.getBoolean("hide", true);
    }

    void setHideNative(boolean hide) {
        prefs.edit().putBoolean("hide", hide).apply();
        writeConfigFile();
    }

    private void loadProfiles() {
        profiles.clear();
        try {
            JSONArray a = new JSONArray(prefs.getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) profiles.add(Profile.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        if (profiles.isEmpty() || !profiles.get(0).isDefault()) profiles.add(0, new Profile("", "Default"));
    }

    private void saveProfiles() {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : profiles) a.put(p.toJson());
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("profiles", a.toString()).apply();
    }

    /** The daemon reads this at start (and when it changes) so it has the token and default shaping. */
    private void writeConfigFile() {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return;
        Profile d = defaultProfile();
        String text = "# written by the StickFix app\n"
                + "token=" + token + "\n"
                + "hide=" + (hideNative() ? 1 : 0) + "\n"
                + d.left.toSet("l").replace(' ', '\n') + "\n"
                + d.right.toSet("r").replace(' ', '\n') + "\n";
        File tmp = new File(dir, "stickfix.conf.tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(new File(dir, "stickfix.conf"));
    }

    // ------------------------------------------------------------ live profile

    void setForegroundPackage(String pkg) {
        if (pkg == null || pkg.equals(foregroundPkg) || pkg.equals(ctx.getPackageName())) return;
        foregroundPkg = pkg;
        updateActive(false);
    }

    /** While editing, the edited profile is live so the visualiser shows its effect. */
    void setPreview(Profile p) {
        preview = p;
        updateActive(false);
    }

    Profile activeProfile() {
        return active;
    }

    private void updateActive(boolean force) {
        Profile next = preview != null ? preview : profileFor(foregroundPkg);
        if (!force && next == active) return;
        active = next;
        client.applyProfile(next);
        main.post(() -> {
            for (Listener l : listeners) l.onActiveProfile(next);
        });
    }

    // ------------------------------------------------------------ listeners

    void addListener(Listener l) {
        listeners.add(l);
        main.post(() -> {
            l.onConnection(connected, connectionInfo);
            if (active != null) l.onActiveProfile(active);
        });
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    boolean isConnected() {
        return connected;
    }

    @Override
    public void onConnection(boolean connected, String info) {
        main.post(() -> {
            this.connected = connected;
            if (info != null || !connected) connectionInfo = info;
            for (Listener l : listeners) l.onConnection(connected, connectionInfo);
        });
    }

    @Override
    public void onSample(float[] s) {
        main.post(() -> {
            for (Listener l : listeners) l.onSample(s);
        });
    }
}
