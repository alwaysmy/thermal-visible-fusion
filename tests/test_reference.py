from dataclasses import replace
import hashlib
import cv2
import numpy as np
import pytest
from thermal_fusion.core import (CalibrationError, CameraGeometry, Correspondences, FramePair,
    GateConfig, calibrate, fuse, parallax_residual_px, project)
from thermal_fusion.detection import detect_markers, match_markers
from thermal_fusion.synthetic import geometry, image_pair, display, transform


def grid():
    return np.array([(x, y) for y in np.linspace(70, 420, 6) for x in np.linspace(70, 550, 8)])


def observations(kind="perspective", noise=0.15, outliers=0):
    rng = np.random.default_rng(81)
    src = grid()
    dst = project(src, transform(kind))+rng.normal(0, noise, src.shape)
    if outliers:
        dst[:outliers] = rng.uniform([40, 40], [590, 470], (outliers, 2))
    val_src = src+np.array([0.5, 0.7])
    val_dst = project(val_src, transform(kind))+rng.normal(0, noise, src.shape)
    return (Correspondences(src, dst, "fit", "synthetic"),
            Correspondences(val_src, val_dst, "validation", "synthetic"))


def fit_calibration(**kwargs):
    a, b = observations(**kwargs)
    return calibrate(a, b, geometry(), created_unix_s=1000, valid_until_unix_s=2000)


def frame_pair():
    v, raw = image_pair()
    temperatures = raw.astype(np.float32)*0.002-20  # arbitrary fixture, not a real SDK conversion
    return FramePair(v, display(raw), raw, temperatures, 1_000_000_000, 1_010_000_000, True)


def render(pair=None, calibration=None, geo=None, **kwargs):
    return fuse(pair or frame_pair(), calibration or fit_calibration(), geo or geometry(),
                now_unix_s=kwargs.pop("now_unix_s", 1500),
                now_monotonic_ns=kwargs.pop("now_monotonic_ns", 1_020_000_000),
                live=kwargs.pop("live", False), **kwargs)


@pytest.mark.parametrize("kind", ["identity", "translation", "rotation", "perspective"])
def test_noisy_transforms_recover_ground_truth(kind):
    c = fit_calibration(kind=kind)
    e = np.linalg.norm(project(grid(), c.homography)-project(grid(), transform(kind)), axis=1)
    assert np.percentile(e, 95) < .2
    assert c.metrics["validation_p95_px"] < .6


@pytest.mark.parametrize("kind", ["translation", "rotation", "perspective"])
@pytest.mark.parametrize("invert", [False, True])
def test_automatic_image_detection(kind, invert):
    v, t = image_pair(kind, seed=5)
    v2, t2 = image_pair(kind, seed=23, invert_thermal=invert)
    a = match_markers(v, t, capture_id="a", evidence="synthetic")
    b = match_markers(v2, t2, capture_id="b", evidence="synthetic")
    c = calibrate(a, b, geometry(), created_unix_s=0, valid_until_unix_s=1)
    assert len(a.visible) == 48
    assert c.metrics["validation_max_px"] < .7
    assert detect_markers(t2).inverted == invert


def test_ransac_rejects_bounded_outliers():
    c = fit_calibration(outliers=8)
    assert c.metrics["fit_inliers"] == 40
    assert c.metrics["validation_p95_px"] < .6


def test_too_many_outliers_fail():
    with pytest.raises(CalibrationError, match="outliers"):
        fit_calibration(outliers=20)


@pytest.mark.parametrize("failure", ["insufficient", "nan", "duplicate", "collinear", "clustered", "outside"])
def test_bad_observations_fail(failure):
    a, b = observations()
    p = a.visible.copy()
    if failure == "insufficient": p = p[:4]
    if failure == "nan": p[0, 0] = np.nan
    if failure == "duplicate": p[0] = p[1]
    if failure == "collinear": p[:, 1] = np.arange(len(p)) + 70; p[:, 0] = 2*p[:, 1]
    if failure == "clustered": p = p/100 + 200
    if failure == "outside": p[0, 0] = -1
    with pytest.raises(CalibrationError):
        calibrate(replace(a, visible=p), b, geometry(), created_unix_s=0, valid_until_unix_s=1)


def test_separate_validation_capture_required():
    a, b = observations()
    with pytest.raises(CalibrationError, match="separate"):
        calibrate(a, replace(b, capture_id="fit"), geometry(), created_unix_s=0, valid_until_unix_s=1)


def test_bad_validation_is_not_discarded_as_outlier():
    a, b = observations()
    shifted = b.thermal.copy()
    shifted[0] += [12, 0]
    with pytest.raises(CalibrationError, match="independent validation"):
        calibrate(a, replace(b, thermal=shifted), geometry(), created_unix_s=0, valid_until_unix_s=1)


