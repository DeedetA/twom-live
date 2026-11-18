package fr.twomlive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Couche transparente par-dessus le jeu : un cercle autour de chaque mob surveille.
 * Elle ne recoit aucune touche (tout passe au jeu), sauf le petit panneau de calibration.
 *
 * Ecran = centre + decalage + (position du mob - ta position) x echelle (x ratio vertical pour y).
 */
public final class Overlay {
    static final String KEY_ON = "overlay_on", KEY_SCALE = "ov_scale", KEY_RATIO = "ov_ratio",
            KEY_DX = "ov_dx", KEY_DY = "ov_dy";

    private static Overlay instance;

    private final Context ctx;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private MarkView view;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView panelInfo;
    volatile boolean calibrating = false;
    volatile boolean appInFront = false;
    float scale, ratio, dx, dy;

    public static synchronized Overlay get(Context c) {
        if (instance == null) instance = new Overlay(c.getApplicationContext());
        return instance;
    }

    private Overlay(Context c) {
        ctx = c;
        wm = c.getSystemService(WindowManager.class);
        prefs = c.getSharedPreferences(Alerter.PREFS, Context.MODE_PRIVATE);
        float defScale = c.getResources().getDisplayMetrics().density * 0.9f;
        scale = prefs.getFloat(KEY_SCALE, defScale);
        ratio = prefs.getFloat(KEY_RATIO, 1f);
        dx = prefs.getFloat(KEY_DX, 0f);
        dy = prefs.getFloat(KEY_DY, 0f);
    }

    public boolean canDraw() { return Settings.canDrawOverlays(ctx); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics());
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** affiche ou retire les cercles */
    public void setEnabled(boolean on) {
        if (on && canDraw()) {
            if (view != null) return;
            view = new MarkView(ctx);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.alpha = 0.8f;   // au-dela, Android 12+ bloque les touches vers le jeu
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            try { wm.addView(view, lp); } catch (RuntimeException e) { view = null; }
        } else if (!on) {
            calibrating = false;
            removePanel();
            if (view != null) {
                try { wm.removeView(view); } catch (RuntimeException ignored) { }
                view = null;
            }
        }
    }

    private void save() {
        prefs.edit().putFloat(KEY_SCALE, scale).putFloat(KEY_RATIO, ratio)
                .putFloat(KEY_DX, dx).putFloat(KEY_DY, dy).apply();
        if (panelInfo != null) panelInfo.setText(String.format(Locale.US,
                "scale %.2f  ratio %.2f  offset %.0f,%.0f", scale, ratio, dx, dy));
    }

    /** mode calibration : cercles sur tous les mobs + panneau de reglage flottant */
    @SuppressLint("ClickableViewAccessibility")
    public void startCalibration() {
        if (!canDraw()) return;
        setEnabled(true);
        calibrating = true;
        if (panel != null) return;
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.argb(220, 30, 30, 40));
        panel.setPadding(dp(6), dp(4), dp(6), dp(6));

        TextView handle = new TextView(ctx);
        handle.setText("Calibration (drag here) - circle every mob, then Done");
        handle.setTextColor(Color.WHITE);
        handle.setTextSize(11);
        panel.addView(handle);
        panelInfo = new TextView(ctx);
        panelInfo.setTextColor(Color.rgb(255, 204, 128));
        panelInfo.setTextSize(11);
        panel.addView(panelInfo);

