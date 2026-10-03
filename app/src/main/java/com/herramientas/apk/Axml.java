package com.herramientas.apk;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodificador AXML (Android Binary XML) propio, 100% Java, sin APIs ocultas.
 * Formato: magic 00080003, header, string pool UTF-16/UTF-8, nodo de recursos,
 * START/END ELEMENT con atributos tipados (TYPE_INT_DEC / STRING / REFERENCE...).
 */
public final class Axml {

    public static final int RES_NULL_TYPE         = 0x0000;
    public static final int RES_STRING_POOL_TYPE  = 0x0001;
    public static final int RES_XML_TYPE          = 0x0003;
    public static final int RES_XML_START_NAMESPACE_TYPE = 0x0100;
    public static final int RES_XML_END_NAMESPACE_TYPE   = 0x0101;
    public static final int RES_XML_START_ELEMENT_TYPE   = 0x0102;
    public static final int RES_XML_END_ELEMENT_TYPE     = 0x0103;
    public static final int RES_XML_CDATA_TYPE           = 0x0104;

    // tipos de valor de atributo
    public static final int TYPE_NULL      = 0x00;
    public static final int TYPE_REFERENCE = 0x01;
    public static final int TYPE_ATTRIBUTE = 0x02;
    public static final int TYPE_STRING    = 0x03;
    public static final int TYPE_FLOAT     = 0x04;
    public static final int TYPE_INT_DEC   = 0x10;
    public static final int TYPE_INT_HEX   = 0x11;
    public static final int TYPE_INT_BOOLEAN = 0x12;

    /** Elemento del árbol AXML. */
    public static final class El {
        public String ns = "", name;
        public List<Attr> attrs = new ArrayList<>();
        public List<El> children = new ArrayList<>();
        public String text; // cdata

        public Attr attr(String n) {
            for (Attr a : attrs) if (a.name.equals(n)) return a;
            return null;
        }

        public String attrString(String n, String def) {
            Attr a = attr(n);
            return a != null && a.stringValue != null ? a.stringValue : def;
        }

        public int attrInt(String n, int def) {
            Attr a = attr(n);
            if (a == null) return def;
            if (a.type == TYPE_INT_DEC || a.type == TYPE_INT_HEX
                    || a.type == TYPE_INT_BOOLEAN || a.type == TYPE_REFERENCE)
                return a.intValue;
            try { return Integer.decode(a.stringValue); } catch (Exception e) { return def; }
        }

        /** Recorre en profundidad todos los elementos. */
        public void flatten(List<El> out) {
            out.add(this);
            for (El c : children) c.flatten(out);
        }
    }

    public static final class Attr {
        public String ns = "", name;
        public int type, intValue;
        public String stringValue;
    }

    private final byte[] buf;
    private String[] strings;

    private Axml(byte[] buf) { this.buf = buf; }

