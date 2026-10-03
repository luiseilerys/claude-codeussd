package com.herramientas.apk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Firma de APK sin dependencias externas (funciona en Android 5 / API 21):
 *
 *  - Certificado: DER X.509 v3 autofirmado construido a mano (CertBuilder) y
 *    firmado con java.security.Signature — no se usa sun.security.* ni BouncyCastle.
 *  - v1 (JAR signing): MANIFEST.MF + .SF con digeros SHA-256 por entrada y
 *    bloque PKCS#7 SignedData en META-INF/HERRAM.RSA.
 *  - Keystore JKS persistido en /sdcard/HerramientasAPK/herramientasapk.jks
 *    (contraseña "herramientas") para reutilizar la misma clave, como hace
 *    uber-apk-signer con su keystore de depuración.
 *
 * Equivalente funcional a sign.sh de virb3/apk-utilities adaptado a on-device.
 */
public final class Signer {

    public static final char[] DEFAULT_PASS = "herramientas".toCharArray();

    public static File defaultKeystore() {
        return new File(Utils.workDir(), "herramientasapk.jks");
    }

    /** Firma apkIn -> apkOut. keyStore==null usa/crea el keystore por defecto. */
    public static String sign(File apkIn, File keyStore, char[] storePass, String alias,
                              char[] keyPass, File apkOut) throws Exception {
        StringBuilder log = new StringBuilder();
        if (storePass == null) storePass = DEFAULT_PASS;
        if (keyPass == null) keyPass = storePass;

        PrivateKey pk = null;
        X509Holder holder = null;

        File ksFile = keyStore != null ? keyStore : defaultKeystore();
        if (ksFile.exists()) {
            try {
                java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
                try (InputStream in = new java.io.FileInputStream(ksFile)) {
                    ks.load(in, storePass);
                }
                if (alias == null || !ks.containsAlias(alias)) {
                    java.util.Enumeration<String> e = ks.aliases();
                    alias = e.hasMoreElements() ? e.nextElement() : null;
                }
                if (alias != null && ks.isKeyEntry(alias)) {
                    pk = (PrivateKey) ks.getKey(alias, keyPass);
                    java.security.cert.Certificate c = ks.getCertificate(alias);
                    if (c instanceof java.security.cert.X509Certificate) {
                        holder = new X509Holder((java.security.cert.X509Certificate) c);
                    }
                }
            } catch (Exception e) {
                log.append("Keystore existente ilegible (").append(e).append("): se genera uno nuevo.\n");
            }
        }

        if (pk == null || holder == null) {
            log.append("Generando clave RSA 2048 y certificado autofirmado...\n");
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            pk = kp.getPrivate();
            long now = System.currentTimeMillis();
            byte[] der = CertBuilder.selfSignedRsaSha256(kp,
                    "CN=HerramientasAPK, O=HerramientasAPK, C=CU",
                    BigInteger.valueOf(now),
                    new java.util.Date(now - 24L * 3600_000L),
                    new java.util.Date(now + 3650L * 24 * 3600_000L));
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.cert.X509Certificate cert =
                    (java.security.cert.X509Certificate) cf.generateCertificate(
                            new java.io.ByteArrayInputStream(der));
            holder = new X509Holder(cert);
            log.append("Certificado sujeto: ").append(cert.getSubjectDN()).append('\n');

            java.security.KeyStore ks = java.security.KeyStore.getInstance("JKS");
            ks.load(null, null);
            ks.setKeyEntry("herramientasapk", pk, storePass,
                    new java.security.cert.Certificate[]{cert});
            try (OutputStream os = new FileOutputStream(ksFile)) {
                ks.store(os, storePass);
            }
            log.append("Keystore guardado: ").append(ksFile.getAbsolutePath())
               .append(" (contraseña: herramientas)\n");
        } else {
            log.append("Usando clave existente de ").append(ksFile.getName()).append('\n');
        }

        writeV1(apkIn, apkOut, holder.der, pk, log);
        log.append("APK firmado -> ").append(apkOut.getName()).append('\n');
        log.append("Se aplicó firma v1 (JAR). Android 5..9 instalan sin problema; "
                + "en Android 10+ mantén targetSdk<30 o añade v2 con un PC (apksigner).\n");
        return log.toString();
    }

