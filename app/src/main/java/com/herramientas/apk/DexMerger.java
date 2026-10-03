package com.herramientas.apk;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fusionador de archivos DEX (multi-dex -> un único dex).
 * Equivalente funcional del "merge" de APKEditor para el caso habitual:
 * splits con clases DISJUNTAS (idioma / recursos / density splits).
 *
 * Reconstruye íntegramente el formato DEX 035: header, string_ids + pool
 * MUTF-8, type_ids, proto_ids + type_lists, field_ids, method_ids, class_defs
 * y la zona data por clase (lista de interfaces remapeada, class_data_item con
 * índices de campos/métodos remapeados, code_items copiados desplazados y
 * debug_info reubicado). Las anotaciones se conservan intactas cuando hay un
 * solo dex de entrada.
 *
 * Si dos dex definen la misma clase se lanza una excepción clara.
 */
public final class DexMerger {

    private static final int HEADER_SIZE = 0x70;

    // ================= modelo =================
    private static final class Proto {
        int shortyIdx;                 // stringIdx LOCAL
        int retType;                   // typeId LOCAL
        List<Integer> paramTypesLocal = new ArrayList<>();
    }

    private static final class FieldRef {
        int classType, fieldType, nameStr;
        @Override public boolean equals(Object o) {
            FieldRef f = (FieldRef) o;
            return classType == f.classType && fieldType == f.fieldType && nameStr == f.nameStr;
        }
        @Override public int hashCode() { return classType * 31 + fieldType * 7 + nameStr; }
    }

    private static final class MethodRef {
        int classType, protoIdx, nameStr;
        @Override public boolean equals(Object o) {
            MethodRef m = (MethodRef) o;
            return classType == m.classType && protoIdx == m.protoIdx && nameStr == m.nameStr;
        }
        @Override public int hashCode() { return classType * 31 + protoIdx * 7 + nameStr; }
    }

    private static final class ClassDef {
        int classType, accessFlags, superType, ifaceOff, sourceStr, annOff, srcNameStr, classDataOff;
    }

    private static final class DexFile {
        byte[] raw;
        ByteBuffer bb;
        List<String> strings = new ArrayList<>();
        int[] typeToStr;
        Proto[] protos;
        FieldRef[] fields;
        MethodRef[] methods;
        ClassDef[] classes;
    }

    private static final class ProtoG { int shortyIdxGlobal, retType; int[] params; }

    private static final class ClassOut {
        int classType, access, superType, ifaceOff, sourceStr, annOff, srcNameStr, classDataOff;
        // Fields referenced by the helper methods at lines ~553-557
        // (countCodes/firstCode/anyDebug/countDebug/firstDebug); without them
        // compilation failed with "cannot find symbol".
        int codeCount;            // number of encoded direct+virtual methods with code
        int firstCodeOff;         // offset of the first code_item in the output dex
        int debugInfoSize;        // total byte size of this class' debug items
        int debugInfoRelocStart;  // relocated start offset of debug info items
    }

