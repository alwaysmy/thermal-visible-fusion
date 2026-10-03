# Private SDK integration status

The Android code offers an SDK-free default build and an opt-in private vendor build. The vendor AAR is supplied locally and is not uploaded to this repository or public CI. The build checks its SHA-256 against the inspected standalone SDK 1.0.1; the sample project's identically named AAR is not byte-identical.

## Current private variant

- API21+ source compatibility, with version-gated storage and USB permission APIs
- ARM64 test target; runtime ABI and actual memory page-size diagnostics before loading the SDK
- Guide USB2 VID/PID 04B4:F7F7 only, deliberately rejecting ambiguous USB topology
- App-owned package-scoped USB permission request, with actual selected-device permission checks
- SDK open/start/stop/close serialized, connection and app lifecycle invalidation, stale-frame clearing
- Actual reported frame dimensions and packed UYVY preview; callback buffers copied before returning to the SDK
- Host receipt timestamp and app sequence only; neither is a hardware exposure timestamp
- PNG snapshot to shared Pictures/ThermalFusion using the same tested storage transaction
- Temperature conversion disabled, because the actual module/lens mapping must not be guessed

The delivered 0.2.0 APK is an infrared preview and storage milestone. This development branch additionally implements [Camera2 capture and independent dual-preview source](../android/DUAL_PREVIEW.md), with no replacement APK delivery or phone-validation claim. Live automatic calibration/fusion, video recording and raw/temperature SAF export are not implemented. The Python code remains the separate geometric reference.

## Compatibility findings from static inspection

The vendor helper for USB permission creates a mutable implicit PendingIntent, unsuitable for target34+ on Android14+. The app uses its own permission flow instead. The SDK open method does not request permission on its own.

The current ARM64 main native library has 4KiB ELF LOAD alignment. The app checks the actual runtime page size and rejects an unvalidated 16KiB configuration before loading it. Android15 alone does not establish the phone's page size. Zip alignment is not a replacement for rebuilding an incompatible native library.

The standalone AAR's USB3 implementation refers to external classes whose nested dependency archives are empty. This variant does not claim USB3 support. Obtain complete, licensed and compatible dependencies from the vendor before implementing that path.

The SDK callback has no hardware capture timestamp or frame sequence. SDK buffers are recycled. Independent copies and clearly labelled receipt timing are mandatory; a phone-side timestamp does not establish cross-camera synchronization.

Software UYVY-to-RGB conversion currently assumes BT.601 limited range. Arithmetic and bounds are tested, but visual/color equivalence to the vendor preview remains a hardware check. No temperature conversion uses those displayed RGB values.

The inspected vendor example's fixed measurement arguments correspond to a specific module/lens pairing. They are not valid universal settings for a modified 25mm optical setup. No temperature value is manufactured or displayed as measured in this milestone.

## Future automatic-fusion integration

Static build strings identify the vendor-bundled `libopencv_java4.so` as OpenCV4.1.0, without an ArUco module/JNI export. Do not drop a second modern OpenCV library with the same native name into the APK and assume compatibility. Camera2 capture/geometry metadata and receipt-diagnostic lifecycle gates have now been implemented independently in this source branch. The automatic target-detection backend needs an explicit isolation or compatible-library plan before Android integration.

The current IR milestone does not close the automatic-fusion goal. A real successful USB frame, actual dimensions/mode, visible frame and fixed-rig calibration capture are still needed to validate geometry and thermal target visibility.

## Remaining phone validation

Building, linting and unit tests do not prove physical USB compatibility, a live frame, correct palette/range, Gallery visibility on every OEM, or suitability for pinpointing a small PCB component. Check connection, unplug/replug, background/reopen, denied permission, stale/no-frame behavior, saved-image content and exact gallery location on the target phone. Record actual ABI/page size and any diagnostic message.

Source minimum API21 does not certify all Android5 devices. The private artifact is ARM64 and requires the checked native-library page configuration; hardware testing is still required.

## Licensing and publication boundary

The supplied SDK documentation has a confidentiality/copyright notice. No separate binary redistribution grant was established in the supplied material. Public source contains original application/adaptation code and third-party notices; vendor documents, source samples, SDK binaries, official APK and signing material are excluded. A private test APK made from the user's locally supplied SDK is not a public release or redistribution license.

## Official Android references

- https://developer.android.com/about/versions/14/behavior-changes-14
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/develop/connectivity/usb/host
