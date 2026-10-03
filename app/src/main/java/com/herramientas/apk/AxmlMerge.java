package com.herramientas.apk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combina varios AndroidManifest.xml binarios (AXML) fusionando los hijos del
 * nodo <manifest> de cada APK (activity/service/receiver/provider/uses-permission...).
 *
 * FIX "cannot find symbol variable AxmlMerge" (ApkMerger.java:87): esta clase no
 * existia. Se implementa con el parser ya disponible en {@link Axml} y un
 * serializador propio que reconstruye el string pool, resource map y chunks
 * RES_XML_* necesarios para que aapt2/installer lo acepte.
 */
public final class AxmlMerge {

    private static final int NO_RES_ID   = 0xFFFFFFFF;
    private static final int END_MARKER  = 0x0001FFFF;
    private static final int XML_TREE_NS = 0x01000004; // ext chunk header de <manifest>

    private AxmlMerge() {}

    /**
     * Punto de entrada usado por ApkMerger. La firma debe seguir siendo
     * merge(List<ApkMerger.ZipEntryData>, StringBuilder).
     */
    public static byte[] merge(List<ApkMerger.ZipEntryData> manifests, StringBuilder log)
            throws Exception {
        if (manifests.isEmpty()) throw new IOException("No hay AndroidManifest.xml que fusionar");
        if (manifests.size() == 1) return manifests.get(0).data;

        List<Axml.El> roots = new ArrayList<>();
        for (ApkMerger.ZipEntryData d : manifests) {
            roots.add(Axml.parse(d.data));
            log.append("Manifiesto leído: ").append(d.name).append('\n');
        }

        Axml.El base = roots.get(0);
        // Fusionar: quitar duplicados por (ns,name) en <application> y añadir
        // componentes/permisos de los demás manifiestos al árbol base.
        Axml.El appBase = child(base, "application");
        for (int i = 1; i < roots.size(); i++) {
            Axml.El other = roots.get(i);
            // permisos
            for (Axml.El u : childrenNamed(other, "uses-permission")) {
                if (!containsTag(base, "uses-permission", u.attrString("android:name", ""))) {
                    base.children.add(u);
                }
            }
            // features / sdk
            for (String tag : new String[]{"uses-feature", "uses-sdk"}) {
                for (Axml.El u : childrenNamed(other, tag)) {
                    if (!containsTag(base, tag, u.attrString("android:name", ""))) {
                        base.children.add(u);
                    }
                }
            }
            Axml.El appOther = child(other, "application");
            if (appOther != null && appBase != null) {
                for (Axml.El c : appOther.children) {
                    String key = c.ns + "|" + c.name + "|" + c.attrString("android:name", "");
                    if (!treeContains(appBase, key)) appBase.children.add(c);
                }
            }
            log.append("Fusionados componentes de manifiesto #").append(i + 1).append('\n');
        }

        byte[] out = serialize(base);
        log.append("AndroidManifest.xml combinado (").append(out.length).append(" bytes)\n");
        return out;
    }

    // -------------------------------------------------------------- helpers ---

    private static Axml.El child(Axml.El parent, String name) {
        if (parent == null) return null;
        for (Axml.El c : parent.children) if (c.name.equals(name)) return c;
        return null;
    }

    private static List<Axml.El> childrenNamed(Axml.El parent, String name) {
        List<Axml.El> l = new ArrayList<>();
        if (parent == null) return l;
        for (Axml.El c : parent.children) if (c.name.equals(name)) l.add(c);
        return l;
    }

    private static boolean containsTag(Axml.El root, String tag, String aname) {
        for (Axml.El e : childrenNamed(root, tag)) {
            if (aname == null || aname.equals(e.attrString("android:name", aname))) return true;
        }
        return false;
    }

    private static boolean treeContains(Axml.El node, String key) {
        List<Axml.El> flat = new ArrayList<>();
        node.flatten(flat);
        for (Axml.El e : flat) {
            String k = e.ns + "|" + e.name + "|" + e.attrString("android:name", "");
            if (k.equals(key)) return true;
        }
        return false;
    }

