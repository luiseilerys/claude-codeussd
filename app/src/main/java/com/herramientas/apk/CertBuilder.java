package com.herramientas.apk;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Date;
import java.util.TimeZone;

/**
 * Codificador DER mínimo: certificados X.509 v3 autofirmados (RSA-SHA256) y
 * bloques PKCS#7 SignedData detached, usando solo java.security.Signature.
 * Necesario porque Android no expone generadores de certificados ni sun.security.*.
 */
public final class CertBuilder {

    // ---------- primitivas DER ----------
    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = content.length;
        if (len < 0x80) {
            o.write(len);
        } else if (len <= 0xFF) {
            o.write(0x81); o.write(len);
        } else if (len <= 0xFFFF) {
            o.write(0x82); o.write(len >> 8); o.write(len & 0xFF);
        } else if (len <= 0xFFFFFF) {
            o.write(0x83); o.write(len >> 16); o.write((len >> 8) & 0xFF); o.write(len & 0xFF);
        } else {
            o.write(0x84); o.write(len >> 24); o.write((len >> 16) & 0xFF);
            o.write((len >> 8) & 0xFF); o.write(len & 0xFF);
        }
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return tlv(0x30, o.toByteArray());
    }

    private static byte[] set(byte[] part) { return tlv(0x31, part); }

    private static byte[] oid(String dotted) {
        String[] s = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(s[0]) * 40 + Integer.parseInt(s[1]));
        for (int i = 2; i < s.length; i++) {
            long v = Long.parseLong(s[i]);
            byte[] enc = base128(v);
            o.write(enc, 0, enc.length);
        }
        return tlv(0x06, o.toByteArray());
    }

    private static byte[] base128(long v) {
        if (v == 0) return new byte[]{0};
        byte[] tmp = new byte[8];
        int n = 0;
        while (v > 0) { tmp[n++] = (byte) (v & 0x7F); v >>= 7; }
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((tmp[n - 1 - i] & 0x7F) | (i == n - 1 ? 0 : 0x80));
        }
        return out;
    }

    private static byte[] integer(BigInteger v) {
        byte[] b = v.toByteArray();
        return tlv(0x02, b);
    }

    private static byte[] bitString(byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); // sin bits sin usar
        o.write(data, 0, data.length);
        return tlv(0x03, o.toByteArray());
    }

    private static byte[] octetString(byte[] data) { return tlv(0x04, data); }

    private static byte[] utf8(String s) {
        try { return tlv(0x0C, s.getBytes("UTF-8")); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] printable(String s) {
        try { return tlv(0x13, s.getBytes("US-ASCII")); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] nullDer() { return new byte[]{0x05, 0x00}; }

    private static byte[] booleanDer(boolean b) {
        return new byte[]{0x01, 0x01, (byte) (b ? 0xFF : 0x00)};
    }

    /** UTCTime YYMMDDHHMMSSZ */
    private static byte[] utcTime(Date d) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes());
    }

    private static byte[] generalizedTime(Date d) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'");
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x18, f.format(d).getBytes());
    }

    private static byte[] time(Date d) {
        // UTCTime cubre hasta 2049; después GeneralizedTime
        long y = d.getYear() + 1900;
        return y < 2050 ? utcTime(d) : generalizedTime(d);
    }

    // ---------- nombre DN ----------
    /** "CN=X, O=Y, C=Z" -> RDNSequence DER */
    private static byte[] dn(String rfc2253) {
        ByteArrayOutputStream name = new ByteArrayOutputStream();
        String[] parts = rfc2253.split(",\\s*");
        for (String p : parts) {
            int eq = p.indexOf('=');
            String k = p.substring(0, eq).trim().toUpperCase();
            String v = p.substring(eq + 1).trim();
            String oid;
            byte[] strEnc;
            switch (k) {
                case "CN": oid = "2.5.4.3"; break;
                case "O":  oid = "2.5.4.10"; break;
                case "OU": oid = "2.5.4.11"; break;
                case "C":  oid = "2.5.4.6"; break;
                default:   oid = "2.5.4.3"; break;
            }
            strEnc = "C".equals(k) ? printable(v) : utf8(v);
            byte[] atv = seq(oid(oid), strEnc);
            byte[] rdn = set(atv);
            name.write(rdn, 0, rdn.length);
        }
        return tlv(0x30, name.toByteArray()); // RDNSequence ::= SEQUENCE OF RelativeDistinguishedName
    }

    // ---------- AlgorithmIdentifier ----------
    private static final byte[] SHA256_RSA_ALG = seq(oid("1.2.840.113549.1.1.11"), nullDer());
    private static final byte[] RSA_ALG        = seq(oid("1.2.840.113549.1.1.1"), nullDer());

    // ---------- API pública ----------

    /** Construye un certificado X.509 v3 autofirmado RSA con firma SHA256withRSA. */
    public static byte[] selfSignedRsaSha256(KeyPair kp, String subjectDn,
                                             BigInteger serial, Date notBefore, Date notAfter)
            throws Exception {
        byte[] spki = kp.getPublic().getEncoded(); // ya es DER SubjectPublicKeyInfo
        byte[] name = dn(subjectDn);

        byte[] validity = seq(time(notBefore), time(notAfter));
        byte[] sigAlg = SHA256_RSA_ALG;

        ByteArrayOutputStream tbv = new ByteArrayOutputStream();
        // version [0] EXPLICIT INTEGER 2
        tbv.write(tlv(0xA0, integer(BigInteger.valueOf(2))));
        tbv.write(integer(serial));
        tbv.write(sigAlg);
        tbv.write(name);   // issuer = subject (autofirmado)
        tbv.write(validity);
        tbv.write(name);
        tbv.write(spki);
        // extensions [3] EXPLICIT SEQUENCE Extension
        byte[] basicConstraints = seq(oid("2.5.29.19"), booleanDer(false),
                octetString(seq()));                 // cA TRUE? usamos SEQUENCE{} => cA predeterminado false; para cert de firma propio está bien
        byte[] extSeq = seq(basicConstraints);
        tbv.write(tlv(0xA3, extSeq));
        byte[] tbs = tlv(0x30, tbv.toByteArray());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sigBits = s.sign();

        return seq(tbs, sigAlg, bitString(sigBits));
    }

    /**
     * PKCS#7 ContentInfo{ SignedData v1, detached, con certificado y firma
     * RSA-SHA256 sobre el contenido }. Es lo que va en META-INF/*.RSA.
     */
    public static byte[] pkcs7SignedData(byte[] content, byte[] certDer, PrivateKey signingKey)
            throws Exception {
        // digest del contenido (para SignerInfo.messageDigest)
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] contentDigest = md.digest(content);

        byte[] oidSignedData = oid("1.2.840.113549.1.7.2");
        byte[] oidSha256 = oid("2.16.840.1.101.3.4.2.1");
        byte[] oidPkcs9MessageDigest = oid("1.2.840.113549.1.9.4");
        byte[] oidContentType = oid("1.2.840.113549.1.9.3");
        byte[] oidSigningTime = oid("1.2.840.113549.1.9.5");

        // DigestAlgorithmIdentifiers: SET OF
        byte[] digestAlgs = set(seq(oidSha256));

        // EncapsulationContentInfo: contenido detached => OID data + OCTET STRING vacío implícito
        byte[] encapContentInfo = seq(oid("1.2.840.113549.1.7.1"));

        // certificates [0] EXPLICIT der-cert
        byte[] certs = tlv(0xA0, certDer);

        // SignerInfo
        // issuerAndSerialNumber: issuer y serial los extraemos del DER del cert
        BigInteger serial = extractSerial(certDer);
        byte[] issuer = extractIssuer(certDer);
        byte[] issuerAndSerial = seq(issuer, integer(serial));

        // authenticatedAttributes (signedAttrs): contentType, messageDigest, signingTime
        byte[] attrContentType = seq(oidContentType, set(seq(oid("1.2.840.113549.1.7.1"))));
        byte[] attrMsgDigest = seq(oidPkcs9MessageDigest, set(octetString(contentDigest)));
        byte[] attrSigningTime = seq(oidSigningTime, set(utcTime(new Date())));
        byte[] signedAttrs = tlv(0xA0, seqAll(attrContentType, attrMsgDigest, attrSigningTime));

        // firma sobre DER de signedAttrs (con su tag 0xA0 completo)
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(signingKey);
        s.update(signedAttrs);
        byte[] signatureBytes = s.sign();

        byte[] signerInfo = seq(
                integer(BigInteger.ONE),          // version
                issuerAndSerial,
                seq(oidSha256),                   // digestAlgorithm
                signedAttrs,
                SHA256_RSA_ALG,                   // digestEncryptionAlgorithm
                octetString(signatureBytes));     // encryptedDigest (DER como OCTET STRING por convención JAR)

        byte[] signedData = seq(
                integer(BigInteger.ONE),           // version
                digestAlgs,
                encapContentInfo,
                certs,
                set(signerInfo));                  // signerInfos SET OF

        // contenedor: SEQUENCE { id-signedData, [0] EXPLICIT SignedData }
        byte[] ci = seq(oidSignedData, tlv(0xA0, signedData));
        return ci;
    }

    private static byte[] seqAll(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    // ---------- parsing mínimo del DER del certificado propio ----------
    private static int[] readTagLen(byte[] d, int p) {
        int tag = d[p++] & 0xFF;
        int len = d[p++] & 0xFF;
        if ((len & 0x80) != 0) {
            int nb = len & 0x7F;
            len = 0;
            for (int i = 0; i < nb; i++) len = (len << 8) | (d[p++] & 0xFF);
        }
        return new int[]{tag, len, p};
    }

    private static BigInteger extractSerial(byte[] cert) {
        // TBSCertificate: SEQUENCE { [0]version?, serial INTEGER, ... }
        int[] h = readTagLen(cert, 0);              // cert SEQ
        int p = h[2];
        int[] t = readTagLen(cert, p);              // TBS SEQ
        p = t[2];
        if ((cert[p] & 0xFF) == 0xA0) {             // version explícita
            int[] v = readTagLen(cert, p);
            p = v[2] + v[1];
        }
        int[] ser = readTagLen(cert, p);            // INTEGER
        byte[] sb = new byte[ser[1]];
        System.arraycopy(cert, ser[2], sb, 0, ser[1]);
        return new BigInteger(sb);
    }

    private static byte[] extractIssuer(byte[] cert) {
        int[] h = readTagLen(cert, 0);
        int p = h[2];
        int[] t = readTagLen(cert, p);
        p = t[2];
        if ((cert[p] & 0xFF) == 0xA0) {
            int[] v = readTagLen(cert, p); p = v[2] + v[1];
        }
        int[] ser = readTagLen(cert, p); p = ser[2] + ser[1];   // serial
        int[] alg = readTagLen(cert, p); p = alg[2] + alg[1];   // signature alg
        readTagLen(cert, p);                                     // issuer (nombre)
        // TLV completo del issuer: desde su tag hasta el fin de su contenido
        int start = p;
        int[] iss = readTagLen(cert, p);
        int total = (iss[2] + iss[1]) - start;
        byte[] issuerFull = new byte[total];
        System.arraycopy(cert, start, issuerFull, 0, total);
        return issuerFull;
    }

    private CertBuilder() {}
}
