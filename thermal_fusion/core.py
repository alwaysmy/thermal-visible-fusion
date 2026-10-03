"""SDK-independent, fail-closed fixed-plane registration and display fusion.

All reprojection errors are in native thermal pixels. An accepted fit is not a
proof of real target detectability or of alignment away from the calibrated plane.
"""
from __future__ import annotations
from dataclasses import asdict, dataclass
from hashlib import sha256
import json
from typing import Literal
from types import MappingProxyType
import cv2
import numpy as np


class CalibrationError(ValueError):
    """Expected rejection; caller must show pure IR and explain the reason."""


def _integer(value):
    return isinstance(value, (int, np.integer)) and not isinstance(value, (bool, np.bool_))


def _finite_numbers(values):
    return all(isinstance(v, (int, float, np.integer, np.floating))
               and not isinstance(v, (bool, np.bool_)) and np.isfinite(v) for v in values)


def _immutable_array(array):
    array = np.ascontiguousarray(array)
    # Backed by immutable bytes, so callers cannot re-enable the write flag.
    return np.frombuffer(array.tobytes(), dtype=array.dtype).reshape(array.shape)


@dataclass(frozen=True)
class CameraGeometry:
    camera_id: str
    sensor_size: tuple[int, int]  # width, height
    crop_xywh: tuple[int, int, int, int]
    frame_size: tuple[int, int]
    rotation_cw: int = 0
    mirrored: bool = False
    zoom_ratio: float = 1.0
    focus_token: str = "locked"
    distortion_profile: str = "none-unvalidated"
    stabilization_off: bool = True

    def validate(self):
        for values, length in ((self.sensor_size, 2), (self.crop_xywh, 4), (self.frame_size, 2)):
            if not isinstance(values, tuple) or len(values) != length or not all(_integer(v) for v in values):
                raise CalibrationError("pixel dimensions and crop must be integer tuples")
        if not _integer(self.rotation_cw) or any(type(v) is not bool for v in (self.mirrored, self.stabilization_off)):
            raise CalibrationError("invalid orientation or boolean flags")
        w, h = self.sensor_size
        x, y, cw, ch = self.crop_xywh
        fw, fh = self.frame_size
        if min(w, h, cw, ch, fw, fh) <= 0 or min(x, y) < 0 or x+cw > w or y+ch > h:
            raise CalibrationError("invalid camera geometry")
        if self.rotation_cw not in (0, 90, 180, 270):
            raise CalibrationError("unsupported orientation")
        if not _finite_numbers([self.zoom_ratio]) or self.zoom_ratio <= 0:
            raise CalibrationError("invalid zoom ratio")
        if not all(isinstance(s, str) and s for s in (self.camera_id, self.focus_token, self.distortion_profile)):
            raise CalibrationError("missing camera/focus/distortion identity")
        if not self.stabilization_off:
            raise CalibrationError("stabilization must be disabled or its full transform supplied")


@dataclass(frozen=True)
class RigGeometry:
    thermal: CameraGeometry
    visible: CameraGeometry
    rig_revision: str  # change after any physical repositioning
    lens_id: str
    spacer_mm: float
    plane_distance_mm: float  # measured or fixture-enforced; not inferred from the image

    def validate(self):
        if not isinstance(self.thermal, CameraGeometry) or not isinstance(self.visible, CameraGeometry):
            raise CalibrationError("invalid camera geometry objects")
        self.thermal.validate()
        self.visible.validate()
        if not all(isinstance(s, str) and s for s in (self.rig_revision, self.lens_id)):
            raise CalibrationError("missing rig/lens identity")
        if not _finite_numbers([self.spacer_mm, self.plane_distance_mm]):
            raise CalibrationError("nonfinite spacing or distance")
        if self.spacer_mm < 0 or self.plane_distance_mm <= 0:
            raise CalibrationError("invalid spacing or distance")

    def fingerprint(self) -> str:
        self.validate()
        return sha256(json.dumps(asdict(self), sort_keys=True, allow_nan=False).encode()).hexdigest()