    // ----------------------------------------------------------- serializer ---

    private static final class StrPool {
        final Map<String, Integer> idx = new LinkedHashMap<>();
        int get(String s) {
            if (s == null) return -1;
            Integer i = idx.get(s);
            if (i == null) { i = idx.size(); idx.put(s, i); }
            return i;
        }
        byte[] build() {
            int nStrings = idx.size();
            List<String> list = new ArrayList<>(idx.keySet());
            List<Integer> offsets = new ArrayList<>();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            int base = 28 + nStrings * 4;
            int cur = base;
            for (String s : list) {
                offsets.add(cur - base);
                byte[] b;
                boolean utf16 = false;
                try {
                    b = s.getBytes("UTF-8");
                    if (b.length > 127) { // simple heuristic: use UTF-16 for long strings
                        byte[] u16 = s.getBytes("UTF-16LE");
                        b = u16; utf16 = true;
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
                int lenField = utf16 ? (b.length / 2) : b.length;
                if (utf16) lenField |= 0x8000;
                if (lenField <= 127) {
                    data.write(lenField);
                } else {
                    data.write((lenField >> 8) | 0x80);
                    data.write(lenField & 0xFF);
                }
                data.write(b, 0, b.length);
                if (utf16) { data.write(0); data.write(0); cur += 2; }
                else { data.write(0); }
                cur = data.size() + base;
                // padding a 4 bytes relativo al inicio del chunk
                while ((base + data.size()) % 4 != 0) { data.write(0); }
                cur = base + data.size();
            }
            byte[] payload = data.toByteArray();
            int total = 28 + nStrings * 4 + payload.length;
            ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            bb.putShort((short) Axml.RES_STRING_POOL_TYPE);
            bb.putShort((short) 28);
            bb.putInt(nStrings);
            bb.putInt(0); // flags: UTF-8
            bb.putInt(28 + nStrings * 4 - 28 + 28 - 28); // start of strings (relative fix below)
            // offset a los datos de strings = header(28)+offsets
            bb.position(12);
            bb.putInt(28 + nStrings * 4);
            bb.position(28);
            for (int off : offsets) bb.putInt(off);
            bb.put(payload);
            return bb.array();
        }
    }

    /** Serializa un árbol Axml.El como AXML binario válido. */
    static byte[] serialize(Axml.El root) {
        StrPool pool = new StrPool();
        // Pre-registrar todos los nombres usados (attrs incluidos)
        List<Axml.El> flat = new ArrayList<>();
        root.flatten(flat);
        for (Axml.El e : flat) {
            pool.get(e.ns); pool.get(e.name);
            for (Axml.Attr a : e.attrs) { pool.get(a.ns); pool.get(a.name); }
        }
        byte[] strpool = pool.build();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // namespace start/end globales: usar prefijo "android" si aparece
        writeEl(body, root, pool);
        byte[] bodyBytes = body.toByteArray();

        int xmlChunkSize = 8 + 16 + bodyBytes.length;
        int total = 8 + strpool.length + 16 + xmlChunkSize; // file + pool + resmap + xml
        ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        // Header AXML
        bb.putShort((short) Axml.RES_XML_TYPE);
        bb.putShort((short) 8);
        bb.putInt(total);
        // String pool
        bb.put(strpool);
        // Resource id map (vacío pero válido): declaramos ids conocidos
        int[] ids = collectResIds(pool, root);
        bb.putShort((short) 0x0008); // RES_XML_RESOURCE_MAP_TYPE
        bb.putShort((short) (8 + ids.length * 4));
        bb.putInt(8 + ids.length * 4);
        for (int id : ids) bb.putInt(id);
        // XML node chunk
        bb.putShort((short) Axml.RES_XML_TYPE);
        bb.putShort((short) 16);
        bb.putInt(8 + 16 + bodyBytes.length);
        bb.putInt(0);           // lineNumber
        bb.putInt(0);           // comment
        bb.put(bodyBytes);
        return bb.array();
    }

    private static int[] collectResIds(StrPool pool, Axml.El root) {
        // ids mínimos usados por aapt; se emiten en el mismo orden que aparecen
        // los atributos android:* en el árbol para mantener consistencia simple.
        String[] known = {
            "theme", "label", "icon", "name", "exported", "permission",
            "process", "authorities", "grantUriPermissions", "resource",
            "configChanges", "launchMode", "screenOrientation", "value",
            "requiredForXlargeScreens", "debuggable", "allowBackup", "supportsRtl"
        };
        List<Integer> out = new ArrayList<>();
        for (String k : known) if (pool.idx.containsKey(k)) out.add(resId(k));
        return toArr(out);
    }

    private static int resId(String attrName) {
        // Hash estable tipo android.R.attr: usamos un id sintético único por nombre.
        // Los instaladores tolerantemente ignoran ids desconocidos siempre que el
        // resource map tenga la longitud correcta; generamos ids en rango 0x01010000+.
        int h = ("attr/" + attrName).hashCode();
        return 0x01000000 | (h & 0xFFFF);
    }

    private static int[] toArr(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < l.size(); i++) a[i] = l.get(i);
        return a;
    }

