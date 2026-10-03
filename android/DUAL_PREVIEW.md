# Camera2 / independent dual-preview source milestone

This development branch adds actual Android rear-camera capture and two independent
preview panes. It is a source/test milestone, not a phone-tested release. The already
supplied 0.2.0 Guide APK remains unchanged. No replacement APK is delivered here.

## What is implemented

- CameraManager enumerates advertised rear-camera IDs. The user chooses an ID; the
  app does not infer Xiaomi lens names from a model name or assume ID `0` is correct
- Camera2 opens that ID and selects an advertised bounded `YUV_420_888` stream.
  It prefers at most 640×480; larger fallback is capped at 921,600 pixels / 1920 per
  dimension. Copying is throttled to at most 15 fps, independently of hardware FPS
- The ImageReader planes are copied using their actual row stride, pixel stride,
  buffer position, and image crop before the native Image is closed
- Each copied image is joined to a TotalCaptureResult only by an identical sensor
  timestamp. No nearest-timestamp or previous-result substitution is allowed
- Metadata records native/copied dimensions, image crop, active and pre-correction
  arrays, reported scaler crop, sensor/display orientation, logical camera and
  reported active physical ID, focus/focal length/AF state, reported stabilization,
  distortion, rotate-and-crop, exposure duration and rolling-shutter skew
- Requests to disable supported stabilization/distortion are distinguished from
  achieved result values. Missing values remain unknown. Logical-camera physical
  identity is never guessed; reported scaler crop is not a full physical-FOV model
- The UI displays those actual camera pixels and optional actual Guide USB2 thermal
  pixels independently. The SDK-free build can use the rear camera alone
- Pure-IR mode closes the phone camera. Camera failure or denial clears only the
  visible stream; USB failure clears only IR. Pause/background/close clears both
- Per-stream session tokens reject old callbacks after restart, camera selection,
  orientation change, stop or permission failure. Frames older than 500 ms are
  removed; 10 seconds without a fresh frame stops that stream

The independent IR save page and standard Pictures/ThermalFusion implementation
remain available. This new screen does not save or export images, frames or metadata.
No sensor bytes or temperatures are modified. Temperature conversion stays disabled.

## Time and pairing semantics

There are deliberately three distinct fields:

1. Camera2 SENSOR_TIMESTAMP is the camera's reported sensor timestamp. Its source is
   recorded as REALTIME or UNKNOWN; UNKNOWN is never compared with host time
2. Visible host receipt is elapsedRealtimeNanos at Image acquisition, before copying
3. Thermal host receipt is elapsedRealtimeNanos from the SDK callback; the supplied
   SDK exposes no confirmed exposure timestamp

The UI reports the absolute difference between **the two displayed frames' host
receipt times**, only while both are fresh. It does not select a synchronized pair,
prove simultaneous exposure, or account for unknown USB buffering. It can therefore
be small even when exposure times differ substantially. It is a diagnostic, not an
alignment-quality score.

Fusion and calibration are disabled in this bounded milestone because Android
registration and a verified real-data calibration path have not been integrated.
Hardware exposure timestamps are **not declared a permanent prerequisite**: a later
fixed, stationary PCB mode may use explicitly approximate receive-time association
with low-motion checks and disclosed uncertainty. Such a mode still needs real
validation and must reject stale data, changed geometry, and unacceptable alignment.

## Geometry and presentation

Rear-camera presentation uses a clockwise rotation derived from the reported sensor
orientation and current display rotation, with no mirror. Per-frame active physical-camera
orientation is used when reported; an unresolved dynamic logical-camera orientation
is not silently replaced with a cached guess. Non-NONE ISP rotate-and-crop is rejected,
and unknown achieved ISP rotation on API31+ makes upright presentation unavailable. Native dimensions are kept
separate from screen dimensions. A rotation or camera-selection change clears the old
visible frame and requires explicit restart. ImageView FIT_CENTER adds display
letterboxing, which must not be confused with a sensor crop.

When distortion correction is reported OFF, CameraGeometry uses the reported
pre-correction active array if present, matching Camera2's scaler-crop coordinate
system. Both original arrays remain in diagnostics. CameraGeometry is an immutable
metadata snapshot, not a reusable calibration key; its field-completeness check does
not certify geometric equivalence. Future calibration binding must also include
image crop, physical camera, zoom/focus, output aspect crop, distortion model, rig,
lens/spacer and working plane. Unknown metadata cannot silently become a default.

## Phone-side checks still required

No phone, emulator or real USB capture was run for this branch. Local/CI unit tests
exercise Java gates and pixel conversion; they do not emulate Camera2 or Android's
permission/dialog lifecycle. Before a future APK is offered, validate at least:

- Xiaomi 15 / Android 15 actually enumerated rear IDs and logical/physical behavior
- Camera permission denial, later grant, repeated starts, revoke while active
- Camera in use/disconnect/error; fast Back/reopen; screen off/background and return
- Portrait/landscape and 180° rotation; exact displayed image versus metadata
- Both live streams, unplug/replug, USB authorization dialog, stale-frame clearing
- Actual resolution, field overlap, focus at the PCB plane, visible color conversion
- Sustained CPU/memory/USB behavior with both cameras; no image queue growth

No broad storage/media-read permission is added. CAMERA is requested only after the
user presses visible-start, and a grant does not automatically start a late capture.
Returning from a permission dialog/background may require pressing start again.

## Remaining inputs before real automatic registration

- First existing-APK USB/preview/save feedback and actual frame geometry
- Fixed rig, camera ID/physical lens, distances, spacer/lens configuration and overlap
- A real target visible in both thermal and visible bands, with independent validation
  captures; ordinary printed markers are not presumed thermally detectable
- A compatible marker-detection backend. The supplied vendor OpenCV 4.1 native library
  lacks ArUco; adding another same-name `libopencv_java4.so` is not safe by default
- A practical stationary/low-motion association policy and an explicit approximation
  indicator rather than an unsupported hardware-synchronization claim

## Primary references

- [Camera2 capture-result fields and crop/time semantics](https://developer.android.com/reference/android/hardware/camera2/CaptureResult)
- [Camera characteristics and timestamp source](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)
- [Image plane row/pixel stride](https://developer.android.com/reference/android/media/Image.Plane)
- [ImageReader acquisition and close ownership](https://developer.android.com/reference/android/media/ImageReader)
- [Rear-camera image-buffer orientation](https://developer.android.com/develop/devices/chromeos/learn/camera-orientation)

## Verified source checks (2026-10-03)

- Python reference: 85 tests passed
- Java production core: 101 tests plus 31 YUV conversion assertions passed
- SDK-free and local pinned-Guide builds: storage-core check, assembleDebug and lintDebug passed
- Each local lint run: 0 errors, 20 warnings (mostly hard-coded diagnostic text)
- Independent static review fixes: VisibleFrame type reference, frame-time rotation
  check, and optional autofocus hardware declaration; all rechecked
- No device, emulator, Camera2 service or USB execution; compile/test outputs are not
  new delivered APKs and must not be mistaken for hardware validation
