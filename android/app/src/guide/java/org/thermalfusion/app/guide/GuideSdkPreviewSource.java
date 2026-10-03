package org.thermalfusion.app.guide;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import com.guide.sdk.GuideInterface;
import com.guide.sdk.bean.DataCallback;
import com.guide.sdk.bean.DeviceStatusInfo;
import com.guide.sdk.bean.GuideUsbVideoMode;
import com.guide.sdk.bean.PreviewConfig;

import org.thermalfusion.app.sdk.GuideRuntimeCheck;
import org.thermalfusion.app.sdk.IrPreviewFrame;
import org.thermalfusion.app.sdk.IrPreviewSource;

import java.util.concurrent.ExecutorService;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Original adapter invoking public SDK interfaces. Only compiled with -PguideAar.
 * No vendor sample source or native binaries are part of this repository.
 * USB2 only; the supplied AAR's USB3 dependency bundle is incomplete.
 */
public final class GuideSdkPreviewSource implements IrPreviewSource {
    // SDK singleton requires serialization across screen recreation, not just per Activity.
    private static final ExecutorService SDK = Executors.newSingleThreadExecutor();
    private static GuideSdkPreviewSource owner;
    private final Context context;
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private GuideInterface sdk;
    private volatile int width, height;

    public GuideSdkPreviewSource(Context context) { this.context = context.getApplicationContext(); }