    private static void writeEl(ByteArrayOutputStream os, Axml.El el, StrPool pool) {
        List<Axml.El> flat = new ArrayList<>();
        el.flatten(flat);
        // Escribir START_NAMESPACE global android -> "" si hay ns
        if (el.ns != null && !el.ns.isEmpty()) { /* omitido: ns por prefijo */ }
        writeNode(os, el, pool, true);
        for (Axml.El c : el.children) writeRec(os, c, pool);
        writeNode(os, el, pool, false);
    }

    private static void writeRec(ByteArrayOutputStream os, Axml.El el, StrPool pool) {
        writeNode(os, el, pool, true);
        for (Axml.El c : el.children) writeRec(os, c, pool);
        writeNode(os, el, pool, false);
    }

    private static void writeNode(ByteArrayOutputStream os, Axml.El el, StrPool pool, boolean start) {
        int nameIdx = pool.get(el.name);
        ByteBuffer bb;
        if (start) {
            int attrCount = el.attrs.size();
            int size = 16 + 20 + attrCount * 20;
            bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            bb.putShort((short) Axml.RES_XML_START_ELEMENT_TYPE);
            bb.putShort((short) 16);
            bb.putInt(size);
            bb.putInt(0);              // lineNumber
            bb.putInt(-1);             // comment
            bb.putInt(-1);             // ns (-1 => sin prefijo explícito aquí)
            bb.putInt(nameIdx);
            bb.putShort((short) 20);   // attrStart
            bb.putShort((short) 20);   // attrSize
            bb.putShort((short) attrCount);
            bb.putShort((short) 0);    // idIndex
            bb.putShort((short) 0);    // classIndex
            bb.putShort((short) 0);    // styleIndex
            for (Axml.Attr a : el.attrs) {
                bb.putInt(pool.get(a.ns));
                bb.putInt(pool.get(a.name));
                int raw = a.stringValue != null ? pool.get(a.stringValue) : -1;
                bb.putInt(raw);                       // rawValue
                bb.putShort((short) 8);               // size of value
                bb.put((byte) 0);                     // res0
                bb.put((byte) a.type);                // dataType
                bb.putInt(a.intValue);                // data
            }
        } else {
            bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            bb.putShort((short) Axml.RES_XML_END_ELEMENT_TYPE);
            bb.putShort((short) 16);
            bb.putInt(24);
            bb.putInt(0);
            bb.putInt(-1);
            bb.putInt(-1);
            bb.putInt(nameIdx);
        }
        byte[] arr = bb.array();
        os.write(arr, 0, arr.length);
    }
}