@dataclass(frozen=True)
class GateConfig:
    min_points: int = 12
    min_inliers: int = 12
    min_inlier_ratio: float = 0.75
    min_coverage: float = 0.20
    ransac_px: float = 2.0
    max_validation_p95_px: float = 1.5
    max_validation_error_px: float = 2.5
    max_pair_skew_ms: float = 40.0
    max_frame_age_ms: float = 150.0

    def validate(self):
        if not all(_integer(n) for n in (self.min_points, self.min_inliers)):
            raise CalibrationError("count gates must be non-boolean integers")
        if self.min_points < 8 or self.min_inliers < 8:
            raise CalibrationError("at least eight correspondences required")
        if not _finite_numbers([self.min_inlier_ratio, self.min_coverage]) or not 0 < self.min_inlier_ratio <= 1 or not 0 < self.min_coverage <= 1:
            raise CalibrationError("invalid gate ratio")
        if not _finite_numbers([self.ransac_px, self.max_validation_p95_px,
                           self.max_validation_error_px, self.max_pair_skew_ms,
                           self.max_frame_age_ms]):
            raise CalibrationError("nonfinite gate")
        if min(self.ransac_px, self.max_validation_p95_px,
               self.max_validation_error_px, self.max_pair_skew_ms,
               self.max_frame_age_ms) <= 0:
            raise CalibrationError("gate thresholds must be positive")


@dataclass(frozen=True)
class Correspondences:
    visible: np.ndarray
    thermal: np.ndarray
    capture_id: str
    evidence: Literal["synthetic", "real"]


@dataclass(frozen=True)
class Calibration:
    geometry: RigGeometry
    homography: np.ndarray  # visible -> native thermal
    support_polygon: np.ndarray  # no extrapolation beyond inlier support
    visible_support_polygon: np.ndarray
    created_unix_s: float
    valid_until_unix_s: float
    evidence: str
    fit_capture_id: str
    validation_capture_id: str
    metrics: dict
    gates: GateConfig

    def __post_init__(self):
        for field in ("homography", "support_polygon", "visible_support_polygon"):
            object.__setattr__(self, field, _immutable_array(getattr(self, field)))
        object.__setattr__(self, "metrics", MappingProxyType(dict(self.metrics)))

    def to_dict(self):
        return {"schema_version": 1, "geometry": asdict(self.geometry),
                "geometry_fingerprint": self.geometry.fingerprint(),
                "homography": self.homography.tolist(),
                "support_polygon": self.support_polygon.tolist(),
                "visible_support_polygon": self.visible_support_polygon.tolist(),
                "created_unix_s": self.created_unix_s,
                "valid_until_unix_s": self.valid_until_unix_s,
                "evidence": self.evidence, "fit_capture_id": self.fit_capture_id,
                "validation_capture_id": self.validation_capture_id,
                "metrics": dict(self.metrics), "gates": asdict(self.gates),
                "scope": "fixed-plane-only; experimental; no temperature inference"}

    def save(self, path):
        with open(path, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, indent=2, ensure_ascii=False, allow_nan=False)

    def assert_compatible(self, geometry: RigGeometry, now_unix_s: float, *, live: bool):
        if geometry.fingerprint() != self.geometry.fingerprint():
            raise CalibrationError("geometry changed: recalibrate")
        if not _finite_numbers([now_unix_s]) or not self.created_unix_s <= now_unix_s <= self.valid_until_unix_s:
            raise CalibrationError("calibration expired or clock invalid: revalidate")
        if live and self.evidence != "real":
            raise CalibrationError("synthetic calibration cannot be used on live hardware")


def project(points: np.ndarray, h: np.ndarray) -> np.ndarray:
    points = np.asarray(points, np.float64)
    q = np.column_stack([points, np.ones(len(points))]) @ h.T
    if not np.isfinite(q).all() or np.any(np.abs(q[:, 2]) < 1e-9):
        raise CalibrationError("homography has nonfinite projection or horizon")
    return q[:, :2] / q[:, 2, None]


