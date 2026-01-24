package com.omnibridge.network.affinity;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.ptr.LongByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for setting CPU affinity of threads.
 * Uses JNA to call native system functions.
 */
public final class CpuAffinity {

    private static final Logger log = LoggerFactory.getLogger(CpuAffinity.class);

    private CpuAffinity() {
    }

    /**
     * Set the CPU affinity for the current thread.
     *
     * @param cpuId the CPU core to bind to (0-based)
     * @return true if affinity was set successfully, false otherwise
     */
    public static boolean setAffinity(int cpuId) {
        if (cpuId < 0) {
            log.debug("CPU affinity disabled (cpuId={})", cpuId);
            return false;
        }

        try {
            if (Platform.isWindows()) {
                return setAffinityWindows(cpuId);
            } else if (Platform.isLinux()) {
                return setAffinityLinux(cpuId);
            } else {
                log.warn("CPU affinity not supported on this platform: {}", System.getProperty("os.name"));
                return false;
            }
        } catch (Throwable t) {
            log.warn("Failed to set CPU affinity to core {}: {}", cpuId, t.getMessage());
            return false;
        }
    }

    /**
     * Set CPU affinity on Windows using SetThreadAffinityMask.
     */
    private static boolean setAffinityWindows(int cpuId) {
        long mask = 1L << cpuId;
        long currentThread = WindowsKernel32.INSTANCE.GetCurrentThread();
        long result = WindowsKernel32.INSTANCE.SetThreadAffinityMask(currentThread, mask);

        if (result == 0) {
            int error = Native.getLastError();
            log.warn("SetThreadAffinityMask failed with error: {}", error);
            return false;
        }

        log.info("Set thread affinity to CPU {} (mask=0x{})", cpuId, Long.toHexString(mask));
        return true;
    }

    /**
     * Set CPU affinity on Linux using sched_setaffinity.
     */
    private static boolean setAffinityLinux(int cpuId) {
        long mask = 1L << cpuId;
        int pid = 0; // 0 means current thread

        // cpu_set_t is typically 128 bytes on modern Linux
        LongByReference cpuSet = new LongByReference(mask);

        int result = LinuxC.INSTANCE.sched_setaffinity(pid, 8, cpuSet);

        if (result != 0) {
            int error = Native.getLastError();
            log.warn("sched_setaffinity failed with error: {}", error);
            return false;
        }

        log.info("Set thread affinity to CPU {} (mask=0x{})", cpuId, Long.toHexString(mask));
        return true;
    }

    /**
     * Get the number of available CPU cores.
     */
    public static int getAvailableProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Windows Kernel32 interface for thread affinity.
     */
    private interface WindowsKernel32 extends Library {
        WindowsKernel32 INSTANCE = Native.load("kernel32", WindowsKernel32.class);

        long GetCurrentThread();

        long SetThreadAffinityMask(long hThread, long dwThreadAffinityMask);
    }

    /**
     * Linux C library interface for thread affinity.
     */
    private interface LinuxC extends Library {
        LinuxC INSTANCE = Native.load("c", LinuxC.class);

        int sched_setaffinity(int pid, int cpusetsize, LongByReference mask);
    }
}