def test_curved_or_multiplane_validation_fails():
    a, b = observations()
    shifted = b.thermal.copy()
    shifted[::2, 0] += 5
    with pytest.raises(CalibrationError, match="independent validation"):
        calibrate(a, replace(b, thermal=shifted), geometry(), created_unix_s=0, valid_until_unix_s=1)


@pytest.mark.parametrize("field,value", [("rig_revision", "moved"), ("lens_id", "new-lens"),
    ("spacer_mm", 1.8), ("plane_distance_mm", 331)])
def test_rig_mismatch_fails(field, value):
    with pytest.raises(CalibrationError, match="geometry changed"):
        render(geo=replace(geometry(), **{field: value}))


@pytest.mark.parametrize("field,value", [("camera_id", "other-camera"), ("rotation_cw", 90),
    ("mirrored", True), ("crop_xywh", (0, 0, 630, 512)), ("frame_size", (620, 512)),
    ("zoom_ratio", 1.1), ("focus_token", "new-focus"), ("distortion_profile", "new-intrinsics")])
def test_camera_mismatch_fails(field, value):
    g = geometry()
    g = replace(g, visible=replace(g.visible, **{field: value}))
    with pytest.raises(CalibrationError, match="geometry changed"):
        render(geo=g)


@pytest.mark.parametrize("now", [999, 2001, float("nan")])
def test_expired_or_invalid_calibration_time(now):
    with pytest.raises(CalibrationError, match="expired or clock"):
        render(now_unix_s=now)


def test_synthetic_never_accepted_live():
    with pytest.raises(CalibrationError, match="synthetic"):
        render(live=True)


@pytest.mark.parametrize("change,error", [({"clock_verified": False}, "clock"),
    ({"visible_timestamp_ns": 900_000_000}, "synchronization"),
    ({"thermal_valid": False}, "unavailable"),
    ({"visible_timestamp_ns": 1_050_000_000}, "future"),
    ({"visible_timestamp_ns": 1.0}, "integers")])
def test_bad_frame_state_fails(change, error):
    with pytest.raises(CalibrationError, match=error):
        render(pair=replace(frame_pair(), **change))


def test_stale_frame_fails():
    with pytest.raises(CalibrationError, match="stale"):
        render(now_monotonic_ns=2_000_000_000)


@pytest.mark.parametrize("mode", ["ir", "alpha", "edges"])
def test_thermal_and_temperature_unchanged(mode):
    p = frame_pair()
    raw_before, temps_before = p.raw_thermal.copy(), p.temperatures_c.copy()
    r = render(pair=p, mode=mode)
    assert np.array_equal(r.raw_thermal, raw_before)
    assert np.array_equal(p.raw_thermal, raw_before)
    assert np.array_equal(r.temperatures_c, temps_before)
    assert np.array_equal(p.temperatures_c, temps_before)
    assert not r.raw_thermal.flags.writeable and not r.temperatures_c.flags.writeable
    assert not np.shares_memory(r.raw_thermal, p.raw_thermal)
    if mode == "ir":
        assert np.array_equal(r.display_bgr, p.thermal_display_bgr)
        assert not r.overlay_mask.any()
    else:
        assert r.overlay_mask.any()
        assert not r.overlay_mask[0].any()
        assert np.array_equal(r.display_bgr[r.overlay_mask == 0], p.thermal_display_bgr[r.overlay_mask == 0])
        assert np.any(r.display_bgr != p.thermal_display_bgr)


def test_pure_ir_needs_no_registration_or_visible_camera():
    p = replace(frame_pair(), visible_bgr=np.empty((0, 0, 3), np.uint8), clock_verified=False)
    r = fuse(p, None, geometry(), mode="ir", now_unix_s=0, now_monotonic_ns=1_020_000_000)
    assert np.array_equal(r.display_bgr, p.thermal_display_bgr)


@pytest.mark.parametrize("image", [np.zeros((512, 640), np.uint16),
    np.full((512, 640, 3), 127, np.uint8), np.zeros((1, 2, 3, 4))])
def test_missing_target_fails(image):
    with pytest.raises(CalibrationError): detect_markers(image)


def test_not_enough_targets_fail():
    v, _ = image_pair()
    v[180:] = 255
    v[:, 210:] = 255
    with pytest.raises(CalibrationError): detect_markers(v)


def test_parallax_matches_independent_pinhole_projection():
    # Cameras share focal length; visible camera displaced by B along X.
    z, b, height, fov, width, x = 330, 70, 3, 104, 640, 10
    f = width*z/fov
    actual_ir = f*x/(z-height)
    visible = f*(x-b)/(z-height)
    plane_registered = visible+f*b/z
    residual = abs(actual_ir-plane_registered)
    calc = parallax_residual_px(baseline_mm=b, plane_distance_mm=z,
        component_height_mm=height, thermal_width_px=width, plane_fov_width_mm=fov)
    assert calc == pytest.approx(residual)
    assert calc > 3.9  # good planar calibration cannot remove raised-part parallax


