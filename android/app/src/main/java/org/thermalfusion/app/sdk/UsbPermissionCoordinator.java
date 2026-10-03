package org.thermalfusion.app.sdk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * Original, optional USB permission port. NOT wired to this test-pattern UI.
 * Use on the main thread after the user selects a device. The receiver is private,
 * the PendingIntent is package-scoped and immutable, and callback extras are not
 * trusted: the selected device and UsbManager.hasPermission are rechecked.
 * No vendor helper, CAMERA permission, or broad storage permission is involved.
 */
public final class UsbPermissionCoordinator implements AutoCloseable {
    public interface Callback {
        void onGranted(UsbDevice selectedDevice);
        void onUnavailable(String reason);
    }

    private final Context context;
    private final UsbManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String action;
    private UsbDevice selected;
    private Callback callback;
    private PendingIntent permissionIntent;
    private boolean registered;
    private boolean closed;
    private final Runnable timeout = () -> finish(false, "USB permission request timed out; retry explicitly");
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!action.equals(intent.getAction()) || closed || selected == null) return;
            // An immutable PendingIntent does not depend on the system filling mutable extras.
            // Query the authoritative permission state for the device the user selected.
            UsbDevice live = selectedDeviceStillAttached();
            if (live == null) finish(false, "Selected USB device was disconnected or changed");
            else finish(manager.hasPermission(live), "USB permission was not granted");
        }
    };

    public UsbPermissionCoordinator(Context context) {
        this.context = context.getApplicationContext();
        manager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        action = this.context.getPackageName() + ".USB_PERMISSION." + UUID.randomUUID();
    }

    /** One request per coordinator; close it when its owning screen/device session ends. */
    public void request(UsbDevice device, Callback result) {
        requireMainThread();
        if (closed || selected != null) throw new IllegalStateException("Create a new coordinator for each USB request");
        if (device == null || result == null) throw new IllegalArgumentException("A selected device and callback are required");
        selected = device;
        callback = result;
        if (manager == null || selectedDeviceStillAttached() == null) {
            finish(false, "Selected USB device is unavailable");
            return;
        }
        if (manager.hasPermission(device)) {
            finish(true, "");
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(action);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(receiver, filter);
            registered = true;
            Intent response = new Intent(action).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            permissionIntent = PendingIntent.getBroadcast(context, 0, response, flags);
            handler.postDelayed(timeout, 120_000L);
            manager.requestPermission(device, permissionIntent);
        } catch (RuntimeException failure) {
            finish(false, "Cannot request USB permission: " + failure.getMessage());
        }
    }

    private UsbDevice selectedDeviceStillAttached() {
        if (manager == null || selected == null) return null;
        UsbDevice live = manager.getDeviceList().get(selected.getDeviceName());
        if (live == null || live.getDeviceId() != selected.getDeviceId()
                || live.getVendorId() != selected.getVendorId()
                || live.getProductId() != selected.getProductId()) return null;
        return live;
    }

    private void finish(boolean granted, String reason) {
        if (closed) return;
        Callback result = callback;
        UsbDevice live = selectedDeviceStillAttached();
        boolean confirmed = granted && live != null && manager.hasPermission(live);
        close();
        if (result == null) return;
        if (confirmed) result.onGranted(live);
        else result.onUnavailable(reason.isEmpty() ? "USB device/permission is no longer available" : reason);
    }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        handler.removeCallbacks(timeout);
        if (registered) {
            context.unregisterReceiver(receiver);
            registered = false;
        }
        if (permissionIntent != null) permissionIntent.cancel();
        callback = null;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Use USB permission port on main thread");
    }
}
