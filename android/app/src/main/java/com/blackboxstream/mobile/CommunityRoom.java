package com.blackboxstream.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/** Optional invite-only Community relay. The room key and event plaintext are never sent to it. */
final class CommunityRoom {
    private static final String PREFIX = "BBX-ROOM-1:";
    static final int MAX_ENVELOPE = 4096;
    private static final int MAX_FEED = 512 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    final String relay, roomId;
    private final byte[] secret;
    private final SecretKeySpec aes;

    static final class Event {
        final long number;
        final String name, text, link;
        Event(long number, String name, String text, String link) {
            this.number = number; this.name = name; this.text = text; this.link = link;
        }
        static Event make(String name, String text, String link) {
            String label = name == null ? "" : clean(name).trim();
            if (label.isEmpty()) label = "Guest";
            String value = text == null ? "" : clean(text).trim();
            String source = link == null || link.trim().isEmpty() ? null : MediaSources.https(link);
            if (label.length() > 40 || value.isEmpty() || value.length() > 500 || source != null && source.length() > 2048) {
                throw new IllegalArgumentException("Use a name up to 40 characters, text up to 500, and a short HTTPS media link.");
            }
            return new Event(0, label, value, source);
        }
        private static String clean(String value) { return value.replaceAll("[\\p{Cc}\\p{Cf}]", " "); }
    }

    static final class Feed {
        final long cursor;
        final List<Event> events;
        Feed(long cursor, List<Event> events) { this.cursor = cursor; this.events = events; }
    }

    private CommunityRoom(String relay, byte[] secret) {
        URI uri = URI.create(MediaSources.https(relay));
        if (uri.getRawQuery() != null || !uri.getRawPath().matches("(?:/[A-Za-z0-9_.-]+)*/?") || uri.toASCIIString().length() > 512
                || !uri.getRawPath().equals(uri.normalize().getRawPath())) throw new IllegalArgumentException("Enter the HTTPS base address of a BlackBox relay, without a query.");
        this.relay = uri.toASCIIString().replaceFirst("/+$", "");
        this.secret = secret.clone();
        roomId = TorrentMeta.hex(Arrays.copyOf(derive(secret, "blackbox-room-address-v1"), 24));
        aes = new SecretKeySpec(derive(secret, "blackbox-room-aes-gcm-v1"), "AES");
    }

    static CommunityRoom create(String relay) {
        byte[] key = new byte[32]; RANDOM.nextBytes(key);
        return new CommunityRoom(relay, key);
    }

    static CommunityRoom parse(String invitation) {
        String text = invitation == null ? "" : invitation.trim();
        if (!text.startsWith(PREFIX) || text.length() > 620) throw new IllegalArgumentException("Paste a BlackBox Community invitation.");
        int index = PREFIX.length() + 64;
        if (text.length() <= index + 1 || text.charAt(index) != ':') throw new IllegalArgumentException("Invalid Community invitation.");
        try { return new CommunityRoom(text.substring(index + 1), hex(text.substring(PREFIX.length(), index), 32)); }
        catch (IOException e) { throw new IllegalArgumentException("Invalid Community invitation."); }
    }

    String code() { return PREFIX + TorrentMeta.hex(secret) + ":" + relay; }

    void post(Event item) throws IOException {
        byte[] envelope = seal(item);
        HttpsURLConnection request = request("", "POST");
        request.setRequestProperty("Content-Type", "application/octet-stream");
        request.setDoOutput(true);
        request.setFixedLengthStreamingMode(envelope.length);
        try {
            try (java.io.OutputStream out = request.getOutputStream()) { out.write(envelope); }
            int status = request.getResponseCode();
            if (status != 201) throw status(status);
        } finally { request.disconnect(); }
    }

