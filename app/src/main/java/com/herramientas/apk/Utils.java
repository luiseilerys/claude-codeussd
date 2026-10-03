package com.herramientas.apk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Utilidades de archivos, selección y empaquetado para las herramientas. */
public final class Utils {

    /** Directorio de trabajo visible por el usuario: /sdcard/HerramientasAPK */
    public static File workDir() {
        File base = new File(android.os.Environment.getExternalStorageDirectory(), "HerramientasAPK");
        if (!base.exists()) base.mkdirs();
        return base;
    }

    /** Copia un APK/XAPK seleccionado por SAF hacia el directorio de trabajo. */
    public static File importUri(android.content.Context ctx, android.net.Uri uri) throws IOException {
        String name = displayName(ctx, uri);
        File out = new File(workDir(), name);
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            in.close();
        }
        return out;
    }

    public static String displayName(android.content.Context ctx, android.net.Uri uri) {
        String name = null;
        try (android.database.Cursor c = ctx.getContentResolver()
                .query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                        null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {}
        if (name == null) {
            List<String> seg = uri.getPathSegments();
            name = seg.isEmpty() ? "archivo.bin" : seg.get(seg.size() - 1);
        }
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Lista archivos del directorio de trabajo que casan con la extensión dada. */
    public static List<File> listFiles(String ext) {
        List<File> r = new ArrayList<>();
        File[] fs = workDir().listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.isFile() && (ext == null || f.getName().toLowerCase().endsWith(ext))) r.add(f);
            }
        }
        java.util.Collections.sort(r, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return r;
    }

    /** Extrae classes.dex (y splits) de un APK a un directorio. */
    public static List<File> extractDexFromApk(File apk, File destDir) throws IOException {
        destDir.mkdirs();
        List<File> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(apk)) {
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                if (ze.getName().equals("classes.dex")
                        || ze.getName().matches("classes\\d*\\.dex")) {
                    File f = new File(destDir, ze.getName());
                    try (InputStream in = zf.getInputStream(ze);
                         OutputStream os = new FileOutputStream(f)) {
                        copy(in, os);
                    }
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Inserta/reemplaza entries en un APK desde un directorio (p.ej. classes.dex parcheado). */
    public static void injectIntoApk(File apkIn, File srcDir, List<String> names, File apkOut)
            throws IOException {
        try (ZipFile zf = new ZipFile(apkIn);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(apkOut))) {
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry ze = e.nextElement();
                if (names.contains(ze.getName())) continue; // se reemplaza
                ZipEntry ne = new ZipEntry(ze.getName());
                ne.setTime(ze.getTime());
                zos.putNextEntry(ne);
                try (InputStream in = zf.getInputStream(ze)) { copy(in, zos); }
                zos.closeEntry();
            }
            for (String n : names) {
                File f = new File(srcDir, n);
                if (!f.exists()) continue;
                zos.putNextEntry(new ZipEntry(n));
                try (InputStream in = new FileInputStream(f)) { copy(in, zos); }
                zos.closeEntry();
            }
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(in, bos);
        return bos.toByteArray();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    public static String hexDump(byte[] data, long offset, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(data.length - (int) offset, maxBytes);
        for (int i = 0; i < count; i += 16) {
            long addr = offset + i;
            sb.append(String.format("%08X  ", addr));
            StringBuilder ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (i + j < count) {
                    int v = data[(int) addr + j] & 0xFF;
                    sb.append(String.format("%02X ", v));
                    ascii.append(v >= 32 && v < 127 ? (char) v : '.');
                } else sb.append("   ");
                if (j == 7) sb.append(' ');
            }
            sb.append(' ').append(ascii).append('\n');
        }
        return sb.toString();
    }

    public static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private Utils() {}
}
