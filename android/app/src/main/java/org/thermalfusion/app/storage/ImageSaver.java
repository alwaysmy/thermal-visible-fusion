package org.thermalfusion.app.storage;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.thermalfusion.storage.GalleryTransaction;
import org.thermalfusion.storage.SaveCancellation;
import org.thermalfusion.storage.StoragePolicy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Public gallery images only. Raw/temperature exports belong in an explicit SAF flow. */
public final class ImageSaver {
    private final Context context;

    public ImageSaver(Context context) { this.context = context.getApplicationContext(); }

    public SavedImage saveTestPattern(Bitmap bitmap, SaveCancellation cancellation) throws IOException {
        return save(bitmap, "TEST_ONLY", cancellation);
    }

    /** Actual display snapshot only; this is not raw or a radiometric temperature export. */
    public SavedImage saveInfraredPreview(Bitmap bitmap, SaveCancellation cancellation) throws IOException {
        return save(bitmap, "IR_PREVIEW", cancellation);
    }

    private SavedImage save(Bitmap bitmap, String sourceTag, SaveCancellation cancellation) throws IOException {
        if (bitmap == null || bitmap.isRecycled()) throw new IOException("No valid image to save");
        String name = "ThermalFusion_" + sourceTag + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date())
                + "_" + UUID.randomUUID() + ".png";
        GalleryTransaction.Encoder encoder = output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Uri uri = new GalleryTransaction<Uri>().save(
                    new MediaStoreDestination(context.getContentResolver(), name), encoder, cancellation);
            // A content URI is the durable public locator. Never invent a /sdcard path on 29+.
            return new SavedImage(uri.toString(), "MediaStore 目录提示：" + StoragePolicy.RELATIVE_PATH);
        }
        if (context.getPackageManager().checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, context.getPackageName())
                != PackageManager.PERMISSION_GRANTED) {
            throw new IOException("Android 5–9 needs storage permission to save in shared Pictures");
        }
        LegacyEntry entry = new GalleryTransaction<LegacyEntry>().save(
                new LegacyDestination(name), encoder, cancellation);
        // Do not call a completed save a failure if the optional gallery indexing is delayed.
        String detail = "已保存；已请求媒体扫描，相册显示可能稍有延迟";
        try {
            MediaScannerConnection.scanFile(context, new String[] {entry.published.getAbsolutePath()},
                    new String[] {"image/png"}, null);
        } catch (RuntimeException scanFailure) {
            detail = "文件已保存，但媒体扫描请求失败；可按完整路径查找";
        }
        return new SavedImage(entry.published.getAbsolutePath(), detail);
    }

    public static final class SavedImage {
        public final String locator;
        public final String detail;
        SavedImage(String locator, String detail) { this.locator = locator; this.detail = detail; }
    }

    @TargetApi(29)
    private static final class MediaStoreDestination implements GalleryTransaction.Destination<Uri> {
        private final ContentResolver resolver;
        private final String name;
        MediaStoreDestination(ContentResolver resolver, String name) {
            this.resolver = resolver;
            this.name = name;
        }
        @Override public Uri create() throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/"
                    + StoragePolicy.ALBUM + "/");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            return resolver.insert(collection, values);
        }
        @Override public OutputStream open(Uri uri) throws IOException {
            return resolver.openOutputStream(uri, "w");
        }
        @Override public void publish(Uri uri) throws IOException {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) {
                throw new IOException("MediaStore did not publish exactly one image: " + uri);
            }
        }
        @Override public void delete(Uri uri) throws IOException {
            if (resolver.delete(uri, null, null) != 1) {
                throw new IOException("MediaStore could not confirm incomplete-item deletion: " + uri);
            }
        }
    }

    private static final class LegacyEntry {
        final File pending;
        final File published;
        LegacyEntry(File pending, File published) { this.pending = pending; this.published = published; }
        @Override public String toString() { return pending.getAbsolutePath(); }
    }

    @SuppressWarnings("deprecation")
    private static final class LegacyDestination implements GalleryTransaction.Destination<LegacyEntry> {
        private final String name;
        LegacyDestination(String name) { this.name = name; }
        @Override public LegacyEntry create() throws IOException {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                throw new IOException("Shared external storage is not writable");
            }
            File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    StoragePolicy.ALBUM);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create " + folder);
            File published = new File(folder, name);
            if (published.exists()) throw new IOException("Will not overwrite an existing image");
            File pending = File.createTempFile(".ThermalFusion_pending_", ".tmp", folder);
            return new LegacyEntry(pending, published);
        }
        @Override public OutputStream open(LegacyEntry row) throws IOException {
            return new FileOutputStream(row.pending);
        }
        @Override public void publish(LegacyEntry row) throws IOException {
            if (row.published.exists() || !row.pending.renameTo(row.published)) {
                throw new IOException("Cannot publish image to " + row.published);
            }
        }
        @Override public void delete(LegacyEntry row) throws IOException {
            if (row.pending.exists() && !row.pending.delete()) {
                throw new IOException("Cannot remove incomplete image " + row.pending);
            }
        }
    }
}
