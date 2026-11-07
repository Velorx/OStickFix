package com.wawek.stickfix;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps a connection to stickfixd on 127.0.0.1 and reconnects when it drops. */
final class DaemonClient {
    static final int PORT = 47811;

    interface Listener {
        void onConnection(boolean connected, String info);

        /** Raw and shaped stick positions, each -1..1: lx ly rx ry olx oly orx ory. */
        void onSample(float[] s);
    }

    private final String token;
    private final Listener listener;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private volatile Socket socket;
    private volatile boolean running = true;
    private volatile String pendingSet;
    private volatile boolean watch;

    DaemonClient(String token, Listener listener) {
        this.token = token;
        this.listener = listener;
        Thread t = new Thread(this::loop, "stickfix-daemon");
        t.setDaemon(true);
        t.start();
    }

    boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    /** Sends a full SET line now if connected; otherwise it is sent on connect. */
    void applyProfile(Profile p) {
        String line = p.toSetCommand();
        pendingSet = line;
        send(line);
    }

    void setWatch(boolean on) {
        watch = on;
        send("WATCH " + (on ? 1 : 0));
    }

    void send(String line) {
        writer.execute(() -> {
            Socket s = socket;
            if (s == null) return;
            try {
                OutputStream o = s.getOutputStream();
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            } catch (Exception e) {
                closeQuietly(s);
            }
        });
    }

    void close() {
        running = false;
        closeQuietly(socket);
        writer.shutdownNow();
    }

    private void loop() {
        while (running) {
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 500);
                socket = s;
                send("AUTH " + token);
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running && (line = in.readLine()) != null) handleLine(line);
            } catch (Exception ignored) {
            } finally {
                boolean was = socket == s;
                socket = null;
                closeQuietly(s);
                if (was) listener.onConnection(false, null);
            }
            if (!running) break;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handleLine(String line) {
        if (line.startsWith("S ")) {
            String[] p = line.split(" ");
            if (p.length < 9) return;
            float[] v = new float[8];
            for (int i = 0; i < 8; i++) v[i] = Integer.parseInt(p[i + 1]) / 10000f;
            listener.onSample(v);
        } else if (line.startsWith("OK")) {
            String set = pendingSet;
            if (set != null) send(set);
            if (watch) send("WATCH 1");
            send("STATUS");
            listener.onConnection(true, "stickfixd " + line.substring(2).trim());
        } else if (line.startsWith("STATUS")) {
            listener.onConnection(true, line.substring(7));
        } else if (line.startsWith("ERR auth")) {
            listener.onConnection(false, "daemon rejected the token - start it again from the script");
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