def _points(points, size, minimum):
    p = np.asarray(points, dtype=np.float64)
    if p.ndim != 2 or p.shape[1] != 2 or len(p) < minimum or not np.isfinite(p).all():
        raise CalibrationError("insufficient or nonfinite correspondences")
    w, h = size
    if (p < 0).any() or (p[:, 0] >= w).any() or (p[:, 1] >= h).any():
        raise CalibrationError("correspondences outside image bounds")
    if len(np.unique(np.round(p, 5), axis=0)) != len(p):
        raise CalibrationError("duplicate correspondences")
    return p


def _coverage(points, size):
    hull = cv2.convexHull(np.float32(points)).reshape(-1, 2)
    return float(cv2.contourArea(hull) / (size[0] * size[1])), hull


def _check_homography(h, size):
    if h is None or not isinstance(h, np.ndarray) or h.shape != (3, 3) or not np.issubdtype(h.dtype, np.number):
        raise CalibrationError("homography must be a numeric 3x3 matrix")
    if not np.isfinite(h).all() or abs(h[2, 2]) < 1e-10:
        raise CalibrationError("homography estimation failed")
    h = h / h[2, 2]
    w, ht = size
    corners = np.array([[0, 0], [w-1, 0], [w-1, ht-1], [0, ht-1]], np.float64)
    den = np.column_stack([corners, np.ones(4)]) @ h[2]
    if not ((den > 1e-6).all() or (den < -1e-6).all()):
        raise CalibrationError("homography crosses an image horizon")
    dst = project(corners, h).astype(np.float32)
    if not cv2.isContourConvex(dst) or cv2.contourArea(dst, oriented=True) <= 1e-3:
        raise CalibrationError("folded, mirrored or singular transform")
    scale = np.diag([1/w, 1/ht, 1.0])
    hn = scale @ h @ np.linalg.inv(scale)
    if np.linalg.cond(hn) > 1e5:
        raise CalibrationError("ill-conditioned transform")
    return h


def calibrate(fit: Correspondences, validation: Correspondences,
              geometry: RigGeometry, *, created_unix_s: float, valid_until_unix_s: float,
              gates: GateConfig = GateConfig()) -> Calibration:
    geometry.validate()
    gates.validate()
    if not all(isinstance(s, str) and s for s in (fit.capture_id, validation.capture_id)) or fit.capture_id == validation.capture_id:
        raise CalibrationError("a separate validation capture is required")
    if fit.evidence not in ("real", "synthetic") or fit.evidence != validation.evidence:
        raise CalibrationError("invalid/mixed evidence provenance")
    if not _finite_numbers([created_unix_s, valid_until_unix_s]) or valid_until_unix_s <= created_unix_s:
        raise CalibrationError("invalid calibration validity interval")
    src = _points(fit.visible, geometry.visible.frame_size, gates.min_points)
    dst = _points(fit.thermal, geometry.thermal.frame_size, gates.min_points)
    vsrc = _points(validation.visible, geometry.visible.frame_size, gates.min_points)
    vdst = _points(validation.thermal, geometry.thermal.frame_size, gates.min_points)
    if len(src) != len(dst) or len(vsrc) != len(vdst):
        raise CalibrationError("unpaired observations")
    if np.array_equal(src, vsrc) and np.array_equal(dst, vdst):
        raise CalibrationError("validation observations duplicate the fit capture")
    for points, size in ((src, geometry.visible.frame_size), (dst, geometry.thermal.frame_size),
                         (vsrc, geometry.visible.frame_size), (vdst, geometry.thermal.frame_size)):
        if _coverage(points, size)[0] < gates.min_coverage:
            raise CalibrationError("points are collinear, clustered or cover too little of frame")
    h, inliers = cv2.findHomography(src, dst, cv2.RANSAC, gates.ransac_px,
                                   maxIters=5000, confidence=0.999)
    h = _check_homography(h, geometry.visible.frame_size)
    keep = inliers.reshape(-1).astype(bool)
    if keep.sum() < gates.min_inliers or keep.mean() < gates.min_inlier_ratio:
        raise CalibrationError("too few inliers or too many outliers")
    coverage, hull = _coverage(dst[keep], geometry.thermal.frame_size)
    visible_coverage, visible_hull = _coverage(src[keep], geometry.visible.frame_size)
    if min(coverage, visible_coverage) < gates.min_coverage:
        raise CalibrationError("inlier coverage is insufficient")
    fit_err = np.linalg.norm(project(src[keep], h) - dst[keep], axis=1)
    val_err = np.linalg.norm(project(vsrc, h) - vdst, axis=1)
    # Never RANSAC away validation failures: every independent check point counts.
    p95, maximum = float(np.percentile(val_err, 95)), float(val_err.max())
    if p95 > gates.max_validation_p95_px or maximum > gates.max_validation_error_px:
        raise CalibrationError(f"independent validation failed: p95={p95:.3f}px max={maximum:.3f}px")
    metrics = {"fit_points": len(src), "fit_inliers": int(keep.sum()),
               "fit_inlier_ratio": float(keep.mean()), "thermal_coverage": coverage,
               "visible_coverage": visible_coverage, "fit_rmse_px": float(np.sqrt(np.mean(fit_err**2))),
               "validation_points": len(vsrc), "validation_rmse_px": float(np.sqrt(np.mean(val_err**2))),
               "validation_p95_px": p95, "validation_max_px": maximum}
    return Calibration(geometry, h.copy(), hull.copy(), visible_hull.copy(), created_unix_s,
                       valid_until_unix_s, fit.evidence, fit.capture_id,
                       validation.capture_id, metrics, gates)


