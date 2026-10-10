package bots;

import bots.Proto.Buf;
import bots.Proto.Out;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Jeden bot = jedno połączenie TCP z serwerem (offline-mode, Minecraft 1.21.4, protokół 769). */
public class Bot {
    public enum State { OFFLINE, CONNECTING, LOGIN, CONFIG, PLAY }

    public static final class Slot {
        public final int id, count;
        Slot(int id, int count) { this.id = id; this.count = count; }
    }

    public static final int PROTOCOL = 769;

    // ===== ID pakietów dla 1.21.4 (jeśli coś nie działa, to pierwsze miejsce do poprawek) =====
    // serverbound (play)
    static final int S_TELEPORT_CONFIRM = 0x00, S_CHAT_COMMAND = 0x05, S_CHAT = 0x07,
            S_CHUNK_BATCH_RECEIVED = 0x09, S_CLIENT_STATUS = 0x0A, S_KEEPALIVE = 0x1A,
            S_POS_ROT = 0x1D, S_PLAYER_ACTION = 0x26, S_PONG = 0x2A, S_HELD_ITEM = 0x32;
    // clientbound (play)
    static final int C_CHUNK_BATCH_FINISHED = 0x0B, C_CONTAINER_CONTENT = 0x12, C_CONTAINER_SLOT = 0x14,
            C_DISCONNECT = 0x1C, C_DISGUISED_CHAT = 0x1D, C_KEEPALIVE = 0x26, C_LOGIN = 0x2B,
            C_PING = 0x37, C_PLAYER_CHAT = 0x3A, C_POSITION = 0x42, C_HEALTH = 0x5D, C_SYSTEM_CHAT = 0x73;

    static final double SPEED = 0.2158; // bloków na tick (chodzenie)

    public final String name;
    public volatile String host;
    public volatile int port;
    public volatile String password;
    public volatile boolean autoRelog = true;
    public volatile int relogDelaySec = 5;

    public volatile State state = State.OFFLINE;
    public volatile boolean joined;
    public volatile double x, y, z;
    public volatile float yaw, pitch;
    public volatile float health = 20;
    public volatile int food = 20;
    public volatile int held = 0;
    public final Slot[] slots = new Slot[46];
    public final boolean[] unknown = new boolean[46];

    // sterowanie
    public volatile boolean fw, bk, lf, rt;
    private volatile boolean jumping;
    private double jy, jvy;
    private volatile boolean rotDirty;
    private volatile boolean havePos;
    private double groundY;
    private long tickCount;

    // log
    public final List<String> logLines = Collections.synchronizedList(new ArrayList<String>());
    public volatile int logEpoch;

    private Socket sock;
    private DataInputStream in;
    private OutputStream out;
    private int threshold = -1;
    private volatile boolean stopped = true;
    private int registerAttempts;
    private volatile boolean authed;
    private long lastRegister, lastLogin;
    private final ScheduledExecutorService sched;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");

