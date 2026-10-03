package org.thermalfusion.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.thermalfusion.app.sdk.GuideRuntimeCheck;
import org.thermalfusion.app.sdk.IrPreviewFrame;
import org.thermalfusion.app.sdk.IrPreviewSource;
import org.thermalfusion.app.sdk.UsbPermissionCoordinator;
import org.thermalfusion.app.storage.ImageSaver;
import org.thermalfusion.preview.FrameFreshness;
import org.thermalfusion.storage.SaveCancellation;
import org.thermalfusion.storage.StoragePolicy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual SDK frames only. A blank/stale view can never fall back to the synthetic graphic. */
public final class IrPreviewActivity extends Activity {
    private static final int WRITE_PERMISSION = 202;
    private static final long FRESH_NS = 500_000_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService conversion = Executors.newSingleThreadExecutor();
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final AtomicBoolean rendering = new AtomicBoolean();
    private volatile int generation;
    private boolean running, receiverRegistered;
    private IrPreviewSource source;
    private UsbPermissionCoordinator permission;
    private UsbDevice selected;
    private ImageView preview;
    private TextView status, frameInfo, saveResult;
    private Button start, stop, save, open, share;
    private Bitmap latestImage;
    private long lastReceiptNs, startedNs;
    private SaveCancellation activeSave;
    private Uri savedUri;

