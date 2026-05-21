package com.plasmacam;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class XRayBridge {
    private static final String TAG = "XRayBridge";
    private static final int PORT = 43110;

    private final XRayVisionView view;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private Thread thread;

    public XRayBridge(XRayVisionView view) {
        this.view = view;
    }

    public void start() {
        if (running || view == null) return;

        running = true;

        thread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(PORT)) {
                socket.setReuseAddress(true);

                byte[] buf = new byte[512];

                while (running) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    String line = new String(packet.getData(), 0, packet.getLength()).trim();
                    parse(line);
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "bridge stopped", e);
            }
        }, "XRayBridge");

        thread.start();
    }

    public void stop() {
        running = false;

        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void parse(String line) {
        try {
            // Supported:
            // XRAY,nodeA,-63,0.20,0.80
            // nodeA,-63
            String[] parts = line.split(",");

            String id;
            float value;
            float x;
            float y;

            if (parts.length >= 5 && parts[0].equalsIgnoreCase("XRAY")) {
                id = parts[1].trim();
                value = Float.parseFloat(parts[2].trim());
                x = Float.parseFloat(parts[3].trim());
                y = Float.parseFloat(parts[4].trim());
            } else if (parts.length >= 2) {
                id = parts[0].trim();
                value = Float.parseFloat(parts[1].trim());
                x = hash01(id + "x");
                y = hash01(id + "y");
            } else {
                return;
            }

            main.post(() -> view.acceptSample(id, value, x, y));

        } catch (Exception e) {
            Log.e(TAG, "bad xray packet: " + line, e);
        }
    }

    private float hash01(String s) {
        int h = 0x811C9DC5;

        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }

        return (h & 0x7fffffff) / (float)0x7fffffff;
    }
}