    Feed fetch(long after) throws IOException {
        if (after < 0) throw new IllegalArgumentException("Invalid room cursor.");
        HttpsURLConnection request = request("?after=" + after, "GET");
        try {
            int status = request.getResponseCode();
            if (status != 200) throw status(status);
            byte[] data = TorrentMeta.slurp(request.getInputStream(), MAX_FEED);
            long cursor = after;
            List<Event> rows = new ArrayList<>();
            for (String line : new String(data, StandardCharsets.US_ASCII).split("\n")) {
                int tab = line.indexOf('\t');
                if (tab < 1 || tab > 18 || !line.substring(0, tab).matches("[0-9]+")) continue;
                long n = Long.parseLong(line.substring(0, tab));
                if (n <= cursor) continue;
                cursor = n;
                String raw = line.substring(tab + 1);
                if (raw.length() > MAX_ENVELOPE * 2 || raw.length() < 60 || raw.length() % 2 != 0) continue;
                try { rows.add(open(n, hex(raw, raw.length() / 2))); }
                catch (IOException | IllegalArgumentException wrongKeyOrDamaged) { /* not valid room data */ }
            }
            return new Feed(cursor, rows);
        } finally { request.disconnect(); }
    }

    byte[] seal(Event item) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeUTF(item.name); out.writeUTF(item.text); out.writeUTF(item.link == null ? "" : item.link);
        }
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
            byte[] encrypted = cipher.doFinal(payload.toByteArray());
            if (encrypted.length + 13 > MAX_ENVELOPE) throw new IOException("The message is too long to encrypt.");
            ByteArrayOutputStream envelope = new ByteArrayOutputStream();
            envelope.write(1); envelope.write(nonce); envelope.write(encrypted);
            return envelope.toByteArray();
        } catch (GeneralSecurityException crypto) { throw new IOException("Could not encrypt the room message.", crypto); }
    }

    Event open(long seq, byte[] envelope) throws IOException {
        if (envelope.length < 29 || envelope.length > MAX_ENVELOPE || envelope[0] != 1) throw new IOException("Invalid encrypted event.");
        try {
            byte[] data = cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(envelope, 1, 13)).doFinal(envelope, 13, envelope.length - 13);
            try (DataInputStream decoded = new DataInputStream(new ByteArrayInputStream(data))) {
                Event item = Event.make(decoded.readUTF(), decoded.readUTF(), decoded.readUTF());
                if (decoded.read() != -1) throw new IOException("Invalid event.");
                return new Event(seq, item.name, item.text, item.link);
            }
        } catch (GeneralSecurityException crypto) { throw new IOException("Room envelope could not be authenticated.", crypto); }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, aes, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((PREFIX + roomId).getBytes(StandardCharsets.US_ASCII));
        return cipher;
    }

    private HttpsURLConnection request(String suffix, String method) throws IOException {
        HttpsURLConnection conn = (HttpsURLConnection) URI.create(relay + "/v1/rooms/" + roomId + "/events" + suffix).toURL().openConnection();
        conn.setRequestMethod(method); conn.setConnectTimeout(7000); conn.setReadTimeout(10000);
        conn.setInstanceFollowRedirects(false); conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "text/plain"); return conn;
    }

    private static IOException status(int status) {
        return new IOException(status == 429 ? "This relay is rate-limited. Wait a moment and try again."
                : "The Community relay returned HTTP " + status + ". Check the relay address.");
    }
    private static byte[] derive(byte[] key, String domain) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256"); hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hmac.doFinal(domain.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException unavailable) { throw new AssertionError(unavailable); }
    }
    private static byte[] hex(String text, int expected) throws IOException {
        if (text.length() != expected * 2) throw new IOException("Invalid hexadecimal data.");
        byte[] bytes = new byte[expected];
        for (int i = 0; i < expected; i++) {
            int a = Character.digit(text.charAt(i * 2), 16), b = Character.digit(text.charAt(i * 2 + 1), 16);
            if (a < 0 || b < 0) throw new IOException("Invalid hexadecimal data.");
            bytes[i] = (byte) (a * 16 + b);
        }
        return bytes;
    }
}