    private final BroadcastReceiver detached = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction()) && running) {
                stopCapture("USB 连接拓扑发生变化，已停止打开/预览；请只连接目标模组后重试");
                return;
            }
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) || selected == null) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null && selected.getDeviceName().equals(device.getDeviceName())) {
                stopCapture("USB 模组已拔出，图像与待保存帧已失效；重新连接后手动开始");
            }
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (running && source != null) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (latestImage != null && !FrameFreshness.isFresh(lastReceiptNs, now, FRESH_NS)) {
                    invalidatePreview();
                    status.setText("超过 500ms 未收到有效图像；已清除旧图，等待恢复。不是实时曝光同步验证");
                }
                long reference = lastReceiptNs == 0 ? startedNs : lastReceiptNs;
                if (now - reference > 10_000_000_000L) stopCapture("10 秒内没有有效预览帧；已停止，请检查 USB/供电/模组模式后重试");
            }
            main.postDelayed(this, 200);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.setBackgroundColor(0xfff4f7f8);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        content.addView(label("真实红外 · USB2 SDK 预览", 23));
        content.addView(label(GuideRuntimeCheck.diagnostic(), 13));
        content.addView(label("仅显示 SDK 返回的红外预览。温度计算关闭：设备/25mm 镜头映射未确认。"
                + "尚未接入手机可见光相机、标定或融合。", 14));
        preview = new ImageView(this);
        preview.setBackgroundColor(Color.rgb(20, 28, 39));
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setMinimumHeight(dp(220));
        preview.setContentDescription("真实红外预览区；当前无有效帧");
        content.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        status = label("尚未打开设备。连接 USB2 模组后点击开始", 15);
        frameInfo = label("不提供曝光时间戳、NUC 状态或已校准温度", 13);
        content.addView(status);
        content.addView(frameInfo);
        start = button("授权 USB 并开始预览", content, this::begin);
        stop = button("停止并关闭设备", content, () -> stopCapture("预览已停止，设备已请求关闭"));
        stop.setEnabled(false);
        save = button("保存当前红外预览 PNG", content, this::requestSave);
        save.setEnabled(false);
        saveResult = label("图像保存至 Pictures/ThermalFusion；只是显示快照，不含原始测温数据", 14);
        saveResult.setTextIsSelectable(true);
        content.addView(saveResult);
        open = button("打开已保存红外图", content, () -> showSaved(false));
        share = button("选择应用分享红外图…", content, () -> showSaved(true));
        open.setVisibility(View.GONE);
        share.setVisibility(View.GONE);
        button("第三方组件与许可", content, () -> LicenseNotice.show(this));
        String block = GuideRuntimeCheck.blockingReason();
        if (block != null) { status.setText(block); start.setEnabled(false); }
    }

    private void begin() {
        if (running) return;
        String block = GuideRuntimeCheck.blockingReason();
        if (block != null) { status.setText(block); return; }
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbDevice> devices = manager == null ? new ArrayList<>() : new ArrayList<>(manager.getDeviceList().values());
        if (devices.size() != 1) {
            status.setText("发现 " + devices.size() + " 个 USB 设备。此版为防止 SDK 自动选错设备，请只连接目标 USB2 热像模组");
            return;
        }
        selected = devices.get(0);
        if (selected.getVendorId() != 0x04B4 || selected.getProductId() != 0xF7F7) {
            status.setText(String.format(Locale.ROOT, "当前 USB VID:PID=%04X:%04X；此版仅核对 04B4:F7F7 的 USB2 路径。USB3 依赖不完整，已拒绝打开",
                    selected.getVendorId(), selected.getProductId()));
            selected = null;
            return;
        }
        running = true;
        startedNs = SystemClock.elapsedRealtimeNanos();
        lastReceiptNs = 0;
        int session = ++generation;
        start.setEnabled(false);
        stop.setEnabled(true);
        status.setText("请求访问所选 USB 热像设备，不申请相机或媒体读取权限…");
        permission = new UsbPermissionCoordinator(this);
        permission.request(selected, new UsbPermissionCoordinator.Callback() {
            @Override public void onGranted(UsbDevice device) {
                if (session != generation || !running) return;
                try {
                    // Delayed class loading: no Guide/native class is touched before page-size/ABI checks.
                    source = (IrPreviewSource) Class.forName("org.thermalfusion.app.guide.GuideSdkPreviewSource")
                            .getConstructor(Context.class).newInstance(getApplicationContext());
                    startedNs = SystemClock.elapsedRealtimeNanos();
                    source.start(device, new IrPreviewSource.Listener() {
                        @Override public void onStatus(String value) {
                            main.post(() -> { if (session == generation) status.setText(value); });
                        }
                        @Override public void onFrame(IrPreviewFrame frame) { receiveFrame(frame, session); }
                        @Override public void onUnavailable(String reason) {
                            main.post(() -> { if (session == generation) stopCapture(reason); });
                        }
                    });
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    stopCapture("无法加载私有 SDK 适配器：" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
            }
            @Override public void onUnavailable(String reason) {
                if (session == generation) stopCapture(reason);
            }
        });
    }

    private void receiveFrame(IrPreviewFrame frame, int session) {
        if (session != generation || !rendering.compareAndSet(false, true)) return;
        try {
        conversion.execute(() -> {
            try {
                int[] argb = frame.displayArgb();
                Bitmap image = Bitmap.createBitmap(argb, frame.width, frame.height, Bitmap.Config.ARGB_8888);
                main.post(() -> {
                    try {
                        if (session != generation || !running
                                || !FrameFreshness.isFresh(frame.hostReceiptTimeNs, SystemClock.elapsedRealtimeNanos(), FRESH_NS)) {
                            image.recycle();
                            return;
                        }
                        latestImage = image;
                        lastReceiptNs = frame.hostReceiptTimeNs;
                        preview.setImageBitmap(image);
                        preview.setContentDescription("SDK 真实红外预览，温度功能关闭");
                        status.setText("正在接收真实红外预览 · 测温关闭");
                        frameInfo.setText(frame.width + "×" + frame.height + " / " + frame.mode
                                + "\n授权时所选 USB：" + frame.selectedUsbIdentity
                                + "\n主机接收序号 " + frame.sequence + "；主机单调时钟 " + frame.hostReceiptTimeNs
                                + "ns\n不是曝光时间；NUC/温度有效性未提供或未验证");
                        save.setEnabled(activeSave == null);
                    } finally { rendering.set(false); }
                });
            } catch (RuntimeException failure) {
                rendering.set(false);
                main.post(() -> { if (session == generation) stopCapture("预览转换失败：" + failure.getMessage()); });
            }
        });
        } catch (RejectedExecutionException closing) {
            // A callback can race Activity destruction after the generation check.
            rendering.set(false);
        }
    }

    private void stopCapture(String reason) {
        generation++;
        running = false;
        if (permission != null) { permission.close(); permission = null; }
        if (source != null) { source.stop(); source = null; }
        if (activeSave != null) {
            if (activeSave.cancel()) saveResult.setText("预览已停止，正在取消并清理尚未发布的快照…");
            else saveResult.setText("快照已进入发布提交阶段，将报告最终保存结果");
        }
        selected = null;
        invalidatePreview();
        start.setEnabled(GuideRuntimeCheck.blockingReason() == null);
        stop.setEnabled(false);
        status.setText(reason);
    }

    private void invalidatePreview() {
        latestImage = null;
        preview.setImageDrawable(null);
        preview.setContentDescription("没有新鲜的有效红外帧");
        save.setEnabled(false);
    }

    private void requestSave() {
        if (activeSave != null) return;
        savedUri = null;
        open.setVisibility(View.GONE);
        share.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 23 && StoragePolicy.needsRuntimeWritePermission(Build.VERSION.SDK_INT)
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, WRITE_PERMISSION);
            return;
        }
        if (!running || latestImage == null || !FrameFreshness.isFresh(lastReceiptNs, SystemClock.elapsedRealtimeNanos(), FRESH_NS)) {
            saveResult.setText("没有新鲜的有效红外帧；未保存");
            return;
        }
        Bitmap snapshot = latestImage.copy(Bitmap.Config.ARGB_8888, false);
        if (snapshot == null) { saveResult.setText("无法创建图像快照；未保存"); return; }
        SaveCancellation token = new SaveCancellation();
        activeSave = token;
        save.setEnabled(false);
        saveResult.setText("正在保存真实红外显示快照；不包含原始温度数据…");
        ImageSaver saver = new ImageSaver(this);
        storage.execute(() -> {
            String result;
            Uri resultUri = null;
            try {
                ImageSaver.SavedImage saved = saver.saveInfraredPreview(snapshot, token);
                result = "已保存真实红外预览 PNG（非测温原始文件）\n" + saved.detail + "\n实际位置：\n" + saved.locator;
                if (saved.locator.startsWith("content://")) resultUri = Uri.parse(saved.locator);
            } catch (CancellationException cancelled) {
                result = "保存已取消" + cleanupDetail(cancelled);
            } catch (IOException | RuntimeException failure) {
                result = "保存失败：" + failure.getMessage() + cleanupDetail(failure);
            } finally { snapshot.recycle(); }
            String text = result;
            Uri uri = resultUri;
            main.post(() -> {
                if (isDestroyed()) return;
                activeSave = null;
                saveResult.setText(text);
                save.setEnabled(running && latestImage != null
                        && FrameFreshness.isFresh(lastReceiptNs, SystemClock.elapsedRealtimeNanos(), FRESH_NS));
                if (uri != null) {
                    savedUri = uri;
                    open.setVisibility(View.VISIBLE);
                    share.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private static String cleanupDetail(Throwable failure) {
        StringBuilder detail = new StringBuilder();
        for (Throwable suppressed : failure.getSuppressed()) detail.append("\n清理警告：").append(suppressed.getMessage());
        return detail.toString();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == WRITE_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestSave();
            else saveResult.setText("未授予旧系统共享存储写入权限，未保存图片");
        }
    }

    private void showSaved(boolean sharing) {
        if (savedUri == null) return;
        Intent intent = sharing ? new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, savedUri)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(savedUri, "image/png");
        intent.setClipData(ClipData.newRawUri("ThermalFusion infrared preview", savedUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(intent, sharing ? "分享红外预览图" : "打开红外预览图")); }
        catch (RuntimeException failure) { saveResult.setText("无法打开：" + failure.getMessage() + "\n" + savedUri); }
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detached, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(detached, filter);
        receiverRegistered = true;
        main.post(watchdog);
    }

    @Override protected void onStop() {
        stopCapture("页面离开，预览已停止；返回后请手动开始");
        main.removeCallbacks(watchdog);
        if (receiverRegistered) { unregisterReceiver(detached); receiverRegistered = false; }
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (activeSave != null) activeSave.cancel();
        conversion.shutdown();
        storage.shutdown();
        super.onDestroy();
    }

    private Button button(String text, LinearLayout parent, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setMinHeight(dp(48));
        button.setOnClickListener(view -> action.run()); parent.addView(button); return button;
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(0xff192c36); view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
