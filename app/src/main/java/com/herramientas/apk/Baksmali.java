package com.herramientas.apk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * baksmali mínimo en Java puro (sin dependencias, Android 5+):
 * desensambla classes.dex a instrucciones Dalvik legibles.
 * Decodifica el formato de 16/32 bits con sus operandos (registers,
 * literals, offsets de ramas y referencias a campos/métodos/string/types).
 */
public final class Baksmali {

    public static void disassemble(List<byte[]> dexes, StringBuilder sb) throws Exception {
        for (byte[] raw : dexes) disassembleOne(raw, sb);
    }

    // ------------------------------------------------------------------ //

    private static void disassembleOne(byte[] d, StringBuilder sb) throws Exception {
        if (d.length < 112 || !(d[0] == 'd' && d[1] == 'e' && d[2] == 'x'))
            throw new RuntimeException("No es un DEX válido");
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);

        int stringIdsSize = bb.getInt(56), stringIdsOff = bb.getInt(60);
        int typeIdsOff    = bb.getInt(68);
        int protoIdsOff   = bb.getInt(76);
        int fieldIdsOff   = bb.getInt(84);
        int methodIdsOff  = bb.getInt(92);
        int classDefsSize = bb.getInt(96), classDefsOff = bb.getInt(100);

        String[] strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++)
            strings[i] = DexInfo_dumpString(d, bb.getInt(stringIdsOff + i * 4));

        String[] types = new String[bb.getInt(64)];
        for (int i = 0; i < types.length; i++) {
            int sidx = bb.getShort(typeIdsOff + i * 4) & 0xFFFF;
            types[i] = sidx < strings.length ? strings[sidx] : "?";
        }

        sb.append("===== BAKSMALI (").append(types.length).append(" tipos, ")
          .append(classDefsSize).append(" clases) =====\n");

        for (int ci = 0; ci < classDefsSize; ci++) {
            int p = classDefsOff + ci * 32;
            int classIdx = bb.getInt(p);
            int access   = bb.getInt(p + 4);
            int superIdx = bb.getInt(p + 8);
            int classDataOff = bb.getInt(p + 28);
            String name = classIdx < types.length ? types[classIdx] : "?L?;";

            sb.append("\n.class ").append(accessFlags(access)).append(name).append('\n');
            if (superIdx >= 0 && superIdx < types.length)
                sb.append(".super ").append(types[superIdx]).append('\n');

            if (classDataOff == 0) { sb.append("# (sin campos ni métodos)\n"); continue; }

            int[] pos = {classDataOff};
            long sf = uleb(d, pos), inf = uleb(d, pos), dm = uleb(d, pos), vm = uleb(d, pos);
            long lastF = 0;
            for (long i = 0; i < sf + inf; i++) {
                lastF += uleb(d, pos);
                long faccess = uleb(d, pos);
                int fidx = (int) lastF;
                sb.append(".field ").append(accessFlags((int) faccess))
                  .append(fieldName(fieldIdsOff, bb, strings, fidx))
                  .append(": ").append(fieldType(fieldIdsOff, bb, types, fidx))
                  .append('\n');
            }
            long lastM = 0;
            for (long pass = 0; pass < 2; pass++) {
                long count = pass == 0 ? dm : vm;
                for (long i = 0; i < count; i++) {
                    lastM += uleb(d, pos);
                    long maccess = uleb(d, pos);
                    long codeOff = uleb(d, pos);
                    int midx = (int) lastM;
                    sb.append("\n.method ").append(accessFlags((int) maccess))
                      .append(methodName(methodIdsOff, bb, strings, midx))
                      .append(methodProto(methodIdsOff, protoIdsOff, bb, strings, types, midx))
                      .append('\n');
                    if (codeOff != 0) decodeCode(d, bb, (int) codeOff, strings, types,
                            fieldIdsOff, methodIdsOff, sb);
                    sb.append(".end method\n");
                }
            }
        }
    }

    // ------------------------------------------------------------- código ---

    private static void decodeCode(byte[] d, ByteBuffer bb, int codeOff,
                                   String[] strings, String[] types,
                                   int fieldIdsOff, int methodIdsOff, StringBuilder sb) {
        int registers = bb.getShort(codeOff) & 0xFFFF;
        int ins       = bb.getShort(codeOff + 2) & 0xFFFF;
        int outs      = bb.getShort(codeOff + 4) & 0xFFFF;
        int insnsSize = bb.getInt(codeOff + 12);
        sb.append("    .registers ").append(registers).append('\n');
        sb.append("    .insns size=").append(insnsSize).append(" (regs in=")
          .append(ins).append(" out=").append(outs).append(")\n");

        int end = codeOff + 16 + insnsSize * 2;
        for (int pc = codeOff + 16; pc + 1 < end; ) {
            int unit = bb.getShort(pc) & 0xFFFF;
            int op = unit & 0xFF;
            int line = String.format("    %04x: ", (pc - codeOff - 16) / 2);
            int width;
            switch (op) {
                case 0x00: sb.append(line).append("nop\n");  width = 2; break;
                case 0x01: // move
                    sb.append(line).append("v").append((unit >> 12) & 0xF)
                      .append(", v").append((unit >> 8) & 0xF).append('\n'); width = 2; break;
                case 0x02: case 0x03: case 0x04: case 0x05: // move-wide/from16/16/whole
                    sb.append(line).append(OPC[op]).append(" v").append((unit >> 12) & 0xF)
                      .append(", v").append((unit >> 8) & 0xF).append('\n'); width = 2; break;
                case 0x06: case 0x07: case 0x08: case 0x09: case 0x0A: case 0x0B: // move-object*
                    sb.append(line).append(OPC[op]).append(" v").append((unit >> 12) & 0xF)
                      .append(", v").append((unit >> 8) & 0xF).append('\n'); width = 2; break;
                case 0x0D: // move-exception
                    sb.append(line).append("move-exception v").append(unit >> 12).append('\n'); width = 2; break;
                case 0x0E: case 0x0F: case 0x10: // return(-wide,-object)
                    sb.append(line).append(OPC[op]);
                    if ((unit >> 12) != 0) sb.append(" v").append(unit >> 12);
                    sb.append('\n'); width = 2; break;
                case 0x12: // const/4
                    sb.append(line).append("const/4 v").append(unit >> 12)
                      .append(", #").append(s4(unit >> 8)).append('\n'); width = 2; break;
                case 0x13: case 0x19: case 0x1A: // const/16, const/high16, const-wide/16
                    sb.append(line).append(OPC[op]).append(" v").append(unit >> 12)
                      .append(", #").append(s16(bb.getShort(pc + 2))).append('\n'); width = 4; break;
                case 0x14: case 0x15: case 0x16: case 0x17: case 0x18: // const wide 32/64
                case 0x1B: case 0x1C: case 0x1D: case 0x1E: {
                    int lit = readInt(d, pc + 4);
                    sb.append(line).append(OPC[op]).append(" v").append(unit >> 12)
                      .append(", #").append(lit).append(" (0x").append(Integer.toHexString(lit))
                      .append(")\n");
                    width = (op == 0x14 || op == 0x16 || op == 0x18 || op == 0x1C) ? 6 : 8;
                    break;
                }
                case 0x1F: { // const-string
                    int strIdx = bb.getShort(pc + 2) & 0xFFFF;
                    sb.append(line).append("const-string v").append(unit >> 12)
                      .append(", \"").append(esc(strAt(strings, strIdx))).append("\"\n");
                    width = 4; break;
                }
                case 0x20: { // const-class
                    int t = bb.getShort(pc + 2) & 0xFFFF;
                    sb.append(line).append("const-class v").append(unit >> 12)
                      .append(", ").append(t < types.length ? types[t] : "?").append('\n');
                    width = 4; break;
                }
                case 0x50: case 0x51: case 0x52: case 0x53: case 0x54: case 0x55: case 0x56:
                case 0x57: case 0x58: case 0x59: case 0x5A: case 0x5B: case 0x5C: case 0x5D: // iget/iput (22c)
                    sb.append(line).append(OPC[op]).append(" v")
                      .append((unit >> 8) & 0xF).append(", v").append(unit >> 12)
                      .append(", [field@").append(bb.getShort(pc + 2) & 0xFFFF).append("]\n");
                    width = 4; break;
                case 0x32: case 0x33: case 0x34: case 0x35: case 0x36: case 0x37:
                case 0x38: // if-*
                    sb.append(line).append(OPC[op]).append(" v").append(unit >> 12)
                      .append(", +").append(s16(bb.getShort(pc + 2))).append('\n');
                    width = 4; break;
                case 0x39: case 0x3A: case 0x3B: case 0x3C: case 0x3D: case 0x3E:
                    sb.append(line).append(OPC[op]).append(" v").append((unit >> 8) & 0xF)
                      .append(", v").append(unit >> 12)
                      .append(", +").append(s16(bb.getShort(pc + 2))).append('\n');
                    width = 4; break;
                case 0x3F: // goto
                    sb.append(line).append("goto +").append(s16(bb.getShort(pc + 2))).append('\n');
                    width = 4; break;
                case 0x27: case 0x2B: { // monitor-enter/exit (10x)
                    sb.append(line).append(OPC[op]).append(" v").append(unit >> 12).append('\n');
                    width = 2; break;
                }
                case 0x2A: { // inter-to-short? no: 0x2A fill-array-data-handler no existe como instrucción
                    sb.append(line).append(String.format("op_%02x%n", op));
                    width = 2; break;
                }
                case 0x2C: case 0x2D: { // nop-in-line / invalid
                    sb.append(line).append(String.format("op_%02x%n", op));
                    width = 2; break;
                }
                case 0x2E: case 0x2F: { // iget-boolean-wide? -> en realidad 0x52+; estos son unused
                    sb.append(line).append(String.format("op_%02x%n", op));
                    width = 2; break;
                }
                case 0x28: case 0x29: { // packed-switch / sparse-switch (tabla fuera de línea)
                    sb.append(line).append(OPC[op]).append(" v").append(unit >> 12)
                      .append(", tablaswitch@").append(readInt(d, pc + 4)).append('\n');
                    width = 4; break;
                }
                default: {
                    // ancho real por formato Dalvik para no descarrilar el PC
                    int w;
                    if ((op >= 0x52 && op <= 0x6F) || (op >= 0x22 && op <= 0x2D)
                            || (op >= 0x70 && op <= 0x7A) || op == 0x31 || op == 0x30
                            || (op >= 0x20 && op <= 0x21) || (op >= 0x44 && op <= 0x45)
                            || (op >= 0x42 && op <= 0x43))
                        w = 4;                       // 22c/22x/23x/31c/31i/21c/35c/3rc
                    else if (op == 0x0C || op == 0x11 || op == 0x1B || op == 0x1E
                            || op == 0x24 || op == 0x25
                            || (op >= 0x46 && op <= 0x49) || (op >= 0x4E && op <= 0x4F)
                            || (op >= 0x62 && op <= 0x6D) || (op >= 0x7B && op <= 0x8F)
                            || (op >= 0xB0 && op <= 0xCF))
                        w = 2;                       // 10x/11x/11n/12x
                    else
                        w = 2;
                    String nm = OPC[op];
                    if (nm != null) {
                        sb.append(line).append(nm).append(" v").append((unit >> 8) & 0xF)
                          .append(", v").append(unit >> 12);
                        if (w == 4) sb.append(", +").append(s16(bb.getShort(pc + 2)));
                        sb.append('\n');
                    } else {
                        sb.append(line).append(String.format("op_%02x (0x%04x)%n", op, unit));
                    }
                    width = w; break;
                }
            }
            pc += width;
        }
    }

    // -------------------------------------------------------------- util ---

    /** Nombres parciales de opcode (los más comunes; el resto sale como hex). */
    private static final String[] OPC = new String[0x100];
    static {
        String[] n = {
            "move","move/from16","move/16","move-wide","move-wide/from16","move-wide/16",
            "move-object","move-object/from16","move-object/16","move-result","move-result-wide",
            "move-result-object","move-exception","return-void","return","return-wide","return-object",
            "const/4","const/16","const","const/high16","const-wide/16","const-wide/32","const-wide",
            "const-wide/high16","const-string","const-string/jumbo","const-class","monitor-enter",
            "monitor-exit","check-cast","instance-of","array-length","new-instance",
            "new-array","filled-new-array","fill-array-data","packed-switch","sparse-switch",
            "cmpl-float","cmpg-float","cmpl-double","cmpg-double","cmp-long",
            "if-eq","if-ne","if-lt","if-ge","if-gt","if-le","if-eqz","if-nez","if-ltz","if-gez","if-gtz","if-lez",
            "goto","goto/16","goto/32","packed-switch-data","sparse-switch-data",
            "aget","aget-wide","aget-object","aget-boolean","aget-byte","aget-char","aget-short",
            "aput","aput-wide","aput-object","aput-boolean","aput-byte","aput-char","aput-short",
            "iget","iget-wide","iget-object","iget-boolean","iget-byte","iget-char","iget-short",
            "iput","iput-wide","iput-object","iput-boolean","iput-byte","iput-char","iput-short",
            "invoke-virtual","invoke-super","invoke-direct","invoke-static","invoke-interface",
            "invoke-virtual/range","invoke-super/range","invoke-direct/range","invoke-static/range","invoke-interface/range",
            "neg-int","not-int","int-to-long","int-to-float","int-to-double","long-to-int","long-to-float",
            "long-to-double","float-to-int","float-to-double","double-to-int","double-to-float",
            "int-to-byte","int-to-char","int-to-short",
            "add-int","sub-int","mul-int","div-int","rem-int","and-int","or-int","xor-int","shl-int","shr-int","ushr-int",
            "add-long","sub-long","mul-long","div-long","rem-long","and-long","or-long","xor-long","shl-long","shr-long","ushr-long",
            "add-float","sub-float","mul-float","div-float","rem-float",
            "add-double","sub-double","mul-double","div-double","rem-double"};
        for (int i = 0; i < n.length && i + 1 < 0x100; i++) OPC[i + 1] = n[i];
        OPC[0] = "nop";
    }

    private static String esc(String s) {
        return s == null ? "?" : s.replace("\"", "\\\"");
    }

    private static String strAt(String[] ss, int i) { return i < ss.length ? ss[i] : "?"; }

    private static String fieldName(int off, ByteBuffer bb, String[] ss, int idx) {
        int nameIdx = bb.getShort(off + idx * 8 + 6) & 0xFFFF;
        return strAt(ss, nameIdx);
    }

    private static String fieldType(int off, ByteBuffer bb, String[] types, int idx) {
        int t = bb.getShort(off + idx * 8 + 4) & 0xFFFF;
        return t < types.length ? types[t] : "?";
    }

    private static String methodName(int off, ByteBuffer bb, String[] ss, int idx) {
        int nameIdx = bb.getShort(off + idx * 8 + 6) & 0xFFFF;
        return strAt(ss, nameIdx);
    }

    private static String methodProto(int mOff, int pOff, ByteBuffer bb,
                                      String[] ss, String[] types, int idx) {
        int protoIdx = bb.getShort(mOff + idx * 8 + 2) & 0xFFFF;
        int retT = bb.getInt(pOff + protoIdx * 12 + 4);
        int paramsOff = bb.getInt(pOff + protoIdx * 12 + 8);
        StringBuilder r = new StringBuilder("(");
        if (paramsOff != 0) {
            int size = bb.getInt(paramsOff) & 0xFFFF;
            for (int k = 0; k < size; k++) {
                int t = bb.getShort(paramsOff + 4 + k * 2) & 0xFFFF;
                r.append(t < types.length ? types[t] : "?");
            }
        }
        r.append(")").append(retT < types.length ? types[retT] : "?");
        return r.toString();
    }

    private static String accessFlags(int f) {
        StringBuilder s = new StringBuilder();
        int[][] table = {{0x1,"public"},{0x2,"private"},{0x4,"protected"},{0x8,"static"},
                {0x10,"final"},{0x20,"synchronized"},{0x40,"volatile/bridge"},{0x80,"transient/varargs"},
                {0x100,"native"},{0x400,"abstract"},{0x1000,"synthetic"},{0x2000,"annotation"},{0x4000,"enum"}};
        for (int[] e : table) if ((f & e[0]) != 0) s.append(e[1]).append(' ');
        return s.toString();
    }

    private static String DexInfo_dumpString(byte[] d, int off) {
        try {
            int[] pos = {off};
            long len = uleb(d, pos);
            int start = pos[0];
            int nl = start;
            while (nl < d.length && d[nl] != 0) nl++;
            return new String(d, start, Math.min(nl - start, (int) Math.min(len, 4096)), "UTF-8");
        } catch (Exception e) { return "<bin>"; }
    }

    private static long uleb(byte[] d, int[] pos) {
        long r = 0; int sh = 0;
        while (pos[0] < d.length) {
            int b = d[pos[0]++] & 0xFF;
            r |= ((long) (b & 0x7F)) << sh;
            if ((b & 0x80) == 0) break;
            sh += 7;
        }
        return r;
    }

    private static int readInt(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p+1] & 0xFF) << 8) | ((d[p+2] & 0xFF) << 16) | ((d[p+3] & 0xFF) << 24);
    }

    private static int s4(int v) { return v > 7 ? v - 16 : v; }
    private static int s16(short v) { return v; }

    private Baksmali() {}
}
