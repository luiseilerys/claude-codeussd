package com.herramientas.apk;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ejecutor genérico de las 14 herramientas (adaptadas de virb3/apk-utilities,
 * sin ADB). Todo se resuelve con Java puro sobre archivos locales o
 * importados por SAF. minSdk 21: no se usan APIs modernas.
 */
public class ToolActivity extends Activity {

    public static final String EXTRA_TOOL = "tool";

    private static final int REQ_FILE   = 1;
    private static final int REQ_FILE2  = 2;
    private static final int REQ_FOLDER = 3;

    private int toolId;
    private File file1, file2;
    private File folder;                       // apktool build
    private final List<File> batch = new ArrayList<>(); // fusión XAPK
    private EditText input;
    private TextView output;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        toolId = getIntent().getIntExtra(EXTRA_TOOL, -1);
        setTitle(toolName(toolId));
        buildUi();
        buildForm();
    }

    // ---------------------------------------------------------------- UI ---

    private void buildUi() {
        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setPadding(24, 24, 24, 24);

        scroll = new ScrollView(this);
        scroll.addView(output);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(24, 24, 24, 12);
        this.form_ = form;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private LinearLayout form_;

    private void buildForm() {
        if (toolId == Tools.BASE64 || toolId == Tools.TEXT_SEARCH) {
            input = new EditText(this);
            input.setHint(toolId == Tools.BASE64
                    ? "Texto a codificar (o Base64 a decodificar)…"
                    : "Patrón de búsqueda…");
            input.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            form_.addView(input);
        }

        switch (toolId) {
            case Tools.APKTOOL_BUILD:
                addBtn("Seleccionar carpeta decodificada", v -> pick(REQ_FOLDER));
                break;
            case Tools.SMALI:
                addBtn("Seleccionar APK destino", v -> pick(REQ_FILE));
                addBtn("Seleccionar classes.dex parcheado", v -> pick(REQ_FILE2));
                break;
            case Tools.MERGE_XAPK:
                addBtn("Añadir APK/XAPK al lote", v -> pick(REQ_FILE2));
                break;
            default:
                addBtn("Seleccionar archivo", v -> pick(REQ_FILE));
                break;
        }

        Button run = new Button(this);
        run.setText("EJECUTAR");
        run.setOnClickListener(v -> run());
        form_.addView(run);
    }

    private void addBtn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        form_.addView(b);
    }

    private void show(String s) {
        output.setText(s);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ------------------------------------------------------------- picks ---

    private void pick(int req) {
        Intent i = new Intent(req == REQ_FOLDER
                ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT);
        if (req != REQ_FOLDER) i.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(i, req); }
        catch (Exception ex) { toast("Selector no disponible: " + ex.getMessage()); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        String name = Utils.displayName(this, u);
        try {
            switch (req) {
                case REQ_FILE:
                    file1 = Utils.importUri(this, u);
                    show("Archivo 1: " + name + " (" + human(file1.length()) + ")");
                    break;
                case REQ_FILE2:
                    file2 = Utils.importUri(this, u);
                    if (toolId == Tools.MERGE_XAPK) {
                        batch.add(file2);
                        show("Lote: " + batch.size() + " archivo(s) añadidos");
                    } else {
                        show("Archivo 2: " + name + " (" + human(file2.length()) + ")");
                    }
                    break;
                case REQ_FOLDER:
                    folder = copyTree(u, name);
                    show("Carpeta copiada a: " + folder);
                    break;
            }
        } catch (Exception e) {
            show("Error al importar: " + e);
        }
    }

    /** Copia recursiva de un árbol SAF (ACTION_OPEN_DOCUMENT_TREE) al caché. API 21+. */
    private File copyTree(Uri tree, String name) throws Exception {
        File dst = new File(getCacheDir(), "folder_" + System.currentTimeMillis());
        dst.mkdirs();
        String rootDocId = android.provider.DocumentsContract.getTreeDocumentId(tree);
        copyTreeNode(tree, rootDocId, dst);
        return dst;
    }

    private void copyTreeNode(Uri treeUri, String docId, File dir) throws Exception {
        Uri children = android.provider.DocumentsContract
                .buildChildDocumentsUriUsingTree(treeUri, docId);
        android.database.Cursor c = getContentResolver().query(children,
                new String[]{
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null);
        if (c == null) throw new RuntimeException("No se pudo leer la carpeta (¿provider sin soporte?)");
        try {
            while (c.moveToNext()) {
                String id   = c.getString(0);
                String disp = sanitize(c.getString(1));
                String mime = c.getString(2);
                Uri childUri = android.provider.DocumentsContract
                        .buildDocumentUriUsingTree(treeUri, id);
                File f = new File(dir, disp);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    f.mkdirs();
                    copyTreeNode(treeUri, id, f);
                } else {
                    java.io.InputStream in = getContentResolver().openInputStream(childUri);
                    java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                    Utils.copy(in, o);
                    in.close(); o.close();
                }
            }
        } finally {
            c.close();
        }
    }

    private static String sanitize(String s) {
        return s == null ? "file" : s.replaceAll("[^A-Za-z0-9._\\- ]", "_");
    }

    // ------------------------------------------------------------ runner ---

    private void run() {
        StringBuilder sb = new StringBuilder();
        try {
            switch (toolId) {
                case Tools.DEX_INFO: {
                    require(file1);
                    sb.append(DexInfo.dump(asDex(file1)));
                    break;
                }
                case Tools.APK_INFO: {
                    require(file1);
                    sb.append(ApkInfo.dump(file1));
                    break;
                }
                case Tools.ZIP_LIST: {
                    require(file1);
                    try (ZipFile zf = new ZipFile(file1)) {
                        List<ZipEntry> es = new ArrayList<>();
                        java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                        while (en.hasMoreElements()) es.add(en.nextElement());
                        es.sort((a, b) -> a.getName().compareTo(b.getName()));
                        long total = 0;
                        for (ZipEntry e : es) {
                            sb.append(String.format("%10d  %s%n", e.getSize(), e.getName()));
                            total += Math.max(0, e.getSize());
                        }
                        sb.append(es.size()).append(" entradas, ").append(human(total));
                    }
                    break;
                }
                case Tools.HASHES: {
                    require(file1);
                    byte[] d = Utils.readAll(new java.io.FileInputStream(file1));
                    for (String alg : new String[]{"MD5", "SHA-1", "SHA-256"}) {
                        java.security.MessageDigest md = java.security.MessageDigest.getInstance(alg);
                        byte[] h = md.digest(d);
                        StringBuilder hx = new StringBuilder();
                        for (byte x : h) hx.append(String.format("%02x", x));
                        sb.append(alg).append(": ").append(hx).append('\n');
                    }
                    break;
                }
                case Tools.HEX_VIEW: {
                    require(file1);
                    byte[] d = Utils.readAll(new java.io.FileInputStream(file1));
                    sb.append(Utils.hexDump(d, 0, Math.min(d.length, 8192)));
                    if (d.length > 8192) sb.append("\n… truncado a 8 KiB de ").append(d.length).append(" bytes");
                    break;
                }
                case Tools.BASE64: {
                    String in = textOrFile();
                    try {
                        // si parece Base64 válido, decodifica; si no, codifica
                        byte[] dec = android.util.Base64.decode(in, android.util.Base64.DEFAULT);
                        sb.append("DECODE →\n").append(new String(dec, "UTF-8"));
                    } catch (IllegalArgumentException notB64) {
                        sb.append("ENCODE →\n")
                          .append(android.util.Base64.encodeToString(
                                  in.getBytes("UTF-8"), android.util.Base64.NO_WRAP));
                    }
                    break;
                }
                case Tools.TEXT_SEARCH: {
                    require(file1);
                    String pat = input.getText().toString();
                    if (pat.isEmpty()) throw new RuntimeException("Escribe un patrón");
                    java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(new java.io.FileInputStream(file1), "UTF-8"));
                    String line; long ln = 0, hits = 0;
                    while ((line = r.readLine()) != null && hits < 500) {
                        ln++;
                        if (line.contains(pat)) { sb.append(ln).append(": ").append(line).append('\n'); hits++; }
                    }
                    r.close();
                    sb.append(hits).append(" coincidencias");
                    break;
                }
                case Tools.BAKSMALI: {
                    require(file1);
                    File dex = asDex(file1);
                    List<byte[]> parts = new ArrayList<>();
                    parts.add(Utils.readAll(new java.io.FileInputStream(dex)));
                    Baksmali.disassemble(parts, sb);
                    break;
                }
                case Tools.SMALI: {
                    require(file1); require(file2);
                    // reemplaza classes.dex dentro del APK por el dex parcheado
                    File out = new File(getCacheDir(), "patched.apk");
                    replaceDex(file1, file2, out, sb);
                    break;
                }
                case Tools.APKTOOL_DECODE: {
                    require(file1);
                    File out = new File(getCacheDir(), "decoded_" + file1.getName());
                    Utils.deleteRecursive(out); out.mkdirs();
                    decodeApk(file1, out, sb);
                    break;
                }
                case Tools.APKTOOL_BUILD: {
                    if (folder == null) throw new RuntimeException("Elige primero la carpeta decodificada");
                    File out = new File(getCacheDir(), "rebuilt_unsigned.apk");
                    buildApk(folder, out, sb);
                    break;
                }
                case Tools.SIGN: {
                    require(file1);
                    File out = new File(getCacheDir(), "signed.apk");
                    sb.append(Signer.sign(file1, null, null, null, null, out));
                    sb.append("\nSalida: ").append(out.getAbsolutePath());
                    break;
                }
                case Tools.MERGE_XAPK: {
                    if (batch.size() < 2) throw new RuntimeException("Añade al menos 2 APK/XAPK al lote");
                    File out = new File(getCacheDir(), "universal.apk");
                    sb.append(ApkMerger.merge(batch, out));
                    sb.append("\nSalida: ").append(out.getAbsolutePath());
                    break;
                }
                case Tools.INSTALL_APK: {
                    require(file1);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.fromFile(file1), "application/vnd.android.package-archive");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Instalar APK"));
                    sb.append("Instalador abierto. Si Android bloquea la fuente, activa"
                            + " «Instalar apps desconocidas» para esta app.");
                    break;
                }
                default:
                    throw new RuntimeException("Herramienta desconocida");
            }
        } catch (Throwable t) {
            sb.append("ERROR: ").append(t).append('\n');
            StackTraceElement[] st = t.getStackTrace();
            for (int k = 0; k < Math.min(8, st.length); k++) sb.append("  at ").append(st[k]).append('\n');
        }
        show(sb.toString());
    }

    // --------------------------------------------------------- helpers ----

    private String textOrFile() throws Exception {
        if (input != null && input.getText().length() > 0)
            return input.getText().toString();
        require(file1);
        return new String(Utils.readAll(new java.io.FileInputStream(file1)), "UTF-8").trim();
    }

    private void require(File f) {
        if (f == null) throw new RuntimeException("Selecciona primero un archivo");
    }

    /** Si es APK/ZIP devuelve su classes.dex extraído; si es .dex lo pasa tal cual. */
    private File asDex(File f) throws Exception {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".dex")) return f;
        File out = new File(getCacheDir(), "extracted.dex");
        try (ZipFile zf = new ZipFile(f)) {
            ZipEntry e = zf.getEntry("classes.dex");
            if (e == null) throw new RuntimeException("No contiene classes.dex");
            Utils.copy(zf.getInputStream(e), new java.io.FileOutputStream(out));
        }
        return out;
    }

    private void replaceDex(File apk, File newDex, File out, StringBuilder sb) throws Exception {
        try (ZipFile zf = new ZipFile(apk);
             java.util.zip.ZipOutputStream zo =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.getName().startsWith("META-INF/")) continue; // se re-firma después
                zo.putNextEntry(new ZipEntry(e.getName()));
                if (e.getName().equals("classes.dex"))
                    Utils.copy(new java.io.FileInputStream(newDex), zo);
                else
                    Utils.copy(zf.getInputStream(e), zo);
                zo.closeEntry();
            }
        }
        sb.append("classes.dex sustituido en ").append(out)
          .append("\nIMPORTANTE: vuelve a firmar con «Firmar APK» antes de instalar.");
    }

    /** apktool decode nativo: manifiesto a texto + recursos + dex a smali. */
    private void decodeApk(File apk, File out, StringBuilder sb) throws Exception {
        try (ZipFile zf = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                File dst = new File(out, name);
                dst.getParentFile().mkdirs();
                if (name.endsWith(".xml") && !name.startsWith("res/values")) {
                    // binario AXML → texto legible
                    try {
                        Axml.El root = Axml.parse(zf.getInputStream(e));
                        java.io.PrintWriter pw = new java.io.PrintWriter(dst, "UTF-8");
                        pw.print(Axml.toText(root));
                        pw.close();
                        sb.append("AXML→txt: ").append(name).append('\n');
                    } catch (Exception ex) {
                        Utils.copy(zf.getInputStream(e), new java.io.FileOutputStream(dst));
                        sb.append("AXML copiado (sin convertir): ").append(name).append('\n');
                    }
                } else if (name.endsWith(".dex")) {
                    Utils.copy(zf.getInputStream(e), new java.io.FileOutputStream(dst));
                    List<byte[]> one = new ArrayList<>();
                    one.add(Utils.readAll(new java.io.FileInputStream(dst)));
                    Baksmali.disassemble(one, sb);
                } else {
                    Utils.copy(zf.getInputStream(e), new java.io.FileOutputStream(dst));
                }
            }
        }
        sb.append("Decodificado en: ").append(out.getAbsolutePath());
    }

    /** apktool build nativo: empaqueta la carpeta decodificada en un APK sin firmar. */
    private void buildApk(File dir, File out, StringBuilder sb) throws Exception {
        try (java.util.zip.ZipOutputStream zo =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
            zipRec(dir, "", zo, sb);
        }
        sb.append("APK reconstruido (sin firmar): ").append(out.getAbsolutePath())
          .append("\nPásalo por «Firmar APK» para poder instalarlo.");
    }

    private void zipRec(File d, String base, java.util.zip.ZipOutputStream zo, StringBuilder sb)
            throws Exception {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String rel = base.isEmpty() ? f.getName() : base + "/" + f.getName();
            if (f.isDirectory()) { zipRec(f, rel, zo, sb); continue; }
            if (rel.endsWith("/AndroidManifest.xml.txt") || rel.equals("AndroidManifest.xml.txt"))
                continue; // placeholder de texto; el build usa el .xml original
            zo.putNextEntry(new ZipEntry(rel));
            Utils.copy(new java.io.FileInputStream(f), zo);
            zo.closeEntry();
        }
    }

    private static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    static String toolName(int id) {
        switch (id) {
            case Tools.DEX_INFO: return "Información de DEX";
            case Tools.APK_INFO: return "Información de APK";
            case Tools.APKTOOL_DECODE: return "apktool decode";
            case Tools.APKTOOL_BUILD: return "apktool build";
            case Tools.BAKSMALI: return "baksmali";
            case Tools.SMALI: return "smali (inyectar dex)";
            case Tools.SIGN: return "Firmar APK";
            case Tools.MERGE_XAPK: return "Fusionar XAPK/splits";
            case Tools.ZIP_LIST: return "Listar ZIP/APK";
            case Tools.HASHES: return "Hashes MD5/SHA1/SHA256";
            case Tools.HEX_VIEW: return "Visor hexadecimal";
            case Tools.BASE64: return "Base64";
            case Tools.TEXT_SEARCH: return "Búsqueda en texto";
            case Tools.INSTALL_APK: return "Instalar APK";
            default: return "Herramienta";
        }
    }
}
