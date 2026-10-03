package org.thermalfusion.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.thermalfusion.app.camera.Camera2VisibleSource;
import org.thermalfusion.app.camera.CameraFrameMetadata;
import org.thermalfusion.app.camera.VisibleFrame;
import org.thermalfusion.app.sdk.GuideRuntimeCheck;
import org.thermalfusion.app.sdk.IrPreviewFrame;
import org.thermalfusion.app.sdk.IrPreviewSource;
import org.thermalfusion.app.sdk.UsbPermissionCoordinator;
import org.thermalfusion.preview.CameraGeometry;
import org.thermalfusion.preview.DualPreviewGate;
import org.thermalfusion.preview.FrameFreshness;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent actual-camera previews. No spatial transform, radiometry, or fused-image saving. */
public final class DualPreviewActivity extends Activity {
    private static final int CAMERA_PERMISSION = 303;
    private static final long FRESH_NS = 500_000_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DualPreviewGate gate = new DualPreviewGate(FRESH_NS);
    private final ExecutorService conversion = Executors.newSingleThreadExecutor();
    private final AtomicBoolean infraredRendering = new AtomicBoolean();
    private Camera2VisibleSource visibleSource;
    private IrPreviewSource infraredSource;
    private UsbPermissionCoordinator usbPermission;
    private List<Camera2VisibleSource.CameraOption> cameras = new ArrayList<>();
    private UsbDevice selectedUsb;
    private Spinner chooser;
    private CheckBox infraredOnly;
    private ImageView visibleView, infraredView;
    private TextView visibleStatus, infraredStatus, visibleMetadata, infraredMetadata, pairingStatus;
    private Button startVisible, startInfrared;
    private boolean resumed, registered, waitingCameraPermission;
    private long visibleToken, infraredToken, visibleStartedNs, infraredStartedNs;
    private boolean visibleRunning, infraredRunning;
    private int visibleDisplayRotation;