    public static El parse(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return parse(readAllBytes(in));
        }
    }

    public static El parse(InputStream in) throws IOException {
        return parse(readAllBytes(in));
    }

    public static El parse(byte[] data) throws IOException {
        Axml ax = new Axml(data);
        DataInputStream d = new DataInputStream(new java.io.ByteArrayInputStream(data));
        int magic = le32(data, 0);
        if ((magic & 0xFFFF) != RES_XML_TYPE)
            throw new IOException("No es AXML (magic=" + Integer.toHexString(magic) + ")");
        int fileSize = le32(data, 4);
        int pos = 8;
        // header del XML chunk (RES_XML_START_TYPE): lineNumber(4) comment(4)=8 bytes extra
        int poolSize = le32(data, pos);
        ax.strings = readStringPool(data, pos);
        pos += poolSize;
        El root = new El();
        root.name = "";
        List<El> stack = new ArrayList<>();
        stack.add(root);
        while (pos + 8 <= Math.min(fileSize, data.length)) {
            int type = le16(data, pos);
            int hdr  = le16(data, pos + 2);
            int size = le32(data, pos + 4);
            if (size <= 0) break;
            if (type == RES_XML_START_ELEMENT_TYPE) {
                El el = new El();
                // ext: lineNumber(4) comment(4) startNs(4) startChr(4) endNs(4) endChr(4)
                //       attrStart(2) attrSize(2) attrCount(2) idIndex(2) classIndex(2) styleIndex(2)
                //       themeFlag? -> resource start (4+4+4) after 28 bytes of nodeExtHeader
                int np = pos + 16; // después de lineNumber+comment+chunkheader... ver below
                int resOff = pos + 8 + 28; // node header estándar
                int nsIdx = le32(data, pos + 8 + 8);
                int nameIdx = le32(data, pos + 8 + 16);
                el.name = str(ax.strings, nameIdx);
                el.ns = str(ax.strings, nsIdx);
                int attrCount = le16(data, pos + 8 + 24);
                int attrStart = pos + 8 + 28 + 8; // tras ResXMLTree_attrExt + ResStringPool_header de attrs
                for (int i = 0; i < attrCount; i++) {
                    int ap = attrStart + i * 20;
                    Attr a = new Attr();
                    int aNs = le32(data, ap);
                    int aName = le32(data, ap + 4);
                    int rawVal = le32(data, ap + 8);
                    int sizeH = le16(data, ap + 12);
                    int res0 = data[ap + 15] & 0xFF;
                    int dataType = data[ap + 16] & 0xFF;
                    int aValue = le32(data, ap + 16); // includes type byte at LSB
                    a.ns = str(ax.strings, aNs);
                    a.name = str(ax.strings, aName);
                    a.type = dataType;
                    a.intValue = aValue;
                    if (rawVal >= 0) a.stringValue = str(ax.strings, rawVal);
                    else if (dataType == TYPE_STRING) a.stringValue = str(ax.strings, aValue >>> 8 ^ (aValue & 0xFF) << 24 | 0); // raro
                    el.attrs.add(a);
                }
                stack.get(stack.size() - 1).children.add(el);
                stack.add(el);
            } else if (type == RES_XML_END_ELEMENT_TYPE) {
                if (stack.size() > 1) stack.remove(stack.size() - 1);
            } else if (type == RES_XML_CDATA_TYPE) {
                int idx = le32(data, pos + 16);
                if (stack.size() > 1) {
                    El top = stack.get(stack.size() - 1);
                    top.text = (top.text == null ? "" : top.text) + str(ax.strings, idx);
                }
            }
            pos += size;
        }
        if (root.children.isEmpty()) throw new IOException("AXML vacío");
        return root.children.get(0);
    }

    private static String str(String[] pool, int idx) {
        if (pool == null || idx < 0 || idx >= pool.length) return null;
        return pool[idx];
    }

    /** Lee la string pool (UTF-16 o UTF-8) del chunk en offset dado. */
    private static String[] readStringPool(byte[] d, int off) {
        int type = le16(d, off);
        if (type != RES_STRING_POOL_TYPE) return new String[0];
        int stringCount = le32(d, off + 8);
        int flags = le32(d, off + 16);
        int stringsStart = le32(d, off + 20);
        boolean utf8 = (flags & (1 << 8)) != 0;
        int dataStart = off + stringsStart;
        String[] out = new String[stringCount];
        for (int i = 0; i < stringCount; i++) {
            int so = off + 4 * (i + 3) + le32(d, off + 12 + i * 4) - stringsStart;
            // offsets relativos a stringsStart dentro del área de strings
            int rel = le32(d, off + 12 + i * 4);
            int p = dataStart + rel;
            try {
                if (utf8) {
                    // u16len (número de codepoints), u8len (bytes), luego datos
                    int skip = decodeUshortLen(d, p);
                    int len = decodeUshortLen(d, p + skip);
                    int start = p + skip + decodeUshortLenAt(d, p + skip);
                    out[i] = new String(d, start, len, "UTF-8");
                } else {
                    int[] l = decodeU16Len(d, p);
                    out[i] = new String(d, p + l[1], l[0] * 2, "UTF-16LE");
                }
            } catch (Exception e) {
                out[i] = null;
            }
        }
        return out;
    }

    private static int decodeUshortLen(byte[] d, int p) {
        int hi = d[p] & 0xFF;
        if ((hi & 0x80) != 0) return 2;
        return 1;
    }

    private static int decodeUshortLenAt(byte[] d, int p) {
        int hi = d[p] & 0xFF;
        if ((hi & 0x80) != 0) {
            int lo = d[p + 1] & 0xFF;
            return ((hi & 0x7F) << 8) | lo;
        }
        return hi;
    }

    /** Devuelve [caracteres, bytesConsumidosPorLongitud]. */
    private static int[] decodeU16Len(byte[] d, int p) {
        int hi = d[p] & 0xFF;
        if ((hi & 0x80) != 0) {
            int lo = d[p + 1] & 0xFF;
            int len = ((hi & 0x7F) << 8) | lo;
            return new int[]{len, 2};
        }
        return new int[]{hi, 1};
    }

    static int le16(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    static int le32(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8)
                | ((d[p + 2] & 0xFF) << 16) | ((d[p + 3] & 0xFF) << 24);
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toByteArray();
    }

    /** Formatea el árbol como texto tipo `aapt dump xmltree`. */
    public static String toText(El root) {
        StringBuilder sb = new StringBuilder();
        write(sb, root, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, El el, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("E: ").append(prefix(el.ns)).append(el.name).append('\n');
        for (Attr a : el.attrs) {
            for (int i = 0; i < depth + 1; i++) sb.append("  ");
            sb.append(prefix(a.ns)).append(a.name).append("=");
            switch (a.type) {
                case TYPE_STRING:
                case TYPE_ATTRIBUTE:
                    sb.append('"').append(a.stringValue).append('"');
                    break;
                case TYPE_INT_BOOLEAN:
                    sb.append(a.intValue != 0 ? "true" : "false");
                    break;
                case TYPE_REFERENCE:
                    sb.append(String.format("0x%08X", a.intValue));
                    break;
                case TYPE_FLOAT:
                    sb.append(Float.intBitsToFloat(a.intValue));
                    break;
                default:
                    if (a.stringValue != null) sb.append('"').append(a.stringValue).append('"');
                    else sb.append(a.intValue);
            }
            sb.append('\n');
        }
        if (el.text != null && !el.text.isEmpty()) {
            for (int i = 0; i < depth + 1; i++) sb.append("  ");
            sb.append("#text: ").append(el.text).append('\n');
        }
        for (El c : el.children) write(sb, c, depth + 1);
    }

    private static String prefix(String ns) {
        if (ns == null || ns.isEmpty()) return "";
        if ("android".equals(ns)) return "android:";
        return ns + ":";
    }
}