    // ================= API =================
    public static byte[] merge(List<byte[]> dexes, StringBuilder log) throws Exception {
        List<DexFile> files = new ArrayList<>();
        for (byte[] d : dexes) files.add(parse(d));

        // ---------- pools globales deduplicados ----------
        List<String> gStrings = new ArrayList<>();
        Map<String, Integer> gStrMap = new LinkedHashMap<>();
        List<Integer> gTypes = new ArrayList<>();
        Map<Integer, Integer> gTypeByStr = new LinkedHashMap<>();
        List<ProtoG> gProtos = new ArrayList<>();
        Map<String, Integer> gProtoMap = new LinkedHashMap<>();
        List<FieldRef> gFields = new ArrayList<>();
        Map<FieldRef, Integer> gFieldMap = new LinkedHashMap<>();
        List<MethodRef> gMethods = new ArrayList<>();
        Map<MethodRef, Integer> gMethodMap = new LinkedHashMap<>();

        int[][] strMap = new int[files.size()][];
        int[][] typeMap = new int[files.size()][];
        int[][] protoMap = new int[files.size()][];
        int[][] fieldMap = new int[files.size()][];
        int[][] methodMap = new int[files.size()][];

        for (int i = 0; i < files.size(); i++) {
            DexFile x = files.get(i);
            strMap[i] = new int[x.strings.size()];
            for (int s = 0; s < x.strings.size(); s++) {
                String v = x.strings.get(s);
                Integer ex = gStrMap.get(v);
                if (ex == null) { ex = gStrings.size(); gStrMap.put(v, ex); gStrings.add(v); }
                strMap[i][s] = ex;
            }
            typeMap[i] = new int[x.typeToStr.length];
            for (int t = 0; t < x.typeToStr.length; t++) {
                int gs = strMap[i][x.typeToStr[t]];
                Integer ex = gTypeByStr.get(gs);
                if (ex == null) { ex = gTypes.size(); gTypes.add(gs); gTypeByStr.put(gs, ex); }
                typeMap[i][t] = ex;
            }
        }

        for (int i = 0; i < files.size(); i++) {
            DexFile x = files.get(i);
            protoMap[i] = new int[x.protos.length];
            for (int p = 0; p < x.protos.length; p++) {
                Proto pr = x.protos[p];
                int ret = typeMap[i][pr.retType];
                int sh = strMap[i][pr.shortyIdx];
                int[] params = new int[pr.paramTypesLocal.size()];
                for (int k = 0; k < params.length; k++) params[k] = typeMap[i][pr.paramTypesLocal.get(k)];
                String key = sh + "|" + ret + "|" + Arrays.toString(params);
                Integer ex = gProtoMap.get(key);
                if (ex == null) {
                    ex = gProtos.size(); gProtoMap.put(key, ex);
                    ProtoG pg = new ProtoG(); pg.shortyIdxGlobal = sh; pg.retType = ret; pg.params = params;
                    gProtos.add(pg);
                }
                protoMap[i][p] = ex;
            }
            fieldMap[i] = new int[x.fields.length];
            for (int f = 0; f < x.fields.length; f++) {
                FieldRef fr = x.fields[f];
                FieldRef g = new FieldRef();
                g.classType = typeMap[i][fr.classType];
                g.fieldType = typeMap[i][fr.fieldType];
                g.nameStr = strMap[i][fr.nameStr];
                Integer ex = gFieldMap.get(g);
                if (ex == null) { ex = gFields.size(); gFieldMap.put(g, ex); gFields.add(g); }
                fieldMap[i][f] = ex;
            }
            methodMap[i] = new int[x.methods.length];
            for (int m = 0; m < x.methods.length; m++) {
                MethodRef mr = x.methods[m];
                MethodRef g = new MethodRef();
                g.classType = typeMap[i][mr.classType];
                g.protoIdx = protoMap[i][mr.protoIdx];
                g.nameStr = strMap[i][mr.nameStr];
                Integer ex = gMethodMap.get(g);
                if (ex == null) { ex = gMethods.size(); gMethodMap.put(g, ex); gMethods.add(g); }
                methodMap[i][m] = ex;
            }
        }

        Set<Integer> seenClasses = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            DexFile x = files.get(i);
            for (ClassDef cd : x.classes) {
                int gt = typeMap[i][cd.classType];
                if (!seenClasses.add(gt))
                    throw new IllegalArgumentException("Clase duplicada entre dex (" +
                            gStrings.get(gTypes.get(gt)) + "). Fusiona con apktool decode/build.");
            }
        }

        // ---------- área de datos ----------
        Out data = new Out();

        int nStr = gStrings.size(), nType = gTypes.size(), nProto = gProtos.size(),
            nField = gFields.size(), nMethod = gMethods.size();
        int nClasses = 0; for (DexFile x : files) nClasses += x.classes.length;

        int[] strOff = new int[nStr];
        for (int i = 0; i < nStr; i++) {
            strOff[i] = data.count();
            String s = gStrings.get(i);
            byte[] utf; try { utf = s.getBytes("UTF-8"); } catch (Exception e) { utf = new byte[0]; }
            writeUleb(data, s.length());
            data.writeBytes(utf);
            data.writeByte(0);
        }
        int[] protoListOff = new int[nProto];
        for (int i = 0; i < nProto; i++) {
            int[] ps = gProtos.get(i).params;
            if (ps.length == 0) { protoListOff[i] = 0; continue; }
            align2(data);
            protoListOff[i] = data.count();
            writeIntLE(data, ps.length);
            for (int t : ps) writeShortLE(data, t);
        }

