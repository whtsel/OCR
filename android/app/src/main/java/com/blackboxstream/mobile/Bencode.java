package com.blackboxstream.mobile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded BEP 3 parser. Offsets refer to the exact bytes used in an info hash. */
final class Bencode {
    static final class Node {
        int start, end;
        byte[] bytes;
        Long number;
        Map<String, Node> map;
        List<Node> list;
        Node get(String key) { return map == null ? null : map.get(key); }
        String text() { return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8); }
        long value(long fallback) { return number == null ? fallback : number; }
    }

    private final byte[] data;
    private int at, nodes;
    private Bencode(byte[] data, int at) { this.data = data; this.at = at; }

    static Node parse(byte[] bytes) throws IOException {
        Node out = prefix(bytes, 0);
        if (out.end != bytes.length) throw new IOException("Unexpected data after the bencoded value.");
        return out;
    }

    static Node prefix(byte[] bytes, int at) throws IOException {
        if (bytes.length > 4 * 1024 * 1024 || at < 0 || at >= bytes.length) throw invalid();
        return new Bencode(bytes, at).read(0);
    }

    private Node read(int depth) throws IOException {
        if (++nodes > 65536 || depth > 24 || at >= data.length) throw invalid();
        Node out = new Node();
        out.start = at;
        int type = data[at];
        if (type == 'i') {
            int start = ++at;
            while (at < data.length && data[at] != 'e' && at - start <= 21) at++;
            if (at >= data.length || data[at] != 'e' || at == start) throw invalid();
            try { out.number = Long.parseLong(new String(data, start, at - start, StandardCharsets.US_ASCII)); }
            catch (NumberFormatException malformed) { throw invalid(); }
            at++;
        } else if (type == 'd' || type == 'l') {
            at++;
            if (type == 'l') {
                out.list = new ArrayList<>();
                while (at < data.length && data[at] != 'e') out.list.add(read(depth + 1));
            } else {
                out.map = new LinkedHashMap<>();
                while (at < data.length && data[at] != 'e') {
                    byte[] key = string(256);
                    String k = new String(key, StandardCharsets.ISO_8859_1);
                    if (out.map.put(k, read(depth + 1)) != null) throw invalid();
                }
            }
            if (at >= data.length) throw invalid();
            at++;
        } else out.bytes = string(data.length);
        out.end = at;
        return out;
    }

    private byte[] string(int limit) throws IOException {
        int start = at;
        long length = 0;
        while (at < data.length && data[at] >= '0' && data[at] <= '9') {
            length = length * 10 + data[at++] - '0';
            if (length > limit || at - start > 9) throw invalid();
        }
        if (at == start || at >= data.length || data[at++] != ':' || length > data.length - at) throw invalid();
        byte[] value = Arrays.copyOfRange(data, at, at + (int) length);
        at += (int) length;
        return value;
    }

    static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, value);
        return out.toByteArray();
    }

    /** Used only for an already validated, exact peer info dictionary. */
    static final class Raw {
        final byte[] bytes;
        Raw(byte[] bytes) { this.bytes = bytes; }
    }

    private static void write(ByteArrayOutputStream out, Object value) {
        if (value instanceof Raw) raw(out, ((Raw) value).bytes);
        else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            raw(out, Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            out.write(':'); raw(out, bytes);
        } else if (value instanceof String) write(out, ((String) value).getBytes(StandardCharsets.UTF_8));
        else if (value instanceof Number) {
            out.write('i'); raw(out, value.toString().getBytes(StandardCharsets.US_ASCII)); out.write('e');
        } else if (value instanceof List<?>) {
            out.write('l');
            for (Object item : (List<?>) value) write(out, item);
            out.write('e');
        } else if (value instanceof Map<?, ?>) {
            out.write('d');
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> field : ((Map<?, ?>) value).entrySet()) sorted.put((String) field.getKey(), field.getValue());
            for (Map.Entry<String, Object> field : sorted.entrySet()) { write(out, field.getKey()); write(out, field.getValue()); }
            out.write('e');
        } else throw new IllegalArgumentException("Unsupported bencode type");
    }

    private static void raw(ByteArrayOutputStream out, byte[] bytes) { out.write(bytes, 0, bytes.length); }
    private static IOException invalid() { return new IOException("Invalid or oversized torrent data."); }
}