    @Override public void start(UsbDevice selected, Listener listener) {
        int session = generation.incrementAndGet();
        SDK.execute(() -> {
            if (session != generation.get()) return;
            try {
                String block = GuideRuntimeCheck.blockingReason();
                if (block != null) throw new IllegalStateException(block);
                UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                if (usb == null || selected.getVendorId() != 0x04B4 || selected.getProductId() != 0xF7F7) {
                    throw new IllegalStateException("仅支持已核对的 USB2 设备 04B4:F7F7；USB3 依赖尚不完整");
                }
                // SDK open() auto-selects. Restrict topology to prevent opening a different device.
                requireSelectedTopology(usb, selected);
                if (owner != null && owner != this) {
                    owner.generation.incrementAndGet();
                    owner.closeOnSdkThread();
                }
                if (sdk != null) closeOnSdkThread();
                owner = this;
                listener.onStatus("USB 已授权，正在读取设备配置…");
                sdk = GuideInterface.getInstance();
                sdk.setDebugLog(false);
                final DeviceStatusInfo[] actual = new DeviceStatusInfo[1];
                requireSelectedTopology(usb, selected); // Immediately before the SDK's auto-selection.
                boolean opened = sdk.open(context, value -> actual[0] = value);
                if (!opened || actual[0] == null) throw new IllegalStateException("SDK open() 失败或未返回设备配置");
                requireSelectedTopology(usb, selected); // Detect selection/topology change during open.
                if (session != generation.get()) { closeOnSdkThread(); return; }
                DeviceStatusInfo configuration = actual[0];
                width = configuration.width;
                height = configuration.height;
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                        || (width & 1) != 0 || (long) width * height > 4_194_304) {
                    throw new IllegalStateException("设备报告未支持的尺寸：" + width + "×" + height);
                }
                PreviewConfig preview = new PreviewConfig();
                preview.deviceStatusInfo = configuration;
                // startPreview ignores the mode-write boolean in this SDK. Verify device
                // readback ourselves before allowing its parser to assume packed YUV.
                if (sdk.getGuideUsbVideoMode() != GuideUsbVideoMode.YUV) {
                    if (!sdk.sendControlCommand(GuideUsbVideoMode.getCmdBytes(GuideUsbVideoMode.YUV))
                            || sdk.getGuideUsbVideoMode() != GuideUsbVideoMode.YUV) {
                        throw new IllegalStateException("设备未确认 YUV 输出模式；拒绝用错误格式解释数据");
                    }
                }
                preview.guideUsbVideoMode = GuideUsbVideoMode.YUV;
                preview.paletteIndex = -1; // Preserve the device's palette; no unrequested palette change.
                sdk.setPreviewConfig(preview);
                requireSelectedTopology(usb, selected);
                if (session != generation.get()) { closeOnSdkThread(); return; }
                sequence.set(0);
                listener.onStatus("已打开 " + width + "×" + height + "；请求 YUV 预览，等待真实回调。测温关闭");
                String usbIdentity = String.format(Locale.ROOT, "%04X:%04X / %s / id=%d",
                        selected.getVendorId(), selected.getProductId(), selected.getDeviceName(), selected.getDeviceId());
                sdk.startPreview(frame -> acceptFrame(frame, session, usbIdentity, listener));
            } catch (RuntimeException | LinkageError failure) {
                closeOnSdkThread();
                if (session == generation.get()) {
                    generation.incrementAndGet();
                    listener.onUnavailable("SDK 无法开始预览：" + failure.getClass().getSimpleName()
                            + ": " + failure.getMessage());
                }
            }
        });
    }

    private static void requireSelectedTopology(UsbManager usb, UsbDevice selected) {
        Map<String, UsbDevice> snapshot = usb.getDeviceList();
        UsbDevice live = snapshot.get(selected.getDeviceName());
        if (snapshot.size() != 1 || live == null || live.getDeviceId() != selected.getDeviceId()
                || live.getVendorId() != selected.getVendorId() || live.getProductId() != selected.getProductId()
                || !usb.hasPermission(live)) {
            throw new IllegalStateException("设备拓扑/身份/权限已变化；仅允许单个已选择的 USB2 模组");
        }
        // The SDK does not expose the opened UsbDevice. This is a bounded topology check,
        // not a claim that its internal handle identity has been independently verified.
    }

    private void acceptFrame(DataCallback callback, int session, String usbIdentity, Listener listener) {
        long hostReceipt = SystemClock.elapsedRealtimeNanos();
        if (session != generation.get() || callback == null || !callback.isSuccess) return;
        try {
            GuideUsbVideoMode mode = callback.mode;
            boolean containsUyvy = mode == GuideUsbVideoMode.YUV || mode == GuideUsbVideoMode.YUV_PARAM
                    || mode == GuideUsbVideoMode.Y16_YUV || mode == GuideUsbVideoMode.Y16_PARAM_YUV
                    || mode == GuideUsbVideoMode.TEMP_YUV || mode == GuideUsbVideoMode.TEMP_PARAM_YUV;
            boolean containsSamples = mode == GuideUsbVideoMode.Y16_YUV || mode == GuideUsbVideoMode.Y16_PARAM_YUV
                    || mode == GuideUsbVideoMode.TEMP_YUV || mode == GuideUsbVideoMode.TEMP_PARAM_YUV;
            boolean containsParameters = mode == GuideUsbVideoMode.YUV_PARAM || mode == GuideUsbVideoMode.Y16_PARAM_YUV
                    || mode == GuideUsbVideoMode.TEMP_PARAM_YUV;
            if (!containsUyvy) throw new IllegalArgumentException("回调模式 " + mode + " 不含 UYVY；不伪造图像或温度");
            if (containsSamples && callback.y16 == null) throw new IllegalArgumentException("模式声明 Y16/TEMP 但未提供数组");
            if (containsParameters && callback.paramLine == null) throw new IllegalArgumentException("模式声明参数行但未提供数组");
            // Clone all retained mode-specific buffers INSIDE the callback before the pool reuses them.
            IrPreviewFrame copied = new IrPreviewFrame(width, height, sequence.incrementAndGet(), hostReceipt,
                    mode.name(), usbIdentity, callback.uyvy, containsSamples ? callback.y16 : null,
                    containsParameters ? callback.paramLine : null);
            if (session == generation.get()) listener.onFrame(copied);
        } catch (RuntimeException failure) {
            if (generation.compareAndSet(session, session + 1)) {
                listener.onUnavailable("无效 SDK 帧，已停止：" + failure.getMessage());
                SDK.execute(this::closeOnSdkThread);
            }
        }
    }

    @Override public void stop() {
        generation.incrementAndGet(); // Old callbacks become invalid immediately.
        SDK.execute(this::closeOnSdkThread);
    }

    private void closeOnSdkThread() {
        if (owner != this) { sdk = null; return; }
        if (sdk != null) {
            try { sdk.stopPreview(); } catch (RuntimeException | LinkageError ignored) { }
            try { sdk.close(); } catch (RuntimeException | LinkageError ignored) { }
        }
        sdk = null;
        owner = null;
    }
}