@dataclass(frozen=True)
class FramePair:
    visible_bgr: np.ndarray
    thermal_display_bgr: np.ndarray
    raw_thermal: np.ndarray
    temperatures_c: np.ndarray | None
    visible_timestamp_ns: int
    thermal_timestamp_ns: int
    clock_verified: bool  # host receipt times alone do not prove capture synchronization
    thermal_valid: bool = True  # false for NUC/shutter/USB loss/corrupt frame


@dataclass(frozen=True)
class FusionResult:
    display_bgr: np.ndarray
    overlay_mask: np.ndarray
    raw_thermal: np.ndarray  # unchanged, read-only snapshot
    temperatures_c: np.ndarray | None  # unchanged, read-only snapshot
    mode: str


def _snapshot(array):
    if array is None:
        return None
    return _immutable_array(array)


def fuse(pair: FramePair, calibration: Calibration | None, geometry: RigGeometry, *,
         mode: Literal["ir", "alpha", "edges"] = "edges", alpha: float = 0.35,
         now_unix_s: float, now_monotonic_ns: int, live: bool = True) -> FusionResult:
    geometry.validate()
    if type(live) is not bool or type(pair.thermal_valid) is not bool or type(pair.clock_verified) is not bool:
        raise CalibrationError("frame and live flags must be booleans")
    tw, th = geometry.thermal.frame_size
    vw, vh = geometry.visible.frame_size
    if not all(isinstance(a, np.ndarray) for a in (pair.thermal_display_bgr, pair.raw_thermal)):
        raise CalibrationError("thermal buffers must be ndarrays")
    if pair.temperatures_c is not None and not isinstance(pair.temperatures_c, np.ndarray):
        raise CalibrationError("temperature grid must be an ndarray")
    if pair.thermal_display_bgr.shape != (th, tw, 3) or pair.thermal_display_bgr.dtype != np.uint8:
        raise CalibrationError("invalid thermal display shape or dtype")
    if pair.raw_thermal.shape != (th, tw) or pair.raw_thermal.dtype != np.uint16:
        raise CalibrationError("raw thermal must be a native-grid uint16 frame")
    if pair.temperatures_c is not None and pair.temperatures_c.shape != (th, tw):
        raise CalibrationError("temperature grid mismatch")
    if not pair.thermal_valid:
        raise CalibrationError("thermal frame unavailable/NUC: no valid display")
    if any(not _integer(t) for t in
           (pair.thermal_timestamp_ns, now_monotonic_ns)):
        raise CalibrationError("thermal timestamps must be monotonic nanosecond integers")
    thermal_age_ms = (now_monotonic_ns-pair.thermal_timestamp_ns)/1e6
    max_age_ms = calibration.gates.max_frame_age_ms if calibration else GateConfig().max_frame_age_ms
    if thermal_age_ms < 0 or thermal_age_ms > max_age_ms:
        raise CalibrationError("thermal frame is stale or from a future clock")
    out = pair.thermal_display_bgr.copy()
    mask = np.zeros((th, tw), np.uint8)
    if mode == "ir":
        return FusionResult(out, mask, _snapshot(pair.raw_thermal), _snapshot(pair.temperatures_c), mode)
    if mode not in ("alpha", "edges") or not _finite_numbers([alpha]) or not 0 <= alpha <= 1:
        raise CalibrationError("invalid display mode or alpha")
    if calibration is None:
        raise CalibrationError("calibration is required for fusion")
    calibration.assert_compatible(geometry, now_unix_s, live=live)
    if not isinstance(pair.visible_bgr, np.ndarray) or pair.visible_bgr.shape != (vh, vw, 3) or pair.visible_bgr.dtype != np.uint8:
        raise CalibrationError("invalid visible frame shape or dtype")
    if not pair.clock_verified:
        raise CalibrationError("frame clock relationship is not verified")
    if any(not _integer(t) for t in
           (pair.thermal_timestamp_ns, pair.visible_timestamp_ns, now_monotonic_ns)):
        raise CalibrationError("timestamps must be monotonic nanosecond integers")
    if abs(pair.visible_timestamp_ns - pair.thermal_timestamp_ns) / 1e6 > calibration.gates.max_pair_skew_ms:
        raise CalibrationError("frame pair exceeds synchronization budget")
    ages = [(now_monotonic_ns - t)/1e6 for t in (pair.visible_timestamp_ns, pair.thermal_timestamp_ns)]
    if min(ages) < 0 or max(ages) > calibration.gates.max_frame_age_ms:
        raise CalibrationError("frame is stale or from a future clock")
    h = _check_homography(calibration.homography, (vw, vh))
    warped = cv2.warpPerspective(pair.visible_bgr, h, (tw, th), flags=cv2.INTER_LINEAR)
    support = np.zeros((vh, vw), np.float32)
    cv2.fillConvexPoly(support, np.round(calibration.visible_support_polygon).astype(np.int32), 1.0)
    mapped_support = cv2.warpPerspective(support, h, (tw, th), flags=cv2.INTER_LINEAR)
    cv2.fillConvexPoly(mask, np.round(calibration.support_polygon).astype(np.int32), 255)
    valid = (mask != 0) & (mapped_support > 0.999)
    mask = (valid.astype(np.uint8) * 255)
    if mode == "alpha":
        mixed = cv2.addWeighted(out, 1-alpha, warped, alpha, 0)
        out[valid] = mixed[valid]
    else:
        edges = cv2.Canny(cv2.cvtColor(pair.visible_bgr, cv2.COLOR_BGR2GRAY), 60, 150)
        mapped_edges = cv2.warpPerspective(edges, h, (tw, th), flags=cv2.INTER_NEAREST) != 0
        draw = valid & mapped_edges
        # Cyan contour is display-only; it is never fed back into radiometry.
        mixed = np.clip((1-alpha)*out.astype(float)+alpha*np.array([255, 255, 0]), 0, 255).astype(np.uint8)
        out[draw] = mixed[draw]
    return FusionResult(out, mask, _snapshot(pair.raw_thermal), _snapshot(pair.temperatures_c), mode)


def parallax_residual_px(*, baseline_mm: float, plane_distance_mm: float,
                         component_height_mm: float, thermal_width_px: int,
                         plane_fov_width_mm: float) -> float:
    """Parallel-pinhole illustration, not a measured error estimate for this rig.

    Registration is exact on the base plane; positive height is toward cameras.
    Residual = pixels_per_plane_mm * baseline * height / (plane_distance-height).
    """
    values = [baseline_mm, plane_distance_mm, component_height_mm, plane_fov_width_mm]
    if not np.isfinite(values).all() or baseline_mm < 0 or thermal_width_px <= 0 or plane_fov_width_mm <= 0:
        raise ValueError("invalid parallax inputs")
    if not 0 <= component_height_mm < plane_distance_mm:
        raise ValueError("height must lie between zero and the camera distance")
    return thermal_width_px/plane_fov_width_mm*baseline_mm*component_height_mm/(plane_distance_mm-component_height_mm)
