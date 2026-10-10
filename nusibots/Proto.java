package bots;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Proste narzędzia do czytania/pisania pakietów Minecrafta (protokół 769 = 1.21.4). */
final class Proto {
    private Proto() {}

    static final class Out {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        Out varint(int v) { while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; } o.write(v); return this; }
        Out str(String s) { byte[] b = s.getBytes(StandardCharsets.UTF_8); varint(b.length); o.write(b, 0, b.length); return this; }
        Out u8(int v) { o.write(v); return this; }
        Out bool(boolean v) { o.write(v ? 1 : 0); return this; }
        Out i16(int v) { o.write(v >> 8); o.write(v); return this; }
        Out i32(int v) { for (int i = 24; i >= 0; i -= 8) o.write(v >> i); return this; }
        Out i64(long v) { for (int i = 56; i >= 0; i -= 8) o.write((int) (v >> i)); return this; }
        Out f32(float f) { return i32(Float.floatToIntBits(f)); }
        Out f64(double d) { return i64(Double.doubleToLongBits(d)); }
        Out raw(byte[] b) { o.write(b, 0, b.length); return this; }
        byte[] bytes() { return o.toByteArray(); }
    }

    static final class Buf {
        final byte[] d;
        int p;
        Buf(byte[] d) { this.d = d; }
        int u8() { return d[p++] & 0xFF; }
        int varint() {
            int v = 0, s = 0;
            while (true) {
                int b = u8();
                v |= (b & 0x7F) << s;
                if ((b & 0x80) == 0) return v;
                s += 7;
                if (s >= 35) throw new RuntimeException("varint za duży");
            }
        }
        int i16() { int v = (short) (((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF)); p += 2; return v; }
        int i32() { int v = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF); p += 4; return v; }
        long i64() { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | (d[p + i] & 0xFF); p += 8; return v; }
        float f32() { return Float.intBitsToFloat(i32()); }
        double f64() { return Double.longBitsToDouble(i64()); }
        boolean bool() { return u8() != 0; }
        String str() { int n = varint(); String s = new String(d, p, n, StandardCharsets.UTF_8); p += n; return s; }
        byte[] bytes(int n) { byte[] r = Arrays.copyOfRange(d, p, p + n); p += n; return r; }
        void skip(int n) { p += n; }
    }

    // ---- NBT (sieciowy: korzeń bez nazwy) -> obiekty Javy ----
    static Object nbtRoot(Buf b) {
        int t = b.u8();
        if (t == 0) return "";
        return nbtVal(b, t);
    }

    static Object nbtVal(Buf b, int t) {
        switch (t) {
            case 1: return (int) b.d[b.p++];
            case 2: return b.i16();
            case 3: return b.i32();
            case 4: return b.i64();
            case 5: return b.f32();
            case 6: return b.f64();
            case 7: { int n = b.i32(); b.skip(n); return ""; }
            case 8: { int n = b.i16() & 0xFFFF; String s = new String(b.d, b.p, n, StandardCharsets.UTF_8); b.p += n; return s; }
            case 9: {
                int et = b.u8(); int n = b.i32();
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < n; i++) l.add(et == 0 ? "" : nbtVal(b, et));
                return l;
            }
            case 10: {
                Map<String, Object> m = new LinkedHashMap<>();
                while (true) {
                    int tt = b.u8();
                    if (tt == 0) break;
                    int nl = b.i16() & 0xFFFF;
                    String k = new String(b.d, b.p, nl, StandardCharsets.UTF_8);
                    b.p += nl;
                    m.put(k, nbtVal(b, tt));
                }
                return m;
            }
            case 11: { int n = b.i32(); b.skip(4 * n); return ""; }
            case 12: { int n = b.i32(); b.skip(8 * n); return ""; }
            default: throw new RuntimeException("nieznany tag NBT " + t);
        }
    }

    static String text(Object o) {
        return text0(o).replaceAll("§.", "");
    }

    private static String text0(Object o) {
        if (o == null) return "";
        if (o instanceof String) return (String) o;
        if (o instanceof Number) return o.toString();
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object x : (List<?>) o) sb.append(text0(x));
            return sb.toString();
        }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            StringBuilder sb = new StringBuilder();
            if (m.containsKey("text")) {
                sb.append(text0(m.get("text")));
            } else if (m.containsKey("translate")) {
                String key = String.valueOf(m.get("translate"));
                List<?> w = m.get("with") instanceof List ? (List<?>) m.get("with") : new ArrayList<Object>();
                if (key.equals("chat.type.text") && w.size() >= 2) {
                    sb.append("<").append(text0(w.get(0))).append("> ").append(text0(w.get(1)));
                } else if (m.containsKey("fallback")) {
                    sb.append(text0(m.get("fallback")));
                } else {
                    sb.append(key);
                    for (Object x : w) sb.append(' ').append(text0(x));
                }
            }
            if (m.get("extra") instanceof List) for (Object x : (List<?>) m.get("extra")) sb.append(text0(x));
            return sb.toString();
        }
        return "";
    }

    private static final Pattern JSON_TEXT = Pattern.compile("\"(?:text|translate)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Wyciąga tekst z komponentu JSON (używane tylko przy odrzuceniu logowania). */
    static String jsonText(String json) {
        Matcher m = JSON_TEXT.matcher(json);
        StringBuilder sb = new StringBuilder();
        while (m.find()) sb.append(m.group(1).replace("\\n", " ").replace("\\\"", "\"")).append(' ');
        String r = sb.toString().trim();
        return (r.isEmpty() ? json : r).replaceAll("§.", "");
    }
}
