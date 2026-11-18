package fr.twomlive;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;

public class MainActivity extends Activity implements GameState.Listener {
    private static final int REQ_VPN = 1;
    private static final int MAX_LINES = 300;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<GameState.Line> shown = new ArrayDeque<>();

    // vert = recu du serveur, bleu = envoye par le telephone, rouge = drop, gris = messages de l'appli
    private static final int[] COLORS = {
            Color.rgb(46, 125, 50), Color.rgb(21, 101, 192), Color.rgb(198, 40, 40), Color.rgb(110, 110, 110),
            Color.rgb(230, 81, 0)};
    private Button toggle;
    private TextView mobs, log;
    private ScrollView logScroll;
    private boolean mobsPending;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        GameState gs = GameState.INSTANCE;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        SharedPreferences prefs = getSharedPreferences(Alerter.PREFS, MODE_PRIVATE);
        Alerter alerter = Alerter.get(this);
        alerter.attach();

        TextView credit = new TextView(this);
        credit.setText("Made with love by @Deedet, pm me if issue found.\nPlease don't use it for botting or for macros ❤️");
        credit.setTextSize(13);
        credit.setGravity(Gravity.CENTER_HORIZONTAL);
        credit.setPadding(0, dp(4), 0, dp(4));
        root.addView(credit);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(4));
        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        row.addView(toggle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button open = new Button(this);
        open.setText("Open TWOM");
        open.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(TwomVpnService.TWOM_PKG);
            if (i != null) startActivity(i);
            else Toast.makeText(this, "TWOM is not installed", Toast.LENGTH_SHORT).show();
        });
        row.addView(open, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox chat = new CheckBox(this);
        chat.setText("Chat");
        chat.setChecked(gs.showChat);
        chat.setOnCheckedChangeListener((c, on) -> gs.showChat = on);
        opts.addView(chat);
        CheckBox sent = new CheckBox(this);
        sent.setText("Sent");
        sent.setChecked(gs.showSent);
        sent.setOnCheckedChangeListener((c, on) -> gs.showSent = on);
        opts.addView(sent);
        CheckBox details = new CheckBox(this);
        details.setText("Details");
        details.setChecked(gs.verbose);
        details.setOnCheckedChangeListener((c, on) -> gs.verbose = on);
        opts.addView(details);
        root.addView(opts);

        // ------------------------------------------------ alerte d'apparition
        TextView at = new TextView(this);
        at.setText("Mob alert");
        at.setTypeface(Typeface.DEFAULT_BOLD);
        at.setPadding(0, dp(6), 0, 0);
        root.addView(at);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Name to watch (several: Imp, Spector)");
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setText(prefs.getString(Alerter.KEY_NAME, ""));
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(Alerter.KEY_NAME, s.toString()).apply();
                gs.setWatch(s.toString());
            }
        });
        root.addView(name);
        LinearLayout alertRow = new LinearLayout(this);
        alertRow.setOrientation(LinearLayout.HORIZONTAL);
        alertRow.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox notif = new CheckBox(this);
        notif.setText("Notification");
        notif.setChecked(prefs.getBoolean(Alerter.KEY_NOTIF, true));
        notif.setOnCheckedChangeListener((c, on) -> {
            prefs.edit().putBoolean(Alerter.KEY_NOTIF, on).apply();
            if (on) askNotificationPermission();
        });
        alertRow.addView(notif);
        CheckBox vib = new CheckBox(this);
        vib.setText("Vibration");
        vib.setChecked(prefs.getBoolean(Alerter.KEY_VIBRATE, true));
        vib.setOnCheckedChangeListener((c, on) -> prefs.edit().putBoolean(Alerter.KEY_VIBRATE, on).apply());
        alertRow.addView(vib);
        CheckBox voice = new CheckBox(this);
        voice.setText("Voice");
        voice.setChecked(prefs.getBoolean(Alerter.KEY_VOICE, false));
        voice.setOnCheckedChangeListener((c, on) -> prefs.edit().putBoolean(Alerter.KEY_VOICE, on).apply());
        alertRow.addView(voice);
        Button test = new Button(this);
        test.setText("Test");
        test.setOnClickListener(v -> alerter.fire("Test alert", "ID 8ee6a91c at (2267,1240)",
                "Test alert. Imp spawned at 2267, 1240"));
        alertRow.addView(test);
        android.widget.HorizontalScrollView alertScroll = new android.widget.HorizontalScrollView(this);
        alertScroll.addView(alertRow);
        root.addView(alertScroll);
        if (notif.isChecked()) askNotificationPermission();

        // ------------------------------------------------ cercles par-dessus le jeu
        Overlay overlay = Overlay.get(this);
        LinearLayout ovRow = new LinearLayout(this);
        ovRow.setOrientation(LinearLayout.HORIZONTAL);
        ovRow.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox circles = new CheckBox(this);
        circles.setText("Circles on screen");
        circles.setChecked(prefs.getBoolean(Overlay.KEY_ON, false));
        circles.setOnCheckedChangeListener((c, on) -> {
            prefs.edit().putBoolean(Overlay.KEY_ON, on).apply();
            if (on && !overlay.canDraw()) askOverlayPermission();
            else overlay.setEnabled(on);
        });
        ovRow.addView(circles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button calib = new Button(this);
        calib.setText("Calibrate");
        calib.setOnClickListener(v -> {
            if (!overlay.canDraw()) { askOverlayPermission(); return; }
            overlay.startCalibration();
            Intent i = getPackageManager().getLaunchIntentForPackage(TwomVpnService.TWOM_PKG);
            if (i != null) startActivity(i);
        });
        ovRow.addView(calib);
        root.addView(ovRow);

        mobs = new TextView(this);
        mobs.setTypeface(Typeface.MONOSPACE);
        mobs.setTextSize(12);
        mobs.setBackgroundColor(Color.rgb(236, 242, 250));
        mobs.setTextColor(Color.rgb(20, 30, 50));
        mobs.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView mobsScroll = new ScrollView(this);
        mobsScroll.addView(mobs);
        root.addView(mobsScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView jt = new TextView(this);
        jt.setText("Log");
        jt.setTypeface(Typeface.DEFAULT_BOLD);
        row2.addView(jt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button share = new Button(this);
        share.setText("Share");
        share.setOnClickListener(v -> {
            StringBuilder t = new StringBuilder("=== Log ===\n");
            for (String l : gs.lines()) t.append(l).append('\n');
            t.append("\n=== Events (CSV) ===\n").append(gs.csv());
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_SUBJECT, "TWOM Live - log");
            s.putExtra(Intent.EXTRA_TEXT, t.toString());
            startActivity(Intent.createChooser(s, "Share the log"));
        });
        row2.addView(share);
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> { gs.reset(); shown.clear(); log.setText(""); });
        row2.addView(clear);
        root.addView(row2);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(log);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        GameState gs = GameState.INSTANCE;
        gs.addListener(this);
        Overlay ov = Overlay.get(this);
        ov.appInFront = true;
        if (getSharedPreferences(Alerter.PREFS, MODE_PRIVATE).getBoolean(Overlay.KEY_ON, false) && ov.canDraw()) {
            ov.setEnabled(true);
        }
        shown.clear();
        List<GameState.Line> l = gs.entries();
        for (int i = Math.max(0, l.size() - MAX_LINES); i < l.size(); i++) shown.add(l.get(i));
        renderLog();
        mobs.setText(gs.mobsSummary());
        refreshToggle();
    }

    @Override
    protected void onPause() {
        Overlay.get(this).appInFront = false;
        GameState.INSTANCE.removeListener(this);
        super.onPause();
    }

    private void askOverlayPermission() {
        Toast.makeText(this, "Allow \"Display over other apps\" for TWOM Live", Toast.LENGTH_LONG).show();
        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    private void refreshToggle() {
        toggle.setText(TwomVpnService.running ? "Stop" : "Start");
    }

    private void onToggle() {
        if (TwomVpnService.running) {
            startService(new Intent(this, TwomVpnService.class).setAction(TwomVpnService.ACTION_STOP));
            ui.postDelayed(this::refreshToggle, 300);
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, REQ_VPN);
        else onActivityResult(REQ_VPN, RESULT_OK, null);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_VPN && res == RESULT_OK) {
            startService(new Intent(this, TwomVpnService.class).setAction(TwomVpnService.ACTION_START));
            ui.postDelayed(this::refreshToggle, 300);
        } else if (req == REQ_VPN) {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
        }
    }

    private static CharSequence colored(String text, int kind) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.append('\n');
        sb.setSpan(new ForegroundColorSpan(COLORS[kind]), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void renderLog() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (GameState.Line l : shown) sb.append(colored(l.text, l.kind));
        log.setText(sb);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onEvent(String line, int kind) {
        ui.post(() -> {
            shown.add(new GameState.Line(line, kind));
            while (shown.size() > MAX_LINES) shown.removeFirst();
            log.append(colored(line, kind));
            if (shown.size() >= MAX_LINES && log.getLineCount() > MAX_LINES + 50) renderLog();
            else logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            refreshToggle();
        });
    }

    @Override
    public void onMobsChanged() {
        synchronized (this) {
            if (mobsPending) return;
            mobsPending = true;
        }
        ui.postDelayed(() -> {
            synchronized (this) { mobsPending = false; }
            mobs.setText(GameState.INSTANCE.mobsSummary());
        }, 400);
    }
}