    private static final class X509Holder {
        final java.security.cert.X509Certificate cert;
        final byte[] der;
        X509Holder(java.security.cert.X509Certificate c) {
            this.cert = c;
            byte[] d = null;
            try (InputStream in = c.getInputStream()) { d = Utils.readAll(in); }
            catch (Exception ignored) {}
            this.der = d;
        }
    }

    /** Reescribe el APK regenerando MANIFEST.MF, .SF y .RSA (firma JAR v1). */
    private static void writeV1(File src, File dst, byte[] certDer, PrivateKey pk,
                                StringBuilder log) throws Exception {
        String mainAttrs = "Manifest-Version: 1.0\r\n"
                + "Created-By: HerramientasAPK (apk-utilities port)\r\n\r\n";
        StringBuilder mf = new StringBuilder(mainAttrs);
        StringBuilder sfHead = new StringBuilder("Signature-Version: 1.0\r\n"
                + "Created-By: HerramientasAPK\r\n");

        java.util.LinkedHashMap<String, String> digests = new java.util.LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(src)) {
            List<ZipEntry> es = sortedEntries(zf);
            for (ZipEntry ze : es) {
                String n = ze.getName();
                if (isSignatureFile(n)) continue;
                byte[] data = Utils.readAll(zf.getInputStream(ze));
                String dig = sha256B64(data);
                digests.put(n, dig);
                mf.append("Name: ").append(n).append("\r\nSHA-256-Digest: ")
                  .append(dig).append("\r\n\r\n");
            }
            byte[] mfBytes = mf.toString().getBytes("UTF-8");
            String mfDigest = sha256B64(mfBytes);
            String mainDigest = sha256B64(mainAttrs.getBytes("UTF-8"));

            StringBuilder sf = new StringBuilder(sfHead);
            sf.append("SHA-256-Digest-Manifest-Main-Attributes: ").append(mainDigest).append("\r\n");
            sf.append("SHA-256-Digest-Manifest: ").append(mfDigest).append("\r\n\r\n");
            for (java.util.Map.Entry<String, String> e : digests.entrySet()) {
                sf.append("Name: ").append(e.getKey()).append("\r\nSHA-256-Digest: ")
                  .append(e.getValue()).append("\r\n\r\n");
            }
            byte[] sfBytes = sf.toString().getBytes("UTF-8");

            // PKCS#7 SignedData (detached) sobre el .SF
            byte[] p7 = CertBuilder.pkcs7SignedData(sfBytes, certDer, pk);

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dst))) {
                for (ZipEntry ze : es) {
                    String n = ze.getName();
                    if (isSignatureFile(n)) continue;
                    ZipEntry ne = new ZipEntry(n);
                    ne.setTime(ze.getTime());
                    if (ze.getMethod() == ZipEntry.STORED) {
                        byte[] d = Utils.readAll(zf.getInputStream(ze));
                        CRC32 crc = new CRC32();
                        crc.update(d);
                        ne.setMethod(ZipEntry.STORED);
                        ne.setSize(d.length);
                        ne.setCompressedSize(d.length);
                        ne.setCrc(crc.getValue());
                        zos.putNextEntry(ne);
                        zos.write(d);
                    } else {
                        zos.putNextEntry(ne);
                        try (InputStream in = zf.getInputStream(ze)) { Utils.copy(in, zos); }
                    }
                    zos.closeEntry();
                }
                put(zos, "META-INF/MANIFEST.MF", mfBytes);
                put(zos, "META-INF/HERRAM.SF", sfBytes);
                put(zos, "META-INF/HERRAM.RSA", p7);
            }
        }
        log.append("Firma v1 (JAR): ").append(digests.size())
           .append(" entradas firmadas con SHA-256.\n");
    }

    private static boolean isSignatureFile(String n) {
        return n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA")
                || n.endsWith(".DSA") || n.endsWith(".EC") || n.equals("META-INF/MANIFEST.MF"));
    }

    private static List<ZipEntry> sortedEntries(ZipFile zf) {
        List<ZipEntry> l = new java.util.ArrayList<>();
        java.util.Enumeration<? extends ZipEntry> e = zf.entries();
        while (e.hasMoreElements()) l.add(e.nextElement());
        java.util.Collections.sort(l, new java.util.Comparator<ZipEntry>() {
            @Override public int compare(ZipEntry a, ZipEntry b) { return a.getName().compareTo(b.getName()); }
        });
        return l;
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private static String sha256B64(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return android.util.Base64.encodeToString(md.digest(data), android.util.Base64.NO_WRAP);
    }
}
