package com.wawek.stickfix;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

public class MainActivity extends Activity implements Engine.Listener {
    private Engine engine;
    private float dp;
    private TextView status;
    private Spinner profileSpinner;
    private ArrayAdapter<String> profileAdapter;
    private Button deleteButton;
    private StickView leftView, rightView;
    private final List<Slider> sliders = new ArrayList<>();
    private Profile editing;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        engine = Engine.get(this);
        dp = getResources().getDisplayMetrics().density;
        editing = engine.defaultProfile();
        setContentView(buildUi());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        StickFixService.start(this);
        selectProfile(editing);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        engine.addListener(this);
        engine.setPreview(editing);
        engine.client.setWatch(true);
    }

    @Override
    protected void onPause() {
        resumed = false;
        engine.client.setWatch(false);
        engine.setPreview(null);
        engine.removeListener(this);
        super.onPause();
    }

    // ------------------------------------------------------------ engine events

    @Override
    public void onConnection(boolean connected, String info) {
        if (connected) {
            status.setText("Connected - " + (info == null ? "" : info));
            status.setTextColor(0xFF81C784);
        } else {
            String why = !PServer.available() ? "PServerBinder is not available on this firmware, so the remapper cannot start."
                    : engine.enabled() ? "Starting remapper... (log: /data/local/tmp/stickfix/log)"
                    : "Remapper stopped. Tap Start remapper.";
            status.setText(info != null ? info : why);
            status.setTextColor(0xFFFFB74D);
        }
    }

    @Override
    public void onSample(float[] s) {
        leftView.setSample(s[0], s[1], s[4], s[5]);
        rightView.setSample(s[2], s[3], s[6], s[7]);
    }

    // ------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = vertical();
        int pad = (int) (12 * dp);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF15171B);

        status = new TextView(this);
        status.setTextSize(14);
        root.addView(status);

        LinearLayout actions = horizontal();
        actions.addView(button("Start remapper", v -> startDaemon()));
        actions.addView(button("Stop remapper", v -> engine.stopDaemon()));
        actions.addView(button("Usage access", v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))));
        Switch hide = new Switch(this);
        hide.setText("Hide original pad");
        hide.setTextColor(Color.WHITE);
        hide.setChecked(engine.hideNative());
        hide.setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
            engine.setHideNative(on);
            if (engine.enabled()) startDaemon();  // the launcher replaces the running copy
        });
        actions.addView(hide);
        root.addView(actions);

        LinearLayout profileRow = horizontal();
        profileRow.addView(label("Profile"));
        profileSpinner = new Spinner(this);
        profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        profileSpinner.setAdapter(profileAdapter);
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < engine.profiles.size() && engine.profiles.get(position) != editing)
                    selectProfile(engine.profiles.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        profileRow.addView(profileSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        profileRow.addView(button("+ Game profile", v -> pickGame()));
        deleteButton = button("Delete", v -> {
            engine.removeProfile(editing);
            selectProfile(engine.defaultProfile());
        });
        profileRow.addView(deleteButton);
        root.addView(profileRow);

        LinearLayout sticks = horizontal();
        sticks.setGravity(Gravity.TOP);
        leftView = new StickView(this, "Left stick");
        rightView = new StickView(this, "Right stick");
        sticks.addView(stickColumn(leftView, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sticks.addView(stickColumn(rightView, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(sticks);

        TextView help = new TextView(this);
        help.setTextColor(0xFF8A939C);
        help.setTextSize(12);
        help.setText("Grey dot = what the controller reports, blue dot = what games get.\n"
                + "Deadzone: ignore movement below this (raise it if the stick drifts; use it to skip a jump at the firmware's 5% cutoff).\n"
                + "Outer: deflection that counts as 100%.  Anti-deadzone: smallest output once you leave the deadzone - set it to the game's own deadzone.\n"
                + "Curve: below 1 = more sensitive near center, above 1 = finer aim near center.\n"
                + "Game profiles switch automatically when that game is in front (needs Usage access).");
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF15171B);
        scroll.addView(root);
        // targetSdk 35+ draws edge-to-edge: keep content clear of the status/nav bars.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
            return insets;
        });
        return scroll;
    }

    private View stickColumn(StickView view, boolean left) {
        LinearLayout col = vertical();
        col.setPadding((int) (4 * dp), 0, (int) (4 * dp), 0);
        col.addView(view);
        col.addView(slider("Deadzone", 0, 0.30, 0.005, true, () -> stick(left).inner, v -> stick(left).inner = v));
        col.addView(slider("Outer", 0.50, 1.0, 0.005, true, () -> stick(left).outer, v -> stick(left).outer = v));
        col.addView(slider("Anti-deadzone", 0, 0.50, 0.005, true, () -> stick(left).anti, v -> stick(left).anti = v));
        col.addView(slider("Curve", 0.30, 3.0, 0.05, false, () -> stick(left).curve, v -> stick(left).curve = v));
        col.addView(button(left ? "Copy to right stick →" : "← Copy to left stick", v -> {
            if (left) editing.right = editing.left.copy();
            else editing.left = editing.right.copy();
            onProfileEdited();
        }));
        return col;
    }

    private StickConfig stick(boolean left) {
        return left ? editing.left : editing.right;
    }

    private void selectProfile(Profile p) {
        editing = p;
        refreshProfiles();
        for (Slider s : sliders) s.load();
        leftView.setConfig(p.left);
        rightView.setConfig(p.right);
        deleteButton.setEnabled(!p.isDefault());
        if (resumed) engine.setPreview(p);
    }

    private void refreshProfiles() {
        profileAdapter.clear();
        int sel = 0;
        for (int i = 0; i < engine.profiles.size(); i++) {
            Profile p = engine.profiles.get(i);
            profileAdapter.add(p.isDefault() ? "Default (all other apps)" : p.label);
            if (p == editing) sel = i;
        }
        profileAdapter.notifyDataSetChanged();
        profileSpinner.setSelection(sel);
    }

    private void onProfileEdited() {
        for (Slider s : sliders) s.load();
        leftView.setConfig(editing.left);
        rightView.setConfig(editing.right);
        engine.profileChanged(editing);
    }

    private void pickGame() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(launcher, 0);
        List<String[]> items = new ArrayList<>();
        for (ResolveInfo ri : apps) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !engine.profileFor(pkg).isDefault()) continue;
            items.add(new String[]{ri.loadLabel(pm).toString(), pkg});
        }
        items.sort(Comparator.comparing(a -> a[0].toLowerCase(Locale.ROOT)));
        String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = items.get(i)[0];
        new AlertDialog.Builder(this)
                .setTitle("New profile for (starts as a copy of Default)")
                .setItems(labels, (d, which) -> {
                    String[] it = items.get(which);
                    selectProfile(engine.addProfile(it[1], it[0]));
                    if (!StickFixService.hasUsageAccess(this)) {
                        Toast.makeText(this, "Grant Usage access so StickFix can tell which game is in front", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void startDaemon() {
        status.setText("Starting remapper...");
        engine.startDaemon(out -> {
            if (!engine.isConnected() && out != null && !out.isEmpty()) status.setText(out);
        });
    }

    // ------------------------------------------------------------ widgets

    private final class Slider {
        final LinearLayout row = horizontal();
        final TextView value = label("");
        final SeekBar bar = new SeekBar(MainActivity.this);
        final double min, step;
        final boolean percent;
        final DoubleSupplier get;
        final DoubleConsumer set;
        boolean loading;

        Slider(String name, double min, double max, double step, boolean percent, DoubleSupplier get, DoubleConsumer set) {
            this.min = min;
            this.step = step;
            this.percent = percent;
            this.get = get;
            this.set = set;
            TextView n = label(name);
            n.setMinWidth((int) (100 * dp));
            value.setMinWidth((int) (56 * dp));
            bar.setMax((int) Math.round((max - min) / step));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                    double v = Slider.this.min + progress * Slider.this.step;
                    show(v);
                    if (loading) return;
                    Slider.this.set.accept(v);
                    leftView.setConfig(editing.left);
                    rightView.setConfig(editing.right);
                    engine.profileChanged(editing);
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            row.addView(n);
            row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(value);
        }

        void load() {
            loading = true;
            double v = get.getAsDouble();
            bar.setProgress((int) Math.round((v - min) / step));
            show(v);
            loading = false;
        }

        void show(double v) {
            value.setText(percent ? String.format(Locale.ROOT, "%.1f%%", v * 100) : String.format(Locale.ROOT, "%.2f", v));
        }
    }

    private View slider(String name, double min, double max, double step, boolean percent, DoubleSupplier get, DoubleConsumer set) {
        Slider s = new Slider(name, min, max, step, percent, get, set);
        sliders.add(s);
        return s.row;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setPadding((int) (4 * dp), 0, (int) (8 * dp), 0);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }
}