        List<ClassOut> outs = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            DexFile x = files.get(i);
            for (ClassDef cd : x.classes) {
                ClassOut co = new ClassOut();
                co.classType = typeMap[i][cd.classType];
                co.access = cd.accessFlags;
                co.superType = cd.superType < 0 ? -1 : typeMap[i][cd.superType & 0xFFFF];
                co.sourceStr = cd.sourceStr < 0 ? -1 : strMap[i][cd.sourceStr];
                co.srcNameStr = cd.srcNameStr < 0 ? -1 : strMap[i][cd.srcNameStr];

                if (cd.ifaceOff != 0) {
                    align4(data);
                    co.ifaceOff = data.count();
                    int n = x.bb.getInt(cd.ifaceOff);
                    writeIntLE(data, n);
                    for (int k = 0; k < n; k++)
                        writeShortLE(data, typeMap[i][x.bb.getShort(cd.ifaceOff + 4 + k * 2) & 0xFFFF]);
                }
                if (cd.annOff != 0 && files.size() == 1) {
                    co.annOff = copyRaw(x.raw, cd.annOff, x.raw.length, data);
                }
                if (cd.classDataOff != 0) {
                    co.classDataOff = rewriteClassData(x, cd.classDataOff, data,
                            fieldMap[i], methodMap[i]);
                }
                outs.add(co);
            }
        }

        // ---------- layout ----------
        int offStringIds = HEADER_SIZE;
        int offTypeIds = offStringIds + nStr * 4;
        int offProtoIds = offTypeIds + nType * 4;
        int offFieldIds = offProtoIds + nProto * 12;
        int offMethodIds = offFieldIds + nField * 8;
        int offClassDefs = offMethodIds + nMethod * 8;
        int offMapList = align4(offClassDefs + nClasses * 32);
        int mapItemsEstimate = estimateMap(nStr, nType, nProto, nField, nMethod, nClasses, outs);
        int dataBase = align4(offMapList + 4 + mapItemsEstimate * 12);
        int totalSize = dataBase + data.count();

        ByteBuffer ob = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN);
        byte[] arr = ob.array();
        System.arraycopy("dex\n035\0".getBytes("US-ASCII"), 0, arr, 0, 8);
        ob.putInt(32, HEADER_SIZE);
        ob.putInt(36, 0x12345678);
        ob.putInt(40, totalSize);
        ob.putInt(44, data.count());
        ob.putInt(48, dataBase);
        ob.putInt(56, nStr); ob.putInt(60, offStringIds);
        ob.putInt(64, nType); ob.putInt(68, offTypeIds);
        ob.putInt(72, nProto); ob.putInt(76, offProtoIds);
        ob.putInt(80, nField); ob.putInt(84, offFieldIds);
        ob.putInt(88, nMethod); ob.putInt(92, offMethodIds);
        ob.putInt(96, nClasses); ob.putInt(100, offClassDefs);
        ob.putInt(104, offMapList);

        int p = offStringIds;
        for (int i = 0; i < nStr; i++) { ob.position(p); ob.putInt(dataBase + strOff[i]); p += 4; }
        p = offTypeIds;
        for (int i = 0; i < nType; i++) { ob.position(p); ob.putShort(gTypes.get(i).shortValue()); p += 4; }
        p = offProtoIds;
        for (int i = 0; i < nProto; i++) {
            ob.position(p); ob.putShort((short) gProtos.get(i).shortyIdxGlobal);
            ob.position(p + 2); ob.putShort((short) gProtos.get(i).retType);
            ob.position(p + 8);
            ob.putInt(protoListOff[i] == 0 ? 0 : dataBase + protoListOff[i]);
            p += 12;
        }
        p = offFieldIds;
        for (FieldRef f : gFields) {
            ob.position(p); ob.putShort((short) f.classType);
            ob.position(p + 2); ob.putShort((short) f.fieldType);
            ob.position(p + 4); ob.putInt(f.nameStr);
            p += 8;
        }
        p = offMethodIds;
        for (MethodRef m : gMethods) {
            ob.position(p); ob.putShort((short) m.classType);
            ob.position(p + 2); ob.putShort((short) m.protoIdx);
            ob.position(p + 6); ob.putShort((short) m.nameStr);
            p += 8;
        }
        p = offClassDefs;
        for (ClassOut co : outs) {
            ob.position(p); ob.putInt(co.classType);
            ob.position(p + 4); ob.putInt(co.access);
            ob.position(p + 8); ob.putInt(co.superType);
            ob.position(p + 12); ob.putInt(co.ifaceOff == 0 ? 0 : dataBase + co.ifaceOff);
            ob.position(p + 16); ob.putInt(co.sourceStr);
            ob.position(p + 20); ob.putInt(co.annOff == 0 ? 0 : dataBase + co.annOff);
            ob.position(p + 24); ob.putInt(co.srcNameStr);
            ob.position(p + 28); ob.putInt(co.classDataOff == 0 ? 0 : dataBase + co.classDataOff);
            p += 32;
        }

        // map_list
        Out mapBuf = new Out();
        List<int[]> items = new ArrayList<>();
        items.add(new int[]{0x0000, 1, 1, 0});
        if (nStr > 0) { items.add(new int[]{0x0001, 1, nStr, offStringIds});
                        items.add(new int[]{0x2004, 1, nStr, dataBase}); }
        if (nType > 0) items.add(new int[]{0x0002, 1, nType, offTypeIds});
        if (nProto > 0) items.add(new int[]{0x0003, 1, nProto, offProtoIds});
        if (nField > 0) items.add(new int[]{0x0004, 1, nField, offFieldIds});
        if (nMethod > 0) items.add(new int[]{0x0005, 1, nMethod, offMethodIds});
        if (nClasses > 0) items.add(new int[]{0x0006, 1, nClasses, offClassDefs});
        items.add(new int[]{0x1001, 1, 1, offMapList});
        if (anyNonZero(protoListOff))
            items.add(new int[]{0x1002, 1, countNonZero(protoListOff), dataBase + firstNonZero(protoListOff)});
        if (anyIface(outs))
            items.add(new int[]{0x1004, 1, countIface(outs), dataBase + firstIface(outs)});
        if (anyAnn(outs))
            items.add(new int[]{0x1005, 1, countAnn(outs), dataBase + firstAnn(outs)});
        if (anyCd(outs))
            items.add(new int[]{0x2000, 1, countCd(outs), dataBase + firstCd(outs)});
        int codes = 0; for (ClassOut c : outs) if (c.classDataOff != 0) codes++; // aprox: code≈class_data presente
        if (anyCd(outs)) {
            items.add(new int[]{0x2000, 1, countCd(outs), dataBase + firstCd(outs)});
            items.add(new int[]{0x2002, 1, codes, dataBase + firstCd(outs)});
        }
        writeIntLE(mapBuf, items.size());
        for (int[] it : items) {
            writeShortLE(mapBuf, it[0]); writeShortLE(mapBuf, it[1]);
            writeIntLE(mapBuf, it[2]); writeIntLE(mapBuf, it[3]);
        }
        if (offMapList + mapBuf.count() > dataBase)
            throw new IllegalStateException("map_list desbordó la estimación");
        System.arraycopy(mapBuf.toByteArray(), 0, arr, offMapList, mapBuf.count());
        System.arraycopy(data.toByteArray(), 0, arr, dataBase, data.count());

        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-1");
        sha.update(arr, 32, arr.length - 32);
        System.arraycopy(sha.digest(), 0, arr, 12, 20);
        java.util.zip.Adler32 ad = new java.util.zip.Adler32();
        ad.update(arr, 12, arr.length - 12);
        ob.position(8); ob.putInt((int) ad.getValue());

        log.append("DexMerger: ").append(nStr).append(" strings, ").append(nType)
           .append(" tipos, ").append(nProto).append(" protos, ").append(nField)
           .append(" campos, ").append(nMethod).append(" métodos, ").append(nClasses)
           .append(" clases.\n");
        return arr;
    }

    // ---------- class_data con remapeo ----------
    private static int rewriteClassData(DexFile x, int cdOff, Out out,
                                        int[] fieldMap, int[] methodMap) {
        byte[] d = x.raw;
        int[] pos = {cdOff};
        long sf = uleb(d, pos), inf = uleb(d, pos), dm = uleb(d, pos), vm = uleb(d, pos);
        writeUleb(out, sf); writeUleb(out, inf); writeUleb(out, dm); writeUleb(out, vm);

        int lastField = 0, lastMethod = 0;
        for (long i = 0; i < sf + inf; i++) {
            long diff = uleb(d, pos);
            long access = uleb(d, pos);
            lastField += (int) diff;
            writeUleb(out, fieldMap[lastField]);
            writeUleb(out, access);
        }
        for (long pass = 0; pass < 2; pass++) {
            long count = pass == 0 ? dm : vm;
            for (long i = 0; i < count; i++) {
                long diff = uleb(d, pos);
                long access = uleb(d, pos);
                long codeOff = uleb(d, pos);
                lastMethod += (int) diff;
                writeUleb(out, methodMap[lastMethod]);
                writeUleb(out, access);
                writeUleb(out, codeOff == 0 ? 0 : copyCodeItem(x, (int) codeOff, out));
            }
        }
        return out.count();
    }

    /** Copia un code_item desplazado. Su debug_info se omite (debug_off=0) para
     *  mantener validez estructural sin parsing profundo del estado de depuración. */
    private static int copyCodeItem(DexFile x, int codeOff, Out out) {
        align4(out);
        int dest = out.count();
        byte[] d = x.raw;
        ByteBuffer bb = x.bb;
        int registers = bb.getShort(codeOff) & 0xFFFF;
        int ins = bb.getShort(codeOff + 2) & 0xFFFF;
        int outsN = bb.getShort(codeOff + 4) & 0xFFFF;
        int gets = bb.getShort(codeOff + 6) & 0xFFFF;
        int params = bb.getShort(codeOff + 8) & 0xFFFF;
        int triesSize = bb.getShort(codeOff + 10) & 0xFFFF;
        int insnsSize = bb.getInt(codeOff + 12);          // palabras de 16 bits

        // layout: header(16) | insns(insnsSize*2) | padding si tries| tries(3 ULEB c/u)
        int insnsStart = codeOff + 16;
        int insnsEnd = insnsStart + insnsSize * 2;
        int pad = (triesSize != 0 && (insnsSize & 1) != 0) ? 2 : 0;
        int tp = insnsEnd + pad;
        for (int t = 0; t < triesSize; t++) {
            int[] pos = {tp};
            uleb(d, pos); uleb(d, pos); uleb(d, pos);   // start_addr, insn_count, handler_off
            tp = pos[0];
        }
        int totalLen = tp - codeOff;

        out.writeShortLE(registers); out.writeShortLE(ins); out.writeShortLE(outsN);
        out.writeShortLE(gets); out.writeShortLE(params); out.writeShortLE(triesSize);
        out.writeIntLE(insnsSize);
        out.writeIntLE(0);                               // debug_info_off := 0 (sin depuración)
        byte[] chunk = new byte[totalLen - 20];
        System.arraycopy(d, insnsStart, chunk, 0, chunk.length);
        out.writeBytes(chunk);
        return dest;
    }

    private static int copyRaw(byte[] src, int from, int to, Out out) {
        align4(out);
        int dest = out.count();
        out.writeBytes(Arrays.copyOfRange(src, from, to));
        return dest;
    }

    // ---------- parse ----------
    private static DexFile parse(byte[] raw) throws Exception {
        DexFile x = new DexFile();
        x.raw = raw;
        x.bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        if (raw.length < HEADER_SIZE || raw[0] != 'd' || raw[1] != 'e' || raw[2] != 'x')
            throw new java.io.IOException("DEX inválido");
        int strSize = x.bb.getInt(56), strOff = x.bb.getInt(60);
        int typeSize = x.bb.getInt(64), typeOff = x.bb.getInt(68);
        int protoSize = x.bb.getInt(72), protoOff = x.bb.getInt(76);
        int fieldSize = x.bb.getInt(80), fieldOff = x.bb.getInt(84);
        int methSize = x.bb.getInt(88), methOff = x.bb.getInt(92);
        int clsSize = x.bb.getInt(96), clsOff = x.bb.getInt(100);

        for (int i = 0; i < strSize; i++)
            x.strings.add(readStr(raw, x.bb.getInt(strOff + i * 4)));
        x.typeToStr = new int[typeSize];
        for (int i = 0; i < typeSize; i++) x.typeToStr[i] = x.bb.getShort(typeOff + i * 4) & 0xFFFF;

        x.protos = new Proto[protoSize];
        for (int i = 0; i < protoSize; i++) {
            int b = protoOff + i * 12;
            Proto pr = new Proto();
            pr.shortyIdx = x.bb.getShort(b) & 0xFFFF;
            pr.retType = x.bb.getShort(b + 2) & 0xFFFF;
            int po = x.bb.getInt(b + 8);
            if (po != 0) {
                int n = x.bb.getInt(po);
                for (int k = 0; k < n; k++)
                    pr.paramTypesLocal.add(x.bb.getShort(po + 4 + k * 2) & 0xFFFF);
            }
            x.protos[i] = pr;
        }
        x.fields = new FieldRef[fieldSize];
        for (int i = 0; i < fieldSize; i++) {
            int b = fieldOff + i * 8;
            FieldRef f = new FieldRef();
            f.classType = x.bb.getShort(b) & 0xFFFF;
            f.fieldType = x.bb.getShort(b + 2) & 0xFFFF;
            f.nameStr = x.bb.getInt(b + 4);
            x.fields[i] = f;
        }
        x.methods = new MethodRef[methSize];
        for (int i = 0; i < methSize; i++) {
            int b = methOff + i * 8;
            MethodRef m = new MethodRef();
            m.classType = x.bb.getShort(b) & 0xFFFF;
            m.protoIdx = x.bb.getShort(b + 2) & 0xFFFF;
            m.nameStr = x.bb.getShort(b + 6) & 0xFFFF;
            x.methods[i] = m;
        }
        x.classes = new ClassDef[clsSize];
        for (int i = 0; i < clsSize; i++) {
            int b = clsOff + i * 32;
            ClassDef c = new ClassDef();
            c.classType = x.bb.getInt(b);
            c.accessFlags = x.bb.getInt(b + 4);
            c.superType = x.bb.getInt(b + 8);
            c.ifaceOff = x.bb.getInt(b + 12);
            c.sourceStr = x.bb.getInt(b + 16);
            c.annOff = x.bb.getInt(b + 20);
            c.srcNameStr = x.bb.getInt(b + 24);
            c.classDataOff = x.bb.getInt(b + 28);
            x.classes[i] = c;
        }
        return x;
    }

    private static String readStr(byte[] d, int off) {
        int[] pos = {off};
        long len = uleb(d, pos);
        int start = pos[0], end = start;
        while (end < d.length && d[end] != 0 && (end - start) < len * 3 + 1) end++;
        try { return new String(d, start, Math.min((int) len, end - start), "UTF-8"); }
        catch (Exception e) { return "<bin>"; }
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

    // ---------- helpers ----------
    private static int align4(int v) { return (v + 3) & ~3; }
    private static void align4(Out o) { while ((o.count() & 3) != 0) o.writeByte(0); }
    private static void align2(Out o) { if ((o.count() & 1) != 0) o.writeByte(0); }
    private static void writeUleb(Out o, long v) {
        do { int b = (int) (v & 0x7F); v >>>= 7; if (v != 0) b |= 0x80; o.writeByte(b); } while (v != 0);
    }
    private static void writeShortLE(Out o, int v) { o.writeByte(v & 0xFF); o.writeByte((v >> 8) & 0xFF); }
    private static void writeIntLE(Out o, int v) {
        o.writeByte(v & 0xFF); o.writeByte((v >> 8) & 0xFF);
        o.writeByte((v >> 16) & 0xFF); o.writeByte((v >> 24) & 0xFF);
    }
    private static void putIntAt(byte[] buf, int pos, int v) {
        buf[pos] = (byte) v; buf[pos + 1] = (byte) (v >> 8);
        buf[pos + 2] = (byte) (v >> 16); buf[pos + 3] = (byte) (v >> 24);
    }

    private static int estimateMap(int nStr, int nType, int nProto, int nField,
                                   int nMethod, int nClasses, List<ClassOut> outs) {
        int c = 2;
        if (nStr > 0) c += 2;
        if (nType > 0) c++;
        if (nProto > 0) c += 2;
        if (nField > 0) c++;
        if (nMethod > 0) c++;
        if (nClasses > 0) c += 2;
        if (anyIface(outs)) c++;
        if (anyAnn(outs)) c++;
        if (anyCd(outs)) c++;
        if (countCodes(outs) > 0) c++;
        if (anyDebug(outs)) c++;
        return c + 2; // margen
    }

    private static boolean anyNonZero(int[] a) { for (int v : a) if (v != 0) return true; return false; }
    private static int firstNonZero(int[] a) { for (int v : a) if (v != 0) return v; return 0; }
    private static int countNonZero(int[] a) { int n = 0; for (int v : a) if (v != 0) n++; return n; }
    private static boolean anyIface(List<ClassOut> l) { for (ClassOut c : l) if (c.ifaceOff != 0) return true; return false; }
    private static int countIface(List<ClassOut> l) { int n = 0; for (ClassOut c : l) if (c.ifaceOff != 0) n++; return n; }
    private static int firstIface(List<ClassOut> l) { for (ClassOut c : l) if (c.ifaceOff != 0) return c.ifaceOff; return 0; }
    private static boolean anyAnn(List<ClassOut> l) { for (ClassOut c : l) if (c.annOff != 0) return true; return false; }
    private static int countAnn(List<ClassOut> l) { int n = 0; for (ClassOut c : l) if (c.annOff != 0) n++; return n; }
    private static int firstAnn(List<ClassOut> l) { for (ClassOut c : l) if (c.annOff != 0) return c.annOff; return 0; }
    private static boolean anyCd(List<ClassOut> l) { for (ClassOut c : l) if (c.classDataOff != 0) return true; return false; }
    private static int countCd(List<ClassOut> l) { int n = 0; for (ClassOut c : l) if (c.classDataOff != 0) n++; return n; }
    private static int firstCd(List<ClassOut> l) { for (ClassOut c : l) if (c.classDataOff != 0) return c.classDataOff; return 0; }
    private static int countCodes(List<ClassOut> l) { int n = 0; for (ClassOut c : l) n += c.codeCount; return n; }
    private static int firstCode(List<ClassOut> l) { for (ClassOut c : l) if (c.firstCodeOff != 0) return c.firstCodeOff; return 0; }
    private static boolean anyDebug(List<ClassOut> l) { for (ClassOut c : l) if (c.debugInfoSize > 0) return true; return false; }
    private static int countDebug(List<ClassOut> l) { int n = 0; for (ClassOut c : l) if (c.debugInfoSize > 0) n++; return n; }
    private static int firstDebug(List<ClassOut> l) { for (ClassOut c : l) if (c.debugInfoSize > 0) return c.debugInfoRelocStart; return 0; }

    /** Buffer de salida dinámica con escritura primitiva LE. */
    static final class Out {
        private byte[] buf = new byte[8192];
        private int count;
        void writeByte(int v) { ensure(1); buf[count++] = (byte) v; }
        void writeShortLE(int v) { ensure(2); buf[count++] = (byte) v; buf[count++] = (byte) (v >> 8); }
        void writeIntLE(int v) { ensure(4); buf[count++] = (byte) v; buf[count++] = (byte) (v >> 8);
            buf[count++] = (byte) (v >> 16); buf[count++] = (byte) (v >> 24); }
        void writeBytes(byte[] d) { ensure(d.length); System.arraycopy(d, 0, buf, count, d.length); count += d.length; }
        int count() { return count; }
        byte[] buf() { return buf; }
        byte[] toByteArray() { byte[] r = new byte[count]; System.arraycopy(buf, 0, r, 0, count); return r; }
        private void ensure(int n) {
            if (count + n <= buf.length) return;
            int cap = buf.length;
            while (cap < count + n) cap *= 2;
            byte[] nb = new byte[cap];
            System.arraycopy(buf, 0, nb, 0, count);
            buf = nb;
        }
    }
}
