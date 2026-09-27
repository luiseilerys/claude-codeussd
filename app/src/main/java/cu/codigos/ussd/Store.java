package cu.codigos.ussd;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Persistencia simple: favoritos, recientes (con contadores de uso) y ajustes. */
public class Store {
    private static final String FILE = "cod_cuba";
    private static final String K_FAV = "fav";
    private static final String K_USES = "uses";      // json {code: count}
    private static final String K_ORDER = "order";    // json [codes...] más reciente primero
    private static final String K_DARK = "dark";

    private final SharedPreferences sp;
    private final Set<String> favs = new LinkedHashSet<>();
    private final Map<String, Integer> uses = new HashMap<>();
    private final List<String> recentOrder = new ArrayList<>();

    public Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        favs.addAll(split(sp.getString(K_FAV, "")));
        try {
            JSONObject o = new JSONObject(TextUtils.isEmpty(sp.getString(K_USES, "{}")) ? "{}" : sp.getString(K_USES, "{}"));
            for (String k : o.keySet()) uses.put(k, o.getInt(k));
            JSONArray a = new JSONArray(TextUtils.isEmpty(sp.getString(K_ORDER, "[]")) ? "[]" : sp.getString(K_ORDER, "[]"));
            for (int i = 0; i < a.length(); i++) recentOrder.add(a.getString(i));
        } catch (Exception ignored) {
        }
    }

    private static Set<String> split(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (!TextUtils.isEmpty(s)) for (String p : s.split("\\|")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    public boolean isFav(String code) { return favs.contains(code); }

    public void toggleFav(String code) {
        if (!favs.remove(code)) favs.add(code);
        sp.edit().putString(K_FAV, TextUtils.join("|", favs)).apply();
    }

    public int getUses(String code) {
        Integer v = uses.get(code);
        return v == null ? 0 : v;
    }

    public void recordUse(String code) {
        uses.merge(code, 1, Integer::sum);
        recentOrder.remove(code);
        recentOrder.add(0, code);
        while (recentOrder.size() > 40) recentOrder.remove(recentOrder.size() - 1);
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Integer> e : uses.entrySet()) o.put(e.getKey(), e.getValue());
            JSONArray a = new JSONArray();
            for (String s : recentOrder) a.put(s);
            sp.edit().putString(K_USES, o.toString()).putString(K_ORDER, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public List<String> recentCodes() { return new ArrayList<>(recentOrder); }

    public void clearRecent() {
        recentOrder.clear();
        uses.clear();
        sp.edit().remove(K_USES).remove(K_ORDER).apply();
    }

    public boolean isDark() { return sp.getBoolean(K_DARK, false); }
    public void setDark(boolean v) { sp.edit().putBoolean(K_DARK, v).apply(); }
}
