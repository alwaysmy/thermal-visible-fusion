package org.thermalfusion.app;

import android.app.Activity;
import android.app.AlertDialog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Readable runtime attribution bundled in the APK, separate from build-tool notices. */
final class LicenseNotice {
    private LicenseNotice() {}
    static void show(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("第三方运行时组件说明")
                .setMessage(read(activity, "THIRD_PARTY_RUNTIME.txt"))
                .setPositiveButton("关闭", null)
                .setNeutralButton("Apache 2.0 许可", (dialog, which) ->
                        new AlertDialog.Builder(activity).setTitle("Apache License 2.0")
                                .setMessage(read(activity, "LICENSE-APACHE-2.0.txt"))
                                .setPositiveButton("关闭", null).show())
                .show();
    }
    private static String read(Activity activity, String asset) {
        try (InputStream input = activity.getAssets().open(asset);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return "许可文件无法读取：" + failure.getMessage();
        }
    }
}
