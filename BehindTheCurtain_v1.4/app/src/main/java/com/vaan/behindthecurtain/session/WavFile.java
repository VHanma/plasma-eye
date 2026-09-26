package com.vaan.behindthecurtain.session;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public final class WavFile {
    private WavFile() {}

    public static final class StreamWriter implements AutoCloseable {
        private final RandomAccessFile raf;
        private final int sampleRate;
        private long dataBytes = 0;

        public StreamWriter(File file, int sampleRate) throws IOException {
            this.sampleRate = sampleRate;
            this.raf = new RandomAccessFile(file, "rw");
            raf.setLength(0);
            raf.write(new byte[44]);
        }

        public synchronized void write(byte[] data, int off, int len) throws IOException {
            raf.write(data, off, len);
            dataBytes += len;
        }

        @Override
        public synchronized void close() throws IOException {
            raf.seek(0);
            writeHeader(raf, dataBytes, sampleRate, 1, 16);
            raf.getFD().sync();
            raf.close();
        }
    }

    public static void reverseMono16(File src, File dst) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(src, "r");
             FileOutputStream out = new FileOutputStream(dst, false)) {
            if (in.length() < 44) throw new IOException("WAV too short");
            int sampleRate = readLeInt(in, 24);
            long dataBytes = in.length() - 44;
            if ((dataBytes & 1) != 0) dataBytes--;
            writeHeader(out, dataBytes, sampleRate, 1, 16);

            final int samplesPerBlock = 8192;
            byte[] block = new byte[samplesPerBlock * 2];
            long totalSamples = dataBytes / 2;
            long endSample = totalSamples;
            while (endSample > 0) {
                int n = (int) Math.min(samplesPerBlock, endSample);
                long startSample = endSample - n;
                in.seek(44 + startSample * 2);
                int bytes = n * 2;
                in.readFully(block, 0, bytes);
                for (int i = n - 1; i >= 0; i--) {
                    out.write(block[i * 2]);
                    out.write(block[i * 2 + 1]);
                }
                endSample = startSample;
            }
        }
    }

    public static int sampleRate(File wav) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
            return readLeInt(raf, 24);
        }
    }

    private static int readLeInt(RandomAccessFile raf, long offset) throws IOException {
        raf.seek(offset);
        int b0 = raf.readUnsignedByte();
        int b1 = raf.readUnsignedByte();
        int b2 = raf.readUnsignedByte();
        int b3 = raf.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static void writeHeader(RandomAccessFile out, long dataBytes, int sampleRate, int channels, int bits) throws IOException {
        byte[] h = header(dataBytes, sampleRate, channels, bits);
        out.write(h);
    }

    private static void writeHeader(OutputStream out, long dataBytes, int sampleRate, int channels, int bits) throws IOException {
        out.write(header(dataBytes, sampleRate, channels, bits));
    }

    private static byte[] header(long dataBytes, int sampleRate, int channels, int bits) {
        long byteRate = (long) sampleRate * channels * bits / 8;
        int blockAlign = channels * bits / 8;
        long riffSize = 36 + dataBytes;
        byte[] h = new byte[44];
        putAscii(h, 0, "RIFF");
        putLeInt(h, 4, riffSize);
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putLeInt(h, 16, 16);
        putLeShort(h, 20, 1);
        putLeShort(h, 22, channels);
        putLeInt(h, 24, sampleRate);
        putLeInt(h, 28, byteRate);
        putLeShort(h, 32, blockAlign);
        putLeShort(h, 34, bits);
        putAscii(h, 36, "data");
        putLeInt(h, 40, dataBytes);
        return h;
    }

    private static void putAscii(byte[] b, int p, String s) {
        for (int i = 0; i < s.length(); i++) b[p + i] = (byte) s.charAt(i);
    }

    private static void putLeShort(byte[] b, int p, long v) {
        b[p] = (byte) (v & 0xff);
        b[p + 1] = (byte) ((v >>> 8) & 0xff);
    }

    private static void putLeInt(byte[] b, int p, long v) {
        b[p] = (byte) (v & 0xff);
        b[p + 1] = (byte) ((v >>> 8) & 0xff);
        b[p + 2] = (byte) ((v >>> 16) & 0xff);
        b[p + 3] = (byte) ((v >>> 24) & 0xff);
    }
}
