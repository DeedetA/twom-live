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
import android.widget.FrameLayout;
import java.util.Locale;
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
        prefs = getSharedPreferences(Alerter.PREFS, MODE_PRIVATE);
        Alerter alerter = Alerter.get(this);
        alerter.attach();
        NamesPrefs.attach(this);
        gs.rawSink = RawRecorder.get(this);          // tout ce qui passe, en mode Raw

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView credit = new TextView(this);
        credit.setText("Made with love by @Deedet, pm me if issue found.\nPlease don't use it for botting or for macros ❤️");
        credit.setTextSize(13);
        credit.setGravity(Gravity.CENTER_HORIZONTAL);
        credit.setPadding(0, dp(4), 0, dp(4));
        root.addView(credit);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(2));
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

        // ------------------------------------------------ onglets
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout pages = new FrameLayout(this);
        View[] views = {buildSettings(gs, alerter), buildLog(gs), buildTable(gs)};
        String[] titles = {"Settings", "Log", "Table"};
        for (int i = 0; i < 3; i++) {
            final int n = i;
            tabButtons[i] = new Button(this);
            tabButtons[i].setText(titles[i]);
            tabButtons[i].setAllCaps(false);
            tabButtons[i].setOnClickListener(v -> showTab(n));
            tabs.addView(tabButtons[i], new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            pages.addView(views[i], new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            pageViews[i] = views[i];
        }
        root.addView(tabs);
        root.addView(pages, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        showTab(prefs.getInt("tab", 1));
    }

    private final Button[] tabButtons = new Button[3];
    private final View[] pageViews = new View[3];
    private int currentTab = 1;
    private SharedPreferences prefs;

    private void showTab(int n) {
        currentTab = n;
        prefs.edit().putInt("tab", n).apply();
        for (int i = 0; i < 3; i++) {
            pageViews[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
            tabButtons[i].setTypeface(null, i == n ? Typeface.BOLD : Typeface.NORMAL);
            tabButtons[i].setTextColor(i == n ? Color.rgb(21, 101, 192) : Color.rgb(90, 90, 90));
        }
        if (n == 2) refreshTable();
        if (n == 1) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView header(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(15);
        v.setPadding(0, dp(12), 0, dp(2));
        return v;
    }

    private CheckBox option(LinearLayout into, String label, String help, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(on);
        c.setOnCheckedChangeListener(l);
        into.addView(c);
        if (help != null) {
            TextView h = new TextView(this);
            h.setText(help);
            h.setTextSize(12);
            h.setTextColor(Color.rgb(110, 110, 110));
            h.setPadding(dp(32), 0, 0, dp(4));
            into.addView(h);
        }
        return c;
    }

    // ------------------------------------------------ onglet 1 : reglages
    private View buildSettings(GameState gs, Alerter alerter) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.addView(header("Show in the log"));
        option(p, "Chat", "Chat messages, yours and other players'", gs.showChat, (c, on) -> gs.showChat = on);
        option(p, "Sent", "What your phone sends: attacks, skills, items, NPCs", gs.showSent, (c, on) -> gs.showSent = on);
        option(p, "Details", "Hits, misses, players in view, unnamed stats, diagnostics", gs.verbose, (c, on) -> gs.verbose = on);
        gs.showMoves = prefs.getBoolean("moves", false);
        option(p, "Moves", "Every movement around you (about 15 lines per second)", gs.showMoves, (c, on) -> {
            prefs.edit().putBoolean("moves", on).apply();
            gs.showMoves = on;
        });
        gs.raw = prefs.getBoolean("raw", false);
        option(p, "Raw", "Every message in hex, recorded for the Raw file", gs.raw, (c, on) -> {
            prefs.edit().putBoolean("raw", on).apply();
            gs.raw = on;
        });

        p.addView(header("Mob alert"));
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
        p.addView(name);
        CheckBox notif = option(p, "Notification", null, prefs.getBoolean(Alerter.KEY_NOTIF, true), (c, on) -> {
            prefs.edit().putBoolean(Alerter.KEY_NOTIF, on).apply();
            if (on) askNotificationPermission();
        });
        option(p, "Vibration", null, prefs.getBoolean(Alerter.KEY_VIBRATE, true),
                (c, on) -> prefs.edit().putBoolean(Alerter.KEY_VIBRATE, on).apply());
        option(p, "Voice", null, prefs.getBoolean(Alerter.KEY_VOICE, false),
                (c, on) -> prefs.edit().putBoolean(Alerter.KEY_VOICE, on).apply());
        p.addView(header("Inventory alert"));
        p.addView(labeledField("Alert when free slots are at most (0 = off)", prefs.getString(Alerter.KEY_INV, "3"), true, s -> {
            prefs.edit().putString(Alerter.KEY_INV, s).apply();
            try { gs.invThreshold = Integer.parseInt(s.trim()); } catch (NumberFormatException e) { gs.invThreshold = 0; }
        }));

        p.addView(header("Enemy player alert"));
        p.addView(labeledField("Faction codes to watch, e.g. 3 (see Table > Factions; empty = off)",
                prefs.getString(Alerter.KEY_ENEMY_FACTIONS, ""), false, s -> {
                    prefs.edit().putString(Alerter.KEY_ENEMY_FACTIONS, s).apply();
                    gs.setPlayerAlert(s, prefs.getString(Alerter.KEY_ENEMY_STATUSES, ""));
                }));
        p.addView(labeledField("Only with these status codes, e.g. 2 (empty = any status)",
                prefs.getString(Alerter.KEY_ENEMY_STATUSES, ""), false, s -> {
                    prefs.edit().putString(Alerter.KEY_ENEMY_STATUSES, s).apply();
                    gs.setPlayerAlert(prefs.getString(Alerter.KEY_ENEMY_FACTIONS, ""), s);
                }));
        TextView how = new TextView(this);
        how.setText("Alerts use the same notification, vibration and voice settings as the Mob alert.");
        how.setTextSize(12);
        how.setTextColor(Color.rgb(110, 110, 110));
        p.addView(how);

        Button test = new Button(this);
        test.setText("Test the alert");
        test.setAllCaps(false);
        test.setOnClickListener(v -> alerter.fire("Test alert: Imp spawned - 300 north-east",
                "ID 8ee6a91c at (2267,1240)", "Test alert. Imp spawned, 300 north-east"));
        p.addView(test, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        if (notif.isChecked()) askNotificationPermission();

        ScrollView sv = new ScrollView(this);
        sv.addView(p);
        return sv;
    }

    private LinearLayout labeledField(String label, String value, boolean number, Setter set) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        l.addView(t);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (number) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { set.set(s.toString()); }
        });
        l.addView(e);
        return l;
    }

    // ------------------------------------------------ onglet 2 : log
    private View buildLog(GameState gs) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        mobs = new TextView(this);
        mobs.setTypeface(Typeface.MONOSPACE);
        mobs.setTextSize(12);
        mobs.setBackgroundColor(Color.rgb(236, 242, 250));
        mobs.setTextColor(Color.rgb(20, 30, 50));
        mobs.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView mobsScroll = new ScrollView(this);
        mobsScroll.addView(mobs);
        p.addView(mobsScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2));

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
            shareText("TWOM Live - log", t.toString(), "Share the log");
        });
        row2.addView(share);
        Button exportRaw = new Button(this);
        exportRaw.setText("Raw file");
        exportRaw.setOnClickListener(v -> shareRaw(exportRaw));
        row2.addView(exportRaw);
        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setOnClickListener(v -> { gs.reset(); shown.clear(); log.setText(""); RawRecorder.get(this).clear(); });
        row2.addView(clear);
        p.addView(row2);

        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.addView(log);
        p.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3));
        return p;
    }

    private void shareText(String subject, String text, String title) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("text/plain");
        s.putExtra(Intent.EXTRA_SUBJECT, subject);
        s.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(s, title));
    }

    private void shareRaw(Button exportRaw) {
        RawRecorder rec = RawRecorder.get(this);
        rec.flush();
        if (rec.size() == 0) {
            Toast.makeText(this, "Nothing recorded yet: tick Raw in Settings, then play", Toast.LENGTH_LONG).show();
            return;
        }
        exportRaw.setEnabled(false);
        Toast.makeText(this, String.format(Locale.US, "Zipping %.1f MB...", rec.size() / 1048576.0), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            long raw;
            try {
                raw = rec.makeZip();
            } catch (java.io.IOException e) {
                runOnUiThread(() -> {
                    exportRaw.setEnabled(true);
                    Toast.makeText(this, "Could not create the zip: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
                return;
            }
            long zipped = rec.zipFile().length();
            runOnUiThread(() -> {
                exportRaw.setEnabled(true);
                Toast.makeText(this, String.format(Locale.US, "%.1f MB zipped to %.1f MB", raw / 1048576.0, zipped / 1048576.0),
                        Toast.LENGTH_LONG).show();
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("application/zip");
                s.putExtra(Intent.EXTRA_STREAM, RawFileProvider.RAW_URI);
                s.putExtra(Intent.EXTRA_SUBJECT, "TWOM Live - raw messages");
                s.setClipData(android.content.ClipData.newRawUri("raw", RawFileProvider.RAW_URI));
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(s, "Share the raw file"));
            });
        }, "raw-zip").start();
    }

    // ------------------------------------------------ onglet 3 : tableau a remplir
    private Names.Cat tableCat = Names.Cat.SKILL;
    private LinearLayout tableRows, catBar;
    private TextView tableInfo;
    private boolean tableDirty;

    private View buildTable(GameState gs) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        try { tableCat = Names.Cat.valueOf(prefs.getString("table_cat", "SKILL")); } catch (IllegalArgumentException ignored) { }

        catBar = new LinearLayout(this);
        catBar.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView cs = new android.widget.HorizontalScrollView(this);
        cs.addView(catBar);
        p.addView(cs);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        tableInfo = new TextView(this);
        tableInfo.setTextSize(12);
        tableInfo.setTextColor(Color.rgb(90, 90, 90));
        info.addView(tableInfo, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button share = new Button(this);
        share.setText("Share table");
        share.setAllCaps(false);
        share.setOnClickListener(v -> shareText("TWOM Live - table", gs.names.csv(), "Share the table"));
        info.addView(share);
        p.addView(info);

        tableRows = new LinearLayout(this);
        tableRows.setOrientation(LinearLayout.VERTICAL);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.addView(tableRows);
        ScrollView vs = new ScrollView(this);
        vs.addView(hs);
        p.addView(vs, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return p;
    }

    private android.graphics.drawable.GradientDrawable cellBg(int fill) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setStroke(1, Color.rgb(200, 200, 200));
        return d;
    }

    private TextView cellText(String t, int width, boolean bold, int fill) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(13);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(6), dp(8), dp(6), dp(8));
        v.setBackground(cellBg(fill));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(width), LinearLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private interface Setter { void set(String s); }

    private EditText cellEdit(String t, int width, boolean number, int fill, Setter set) {
        EditText e = new EditText(this);
        e.setText(t);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (number) e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setPadding(dp(6), dp(6), dp(6), dp(6));
        e.setBackground(cellBg(fill));
        e.setLayoutParams(new LinearLayout.LayoutParams(dp(width), LinearLayout.LayoutParams.MATCH_PARENT));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                synchronized (GameState.INSTANCE.names) { set.set(s.toString()); }
                GameState.INSTANCE.names.edited();
                refreshCatBar();
            }
        });
        return e;
    }

    private void refreshCatBar() {
        Names names = GameState.INSTANCE.names;
        catBar.removeAllViews();
        for (Names.Cat c : Names.Cat.values()) {
            Button b = new Button(this);
            int todo = names.unnamed(c);
            b.setText(c.title + (todo > 0 ? " (" + todo + ")" : ""));
            b.setAllCaps(false);
            b.setTypeface(null, c == tableCat ? Typeface.BOLD : Typeface.NORMAL);
            b.setTextColor(c == tableCat ? Color.rgb(21, 101, 192) : Color.rgb(90, 90, 90));
            b.setOnClickListener(v -> {
                tableCat = c;
                prefs.edit().putString("table_cat", c.name()).apply();
                refreshTable();
            });
            catBar.addView(b);
        }
    }

    private void refreshTable() {
        tableDirty = false;
        refreshCatBar();
        Names names = GameState.INSTANCE.names;
        java.util.List<Names.Entry> list = names.list(tableCat);
        boolean skill = tableCat == Names.Cat.SKILL;
        boolean valued = tableCat == Names.Cat.STAT || tableCat == Names.Cat.BUFF;
        boolean bag = tableCat == Names.Cat.BAG;
        boolean example = tableCat == Names.Cat.CLASS || tableCat == Names.Cat.FACTION || tableCat == Names.Cat.STATUS;
        tableInfo.setText(list.size() + " seen, " + names.unnamed(tableCat) + " without a name (yellow). "
                + (skill ? "Crystal costs feed the crystal counter." : "Names are used in the log."));
        tableRows.removeAllViews();

        int head = Color.rgb(225, 232, 242);
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.addView(cellText("#", 64, true, head));
        h.addView(cellText("Name", 170, true, head));
        if (skill) {
            h.addView(cellText("Crystals", 80, true, head));
            h.addView(cellText("Mana", 70, true, head));
        }
        if (valued) h.addView(cellText(tableCat == Names.Cat.STAT ? "Last value" : "Duration", 90, true, head));
        if (bag) h.addView(cellText("Slots", 70, true, head));
        if (example) h.addView(cellText("Last player", 120, true, head));
        h.addView(cellText("Seen", 56, true, head));
        h.addView(cellText("Note", 260, true, head));
        tableRows.addView(h);

        for (Names.Entry e : list) {
            int fill = e.named() ? Color.WHITE : Color.rgb(255, 248, 214);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.addView(cellText(e.id, 64, false, fill));
            r.addView(cellEdit(e.name, 170, false, fill, s -> e.name = s));
            if (skill) {
                r.addView(cellEdit(e.crystals == 0 ? "" : Names.fmt(e.crystals), 80, true, fill, s -> {
                    try { e.crystals = s.trim().isEmpty() ? 0 : Double.parseDouble(s.replace(',', '.')); } catch (NumberFormatException ignored) { }
                }));
                r.addView(cellEdit(e.mana == 0 ? "" : String.valueOf(e.mana), 70, true, fill, s -> {
                    try { e.mana = s.trim().isEmpty() ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
                }));
            }
            if (valued) r.addView(cellText(e.last, 90, false, fill));
            if (bag) r.addView(cellEdit(e.slots == 0 ? "" : String.valueOf(e.slots), 70, true, fill, s -> {
                try { e.slots = s.trim().isEmpty() ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
            }));
            if (example) r.addView(cellText(e.last, 120, false, fill));
            r.addView(cellText(e.seen == 0 ? "" : String.valueOf(e.seen), 56, false, fill));
            r.addView(cellEdit(e.note, 260, false, fill, s -> e.note = s));
            tableRows.addView(r);
        }
        if (list.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Nothing seen yet: play with Start on, new numbers appear here.");
            none.setPadding(0, dp(12), 0, 0);
            tableRows.addView(none);
        }
    }

    /** une nouvelle ligne est apparue pendant le jeu */
    private void onNewName() {
        ui.post(() -> {
            if (currentTab != 2) { tableDirty = true; return; }
            View f = getCurrentFocus();
            if (f instanceof EditText && tableRows.findFocus() != null) { tableDirty = true; refreshCatBar(); return; }
            refreshTable();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        GameState gs = GameState.INSTANCE;
        gs.addListener(this);
        gs.names.onNew = this::onNewName;
        if (currentTab == 2) refreshTable();
        shown.clear();
        List<GameState.Line> l = gs.entries();
        for (int i = Math.max(0, l.size() - MAX_LINES); i < l.size(); i++) shown.add(l.get(i));
        renderLog();
        mobs.setText(gs.mobsSummary());
        refreshToggle();
    }

    @Override
    protected void onPause() {
        GameState.INSTANCE.removeListener(this);
        GameState.INSTANCE.names.onNew = null;
        super.onPause();
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