def test_zero_height_or_baseline_has_no_parallax():
    common = dict(plane_distance_mm=330, thermal_width_px=640, plane_fov_width_mm=104)
    assert parallax_residual_px(baseline_mm=0, component_height_mm=3, **common) == 0
    assert parallax_residual_px(baseline_mm=70, component_height_mm=0, **common) == 0


def test_mirrored_wrong_correspondences_fail():
    a, b = observations()
    def mirror(p):
        p = p.copy(); p[:, 0] = 639-p[:, 0]; return p
    with pytest.raises(CalibrationError, match="mirrored"):
        calibrate(replace(a, thermal=mirror(a.thermal)), replace(b, thermal=mirror(b.thermal)),
                  geometry(), created_unix_s=0, valid_until_unix_s=1)


def test_exported_json_roundtrips_metrics(tmp_path):
    import json
    c = fit_calibration()
    p = tmp_path/"calibration.json"
    c.save(p)
    d = json.loads(p.read_text())
    assert d["schema_version"] == 1
    assert d["geometry_fingerprint"] == c.geometry.fingerprint()
    assert d["metrics"] == c.metrics
    assert d["evidence"] == "synthetic"


def test_identical_observations_renamed_are_not_independent():
    a, _ = observations()
    with pytest.raises(CalibrationError, match="duplicate the fit"):
        calibrate(a, replace(a, capture_id="renamed"), geometry(), created_unix_s=0, valid_until_unix_s=1)


def test_pure_ir_rejects_stale_thermal():
    with pytest.raises(CalibrationError, match="stale"):
        render(mode="ir", now_monotonic_ns=2_000_000_000)


def test_calibration_arrays_and_metrics_cannot_be_mutated():
    c = fit_calibration()
    for array in (c.homography, c.support_polygon, c.visible_support_polygon):
        with pytest.raises(ValueError): array.flat[0] = 123
        with pytest.raises(ValueError): array.setflags(write=True)
    with pytest.raises(TypeError): c.metrics["validation_max_px"] = 999
    r = render(calibration=c)
    with pytest.raises(ValueError): r.raw_thermal.setflags(write=True)


@pytest.mark.parametrize("value", [np.nan, np.inf, 4.5, True, "12"])
def test_invalid_count_gate_types_fail(value):
    a, b = observations()
    with pytest.raises(CalibrationError, match="count gates"):
        calibrate(a, b, geometry(), created_unix_s=0, valid_until_unix_s=1,
                  gates=GateConfig(min_points=value, min_inliers=value))


@pytest.mark.parametrize("field,value", [("sensor_size", (np.nan, 512)),
    ("sensor_size", (np.inf, 512)), ("frame_size", (640.5, 512)),
    ("crop_xywh", (0.5, 0, 639, 512)), ("frame_size", (True, 512)),
    ("frame_size", [640, 512]), ("rotation_cw", 0.0), ("mirrored", 1)])
def test_malformed_pixel_geometry_fails(field, value):
    with pytest.raises(CalibrationError):
        replace(geometry().visible, **{field: value}).validate()


@pytest.mark.parametrize("field,value", [("raw_thermal", [[1]]),
    ("thermal_display_bgr", np.zeros((2, 2), np.uint8)),
    ("raw_thermal", np.zeros((512, 640), np.float32)),
    ("visible_bgr", []), ("temperatures_c", []),
    ("temperatures_c", np.zeros((5, 5)))])
def test_malformed_frame_buffers_fail(field, value):
    with pytest.raises(CalibrationError): render(pair=replace(frame_pair(), **{field:value}))


@pytest.mark.parametrize("h", [np.zeros((3, 3)),
    np.array([[1,0,0],[0,1,0],[-.01,0,1.]]),
    np.array([[1,0,0],[0,0,0],[0,0,1.]]),
    np.array([[np.nan,0,0],[0,1,0],[0,0,1.]])])
def test_invalid_transform_rejected_at_runtime(h):
    with pytest.raises(CalibrationError): render(calibration=replace(fit_calibration(), homography=h))


def test_exact_skew_boundary_is_allowed_and_one_ns_over_fails():
    p = replace(frame_pair(), visible_timestamp_ns=970_000_000)
    render(pair=p)
    with pytest.raises(CalibrationError, match="synchronization"):
        render(pair=replace(p, visible_timestamp_ns=969_999_999))


@pytest.mark.parametrize("field,value", [("clock_verified", "false"), ("thermal_valid", 1)])
def test_frame_flags_require_actual_boolean(field, value):
    with pytest.raises(CalibrationError, match="booleans"):
        render(pair=replace(frame_pair(), **{field:value}))


def test_malformed_homography_shape_rejected():
    with pytest.raises(CalibrationError, match="3x3"):
        render(calibration=replace(fit_calibration(), homography=np.eye(2)))
