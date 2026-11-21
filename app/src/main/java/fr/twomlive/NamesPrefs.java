package fr.twomlive;

import android.content.Context;
import android.content.SharedPreferences;

/** Enregistre le tableau des noms (onglet Table) dans les preferences de l'appli. */
final class NamesPrefs {
    private static boolean attached;

    private NamesPrefs() { }

    static synchronized void attach(Context c) {
        if (attached) return;
        attached = true;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(Alerter.PREFS, Context.MODE_PRIVATE);
        Names n = GameState.INSTANCE.names;
        n.load(p.getString("names", null));
        n.onChange = () -> p.edit().putString("names", n.serialize()).apply();
        // ancien champ "Crystal costs" (versions 1.21 a 1.23) : recopie dans le tableau
        String old = p.getString("skill_costs", null);
        if (old != null) {
            GameState.INSTANCE.setSkillCosts(old);
            p.edit().remove("skill_costs").apply();
        }
    }
}
