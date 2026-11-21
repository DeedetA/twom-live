package fr.twomlive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Alerte quand un nom surveille apparait : notification, vibration et/ou voix,
 * selon les choix enregistres. Unique pour tout le processus.
 */
public final class Alerter implements GameState.AlertListener {
    public static final String PREFS = "twomlive";
    public static final String KEY_NAME = "watch_name";
    public static final String KEY_INV = "inv_threshold";
    public static final String KEY_ENEMY_FACTIONS = "enemy_factions";
    public static final String KEY_ENEMY_STATUSES = "enemy_statuses";
    public static final String KEY_NOTIF = "alert_notification";
    public static final String KEY_VIBRATE = "alert_vibration";
    public static final String KEY_VOICE = "alert_voice";
    private static final String CHANNEL = "spawn_alerts";

    private static Alerter instance;

    private final Context ctx;
    private final SharedPreferences prefs;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final List<String> pendingSpeech = new ArrayList<>();
    private int notifId = 100;

    public static synchronized Alerter get(Context c) {
        if (instance == null) instance = new Alerter(c.getApplicationContext());
        return instance;
    }

    private Alerter(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Mob alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Alerts when a watched name appears");
            ch.enableVibration(false);   // la vibration est geree a part, selon le choix de l'utilisateur
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    /** a appeler au demarrage : charge le nom surveille et prepare la voix si besoin */
    public void attach() {
        GameState.INSTANCE.setWatch(prefs.getString(KEY_NAME, ""));
        GameState.INSTANCE.setPlayerAlert(prefs.getString(KEY_ENEMY_FACTIONS, ""), prefs.getString(KEY_ENEMY_STATUSES, ""));
        try { GameState.INSTANCE.invThreshold = Integer.parseInt(prefs.getString(KEY_INV, "3").trim()); }
        catch (NumberFormatException e) { GameState.INSTANCE.invThreshold = 0; }
        GameState.INSTANCE.addAlertListener(this);
        if (prefs.getBoolean(KEY_VOICE, false)) ensureTts();
    }

    @Override
    public void onMatch(TwomDecoder.Entity e, boolean spawned) {
        String what = e.name + (spawned ? " spawned" : " in view");
        String rel = GameState.INSTANCE.relative(e.x, e.y);   // ex. "300 north-east"
        if (rel != null) {
            fire(what + " - " + rel, "ID " + e.id + " at (" + e.x + "," + e.y + ")", what + ", " + rel);
        } else {
            fire(what, "ID " + e.id + " at (" + e.x + "," + e.y + ")", what + " at " + e.x + ", " + e.y);
        }
    }

    @Override
    public void onAlert(String title, String text, String speech) { fire(title, text, speech); }

    /** declenche l'alerte avec les moyens choisis */
    public void fire(String title, String text, String speech) {
        if (prefs.getBoolean(KEY_NOTIF, true)) notifyUser(title, text);
        if (prefs.getBoolean(KEY_VIBRATE, true)) vibrate();
        if (prefs.getBoolean(KEY_VOICE, false)) speak(speech);
    }

    private void notifyUser(String title, String text) {
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx).setPriority(Notification.PRIORITY_HIGH);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            ctx.getSystemService(NotificationManager.class).notify(notifId++, b.build());
        } catch (SecurityException ignored) {
            // notifications refusees par l'utilisateur
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = ctx.getSystemService(VibratorManager.class);
            v = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) return;
        long[] pattern = {0, 400, 150, 400, 150, 700};
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else v.vibrate(pattern, -1);
    }

    private synchronized void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(ctx, status -> {
            synchronized (Alerter.this) {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setLanguage(Locale.US);
                    for (String s : pendingSpeech) tts.speak(s, TextToSpeech.QUEUE_ADD, null, "alert");
                }
                pendingSpeech.clear();
            }
        });
    }

    private synchronized void speak(String text) {
        ensureTts();
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, "alert");
        else if (pendingSpeech.size() < 5) pendingSpeech.add(text);
    }
}
