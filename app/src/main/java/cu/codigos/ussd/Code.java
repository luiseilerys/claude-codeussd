package cu.codigos.ussd;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class Code {
    public String code, title, desc, cat, op;
    public boolean danger = false;
    public List<String> params = new ArrayList<>();
    public List<String> tags = new ArrayList<>();
    public int uses = 0; // usado para "recientes"/popularidad

    public static List<Code> fromJson(JSONArray arr) throws Exception {
        List<Code> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Code c = new Code();
            c.code = o.getString("c");
            c.title = o.getString("t");
            c.desc = o.optString("d", "");
            c.cat = o.getString("cat");
            c.op = o.optString("op", "todos");
            c.danger = o.optBoolean("danger", false);
            JSONArray p = o.optJSONArray("params");
            if (p != null) for (int j = 0; j < p.length(); j++) c.params.add(p.getString(j));
            JSONArray t = o.optJSONArray("tag");
            if (t != null) for (int j = 0; j < t.length(); j++) c.tags.add(t.getString(j));
            out.add(c);
        }
        return out;
    }

    public String key() { return code; }

    public boolean matches(String q) {
        if (q == null || q.isEmpty()) return true;
        q = q.toLowerCase().trim();
        if (code.toLowerCase().contains(q)) return true;
        if (title.toLowerCase().contains(q)) return true;
        if (desc.toLowerCase().contains(q)) return true;
        for (String t : tags) if (t.toLowerCase().contains(q)) return true;
        // búsqueda por palabras sueltas sobre todo el texto
        String all = (code + " " + title + " " + desc + " " + String.join(" ", tags)).toLowerCase();
        for (String w : q.split("\\s+")) if (!all.contains(w)) return false;
        return true;
    }
}
