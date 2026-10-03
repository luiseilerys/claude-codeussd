package com.herramientas.apk;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lectura de AndroidManifest.xml binario (AXML) directamente desde el APK,
 * sin apktool: usa android.content.res.AXmlResourceParser, disponible en
 * todos los Android (incluido API 21 / Android 5). Equivalente a `aapt l -a`.
 */
public final class ApkInfo {

    public static String dump(File apk) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== APK: ").append(apk.getName()).append(" ===\n");
        sb.append("Tamaño: ").append(apk.length()).append(" bytes\n\n");

        try (ZipFile zf = new ZipFile(apk)) {
            // --- Cabecera y versiones desde el manifiesto binario -----------
            ZipEntry axml = zf.getEntry("AndroidManifest.xml");
            if (axml == null) { sb.append("ERROR: sin AndroidManifest.xml\n"); return sb.toString(); }

            Parsed p = parseBinaryManifest(zf.getInputStream(axml));
            sb.append("package=").append(p.packageName).append('\n');
            sb.append("versionCode=").append(p.versionCode)
              .append("  versionName=").append(p.versionName).append('\n');
            sb.append("minSdkVersion=").append(p.minSdk)
              .append("  targetSdkVersion=").append(p.targetSdk).append("\n\n");

            sb.append("=== PERMISOS USADOS (").append(p.permissions.size()).append(") ===\n");
            for (String s : p.permissions) sb.append("  uses-permission: ").append(s).append('\n');

            sb.append("\n=== COMPONENTES ===\n");
            for (String c : p.components) sb.append("  ").append(c).append('\n');

            if (!p.features.isEmpty()) {
                sb.append("\n=== FEATURES ===\n");
                for (String f : p.features) sb.append("  uses-feature: ").append(f).append('\n');
            }

            // --- lista rápida de entries del zip ----------------------------
            sb.append("\n=== ARCHIVOS EN APK (primeros 40) ===\n");
            int n = 0;
            java.util.Enumeration<? extends ZipEntry> e = zf.entries();
            while (e.hasMoreElements() && n++ < 40) {
                ZipEntry ze = e.nextElement();
                sb.append(String.format("  %10d  %s%n", ze.getSize(), ze.getName()));
            }
        }
        return sb.toString();
    }

    private static class Parsed {
        String packageName = "?", versionName = "?";
        long versionCode;
        int minSdk = -1, targetSdk = -1;
        List<String> permissions = new ArrayList<>();
        List<String> components = new ArrayList<>();
        List<String> features = new ArrayList<>();
    }

    private static Parsed parseBinaryManifest(InputStream in) throws Exception {
        Parsed r = new ArrayListParsed();
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        XmlPullParser xpp = factory.newPullParser();

        // AXmlResourceParser es una clase interna de android.jar (no pública):
        // usarla vía reflexión para no depender de APIs ocultas en tiempo de compilación.
        Object parser;
        java.lang.reflect.Method setInput = null;
        try {
            Class<?> cls = Class.forName("android.content.res.AXmlResourceParser");
            parser = cls.newInstance();
            setInput = cls.getMethod("open", InputStream.class);
            setInput.invoke(parser, in);
            xpp.setInput((InputStream) null, null); // noop, usamos el parser directo
            return readWithAxml(parser, r);
        } catch (ClassNotFoundException e) {
            // fallback: no debería ocurrir en Android
            in.reset();
            xpp.setInput(in, "UTF-8");
            return readWithPull(xpp, r);
        }
    }

    private static Parsed readWithAxml(Object parser, Parsed r) throws Exception {
        java.lang.reflect.Method next = parser.getClass().getMethod("next");
        java.lang.reflect.Method getName = parser.getClass().getMethod("getName");
        java.lang.reflect.Method getAttributeName =
                parser.getClass().getMethod("getAttributeName", int.class);
        java.lang.reflect.Method getAttributeValue =
                parser.getClass().getMethod("getAttributeValue", int.class);
        java.lang.reflect.Method getAttributeCount =
                parser.getClass().getMethod("getAttributeCount");
        java.lang.reflect.Method getInt = parser.getClass().getMethod("getAttributeIntValue", int.class);
        java.lang.reflect.Method getNameSp = parser.getClass().getMethod("getAttributeNamePrefix", int.class);
        int evt;
        String curTag = "";
        while ((evt = (Integer) next.invoke(parser)) != XmlPullParser.END_DOCUMENT) {
            if (evt == XmlPullParser.START_TAG) {
                curTag = (String) getName.invoke(parser);
                int ac = (Integer) getAttributeCount.invoke(parser);
                java.util.Map<String, String> attrs = new java.util.HashMap<>();
                java.util.Map<String, Integer> ints = new java.util.HashMap<>();
                for (int i = 0; i < ac; i++) {
                    String an = (String) getAttributeName.invoke(parser, i);
                    if (an == null) continue;
                    attrs.put(an, (String) getAttributeValue.invoke(parser, i));
                    try { ints.put(an, (Integer) getInt.invoke(parser, i)); }
                    catch (Exception ignored) {}
                }
                handleTag(curTag, attrs, ints, r);
            }
        }
        return r;
    }

    private static void handleTag(String tag, java.util.Map<String, String> attrs,
                                  java.util.Map<String, Integer> ints, Parsed r) {
        String name = attrs.get("name");
        switch (tag) {
            case "manifest":
                if (name != null) r.packageName = name;
                break;
            case "uses-sdk":
                if (ints.containsKey("minSdkVersion")) r.minSdk = ints.get("minSdkVersion");
                if (ints.containsKey("targetSdkVersion")) r.targetSdk = ints.get("targetSdkVersion");
                break;
            case "uses-permission":
            case "uses-permission-sdk-23":
                if (name != null) r.permissions.add(name);
                break;
            case "uses-feature":
                if (name != null) r.features.add(name);
                break;
            case "application": {
                String vn = attrs.get("versionName");
                if (vn != null) r.versionName = vn;
                Long vc = parseLong(attrs.get("versionCode"));
                if (vc != null) r.versionCode = vc;
                else if (ints.containsKey("versionCode")) r.versionCode = ints.get("versionCode");
                if (name != null) r.components.add("application " + name);
                break;
            }
            case "activity":
            case "service":
            case "receiver":
            case "provider":
                if (name != null) r.components.add(tag + " " + name);
                break;
        }
    }

    private static Parsed readWithPull(XmlPullParser xpp, Parsed r) throws Exception {
        int evt;
        while ((evt = xpp.next()) != XmlPullParser.END_DOCUMENT) {
            if (evt == XmlPullParser.START_TAG) {
                java.util.Map<String, String> attrs = new java.util.HashMap<>();
                for (int i = 0; i < xpp.getAttributeCount(); i++)
                    attrs.put(xpp.getAttributeName(i), xpp.getAttributeValue(i));
                java.util.Map<String, Integer> ints = new java.util.HashMap<>();
                handleTag(xpp.getName(), attrs, ints, r);
            }
        }
        return r;
    }

    private static Long parseLong(String s) {
        try { return s == null ? null : Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private static class ArrayListParsed extends Parsed {}

    private ApkInfo() {}
}
