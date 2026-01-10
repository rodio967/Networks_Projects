package socks5.monitor;

import socks5.util.Log;

public class MemoryMonitor {
    private static final Runtime runtime = Runtime.getRuntime();

    public static void logMemoryUsage() {
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        Log.log("Memory: used=%dMB / total=%dMB / max=%dMB (%.1f%% used)",
                usedMemory / 1024 / 1024,
                totalMemory / 1024 / 1024,
                maxMemory / 1024 / 1024,
                (usedMemory * 100.0) / maxMemory);
    }
}