        LinearLayout r1 = row(), r2 = row();
        r1.addView(btn("Size -", () -> scale /= 1.06f));
        r1.addView(btn("Size +", () -> scale *= 1.06f));
        r1.addView(btn("Height -", () -> ratio -= 0.03f));
        r1.addView(btn("Height +", () -> ratio += 0.03f));
        r2.addView(btn("Left", () -> dx -= dp(6)));
        r2.addView(btn("Right", () -> dx += dp(6)));
        r2.addView(btn("Up", () -> dy -= dp(6)));
        r2.addView(btn("Down", () -> dy += dp(6)));
        r2.addView(btn("Done", this::stopCalibration));
        panel.addView(r1);
        panel.addView(r2);
        save();

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = dp(8);
        panelLp.y = dp(8);
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    sx = e.getRawX(); sy = e.getRawY(); ox = panelLp.x; oy = panelLp.y;
                } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                    panelLp.x = ox + (int) (e.getRawX() - sx);
                    panelLp.y = oy + (int) (e.getRawY() - sy);
                    try { wm.updateViewLayout(panel, panelLp); } catch (RuntimeException ignored) { }
                }
                return true;
            }
        });
        try { wm.addView(panel, panelLp); } catch (RuntimeException e) { panel = null; }
    }

    private void removePanel() {
        if (panel != null) {
            try { wm.removeView(panel); } catch (RuntimeException ignored) { }
            panel = null;
            panelInfo = null;
        }
    }

    public void stopCalibration() {
        calibrating = false;
        removePanel();
        if (!prefs.getBoolean(KEY_ON, false)) setEnabled(false);   // cercles desactives : on retire tout
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button btn(String text, Runnable action) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        b.setOnClickListener(v -> { action.run(); save(); });
        return b;
    }

    // ------------------------------------------------------------------ dessin
    final class MarkView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Map<String, float[]> smooth = new HashMap<>();
        private final Runnable tick = this::invalidate;

        MarkView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(3.5f));
            ring.setColor(Color.rgb(255, 120, 0));
            thin.setStyle(Paint.Style.STROKE);
            thin.setStrokeWidth(dp(1.5f));
            thin.setColor(Color.rgb(0, 230, 118));
            label.setColor(Color.WHITE);
            label.setTextSize(dp(12));
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextAlign(Paint.Align.CENTER);
            labelBg.setColor(Color.argb(170, 0, 0, 0));
            cross.setColor(Color.rgb(0, 176, 255));
            cross.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            postDelayed(tick, 33);                       // ~30 images/s
            if (appInFront && !calibrating) return;      // rien a entourer dans notre propre ecran
            int[] me = GameState.INSTANCE.self();
            float cx = getWidth() / 2f + dx, cy = getHeight() / 2f + dy;
            if (me == null) {
                if (calibrating) {
                    String t = "Move your character once so the app knows where you are";
                    c.drawRect(cx - dp(170), cy - dp(60), cx + dp(170), cy - dp(36), labelBg);
                    c.drawText(t, cx, cy - dp(43), label);
                }
                smooth.clear();
                return;
            }
            if (calibrating) {                          // ta position : croix bleue
                c.drawLine(cx - dp(14), cy, cx + dp(14), cy, cross);
                c.drawLine(cx, cy - dp(14), cx, cy + dp(14), cross);
            }
            List<GameState.Mark> marks = GameState.INSTANCE.marks(calibrating);
            Set<String> keep = new HashSet<>();
            float r = Math.max(dp(16), Math.min(dp(60), dp(26) * scale / getResources().getDisplayMetrics().density));
            for (GameState.Mark m : marks) {
                float tx = cx + (m.x - me[0]) * scale;
                float ty = cy + (m.y - me[1]) * scale * ratio;
                float[] p = smooth.get(m.id);
                if (p == null) { p = new float[]{tx, ty}; smooth.put(m.id, p); }
                else { p[0] += (tx - p[0]) * 0.25f; p[1] += (ty - p[1]) * 0.25f; }   // mouvement adouci
                keep.add(m.id);
                if (m.watched) {
                    c.drawCircle(p[0], p[1], r, ring);
                    float w = label.measureText(m.name) / 2 + dp(4);
                    c.drawRect(p[0] - w, p[1] - r - dp(20), p[0] + w, p[1] - r - dp(4), labelBg);
                    c.drawText(m.name, p[0], p[1] - r - dp(8), label);
                } else {
                    c.drawCircle(p[0], p[1], r, thin);
                }
            }
            smooth.keySet().retainAll(keep);
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }
    }
}
