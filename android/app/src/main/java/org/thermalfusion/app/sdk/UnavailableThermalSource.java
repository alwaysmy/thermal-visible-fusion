package org.thermalfusion.app.sdk;

/** Fail closed. The synthetic UI must never be emitted as a real thermal frame. */
public final class UnavailableThermalSource implements ThermalSource {
    @Override public void start(Listener listener) {
        listener.onUnavailable("尚未接入厂商 SDK 或热像硬件；无真实帧、原始数据或温度");
    }
    @Override public void stop() { /* No device is opened by this starter. */ }
}
