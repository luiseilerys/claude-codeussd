package cu.codigos.ussd;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private final List<Code> all = new ArrayList<>();
    private final List<Code> shown = new ArrayList<>();
    private final Map<String, String> catNames = new LinkedHashMap<>();
    private Store store;
    private Adapter adapter;

    private EditText etSearch;
    private LinearLayout catBar;
    private TextView tabAll, tabFav, tabRecent, fAll, fCel, fFij;
    private View empty;

    private int tabMode = 0;        // 0 todos, 1 favs, 2 recientes
    private String opFilter = "todos"; // todos | celular | fijo
    private String catFilter = null;

    @Override
    protected void onCreate(Bundle b) {
        store = new Store(this);
        AppCompatDelegate.setDefaultNightMode(
                store.isDark() ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        etSearch = findViewById(R.id.etSearch);
        catBar = findViewById(R.id.catBar);
        tabAll = findViewById(R.id.tabAll);
        tabFav = findViewById(R.id.tabFav);
        tabRecent = findViewById(R.id.tabRecent);
        fAll = findViewById(R.id.fAll);
        fCel = findViewById(R.id.fCel);
        fFij = findViewById(R.id.fFij);
        empty = findViewById(R.id.empty);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);

        loadAssets();
        buildChips();
        wireTabs();

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilters(); }
        });

        findViewById(R.id.btnCalc).setOnClickListener(v -> showCalc());
        findViewById(R.id.btnDark).setOnClickListener(v -> {
            boolean dark = !store.isDark();
            store.setDark(dark);
            AppCompatDelegate.setDefaultNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
            recreate();
        });
        findViewById(R.id.btnAbout).setOnClickListener(v -> showAbout());
    }

    // ---------- datos ----------
    private void loadAssets() {
        try (InputStream in = getAssets().open("codes.json")) {
            byte[] buf = new byte[in.available()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
            }
            String json = new String(buf, 0, n, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(json);
            var cats = root.getJSONArray("categories");
            for (int i = 0; i < cats.length(); i++) {
                JSONObject o = cats.getJSONObject(i);
                catNames.put(o.getString("id"), o.getString("name"));
            }
            all.clear();
            all.addAll(Code.fromJson(root.getJSONArray("codes")));
        } catch (Exception e) {
            Toast.makeText(this, "Error al cargar códigos: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void buildChips() {
        catBar.removeAllViews();
        addChip(null, "Todas");
        for (Map.Entry<String, String> e : catNames.entrySet()) addChip(e.getKey(), e.getValue());
    }

    private void addChip(String id, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(ContextCompat.getColorStateList(this, R.color.text_main));
        tv.setBackgroundResource(R.drawable.bg_chip);
        int padH = dp(14), padV = dp(7);
        tv.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        tv.setLayoutParams(lp);
        tv.setSelected(id == null && catFilter == null || id != null && id.equals(catFilter));
        tv.setOnClickListener(v -> {
            catFilter = id;
            refreshChipStates();
            applyFilters();
        });
        tv.setTag(id);
        catBar.addView(tv);
    }

    private void refreshChipStates() {
        for (int i = 0; i < catBar.getChildCount(); i++) {
            View ch = catBar.getChildAt(i);
            Object tag = ch.getTag();
            boolean sel = (tag == null && catFilter == null) || (tag != null && tag.equals(catFilter));
            ch.setSelected(sel);
            ch.setAlpha(sel ? 1f : 0.65f);
        }
    }

    private void wireTabs() {
        tabAll.setOnClickListener(v -> setTab(0));
        tabFav.setOnClickListener(v -> setTab(1));
        tabRecent.setOnClickListener(v -> setTab(2));
        fAll.setOnClickListener(v -> { opFilter = "todos"; refreshOpStates(); applyFilters(); });
        fCel.setOnClickListener(v -> { opFilter = "celular"; refreshOpStates(); applyFilters(); });
        fFij.setOnClickListener(v -> { opFilter = "fijo"; refreshOpStates(); applyFilters(); });
        setTab(0);
        refreshOpStates();
        refreshChipStates();
    }

    private void setTab(int m) {
        tabMode = m;
        tabAll.setSelected(m == 0); tabFav.setSelected(m == 1); tabRecent.setSelected(m == 2);
        tabAll.setAlpha(m == 0 ? 1f : 0.7f);
        tabFav.setAlpha(m == 1 ? 1f : 0.7f);
        tabRecent.setAlpha(m == 2 ? 1f : 0.7f);
        applyFilters();
    }

    private void refreshOpStates() {
        fAll.setSelected(opFilter.equals("todos"));
        fCel.setSelected(opFilter.equals("celular"));
        fFij.setSelected(opFilter.equals("fijo"));
        fAll.setAlpha(opFilter.equals("todos") ? 1f : 0.6f);
        fCel.setAlpha(opFilter.equals("celular") ? 1f : 0.6f);
        fFij.setAlpha(opFilter.equals("fijo") ? 1f : 0.6f);
    }

    // ---------- filtros combinados (búsqueda + categoría + pestaña + operador) ----------
    private void applyFilters() {
        String q = etSearch.getText().toString();
        List<Code> src;
        if (tabMode == 1) {
            src = new ArrayList<>();
            for (Code c : all) if (store.isFav(c.code)) src.add(c);
        } else if (tabMode == 2) {
            Map<String, Code> byKey = new LinkedHashMap<>();
            for (Code c : all) byKey.put(c.code, c);
            src = new ArrayList<>();
            for (String k : store.recentCodes()) {
                Code c = byKey.get(k);
                if (c != null) src.add(c);
            }
        } else {
            src = all;
        }

        shown.clear();
        for (Code c : src) {
            if (!opFilter.equals("todos")) {
                if (opFilter.equals("celular") && !(c.op.equals("celular") || c.op.equals("todos"))) continue;
                if (opFilter.equals("fijo") && !(c.op.equals("fijo") || c.op.equals("todos"))) continue;
            }
            if (catFilter != null && !catFilter.equals(c.cat)) continue;
            if (!c.matches(q)) continue;
            shown.add(c);
        }

        // ordenar por popularidad cuando no hay búsqueda ni recientes
        if (q.isEmpty() && tabMode != 2) {
            shown.sort((a, bb) -> Integer.compare(store.getUses(bb.code), store.getUses(a.code)));
        }

        adapter.notifyDataSetChanged();
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ---------- acciones de tarjeta ----------
    private void onDial(Code c) {
        if (c.danger) {
            new AlertDialog.Builder(this)
                    .setTitle("Código peligroso")
                    .setMessage(getString(R.string.danger_warning) + "\n\n" + c.code)
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Entiendo, marcar", (d, w) -> doDial(c))
                    .show();
            return;
        }
        doDial(c);
    }

    private void doDial(Code c) {
        Dialer.handleCode(this, c, () -> {
            store.recordUse(c.code);
            runOnUiThread(this::applyFilters);
        });
    }

    private void onCopy(Code c) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("ussd", c.code));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private void onShare(Code c) {
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("text/plain");
        it.putExtra(Intent.EXTRA_TEXT, c.code + " — " + c.title + "\n" + c.desc + "\n(vía Códigos Cuba USSD)");
        startActivity(Intent.createChooser(it, getString(R.string.share_via)));
    }

    // ---------- mecánicas extra ----------
    private void showCalc() {
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        lay.setPadding(p, p / 2, p, 0);

        final EditText mb = new EditText(this);
        mb.setHint("Mb que usas al mes");
        mb.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        final EditText price = new EditText(this);
        price.setHint("Precio por Mb (CUP) — ej. 0.10");
        price.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        lay.addView(mb);
        lay.addView(price);

        new AlertDialog.Builder(this)
                .setTitle(R.string.calc_title)
                .setView(lay)
                .setNegativeButton("Cerrar", null)
                .setPositiveButton("Calcular", (d, w) -> {
                    try {
                        double m = Double.parseDouble(mb.getText().toString());
                        double pr = Double.parseDouble(price.getText().toString());
                        double cost = m * pr;
                        String gb = String.format("%.2f GB", m / 1024.0);
                        new AlertDialog.Builder(this)
                                .setTitle("Resultado")
                                .setMessage(gb + " ≈ " + String.format("%.2f CUP", cost)
                                        + "\n\nConsejo: los paquetes *555# suelen costar menos por Mb que el saldo normal.")
                                .setPositiveButton("OK", null)
                                .show();
                    } catch (Exception e) {
                        Toast.makeText(this, "Revisa los números", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Cómo usar")
                .setMessage("• Toca MARCAR para ejecutar el código USSD (o abrir el marcador).\n"
                        + "• ⭐ marca tus códigos favoritos; «Recientes» guarda lo último usado.\n"
                        + "• Busca por palabra («saldo», «recarga», «paquete») o escribiendo parte del código.\n"
                        + "• Los códigos con parámetros abren un formulario para completarlos.\n"
                        + "• Filtros Celular/Fijo y categorías organizan la lista.\n"
                        + "• Modo oscuro disponible abajo.\n\n"
                        + "Los precios/paquetes pueden cambiar: verifica siempre en el menú de la operadora. "
                        + "Códigos de Cubacel, ETECSA, Nauta y códigos GSM/Android estándar.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    // ---------- adapter ----------
    class Adapter extends RecyclerView.Adapter<VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_code, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Code c = shown.get(pos);
            h.code.setText(c.code);
            h.title.setText(c.title);
            h.desc.setText(c.danger ? "⚠ " + c.desc : c.desc);
            if (c.danger) h.code.getPaintFlags(); // keep simple
            h.cat.setText(catNames.getOrDefault(c.cat, c.cat));
            h.fav.setImageResource(store.isFav(c.code) ? R.drawable.ic_star : R.drawable.ic_star_outline);
            h.fav.setOnClickListener(v -> {
                store.toggleFav(c.code);
                notifyItemChanged(h.getBindingAdapterPosition());
                if (tabMode == 1) applyFilters();
            });
            h.dial.setOnClickListener(v -> onDial(c));
            h.copy.setOnClickListener(v -> onCopy(c));
            h.share.setOnClickListener(v -> onShare(c));
            h.itemView.setOnLongClickListener(v -> { onCopy(c); return true; });
        }

        @Override
        public int getItemCount() { return shown.size(); }
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView code, title, desc, cat, dial, copy, share;
        ImageView fav;
        VH(View v) {
            super(v);
            code = v.findViewById(R.id.tvCode);
            title = v.findViewById(R.id.tvTitle);
            desc = v.findViewById(R.id.tvDesc);
            cat = v.findViewById(R.id.tvCat);
            dial = v.findViewById(R.id.btnDial);
            copy = v.findViewById(R.id.btnCopy);
            share = v.findViewById(R.id.btnShare);
            fav = v.findViewById(R.id.btnFav);
        }
    }
}