    public Bot(String name, String host, int port, String password) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.password = password;
        sched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bot-sched-" + name);
            t.setDaemon(true);
            return t;
        });
        sched.scheduleAtFixedRate(() -> { try { tick(); } catch (Throwable ignored) {} }, 50, 50, TimeUnit.MILLISECONDS);
    }

    @Override public String toString() { return name; }

    public void log(String s) {
        synchronized (logLines) {
            logLines.add("[" + fmt.format(new Date()) + "] " + s);
            if (logLines.size() > 2000) {
                logLines.subList(0, 1000).clear();
                logEpoch++;
            }
        }
    }

    // ===================== połączenie =====================
    public synchronized void connect() {
        if (state != State.OFFLINE) return;
        stopped = false;
        state = State.CONNECTING;
        Thread t = new Thread(this::run, "bot-" + name);
        t.setDaemon(true);
        t.start();
    }

    public void disconnect() {
        stopped = true;
        closeSocket();
    }

    public void shutdown() {
        disconnect();
        sched.shutdownNow();
    }

    private void closeSocket() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) {}
    }

    private void run() {
        try {
            log("Łączę z " + host + ":" + port + " ...");
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, port), 8000);
            sock.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
            out = new BufferedOutputStream(sock.getOutputStream());
            threshold = -1;
            joined = false;
            havePos = false;
            authed = false;
            registerAttempts = 0;
            fw = bk = lf = rt = false;
            jumping = false;
            Arrays.fill(slots, null);
            Arrays.fill(unknown, false);

            // handshake -> login
            send(0x00, new Out().varint(PROTOCOL).str(host).i16(port).varint(2));
            state = State.LOGIN;
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            send(0x00, new Out().str(name).i64(uuid.getMostSignificantBits()).i64(uuid.getLeastSignificantBits()));

            while (true) {
                int len = readVarInt(in);
                byte[] raw = new byte[len];
                in.readFully(raw);
                Buf b = new Buf(raw);
                if (threshold >= 0) {
                    int dl = b.varint();
                    if (dl != 0) b = new Buf(inflate(raw, b.p, dl));
                }
                int id = b.varint();
                try {
                    switch (state) {
                        case LOGIN: onLogin(id, b); break;
                        case CONFIG: onConfig(id, b); break;
                        case PLAY: onPlay(id, b); break;
                        default: break;
                    }
                } catch (IOException e) {
                    throw e;
                } catch (RuntimeException e) {
                    log("(błąd parsowania pakietu 0x" + Integer.toHexString(id) + " w stanie " + state + ": " + e + ")");
                }
            }
        } catch (IOException e) {
            if (!stopped) log("Rozłączono: " + e.getMessage());
        } catch (Exception e) {
            log("Błąd: " + e);
        } finally {
            closeSocket();
            state = State.OFFLINE;
            joined = false;
            fw = bk = lf = rt = false;
            if (stopped) {
                log("Rozłączono.");
            } else if (autoRelog) {
                log("Relog za " + relogDelaySec + " s ...");
                try {
                    sched.schedule(() -> { if (!stopped && autoRelog) connect(); }, relogDelaySec, TimeUnit.SECONDS);
                } catch (RejectedExecutionException ignored) {}
            }
        }
    }

    // ===================== stany protokołu =====================
    private void onLogin(int id, Buf b) throws IOException {
        switch (id) {
            case 0x00:
                log("Serwer odrzucił logowanie: " + Proto.jsonText(b.str()));
                throw new IOException("odrzucono logowanie");
            case 0x01:
                log("Serwer działa w trybie online (premium) – boty bez konta premium nie mogą wejść.");
                throw new IOException("tryb online");
            case 0x02:
                send(0x03, new Out()); // Login Acknowledged
                state = State.CONFIG;
                sendClientInfo();
                log("Zalogowano, trwa konfiguracja...");
                break;
            case 0x03:
                threshold = b.varint();
                break;
            case 0x04: // login plugin request -> "nie rozumiem"
                send(0x02, new Out().varint(b.varint()).bool(false));
                break;
            default: break;
        }
    }

    private void sendClientInfo() throws IOException {
        send(0x00, new Out().str("pl_pl").u8(8).varint(0).bool(true).u8(0x7F).varint(1).bool(false).bool(true));
    }

    private void onConfig(int id, Buf b) throws IOException {
        switch (id) {
            case 0x02:
                log("Rozłączono (konfiguracja): " + Proto.text(Proto.nbtRoot(b)));
                throw new IOException("rozłączono w konfiguracji");
            case 0x03:
                send(0x03, new Out());
                state = State.PLAY;
                break;
            case 0x04: send(0x04, new Out().i64(b.i64())); break;
            case 0x05: send(0x05, new Out().i32(b.i32())); break;
            case 0x09: {
                byte[] uuid = b.bytes(16);
                send(0x06, new Out().raw(uuid).varint(3)); // zaakceptowano
                send(0x06, new Out().raw(uuid).varint(0)); // załadowano
                break;
            }
            case 0x0E: send(0x07, new Out().varint(0)); break; // Known Packs: brak
            default: break;
        }
    }

    private void onPlay(int id, Buf b) throws IOException {
        switch (id) {
            case C_DISCONNECT:
                log("Wyrzucono z serwera: " + Proto.text(Proto.nbtRoot(b)));
                throw new IOException("wyrzucono");
            case C_KEEPALIVE: send(S_KEEPALIVE, new Out().i64(b.i64())); break;
            case C_PING: send(S_PONG, new Out().i32(b.i32())); break;
            case C_CHUNK_BATCH_FINISHED: b.varint(); send(S_CHUNK_BATCH_RECEIVED, new Out().f32(20f)); break;
            case C_LOGIN:
                joined = true;
                log("Dołączono do serwera.");
                sched.schedule(this::authStep, 1500, TimeUnit.MILLISECONDS);
                break;
            case C_POSITION: onPosition(b); break;
            case C_HEALTH: {
                float h = b.f32();
                int f = b.varint();
                health = h;
                food = f;
                if (h <= 0) send(S_CLIENT_STATUS, new Out().varint(0)); // respawn
                break;
            }
            case C_CONTAINER_CONTENT: onContent(b); break;
            case C_CONTAINER_SLOT: onSlot(b); break;
            case C_SYSTEM_CHAT: {
                String t = Proto.text(Proto.nbtRoot(b));
                boolean overlay = b.bool();
                if (!overlay && !t.isEmpty()) onChat(t);
                break;
            }
            case C_PLAYER_CHAT: onPlayerChat(b); break;
            case C_DISGUISED_CHAT: {
                String msg = Proto.text(Proto.nbtRoot(b));
                b.varint();
                String sender = Proto.text(Proto.nbtRoot(b));
                onChat("<" + sender + "> " + msg);
                break;
            }
            default: break;
        }
    }

    private void onPosition(Buf b) throws IOException {
        int tid = b.varint();
        double px = b.f64(), py = b.f64(), pz = b.f64();
        b.f64(); b.f64(); b.f64();
        float yw = b.f32(), pt = b.f32();
        int fl = b.i32();
        if ((fl & 0x01) != 0) px += x;
        if ((fl & 0x02) != 0) py += y;
        if ((fl & 0x04) != 0) pz += z;
        if ((fl & 0x08) != 0) yw += yaw;
        if ((fl & 0x10) != 0) pt += pitch;
        x = px; y = py; z = pz; yaw = yw; pitch = pt;
        groundY = py;
        jumping = false;
        jy = 0;
        havePos = true;
        send(S_TELEPORT_CONFIRM, new Out().varint(tid));
        sendPos(true);
    }

    // ===================== ekwipunek =====================
    private void onContent(Buf b) {
        int win = b.u8();
        b.varint(); // stateId
        int n = b.varint();
        if (win != 0) return;
        for (int i = 0; i < n; i++) {
            int cnt = b.varint();
            Slot s = null;
            boolean comp = false;
            if (cnt > 0) {
                int id = b.varint();
                int add = b.varint();
                int rem = b.varint();
                s = new Slot(id, cnt);
                comp = add > 0 || rem > 0;
            }
            if (i < slots.length) { slots[i] = s; unknown[i] = false; }
            if (comp) { // dalej nie umiemy czytać (komponenty przedmiotu)
                for (int j = i + 1; j < n && j < slots.length; j++) { slots[j] = null; unknown[j] = true; }
                break;
            }
        }
    }

    private void onSlot(Buf b) {
        int win = b.u8();
        b.varint();
        int slot = b.i16();
        if (win != 0 || slot < 0 || slot >= slots.length) return;
        int cnt = b.varint();
        if (cnt > 0) {
            int id = b.varint();
            slots[slot] = new Slot(id, cnt);
        } else {
            slots[slot] = null;
        }
        unknown[slot] = false;
    }

    // ===================== czat =====================
    private void onPlayerChat(Buf b) {
        b.skip(16);            // UUID nadawcy
        b.varint();            // index
        if (b.bool()) b.skip(256); // podpis
        String msg = b.str();
        b.i64(); b.i64();      // timestamp, salt
        int prev = b.varint();
        for (int i = 0; i < prev; i++) { int mid = b.varint(); if (mid == 0) b.skip(256); }
        if (b.bool()) Proto.nbtRoot(b); // niepodpisana treść
        int ft = b.varint();
        if (ft == 2) { int n = b.varint(); b.skip(8 * n); }
        b.varint();            // typ czatu
        String sender = Proto.text(Proto.nbtRoot(b));
        onChat("<" + sender + "> " + msg);
    }

    private void onChat(String line) {
        log(line);
        String l = line.toLowerCase(Locale.ROOT);
        if (l.contains("zalogowano") || l.contains("zarejestrowano") || l.contains("successfully logged")
                || l.contains("successful login") || l.contains("successfully registered") || l.contains("pomyślnie")) {
            authed = true;
            return;
        }
        if (l.startsWith("<")) return; // wiadomości graczy nie wyzwalają logowania
        if (l.contains("/login") || l.contains("zaloguj się") || l.contains("zaloguj sie")) doLogin();
        else if (l.contains("/register") || l.contains("/reg ") || l.contains("zarejestruj")) doRegister();
    }

    private void authStep() {
        if (state == State.PLAY && !authed) doRegister();
    }

    private void doRegister() {
        long now = System.currentTimeMillis();
        if (now - lastRegister < 2500) return;
        lastRegister = now;
        String pw = password;
        // pierwsza próba: "/register haslo", kolejne: z powtórzeniem hasła
        String cmd = registerAttempts == 0 ? "register " + pw : "register " + pw + " " + pw;
        registerAttempts++;
        sendChat("/" + cmd);
    }

    private void doLogin() {
        long now = System.currentTimeMillis();
        if (now - lastLogin < 2500) return;
        lastLogin = now;
        sendChat("/login " + password);
    }

    /** Wysyła wiadomość (zaczynającą się od "/" jako komendę). */
    public void sendChat(String msg) {
        if (state != State.PLAY || msg == null || msg.isEmpty()) return;
        try {
            if (msg.startsWith("/")) {
                send(S_CHAT_COMMAND, new Out().str(msg.substring(1)));
                log("» " + msg.replaceAll("(?i)(/(?:login|register|reg|l)\\s+)\\S+.*", "$1***"));
            } else {
                if (msg.length() > 256) msg = msg.substring(0, 256);
                send(S_CHAT, new Out().str(msg).i64(System.currentTimeMillis()).i64(0).bool(false).varint(0).u8(0).u8(0).u8(0));
                log("» " + msg);
            }
        } catch (IOException e) {
            log("Nie udało się wysłać: " + e.getMessage());
        }
    }

    // ===================== sterowanie =====================
    public void jump() {
        if (!jumping && havePos) { jumping = true; jy = 0; jvy = 0.42; }
    }

    public void rotate(float dYaw, float dPitch) {
        yaw = (yaw + dYaw) % 360f;
        pitch = Math.max(-90f, Math.min(90f, pitch + dPitch));
        rotDirty = true;
    }

    public void stopMoving() { fw = bk = lf = rt = false; }

    public void selectSlot(int hotbar) {
        if (state != State.PLAY || hotbar < 0 || hotbar > 8) return;
        held = hotbar;
        try { send(S_HELD_ITEM, new Out().i16(hotbar)); } catch (IOException e) { log("Błąd: " + e.getMessage()); }
    }

    public void dropHeld(boolean wholeStack) {
        if (state != State.PLAY) return;
        try { send(S_PLAYER_ACTION, new Out().varint(wholeStack ? 3 : 4).i64(0).u8(0).varint(0)); }
        catch (IOException e) { log("Błąd: " + e.getMessage()); }
    }

    public Slot heldItem() { return slots[36 + held]; }

    private void tick() throws IOException {
        if (state != State.PLAY || !havePos || !joined) return;
        tickCount++;
        double fwd = (fw ? 1 : 0) - (bk ? 1 : 0), side = (rt ? 1 : 0) - (lf ? 1 : 0);
        double dx = 0, dz = 0;
        boolean moving = false;
        if (fwd != 0 || side != 0) {
            double len = Math.sqrt(fwd * fwd + side * side);
            fwd /= len; side /= len;
            double r = Math.toRadians(yaw);
            double fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
            dx = (fx * fwd + rx * side) * SPEED;
            dz = (fz * fwd + rz * side) * SPEED;
            moving = true;
        }
        boolean airborne = false;
        if (jumping) {
            jy += jvy;
            jvy = (jvy - 0.08) * 0.98;
            if (jy <= 0) { jy = 0; jumping = false; } else airborne = true;
        }
        if (moving || airborne || rotDirty || tickCount % 20 == 0 || (!jumping && y != groundY)) {
            x += dx; z += dz; y = groundY + jy;
            rotDirty = false;
            sendPos(!airborne);
        }
    }

    private void sendPos(boolean onGround) throws IOException {
        send(S_POS_ROT, new Out().f64(x).f64(y).f64(z).f32(yaw).f32(pitch).u8(onGround ? 1 : 0));
    }

    // ===================== niskopoziomowe I/O =====================
    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, s = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new EOFException("połączenie zamknięte");
            v |= (b & 0x7F) << s;
            if ((b & 0x80) == 0) return v;
            s += 7;
            if (s >= 35) throw new IOException("zły varint");
        }
    }

    private static byte[] inflate(byte[] raw, int off, int expected) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(raw, off, raw.length - off);
            byte[] res = new byte[expected];
            int n = 0;
            while (n < expected) {
                int r = inf.inflate(res, n, expected - n);
                if (r == 0 && (inf.finished() || inf.needsInput())) break;
                n += r;
            }
            return res;
        } catch (DataFormatException e) {
            throw new IOException("błąd kompresji: " + e.getMessage());
        } finally {
            inf.end();
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater();
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) bo.write(buf, 0, d.deflate(buf));
        d.end();
        return bo.toByteArray();
    }

    private synchronized void send(int id, Out payload) throws IOException {
        if (out == null) throw new IOException("brak połączenia");
        byte[] body = new Out().varint(id).raw(payload.bytes()).bytes();
        Out frame = new Out();
        if (threshold >= 0) {
            if (body.length >= threshold) {
                byte[] inner = new Out().varint(body.length).raw(deflate(body)).bytes();
                frame.varint(inner.length).raw(inner);
            } else {
                frame.varint(body.length + 1).varint(0).raw(body);
            }
        } else {
            frame.varint(body.length).raw(body);
        }
        out.write(frame.bytes());
        out.flush();
    }
}
