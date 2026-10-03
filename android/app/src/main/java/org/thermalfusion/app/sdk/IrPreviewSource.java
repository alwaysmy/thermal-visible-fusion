package org.thermalfusion.app.sdk;

import android.hardware.usb.UsbDevice;

/** Actual hardware preview boundary. This interface never supplies synthetic frames. */
public interface IrPreviewSource {
    interface Listener {
        void onStatus(String status);
        void onFrame(IrPreviewFrame frame);
        void onUnavailable(String reason);
    }
    /** Call only after platform compatibility and explicit USB permission checks. */
    void start(UsbDevice selected, Listener listener);
    /** Immediately invalidate callbacks, then serialize native stop/close off the UI thread. */
    void stop();
}