    private final BroadcastReceiver topology = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!infraredRunning) return;
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                stopInfrared("USB 连接发生变化，已清除红外；请只连接目标模组后重新开始");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && selectedUsb != null
                        && device.getDeviceName().equals(selectedUsb.getDeviceName())) {
                    stopInfrared("USB 已断开，旧红外帧已清除");
                }
            }
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long now = SystemClock.elapsedRealtimeNanos();
            if (visibleRunning && rotationDegrees() != visibleDisplayRotation) {
                stopVisible("显示方向改变，旧几何与图像已清除；请重新开始后摄");
            }
            DualPreviewGate.Snapshot state = gate.snapshot(now);
            if (!state.visibleFresh) clearVisibleImage();
            if (!state.infraredFresh) clearInfraredImage();
            if (visibleRunning && now - visibleStartedNs > 10_000_000_000L && !state.visibleFresh) {
                stopVisible("后摄 10 秒未提供新鲜有效帧；请重新开始");
            }
            if (infraredRunning && now - infraredStartedNs > 10_000_000_000L && !state.infraredFresh) {
                stopInfrared("红外 10 秒未提供新鲜有效帧；请检查连接后重新开始");
            }
            updatePairing();
            main.postDelayed(this, 200);
        }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        content.addView(label("后摄 / 红外独立预览", 23));
        content.addView(label("实验源码功能，尚未手机实测。两幅图不做叠加或自动对齐。"
                + "红外只有主机接收时间，不能证明两路同时曝光；测温保持关闭。", 14));
        infraredOnly = new CheckBox(this);
        infraredOnly.setText("纯红外视图（关闭手机相机）");
        content.addView(infraredOnly);
        chooser = new Spinner(this);
        content.addView(chooser);
        startVisible = button("授权并开始所选后摄", content, this::beginVisible);
        visibleStatus = label("后摄未启动，选择系统实际提供的相机 ID", 14);
        content.addView(visibleStatus);
        visibleView = preview(content, "可见光预览区；无新鲜帧");
        visibleMetadata = label("几何和时钟信息将在有效图像到达后显示", 12);
        content.addView(visibleMetadata);
        startInfrared = button("授权 USB 并开始红外", content, this::beginInfrared);
        infraredStatus = label("红外未启动", 14);
        content.addView(infraredStatus);
        infraredView = preview(content, "红外预览区；无新鲜帧");
        infraredMetadata = label("红外不提供已验证的曝光时间戳或温度", 12);
        content.addView(infraredMetadata);
        pairingStatus = label("自动标定 / 融合：禁用", 14);
        content.addView(pairingStatus);
        button("停止并清空两路图像", content, () -> stopBoth("已停止；再次开始需手动点击"));
        content.addView(label("本页不保存照片或原始数据。需要保存单路红外 PNG 时，使用独立红外预览页。"
                + "后摄拒绝授权或异常不会替换红外图，也不会修改 SDK 的原始值。", 13));
        infraredOnly.setOnCheckedChangeListener((button, checked) -> {
            if (checked) stopVisible("纯红外模式：手机相机已关闭");
            visibleView.setVisibility(checked ? View.GONE : View.VISIBLE);
            visibleMetadata.setVisibility(checked ? View.GONE : View.VISIBLE);
            chooser.setEnabled(!checked);
            refreshButtons();
        });
        chooser.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (visibleRunning) stopVisible("已切换后摄选择，旧图与几何已清除；请重新开始");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { stopVisible("没有所选后摄"); }
        });
        listCameras();
        String blocked = GuideRuntimeCheck.blockingReason();
        if (blocked != null) infraredStatus.setText(blocked);
        refreshButtons();
    }

    private void listCameras() {
        try {
            cameras = Camera2VisibleSource.listBackCameras(this);
            List<String> names = new ArrayList<>();
            for (Camera2VisibleSource.CameraOption camera : cameras) names.add(camera.label);
            if (names.isEmpty()) names.add("系统未提供可用后摄");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            chooser.setAdapter(adapter);
        } catch (Exception failure) {
            cameras = new ArrayList<>();
            visibleStatus.setText("无法枚举后摄：" + failure.getClass().getSimpleName() + "；可继续纯红外预览");
        }
    }

    private void beginVisible() {
        if (!resumed || visibleRunning || infraredOnly.isChecked() || waitingCameraPermission) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            waitingCameraPermission = true;
            requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            visibleStatus.setText("只为所选手机后摄请求相机权限；授权后再次点击开始");
            refreshButtons();
            return;
        }
        int index = chooser.getSelectedItemPosition();
        if (index < 0 || index >= cameras.size()) { visibleStatus.setText("没有可用后摄"); return; }
        ensureGateRunning();
        Camera2VisibleSource.CameraOption selected = cameras.get(index);
        visibleDisplayRotation = rotationDegrees();
        visibleToken = gate.beginVisible(selected.id, visibleDisplayRotation);
        long token = visibleToken;
        visibleRunning = true;
        visibleStartedNs = SystemClock.elapsedRealtimeNanos();
        clearVisibleImage();
        visibleSource = new Camera2VisibleSource(this);
        visibleSource.start(selected.id, visibleDisplayRotation, new Camera2VisibleSource.Listener() {
            @Override public void onFrame(VisibleFrame frame) { showVisible(frame, token); }
            @Override public void onStatus(String message) {
                if (gate.isVisibleCurrent(token) && resumed) visibleStatus.setText(message);
            }
            @Override public void onUnavailable(String reason) {
                if (gate.isVisibleCurrent(token)) stopVisible(reason);
            }
        });
        refreshButtons();
    }

    private void showVisible(VisibleFrame frame, long token) {
        if (!resumed || !gate.isVisibleCurrent(token)) { frame.bitmap.recycle(); return; }
        if (rotationDegrees() != visibleDisplayRotation) {
            frame.bitmap.recycle();
            stopVisible("显示方向改变，已拒绝旧方向帧；请重新开始后摄");
            return;
        }
        CameraFrameMetadata metadata = frame.metadata;
        // Exact field mapping is defined by the Camera2 adapter, not inferred from a device model.
        if (metadata.bufferToUprightRotationDegrees == null) {
            frame.bitmap.recycle();
            stopVisible("后摄传感器/ISP 旋转状态未知，不能确定显示几何；已停止");
            return;
        }
        CameraGeometry geometry;
        try { geometry = geometry(metadata); }
        catch (IllegalArgumentException invalid) {
            frame.bitmap.recycle();
            stopVisible("后摄几何元数据无效，已清空；可继续红外");
            return;
        }
        if (!FrameFreshness.isFresh(metadata.hostReceiptElapsedRealtimeNanos,
                SystemClock.elapsedRealtimeNanos(), FRESH_NS)) {
            frame.bitmap.recycle();
            return;
        }
        Bitmap display = frame.bitmap;
        if (metadata.bufferToUprightRotationDegrees != 0) {
            Matrix rotation = new Matrix();
            rotation.postRotate(metadata.bufferToUprightRotationDegrees);
            display = Bitmap.createBitmap(frame.bitmap, 0, 0, frame.bitmap.getWidth(), frame.bitmap.getHeight(), rotation, true);
            if (display != frame.bitmap) frame.bitmap.recycle();
        }
        visibleView.setImageBitmap(display);
        if (!gate.visibleDisplayed(token, metadata.hostReceiptElapsedRealtimeNanos,
                SystemClock.elapsedRealtimeNanos(), geometry, metadata.sensorTimestampNanos,
                metadata.timestampSource == CameraFrameMetadata.TimestampSource.REALTIME ? DualPreviewGate.CameraTimestampSource.REALTIME
                        : DualPreviewGate.CameraTimestampSource.UNKNOWN)) {
            stopVisible("后摄帧时间或几何检查未通过，已清空");
            return;
        }
        visibleStartedNs = metadata.hostReceiptElapsedRealtimeNanos;
        visibleStatus.setText("后摄真实帧 · 仅独立预览");
        visibleMetadata.setText(describe(metadata));
        updatePairing();
    }

    private void beginInfrared() {
        if (!resumed || infraredRunning) return;
        String blocked = GuideRuntimeCheck.blockingReason();
        if (blocked != null) { infraredStatus.setText(blocked); return; }
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbDevice> devices = manager == null ? new ArrayList<>() : new ArrayList<>(manager.getDeviceList().values());
        if (devices.size() != 1) { infraredStatus.setText("请只连接一个目标 USB2 热像模组，再点击开始"); return; }
        selectedUsb = devices.get(0);
        if (selectedUsb.getVendorId() != 0x04B4 || selectedUsb.getProductId() != 0xF7F7) {
            infraredStatus.setText("此适配仅支持已检查的 USB2 VID:PID 04B4:F7F7；未打开设备");
            selectedUsb = null;
            return;
        }
        ensureGateRunning();
        infraredToken = gate.beginInfrared();
        long token = infraredToken;
        infraredRunning = true;
        infraredStartedNs = SystemClock.elapsedRealtimeNanos();
        clearInfraredImage();
        infraredStatus.setText("请求 USB 授权；若系统弹窗使页面暂停，返回后请再次手动开始");
        usbPermission = new UsbPermissionCoordinator(this);
        usbPermission.request(selectedUsb, new UsbPermissionCoordinator.Callback() {
            @Override public void onGranted(UsbDevice device) {
                if (!resumed || !gate.isInfraredCurrent(token)) return;
                try {
                    infraredSource = (IrPreviewSource) Class.forName("org.thermalfusion.app.guide.GuideSdkPreviewSource")
                            .getConstructor(Context.class).newInstance(getApplicationContext());
                    infraredSource.start(device, new IrPreviewSource.Listener() {
                        @Override public void onStatus(String value) {
                            main.post(() -> { if (gate.isInfraredCurrent(token)) infraredStatus.setText(value); });
                        }
                        @Override public void onFrame(IrPreviewFrame frame) { receiveInfrared(frame, token); }
                        @Override public void onUnavailable(String reason) {
                            main.post(() -> { if (gate.isInfraredCurrent(token)) stopInfrared(reason); });
                        }
                    });
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    stopInfrared("红外加载失败：" + failure.getClass().getSimpleName() + "；可继续手机后摄");
                }
            }
            @Override public void onUnavailable(String reason) {
                if (gate.isInfraredCurrent(token)) stopInfrared(reason);
            }
        });
        refreshButtons();
    }

    private void receiveInfrared(IrPreviewFrame frame, long token) {
        if (!gate.isInfraredCurrent(token) || !infraredRendering.compareAndSet(false, true)) return;
        try {
            conversion.execute(() -> {
                try {
                    Bitmap bitmap = Bitmap.createBitmap(frame.displayArgb(), frame.width, frame.height, Bitmap.Config.ARGB_8888);
                    main.post(() -> {
                        try {
                            if (!resumed || !gate.isInfraredCurrent(token)
                                    || !FrameFreshness.isFresh(frame.hostReceiptTimeNs,
                                    SystemClock.elapsedRealtimeNanos(), FRESH_NS)) { bitmap.recycle(); return; }
                            infraredView.setImageBitmap(bitmap);
                            if (!gate.infraredDisplayed(token, frame.hostReceiptTimeNs, SystemClock.elapsedRealtimeNanos())) {
                                stopInfrared("红外帧时间检查未通过，已清空");
                                return;
                            }
                            infraredStartedNs = frame.hostReceiptTimeNs;
                            infraredStatus.setText("红外真实帧 · 测温关闭");
                            infraredMetadata.setText(frame.width + "×" + frame.height + " / " + frame.mode
                                    + "\n授权时所选 USB：" + frame.selectedUsbIdentity
                                    + "\n主机接收序号 " + frame.sequence + " / " + frame.hostReceiptTimeNs
                                    + "ns；不是曝光时间，未证明 SDK 实际选中句柄");
                            updatePairing();
                        } finally { infraredRendering.set(false); }
                    });
                } catch (RuntimeException failure) {
                    infraredRendering.set(false);
                    main.post(() -> { if (gate.isInfraredCurrent(token)) stopInfrared("红外图像转换失败，已清空"); });
                }
            });
        } catch (RejectedExecutionException closed) { infraredRendering.set(false); }
    }

    private void stopVisible(String reason) {
        gate.clearVisible();
        visibleRunning = false;
        if (visibleSource != null) { visibleSource.close(); visibleSource = null; }
        clearVisibleImage();
        if (visibleStatus != null) visibleStatus.setText(reason);
        refreshButtons();
        updatePairing();
    }

    private void stopInfrared(String reason) {
        gate.clearInfrared();
        infraredRunning = false;
        if (usbPermission != null) { usbPermission.close(); usbPermission = null; }
        if (infraredSource != null) { infraredSource.stop(); infraredSource = null; }
        selectedUsb = null;
        clearInfraredImage();
        if (infraredStatus != null) infraredStatus.setText(reason);
        refreshButtons();
        updatePairing();
    }

    private void stopBoth(String reason) {
        stopVisible(reason);
        stopInfrared(reason);
        gate.stop();
        updatePairing();
    }

    private void clearVisibleImage() {
        if (visibleView != null) visibleView.setImageDrawable(null);
        if (visibleMetadata != null) visibleMetadata.setText("无新鲜可见光帧；旧几何信息不再显示");
    }
    private void clearInfraredImage() {
        if (infraredView != null) infraredView.setImageDrawable(null);
        if (infraredMetadata != null) infraredMetadata.setText("无新鲜红外帧；测温关闭");
    }
    private void ensureGateRunning() { if (!gate.snapshot(SystemClock.elapsedRealtimeNanos()).running) gate.start(); }
    private void refreshButtons() {
        if (startVisible != null) startVisible.setEnabled(resumed && !visibleRunning && !waitingCameraPermission
                && !infraredOnly.isChecked() && !cameras.isEmpty());
        if (startInfrared != null) startInfrared.setEnabled(resumed && !infraredRunning && GuideRuntimeCheck.blockingReason() == null);
    }
    private void updatePairing() {
        if (pairingStatus == null) return;
        DualPreviewGate.Snapshot state = gate.snapshot(SystemClock.elapsedRealtimeNanos());
        String delta = state.receiptDeltaNs == null ? "没有两路新鲜帧" : String.format(Locale.ROOT,
                "当前显示帧的主机接收时差 %.1f ms", state.receiptDeltaNs / 1_000_000.0);
        pairingStatus.setText("后摄新鲜：" + state.visibleFresh + " / 红外新鲜：" + state.infraredFresh
                + "\n" + delta + "\n这不是曝光时差；未知 USB 缓冲延迟无法由该值排除"
                + "\n自动标定 / 融合：本阶段未接入，需真实双路图像和固定平面标定验证");
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != CAMERA_PERMISSION) return;
        waitingCameraPermission = false;
        stopVisible(results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                ? "相机权限已授予；请点击开始所选后摄" : "相机未授权；仍可使用纯红外模式");
        // Never start capture from a delayed permission callback or while backgrounded.
        listCameras();
        refreshButtons();
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(topology, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(topology, filter);
        registered = true;
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshButtons();
        main.post(watchdog);
    }
    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(watchdog);
        stopBoth("页面暂停，设备已请求关闭且旧图已清除；返回后手动开始");
        gate.background();
        super.onPause();
    }
    @Override protected void onStop() {
        if (registered) { unregisterReceiver(topology); registered = false; }
        super.onStop();
    }
    @Override protected void onDestroy() {
        stopBoth("页面已关闭");
        gate.close();
        conversion.shutdown();
        super.onDestroy();
    }
    private int rotationDegrees() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }
    private ImageView preview(LinearLayout content, String description) {
        ImageView view = new ImageView(this);
        view.setBackgroundColor(Color.rgb(20, 28, 39));
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setMinimumHeight(dp(120));
        view.setContentDescription(description);
        content.addView(view, new LinearLayout.LayoutParams(-1, dp(220)));
        return view;
    }
    private Button button(String title, LinearLayout content, Runnable action) {
        Button view = new Button(this); view.setText(title); view.setMinHeight(dp(48));
        view.setOnClickListener(ignored -> action.run()); content.addView(view); return view;
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(0xff192c36); view.setTextIsSelectable(true); contentPadding(view); return view;
    }
    private void contentPadding(View view) { view.setPadding(0, dp(5), 0, dp(5)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    // Adapter field names are mapped here once, preserving nulls rather than inventing values.
    private static CameraGeometry geometry(CameraFrameMetadata metadata) {
        CameraFrameMetadata.Bounds coordinateArray = metadata.activeArray;
        // Camera2 crop coordinates use pre-correction active array when distortion is OFF.
        if (metadata.distortionCorrectionMode != null && metadata.distortionCorrectionMode == 0
                && metadata.preCorrectionActiveArray != null) coordinateArray = metadata.preCorrectionActiveArray;
        return new CameraGeometry(metadata.cameraId, metadata.bufferWidth, metadata.bufferHeight,
                coordinateArray == null ? null : coordinateArray.toArray(),
                metadata.cropRegion == null ? null : metadata.cropRegion.toArray(),
                metadata.sensorOrientationDegrees, metadata.displayRotationDegrees,
                metadata.focusDistanceDiopters, metadata.videoStabilizationMode,
                metadata.opticalStabilizationMode, metadata.distortionCorrectionMode);
    }
    private static String describe(CameraFrameMetadata m) {
        return "Camera ID " + m.cameraId + (m.logicalCamera ? "（逻辑相机）" : "")
                + " / 实际物理 ID：" + value(m.activePhysicalId)
                + "\n原始 Image " + m.imageWidth + "×" + m.imageHeight + " / Image crop " + value(m.imageCrop)
                + " / 拷贝 " + m.bufferWidth + "×" + m.bufferHeight
                + "\nactive array " + value(m.activeArray) + " / pre-correction " + value(m.preCorrectionActiveArray)
                + "\nactive physical sensor crop " + value(m.activePhysicalSensorCropRegion)
                + "\nSCALER crop " + value(m.cropRegion) + " / zoom " + value(m.zoomRatio)
                + "\n传感器方向 " + value(m.sensorOrientationDegrees) + "° / 显示方向 " + m.displayRotationDegrees
                + "° / 方向来源 ID " + value(m.sensorOrientationCameraId)
                + " / 展示旋转 " + value(m.bufferToUprightRotationDegrees) + "° / 镜像 false"
                + "\n焦距 " + value(m.focalLengthMillimeters) + "mm / 对焦 " + value(m.focusDistanceDiopters)
                + "D / AF mode/state " + value(m.autofocusMode) + "/" + value(m.autofocusState)
                + " / lens state " + value(m.lensState)
                + "\n实际 video/OIS/distortion/rotate-crop " + value(m.videoStabilizationMode) + "/"
                + value(m.opticalStabilizationMode) + "/" + value(m.distortionCorrectionMode) + "/" + value(m.rotateAndCropMode)
                + "\nCamera2 sensor timestamp " + m.sensorTimestampNanos + "ns（" + m.timestampSource
                + "） / frame " + m.captureFrameNumber
                + "\n主机接收 " + m.hostReceiptElapsedRealtimeNanos + "ns（elapsedRealtime）"
                + "\n曝光时长 " + value(m.exposureTimeNanos) + "ns / rolling shutter skew " + value(m.rollingShutterSkewNanos)
                + "ns；这些字段不等于双路同步";
    }
    private static String value(Object value) { return value == null ? "未知" : value.toString(); }
}
