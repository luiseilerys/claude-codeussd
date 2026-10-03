package com.herramientas.apk;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser DEX 100% Java (equivalente a `dexdump` / baksmali-listclasses).
 * Funciona en Android 5 sin librerías externas: lee cabecera, string_ids,
 * type_ids y class_defs para listar clases, interfaces y métodos.
 */
public final class DexInfo {

    public static String dump(File dexFile) throws IOException {
        byte[] data;
        try (RandomAccessFile raf = new RandomAccessFile(dexFile, "r")) {
            data = new byte[(int) raf.length()];
            raf.readFully(data);
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        String magic = new String(data, 0, 8, "US-ASCII").trim();
        StringBuilder sb = new StringBuilder();
        sb.append("=== DEX: ").append(dexFile.getName()).append(" ===\n");
        sb.append("Tamaño: ").append(data.length).append(" bytes\n");
        if (!magic.startsWith("dex\n")) {
            sb.append("ERROR: no es un archivo DEX válido (magic=").append(magic).append(")\n");
            return sb.toString();
        }
        int version = data[6] - '0';
        sb.append("Versión DEX: 0").append(version).append('\n');
        if (version < 35 || version > 40)
            sb.append("(advertencia: versión no estándar)\n");

        int headerSize      = bb.getInt(32);
        int endianTag       = bb.getInt(36);
        int stringIdsSize   = bb.getInt(56);  int stringIdsOff   = bb.getInt(60);
        int typeIdsSize     = bb.getInt(64);  int typeIdsOff     = bb.getInt(68);
        int protoIdsSize    = bb.getInt(72);  int protoIdsOff    = bb.getInt(76);
        int fieldIdsSize    = bb.getInt(80);  int fieldIdsOff    = bb.getInt(84);
        int methodIdsSize   = bb.getInt(88);  int methodIdsOff   = bb.getInt(92);
        int classDefsSize   = bb.getInt(96);  int classDefsOff   = bb.getInt(100);

        sb.append("header_size=").append(headerSize)
          .append(" endian=").append(Integer.toHexString(endianTag)).append('\n');
        sb.append("strings=").append(stringIdsSize)
          .append(" types=").append(typeIdsSize)
          .append(" protos=").append(protoIdsSize)
          .append(" fields=").append(fieldIdsSize)
          .append(" methods=").append(methodIdsSize)
          .append(" class_defs=").append(classDefsSize).append("\n\n");

        // --- tabla de strings (ULEB128 length + UTF-16/MUTF8 data) ----------
        String[] strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            int off = bb.getInt(stringIdsOff + i * 4);
            strings[i] = readString(data, off);
        }

        // --- type ids -> nombres -------------------------------------------
        String[] types = new String[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            int idx = bb.getShort(typeIdsOff + i * 4) & 0xFFFF;
            types[i] = idx < strings.length ? strings[idx] : "?";
        }

        sb.append("=== CLASES (").append(classDefsSize).append(") ===\n");
        for (int i = 0; i < classDefsSize; i++) {
            int p = classDefsOff + i * 32;
            int classIdx   = bb.getInt(p);
            int accessFlags= bb.getInt(p + 4);
            int superclass = bb.getInt(p + 8);
            // interfaces_off -> lista de tipos
            int interfacesOff = bb.getInt(p + 12);
            int sourceIdx    = bb.getInt(p + 24);
            int classDataOff = bb.getInt(p + 28);

            String name = classIdx < types.length ? types[classIdx] : "?";
            sb.append(name).append("  [")
              .append(accessFlagsToString(accessFlags)).append("]");
            if (superclass >= 0 && superclass < types.length)
                sb.append("  extends ").append(types[superclass]);
            if (interfacesOff != 0) {
                List<String> ifs = readTypeList(bb, data, interfacesOff, types);
                if (!ifs.isEmpty()) sb.append("  implements ").append(join(ifs, ", "));
            }
            if (sourceIdx >= 0 && sourceIdx < strings.length)
                sb.append("  fuente=").append(strings[sourceIdx]);
            sb.append('\n');

            if (classDataOff != 0) {
                appendMethods(sb, data, classDataOff, types, strings,
                        methodIdsOff, bb);
            }
        }
        return sb.toString();
    }

    /** method_ids: cada 8 bytes = short classIdx, uint protoIdx, short nameIdx */
    private static void appendMethods(StringBuilder sb, byte[] data, int classDataOff,
                                      String[] types, String[] strings,
                                      int methodIdsOff, ByteBuffer bb) {
        int[] pos = {classDataOff};
        long staticFields  = readUleb(data, pos);
        long instanceFields= readUleb(data, pos);
        long directMethods = readUleb(data, pos);
        long virtualMethods= readUleb(data, pos);
        int uidx = 0;
        for (long pass = 0; pass < 2; pass++) {
            long count = pass == 0 ? directMethods : virtualMethods;
            for (long m = 0; m < count; m++) {
                uidx += (int) readUleb(data, pos);          // method_idx diff
                int access = (int) readUleb(data, pos);
                /*code_off*/ readUleb(data, pos);
                if (uidx * 8 + 8 <= data.length) {
                    int nameIdx = bb.getShort(methodIdsOff + uidx * 8 + 6) & 0xFFFF;
                    String mn = nameIdx < strings.length ? strings[nameIdx] : "?";
                    sb.append("    ")
                      .append(pass == 0 ? "direct  " : "virtual ")
                      .append('[').append(accessFlagsToString(access)).append("] ")
                      .append(mn).append('\n');
                }
            }
        }
    }

    private static List<String> readTypeList(ByteBuffer bb, byte[] data, int off, String[] types) {
        List<String> r = new ArrayList<>();
        int size = bb.getInt(off);
        for (int i = 0; i < size; i++) {
            int t = bb.getShort(off + 4 + i * 2) & 0xFFFF;
            if (t < types.length) r.add(types[t]);
        }
        return r;
    }

    private static String readString(byte[] data, int off) {
        int[] pos = {off};
        long len = readUleb(data, pos);
        int start = pos[0];
        // MUTF-8: decodificación simple ASCII-safe con fallback
        try {
            int end = start;
            while (end < data.length && data[end] != 0 && (end - start) < len * 3 + 1) end++;
            return new String(data, start, Math.min((int) len, end - start), "UTF-8");
        } catch (Exception e) {
            return "<bin>";
        }
    }

    static long readUleb(byte[] data, int[] pos) {
        long result = 0; int shift = 0;
        while (pos[0] < data.length) {
            int b = data[pos[0]++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static String accessFlagsToString(int f) {
        StringBuilder s = new StringBuilder();
        String[] names = {"public","private","protected","static","final",
                "synchronized","bridge|volatile","transient|varargs",
                "native","interface|abstract?","abstract|strict","synthetic",
                "annotation","enum"};
        int[] bits = {1,2,4,8,16,32,64,128,256,512,1024,4096,8192,16384};
        for (int i = 0; i < bits.length; i++)
            if ((f & bits[i]) != 0) { if (s.length()>0) s.append(' '); s.append(names[i].split("\\|")[0]); }
        return s.toString();
    }

    private static String join(List<String> l, String sep) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i>0) s.append(sep); s.append(l.get(i)); }
        return s.toString();
    }

    private DexInfo() {}
}
