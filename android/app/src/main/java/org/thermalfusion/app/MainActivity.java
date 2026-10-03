package org.thermalfusion.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.thermalfusion.app.sdk.ThermalSource;
import org.thermalfusion.app.sdk.UnavailableThermalSource;
import org.thermalfusion.app.storage.ImageSaver;
import org.thermalfusion.storage.SaveCancellation;
import org.thermalfusion.storage.StoragePolicy;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A working image-save starter, not a hardware or radiometric demonstration. */
public final class MainActivity extends Activity {
    private static final int REQUEST_LEGACY_WRITE = 100;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ThermalSource thermalSource = new UnavailableThermalSource();
    private Bitmap testImage;
    private TextView status;
    private Button save;
    private Button cancel;
    private Button openImage;
    private Button shareImage;
    private Uri savedContentUri;
    private SaveCancellation activeSave;
    private boolean waitingForPermission;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 247, 248));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        content.setPadding(padding, padding, padding, padding);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(content);
        setContentView(scroll);

        content.addView(label("ThermalFusion", 28, true));
        content.addView(label("Android 图像保存原型 · TEST ONLY", 17, true));
        Button dualPreview = new Button(this);
        dualPreview.setText("后置相机 / 双路独立预览（尚未融合）");
        dualPreview.setOnClickListener(view -> startActivity(new Intent(this, DualPreviewActivity.class)));
        content.addView(dualPreview);
        if (BuildConfig.GUIDE_SDK_ENABLED) {
            Button hardware = new Button(this);
            hardware.setText("进入真实红外 SDK 预览");
            hardware.setOnClickListener(view -> startActivity(new Intent(this, IrPreviewActivity.class)));
            content.addView(hardware);
            content.addView(label("本页仍为测试图。真实红外采集在独立页面，需连接支持的 USB2 模组并授权。", 15, false));
        }
        TextView hardwareStatus = label("正在检查数据源…", 15, false);
        content.addView(hardwareStatus);
        thermalSource.start(new ThermalSource.Listener() {
            @Override public void onFrame(ThermalSource.ThermalFrame frame) {
                // This starter does not render unvalidated vendor frames.
            }
            @Override public void onUnavailable(String reason) { hardwareStatus.setText(reason); }
        });

        testImage = TestPattern.create();
        ImageView image = new ImageView(this);
        image.setImageBitmap(testImage);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription("合成彩色方块测试图；不是热像采集，不包含实测温度");
        content.addView(image, new LinearLayout.LayoutParams(-1, -2));
        content.addView(label("此图为合成测试图。保存后的 PNG 也带有 TEST PATTERN 标记。"
                + "手机相机与可选 USB 红外采集在独立预览页面；尚未实现手机端标定或融合。", 15, false));
        content.addView(label("保存位置：Pictures/ThermalFusion\n"
                + (StoragePolicy.usesMediaStore(Build.VERSION.SDK_INT)
                ? "Android 10+：使用 MediaStore，不申请存储读取或全盘权限"
                : Build.VERSION.SDK_INT >= 23 ? "Android 6–9：仅保存时请求写入共享存储权限"
                : "Android 5：共享存储写入权限在安装时声明"), 15, false));

        save = new Button(this);
        save.setText("保存测试图片 / Save image");
        save.setMinHeight(dp(48));
        save.setOnClickListener(view -> requestSave());
        content.addView(save);
        cancel = new Button(this);
        cancel.setText("取消待完成的保存");
        cancel.setEnabled(false);
        cancel.setOnClickListener(view -> {
            if (activeSave != null) {
                if (activeSave.cancel()) status.setText("正在取消并清理未完成的图片…");
                else status.setText("图片已进入发布阶段；将显示最终保存结果");
                cancel.setEnabled(false);
            }
        });
        content.addView(cancel);

        status = label(getPreferences(MODE_PRIVATE).getString("lastSave",
                "尚未保存。点击按钮后会显示实际 content URI（Android 10+）或完整文件路径。"), 14, false);
        status.setTextIsSelectable(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        openImage = new Button(this);
        openImage.setText("打开已保存图片");
        openImage.setOnClickListener(view -> openSavedImage(false));
        content.addView(openImage);
        shareImage = new Button(this);
        shareImage.setText("选择应用分享图片…");
        shareImage.setOnClickListener(view -> openSavedImage(true));
        content.addView(shareImage);
        restoreSavedUri(getPreferences(MODE_PRIVATE).getString("lastContentUri", null));
        content.addView(label("原始帧 / 温度导出：当前不可用\n"
                + "没有真实数据时不生成 raw 或温度值。接入 SDK 后再通过系统“另存为”"
                + "（SAF CreateDocument）导出 ZIP + JSON，位置由你选择。", 15, false));
        Button notices = new Button(this);
        notices.setText("第三方组件与许可");
        notices.setOnClickListener(view -> LicenseNotice.show(this));
        content.addView(notices);
        if (state != null) {
            waitingForPermission = state.getBoolean("waitingForPermission", false);
            save.setEnabled(!waitingForPermission);
        }
    }

    private void requestSave() {
        if (activeSave != null || waitingForPermission) return;
        restoreSavedUri(null);
        if (Build.VERSION.SDK_INT >= 23 && StoragePolicy.needsRuntimeWritePermission(Build.VERSION.SDK_INT)
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            waitingForPermission = true;
            save.setEnabled(false);
            status.setText("Android 6–9 需要写入共享 Pictures 的权限；拒绝后不会保存图片");
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_LEGACY_WRITE);
            return;
        }
        startSave();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQUEST_LEGACY_WRITE) return;
        waitingForPermission = false;
        save.setEnabled(true);
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startSave();
        else status.setText("未获得写入权限，图片未保存。可重试；若选择了“不再询问”，请在系统设置中授权");
    }

    private void startSave() {
        if (activeSave != null || isFinishing() || isDestroyed()) return;
        SaveCancellation token = new SaveCancellation();
        activeSave = token;
        save.setEnabled(false);
        cancel.setEnabled(true);
        status.setText("正在写入 PNG…完成前不会发布到相册");
        ImageSaver saver = new ImageSaver(this);
        Bitmap snapshot = testImage;
        io.execute(() -> {
            String result;
            String contentUri = null;
            try {
                ImageSaver.SavedImage saved = saver.saveTestPattern(snapshot, token);
                result = "已保存合成测试图\n" + saved.detail + "\n实际位置：\n" + saved.locator;
                if (saved.locator.startsWith("content://")) contentUri = saved.locator;
                getPreferences(MODE_PRIVATE).edit().putString("lastSave", result)
                        .putString("lastContentUri", contentUri).apply();
            } catch (CancellationException cancelled) {
                result = "保存已取消，未发布图片" + cleanupWarning(cancelled);
            } catch (IOException | RuntimeException failure) {
                result = "保存失败：" + failure.getMessage() + cleanupWarning(failure);
            }
            String finalResult = result;
            String finalContentUri = contentUri;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                activeSave = null;
                save.setEnabled(true);
                cancel.setEnabled(false);
                status.setText(finalResult);
                if (finalContentUri != null) restoreSavedUri(finalContentUri);
            });
        });
    }

    private void restoreSavedUri(String value) {
        savedContentUri = value != null && value.startsWith("content://") ? Uri.parse(value) : null;
        openImage.setVisibility(savedContentUri == null ? View.GONE : View.VISIBLE);
        shareImage.setVisibility(savedContentUri == null ? View.GONE : View.VISIBLE);
    }

    private void openSavedImage(boolean share) {
        if (savedContentUri == null) return;
        Intent intent;
        if (share) {
            intent = new Intent(Intent.ACTION_SEND).setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, savedContentUri);
        } else {
            intent = new Intent(Intent.ACTION_VIEW).setDataAndType(savedContentUri, "image/png");
        }
        intent.setClipData(ClipData.newRawUri("ThermalFusion synthetic test image", savedContentUri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, share ? "分享合成测试图" : "打开合成测试图"));
        } catch (ActivityNotFoundException | SecurityException failure) {
            status.setText("无法打开或分享；图片可能已删除，或没有可用应用。实际 URI：\n" + savedContentUri
                    + "\n" + failure.getMessage());
        }
    }

    private static String cleanupWarning(Throwable failure) {
        StringBuilder detail = new StringBuilder();
        for (Throwable suppressed : failure.getSuppressed()) {
            detail.append("\n注意：未能确认清理完成：").append(suppressed.getMessage());
        }
        return detail.toString();
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(Color.rgb(25, 44, 54));
        view.setPadding(0, dp(8), 0, dp(8));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("waitingForPermission", waitingForPermission);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        if (activeSave != null) activeSave.cancel();
        // Graceful shutdown lets an interrupted transaction run cleanup. Never shutdownNow().
        io.shutdown();
        thermalSource.stop();
        super.onDestroy();
    }
}
