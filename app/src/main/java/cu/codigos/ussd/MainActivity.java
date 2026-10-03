package cu.codigos.ussd;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
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
        findViewById(R.id.btnTimer).setOnClickListener(v -> showTimerDialog());
        findViewById(R.id.btnSpeed).setOnClickListener(v -> showConnectionInfo());
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
            // Fix: no usar 'var' (Java 10+) — el proyecto compila con
            // sourceCompatibility JavaVersion.VERSION_1_8.
            JSONArray cats = root.getJSONArray("categories");
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
                        + "• Categorías nuevas: «WiFi ETECSA (n@una)» y «Nauta Hogar» con todas sus configuraciones.\n"
                        + "• ⏳ Tiempo restante: lanza una burbuja flotante con cuenta regresiva (requiere permiso de mostrar sobre otras apps).\n"
                        + "• 📶 Velocidad: revisa tu conexión actual, fuerza datos móviles y abre un test de velocidad.\n"
                        + "• Filtros Celular/Fijo y categorías organizan la lista.\n"
                        + "• Modo oscuro disponible abajo.\n\n"
                        + "Los precios/paquetes pueden cambiar: verifica siempre en el menú de la operadora. "
                        + "Códigos de Cubacel, ETECSA, Nauta y códigos GSM/Android estándar.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    // ---------- tiempo restante (burbuja flotante) ----------
    private static final int REQ_OVERLAY = 77;
    private static final String[] TIMER_PRESETS = {
            "Personalizado…",
            "WiFi 30 min",
            "WiFi 1 hora",
            "WiFi 3 horas",
            "WiFi 6 horas",
            "WiFi 24 horas",
            "Paquete semanal (7 días)",
            "Paquete mensual (30 días)"
    };

    private long presetMillis(int idx) {
        switch (idx) {
            case 1: return 30 * 60_000L;
            case 2: return 60 * 60_000L;
            case 3: return 3 * 3600_000L;
            case 4: return 6 * 3600_000L;
            case 5: return 24 * 3600_000L;
            case 6: return 7 * 24 * 3600_000L;
            case 7: return 30 * 24 * 3600_000L;
            default: return -1; // personalizado
        }
    }

    private void showTimerDialog() {
        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        lay.setPadding(p, p / 2, p, 0);

        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, TIMER_PRESETS));
        lay.addView(sp);

        final EditText h = new EditText(this);
        h.setHint("Horas"); h.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText m = new EditText(this);
        m.setHint("Minutos"); m.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpw = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        h.setLayoutParams(lpw); m.setLayoutParams(lpw);
        row.addView(h); row.addView(m);
        lay.addView(row);

        final TextView status = new TextView(this);
        status.setTextSize(12f);
        status.setText(FloatService.isRunning()
                ? "⏳ La burbuja flotante está activa ahora mismo."
                : "La burbuja muestra la cuenta regresiva sobre todas las apps.");
        lay.addView(status);

        new AlertDialog.Builder(this)
                .setTitle(R.string.timer_title)
                .setView(lay)
                .setNeutralButton(FloatService.isRunning() ? "Detener burbuja" : "Cerrar", (d, w) -> {
                    if (FloatService.isRunning()) FloatService.stop(this);
                })
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Iniciar burbuja", (d, w) -> {
                    long ms;
                    int idx = sp.getSelectedItemPosition();
                    if (idx == 0) {
                        long hh = parseLong(h.getText().toString(), 0);
                        long mm = parseLong(m.getText().toString(), 0);
                        ms = hh * 3600_000L + mm * 60_000L;
                    } else {
                        ms = presetMillis(idx);
                    }
                    if (ms <= 0) { Toast.makeText(this, "Indica un tiempo mayor que cero", Toast.LENGTH_SHORT).show(); return; }
                    String label = idx == 0
                            ? "Restante"
                            : TIMER_PRESETS[idx].replace("WiFi ", "WiFi · ").replace("Paquete ", "Datos · ");
                    if (!Settings.canDrawOverlays(this)) {
                        Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show();
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception ignored) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
                        }
                        pendingOverlayStart = System.currentTimeMillis() + ms;
                        pendingOverlayLabel = label;
                        return;
                    }
                    FloatService.startWith(this, System.currentTimeMillis() + ms, label);
                    Toast.makeText(this, "Burbuja iniciada: " + label, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private long pendingOverlayStart = 0;
    private String pendingOverlayLabel = null;

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOverlayStart > 0 && Settings.canDrawOverlays(this)) {
            FloatService.startWith(this, pendingOverlayStart, pendingOverlayLabel);
            Toast.makeText(this, "Permiso concedido: burbuja iniciada", Toast.LENGTH_SHORT).show();
            pendingOverlayStart = 0;
            pendingOverlayLabel = null;
        }
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    // ---------- velocidad / estado de conexión ----------
    private void showConnectionInfo() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = null;
        if (cm != null) {
            if (Build.VERSION.SDK_INT >= 23) active = cm.getActiveNetwork();
            else {
                @SuppressWarnings("deprecation") android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    @SuppressWarnings("deprecation") Network[] ns = cm.getAllNetworks();
                    for (Network nn : ns) {
                        @SuppressWarnings("deprecation") NetworkCapabilities nnc = cm.getNetworkCapabilities(nn);
                        if (nnc != null && nnc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { active = nn; break; }
                    }
                    if (active == null && ns.length > 0) active = ns[0];
                }
            }
        }
        NetworkCapabilities nc = cm != null && active != null ? cm.getNetworkCapabilities(active) : null;

        boolean wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        boolean cell = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        boolean eth = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);

        StringBuilder sb = new StringBuilder();
        if (nc == null) sb.append("❌ Sin conexión a Internet.\n\n");
        else {
            sb.append("🔗 Conexión activa: ")
              .append(wifi ? "WiFi" : cell ? "Datos móviles" : eth ? "Cable/Ethernet" : "Otra")
              .append("\n");
            // Fix "cannot find symbol": NET_CAPABILITY_INTERNET_ACCESSIBLE is
            // a @SystemApi and is NOT part of the public SDK, so it can never
            // compile. The public equivalent for "has Internet access" is
            // NET_CAPABILITY_INTERNET (API 21+, within our minSdk 21).
            boolean internet = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            if (internet)
                sb.append("✅ Internet accesible\n");
            else if (wifi)
                sb.append("⚠️ Estás conectado al WiFi pero SIN salida a Internet: probablemente falta iniciar sesión en wifi.etecsa.cu (portal cautivo n@una).\n");
        }

        if (wifi && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            WifiManager wmgr = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wmgr != null ? wmgr.getConnectionInfo() : null;
            if (info != null) {
                int dbm = info.getRssi();
                String quality = dbm >= -50 ? "Excelente" : dbm >= -65 ? "Buena" : dbm >= -80 ? "Regular" : "Débil";
                sb.append("📡 Señal WiFi: ").append(dbm).append(" dBm (").append(quality).append(")\n");
                sb.append("   Red: ").append(info.getSSID() == null ? "?" : info.getSSID()).append("\n");
                if (Build.VERSION.SDK_INT >= 21 && info.getFrequency() > 0)
                    sb.append("   Banda: ").append(info.getFrequency() >= 4900 ? "5 GHz" : "2.4 GHz").append("\n");
            }
        } else if (wifi) {
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 65);
            sb.append("ℹ️ Concede ubicación para ver intensidad y banda del WiFi.\n");
        }

        if (cell) sb.append("📶 Datos móviles activos (consulta Mb restantes con *222*2#).\n");

        LinearLayout lay = new LinearLayout(this);
        lay.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        lay.setPadding(p, p / 2, p, 0);
        TextView msg = new TextView(this);
        msg.setText(sb.toString());
        msg.setTextSize(14f);
        lay.addView(msg);

        Spinner speedSel = new Spinner(this);
        final String[] speeds = {"Auto-detectar", "Forzar solo datos móviles", "Forzar solo WiFi"};
        speedSel.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, speeds));
        lay.addView(speedSel);

        new AlertDialog.Builder(this)
                .setTitle(R.string.speed_title)
                .setView(lay)
                .setNegativeButton("Cerrar", null)
                .setNeutralButton("Ajustes de red", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)); }
                    catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                })
                .setPositiveButton("Test de velocidad", (d, w) -> {
                    int sel = speedSel.getSelectedItemPosition();
                    applyPreferredNetwork(sel);
                    Dialer.openUrl(this, "https://www.speedtest.net/api/js/servers?engine=js&limit=1");
                    Dialer.openUrl(this, "https://fast.com/es/");
                })
                .show();
    }

    /** Cambia el modo de ahorro de datos / restricción según la opción elegida. */
    private void applyPreferredNetwork(int sel) {
        try {
            if (sel == 1) {
                startActivity(new Intent(Settings.ACTION_SETTINGS)); // sin API pública: guía manual
                Toast.makeText(this, "Apaga el WiFi para forzar datos móviles", Toast.LENGTH_LONG).show();
            } else if (sel == 2) {
                Toast.makeText(this, "Conéctate a ETH_WiFi/hogar y abre wifi.etecsa.cu si pide sesión", Toast.LENGTH_LONG).show();
            }
        } catch (Exception ignored) { }
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
            h.code.setText(c.url != null && !c.url.isEmpty() ? "🌐 " + c.url : c.code);
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
