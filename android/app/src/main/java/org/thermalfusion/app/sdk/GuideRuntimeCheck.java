package org.thermalfusion.app.sdk;

import android.os.Build;
import android.system.Os;
import android.system.OsConstants;

import org.thermalfusion.app.BuildConfig;

import java.util.Arrays;

/** Must run before loading any Guide SDK/native class. */
public final class GuideRuntimeCheck {
    private GuideRuntimeCheck() {}

    public static String diagnostic() {
        return "设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT
                + "\nABI：" + Arrays.toString(Build.SUPPORTED_ABIS)
                + "\n系统内存页：" + pageSize() + " 字节";
    }

    public static String blockingReason() {
        if (!BuildConfig.GUIDE_SDK_ENABLED) return "此 APK 未包含私有 Guide SDK；仅提供合成图保存测试";
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) return "当前私有版本仅包含 ARM64 SDK，设备 ABI 不支持";
        long size = pageSize();
        if (size != 4096) return "当前 SDK 的 libmt_android.so 仅验证为 4KiB ELF 对齐，"
                + "此设备页大小为 " + size + "；已阻止加载。需厂商提供适配库；zipalign 不能修复 ELF 对齐";
        return null;
    }

    private static long pageSize() {
        try { return Os.sysconf(OsConstants._SC_PAGESIZE); }
        catch (RuntimeException failure) { return -1; }
    }
}
