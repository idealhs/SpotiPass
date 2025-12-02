package com.spotipass.module;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class SpotiPassRuntimeLog {

    private static final Object LOCK = new Object();
    private static final int MAX_CHARS = 128 * 1024;
    private static final int TRIM_TO_CHARS = 96 * 1024;
    private static final StringBuilder BUFFER = new StringBuilder(8 * 1024);

    private SpotiPassRuntimeLog() {}

    static void append(String line) {
        if (line == null || line.isEmpty()) return;
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (LOCK) {
            BUFFER.append(timestamp).append(" ").append(line).append('\n');
            trimIfNeededLocked();
        }
    }

    static String readTail(int maxChars) {
        synchronized (LOCK) {
            if (BUFFER.length() == 0) return "";
            if (maxChars <= 0 || BUFFER.length() <= maxChars) {
                return BUFFER.toString();
            }
            int start = BUFFER.length() - maxChars;
            return "... (\u622a\u65ad) ...\n" + BUFFER.substring(start);
        }
    }

    static void clear() {
        synchronized (LOCK) {
            BUFFER.setLength(0);
        }
    }

    static int length() {
        synchronized (LOCK) {
            return BUFFER.length();
        }
    }

    private static void trimIfNeededLocked() {
        if (BUFFER.length() <= MAX_CHARS) return;
        int deleteTo = BUFFER.length() - TRIM_TO_CHARS;
        if (deleteTo <= 0) return;
        BUFFER.delete(0, deleteTo);
        int firstNewline = BUFFER.indexOf("\n");
        if (firstNewline > 0 && firstNewline < BUFFER.length()) {
            BUFFER.delete(0, firstNewline + 1);
        }
    }
}
