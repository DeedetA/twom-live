package fr.twomlive;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private final ArrayDeque<String> shown = new ArrayDeque<>();
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

        TextView help = new TextView(this);
        help.setText("Lecture seule : l'appli observe ce que le serveur envoie à TWOM, sans rien modifier "
                + "ni envoyer au jeu. Démarre la capture AVANT d'ouvrir TWOM.");
        help.setTextSize(13);
        root.addView(help);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(4));
        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        row.addView(toggle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button open = new Button(this);
        open.setText("Ouvrir TWOM");
        open.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(TwomVpnService.TWOM_PKG);
            if (i != null) startActivity(i);
            else Toast.makeText(this, "TWOM n'est pas installé", Toast.LENGTH_SHORT).show();
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
        CheckBox details = new CheckBox(this);
        details.setText("Détails (déplacements, coups)");
        details.setChecked(gs.verbose);
        details.setOnCheckedChangeListener((c, on) -> gs.verbose = on);
        opts.addView(details);
        root.addView(opts);

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
        jt.setText("Journal");
        jt.setTypeface(Typeface.DEFAULT_BOLD);
        row2.addView(jt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button share = new Button(this);
        share.setText("Partager");
        share.setOnClickListener(v -> {
            StringBuilder t = new StringBuilder("=== Journal ===\n");
            for (String l : gs.lines()) t.append(l).append('\n');
            t.append("\n=== Événements (CSV) ===\n").append(gs.csv());
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_SUBJECT, "TWOM Live – journal");
            s.putExtra(Intent.EXTRA_TEXT, t.toString());
            startActivity(Intent.createChooser(s, "Partager le journal"));
        });
        row2.addView(share);
        Button clear = new Button(this);
        clear.setText("Effacer");
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
        shown.clear();
        List<String> l = gs.lines();
        for (int i = Math.max(0, l.size() - MAX_LINES); i < l.size(); i++) shown.add(l.get(i));
        renderLog();
        mobs.setText(gs.mobsSummary());
        refreshToggle();
    }

    @Override
    protected void onPause() {
        GameState.INSTANCE.removeListener(this);
        super.onPause();
    }

    private void refreshToggle() {
        toggle.setText(TwomVpnService.running ? "■ Arrêter" : "▶ Démarrer");
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
            Toast.makeText(this, "Autorisation VPN refusée", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String s : shown) sb.append(s).append('\n');
        log.setText(sb);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onEvent(String line) {
        ui.post(() -> {
            shown.add(line);
            while (shown.size() > MAX_LINES) shown.removeFirst();
            log.append(line + "\n");
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
