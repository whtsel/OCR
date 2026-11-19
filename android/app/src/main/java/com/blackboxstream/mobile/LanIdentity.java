package com.blackboxstream.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Per-share TLS identity. The receiver trusts only the SPKI SHA-256 pin in the invitation. */
final class LanIdentity {
    final SSLContext server;
    final byte[] pin;
    private LanIdentity(SSLContext server, byte[] pin) { this.server = server; this.pin = pin; }

    static LanIdentity create() throws GeneralSecurityException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] algorithm = der(0x30, der(6, new byte[] {0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 4, 3, 2})); // ecdsa-with-SHA256
        byte[] name = der(0x30, der(0x31, der(0x30, der(6, new byte[] {0x55, 4, 3}), der(0x0c,
                "BlackBox Local Share".getBytes(StandardCharsets.UTF_8)))));
        SimpleDateFormat utc = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        byte[] dates = der(0x30, der(0x17, utc.format(new Date(now - 86400000L)).getBytes(StandardCharsets.US_ASCII)),
                der(0x17, utc.format(new Date(now + 86400000L)).getBytes(StandardCharsets.US_ASCII)));
        byte[] serial = new BigInteger(96, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = der(0x30, der(2, serial), algorithm, name, dates, name, pair.getPublic().getEncoded());
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(pair.getPrivate()); signature.update(tbs);
        byte[] certificate = der(0x30, tbs, algorithm, der(3, new byte[] {0}, signature.sign()));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));
        char[] password = new char[0];
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, password);
        keys.setKeyEntry("share", pair.getPrivate(), password, new X509Certificate[] {cert});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, password);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        return new LanIdentity(tls, hash(pair.getPublic().getEncoded()));
    }

    static SSLContext pinned(byte[] pin) throws GeneralSecurityException {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Unexpected client certificate"); }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                if (chain == null || chain.length != 1 || !MessageDigest.isEqual(pin, hash(chain[0].getPublicKey().getEncoded()))) {
                    throw new CertificateException("Share identity did not match the invitation");
                }
                chain[0].checkValidity();
            }
        }}, null);
        return tls;
    }

    static byte[] hash(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (GeneralSecurityException impossible) { throw new AssertionError(impossible); }
    }

    private static byte[] der(int tag, byte[]... fields) {
        int length = 0;
        for (byte[] value : fields) length += value.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (length >= 256) { out.write(0x82); out.write(length >>> 8); out.write(length); }
        else if (length >= 128) { out.write(0x81); out.write(length); }
        else out.write(length);
        for (byte[] value : fields) out.write(value, 0, value.length);
        return out.toByteArray();
    }
}
