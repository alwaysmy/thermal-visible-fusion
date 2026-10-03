// SDK-neutral interface sketch only. Not compiled, no Android build or vendor implementation.
package reference.thermalfusion

// All buffers need an explicit ownership policy. The adapter must copy or retain
// vendor buffers before returning from a callback; never use recycled memory.
data class ThermalFrame(
    val sequence: Long,
    val captureTimeNs: Long?,
    val clockId: String?,
    val hostReceiptTimeNs: Long,
    val width: Int,
    val height: Int,
    val rawU16: ShortArray, // unsigned bits; interpretation belongs to vendor SDK
    val temperatureC: FloatArray?, // only when officially supported
    val invalidPixelMask: ByteArray?,
    val nucInProgress: Boolean,
    val valid: Boolean,
    val formatAndGeometryFingerprint: String
)

data class VisibleFrame(
    val sequence: Long,
    val captureTimeNs: Long?,
    val clockId: String?,
    val width: Int,
    val height: Int,
    val rgba: ByteArray,
    val geometryFingerprint: String
)

interface ThermalSource {
    // Start only after the Android USB permission flow succeeds.
    // On unplug/error, invalidate pending pairs and notify the UI immediately.
    fun start(onFrame: (ThermalFrame) -> Unit, onError: (String) -> Unit)
    fun stop()
}

interface VisibleSource {
    // Camera identity, focus, crop, rotation, mirror, zoom and stabilization are
    // tracked and bound to the calibration. No silent lens switching.
    fun start(onFrame: (VisibleFrame) -> Unit, onError: (String) -> Unit)
    fun stop()
}

interface FramePairer {
    // A host callback receipt time alone does not establish capture synchrony.
    // Reject missing/uncertain clocks, excessive skew, stale buffers, and NUC.
    fun offerThermal(frame: ThermalFrame)
    fun offerVisible(frame: VisibleFrame)
    fun reset(reason: String)
}

enum class DisplayMode { PURE_IR, VISIBLE_ALPHA, VISIBLE_EDGES }

// Radiometry is a separate SDK-owned path. Display fusion must not change rawU16,
// temperatureC, emissivity or SDK correction parameters. Tap coordinates must be
// mapped to native thermal pixels before reading a temperature, with validity checks.
