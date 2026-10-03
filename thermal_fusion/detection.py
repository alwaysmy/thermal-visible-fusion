"""ArUco detection is a candidate target path, not proof a printed target works in IR."""
from dataclasses import dataclass
import cv2
import numpy as np
from .core import CalibrationError, Correspondences

DICTIONARY_ID = cv2.aruco.DICT_4X4_50


@dataclass(frozen=True)
class Detection:
    corners_by_id: dict[int, np.ndarray]
    inverted: bool
    contrast: float


def detection_image(image: np.ndarray) -> np.ndarray:
    """Normalize a copy solely for detection; raw data and temperatures stay untouched."""
    if image.ndim == 3 and image.shape[2] == 3 and image.dtype == np.uint8:
        image = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    if image.ndim != 2 or not np.isfinite(image).all():
        raise CalibrationError("invalid detection image")
    lo, hi = np.percentile(image, [1, 99])
    if hi <= lo:
        raise CalibrationError("target image has no usable contrast")
    return np.clip((image.astype(np.float64)-lo)*255/(hi-lo), 0, 255).astype(np.uint8)


def detect_markers(image, *, expected_ids: tuple[int, ...] = tuple(range(12)),
                   min_marker_edge_px: float = 30.0) -> Detection:
    gray = detection_image(image)
    params = cv2.aruco.DetectorParameters()
    params.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
    detector = cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(DICTIONARY_ID), params)
    candidates = []
    for inverted, candidate in ((False, gray), (True, 255-gray)):
        corners, ids, _ = detector.detectMarkers(candidate)
        found = {}
        if ids is not None:
            for marker_id, c in zip(ids.reshape(-1), corners):
                marker_id = int(marker_id)
                if marker_id not in expected_ids:
                    continue
                p = c.reshape(4, 2).astype(np.float64)
                if marker_id in found:
                    raise CalibrationError("duplicate marker ID: target layout is ambiguous")
                lengths = np.linalg.norm(p-np.roll(p, 1, axis=0), axis=1)
                if lengths.min() >= min_marker_edge_px:
                    found[marker_id] = p
        candidates.append(Detection(found, inverted, float(gray.std())))
    best = max(candidates, key=lambda c: len(c.corners_by_id))
    if len(best.corners_by_id) < 3:
        raise CalibrationError("need at least three readable shared target markers")
    if len(candidates[0].corners_by_id) == len(candidates[1].corners_by_id):
        raise CalibrationError("ambiguous thermal contrast polarity")
    return best


def match_markers(visible, thermal, *, capture_id: str, evidence: str) -> Correspondences:
    a, b = detect_markers(visible), detect_markers(thermal)
    shared = sorted(a.corners_by_id.keys() & b.corners_by_id.keys())
    if len(shared) < 3:
        raise CalibrationError("insufficient shared marker identities")
    # Dictionary IDs plus decoded corner order fix both identity and orientation.
    return Correspondences(np.concatenate([a.corners_by_id[i] for i in shared]),
                           np.concatenate([b.corners_by_id[i] for i in shared]),
                           capture_id, evidence)
