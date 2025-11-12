package socks5.util;

import java.util.Locale;

public class Log {
    public static void log(String fmt, Object... args) {
        System.out.printf(Locale.ROOT, "[SOCKS] " + fmt + "%n", args);
    }
}
