package com.herramientas.apk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Fusionador de APKs split / XAPK (equivalente a `merge.sh` con APKEditor):
 * 1) une todos los classes.dex en un multi-dex válido,
 * 2) combina los AndroidManifest.xml binarios (axmlc) cuando hay varios paquetes,
 * 3) copia recursos/librerías de cada split al APK resultante.
 * Sin dependencias externas: usa DexMerger propio y AxmlMerge para manifiestos.
 */
public final class ApkMerger {

    public static String merge(List<File> apks, File out) throws Exception {
        StringBuilder log = new StringBuilder();
        if (apks.size() < 2) throw new IOException("Se requieren al menos 2 APK/XAPK");

        // 1) reunir todas las entries
        List<ZipEntryData> all = new ArrayList<>();
        for (File apk : apks) {
            try (ZipFile zf = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> e = zf.entries();
                while (e.hasMoreElements()) {
                    ZipEntry ze = e.nextElement();
                    if (ze.isDirectory()) continue;
                    if (isSignature(ze.getName())) continue;
                    byte[] data = Utils.readAll(zf.getInputStream(ze));
                    all.add(new ZipEntryData(ze.getName(), data, ze.getMethod()));
                }
            }
            log.append("Leído ").append(apk.getName()).append('\n');
        }

        // 2) si algún input es .xapk/.apks (zip de apks), expandir primero
        //    (los archivos internos ya terminan en .apk y fueron leídos como zip? no:
        //     xapk contiene *.apk sin descomprimir -> se tratan aparte)
        List<ZipEntryData> expanded = new ArrayList<>();
        for (ZipEntryData d : all) {
            String n = d.name.toLowerCase();
            if ((n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm")) && looksLikeZip(d.data)) {
                try (java.util.zip.ZipFile inner = new java.util.zip.ZipFile(writeTemp(d))) {
                    Enumeration<? extends ZipEntry> e = inner.entries();
                    while (e.hasMoreElements()) {
                        ZipEntry ze = e.nextElement();
                        if (ze.isDirectory() || isSignature(ze.getName())) continue;
                        expanded.add(new ZipEntryData(ze.getName(),
                                Utils.readAll(inner.getInputStream(ze)), ze.getMethod()));
                    }
                    log.append("Expandido contenedor ").append(d.name).append('\n');
                }
            } else expanded.add(d);
        }
        all = expanded;

        // 3) fusionar dexes
        List<byte[]> dexParts = new ArrayList<>();
        List<String> dexNames = new ArrayList<>();
        List<ZipEntryData> rest = new ArrayList<>();
        List<ZipEntryData> manifests = new ArrayList<>();
        for (ZipEntryData d : all) {
            if (d.name.matches("classes\\d*\\.dex")) { dexParts.add(d.data); dexNames.add(d.name); }
            else if (d.name.equals("AndroidManifest.xml")) manifests.add(d);
            else rest.add(d);
        }
        if (dexParts.isEmpty()) throw new IOException("Ningún APK contiene classes.dex");
        byte[] mergedDex = DexMerger.merge(dexParts, log);
        log.append("DEX fusionado: ").append(dexNames).append(" -> classes.dex (")
           .append(mergedDex.length).append(" bytes)\n");

        // 4) manifiesto combinado
        byte[] manifest;
        if (manifests.isEmpty()) throw new IOException("Falta AndroidManifest.xml");
        if (manifests.size() == 1) manifest = manifests.get(0).data;
        else manifest = AxmlMerge.merge(manifests, log);

        // 5) escribir APK final
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            putBytes(zos, "AndroidManifest.xml", manifest);
            putBytes(zos, "classes.dex", mergedDex);
            for (ZipEntryData d : rest) {
                if (d.name.equals("META-INF/com/android/build/gradle/app-metadata.properties")) continue;
                putBytes(zos, d.name, d.data);
            }
        }
        log.append("Generado ").append(out.getName()).append(" (").append(out.length()).append(" bytes)\n");
        return log.toString();
    }

    private static boolean isSignature(String n) {
        return n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC") || n.equals("META-INF/MANIFEST.MF"));
    }

    private static boolean looksLikeZip(byte[] d) {
        return d.length > 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 3 && d[3] == 4;
    }

    private static File writeTemp(ZipEntryData d) throws IOException {
        File f = File.createTempFile("cont", ".zip");
        try (OutputStream os = new FileOutputStream(f)) { os.write(d.data); }
        return f;
    }

    private static void putBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static final class ZipEntryData {
        final String name; final byte[] data; final int method;
        ZipEntryData(String n, byte[] d, int m) { name = n; data = d; method = m; }
    }
}
