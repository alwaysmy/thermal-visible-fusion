"""Synthetic fixtures: image tests exercise code, not real thermal target feasibility."""
import cv2
import numpy as np
from .core import CameraGeometry, RigGeometry
from .detection import DICTIONARY_ID

WIDTH, HEIGHT = 640, 512


def geometry():
    c = CameraGeometry("SYNTHETIC_ONLY", (WIDTH, HEIGHT), (0, 0, WIDTH, HEIGHT), (WIDTH, HEIGHT))
    return RigGeometry(c, c, "synthetic-rig-1", "synthetic-lens", 2.0, 330.0)


def marker_target():
    image = np.full((HEIGHT, WIDTH), 230, np.uint8)
    dictionary = cv2.aruco.getPredefinedDictionary(DICTIONARY_ID)
    for row in range(3):
        for col in range(4):
            marker = cv2.aruco.generateImageMarker(dictionary, row*4+col, 66)
            x, y = 65+145*col, 55+155*row
            image[y:y+66, x:x+66] = marker
    return image


def transform(kind="perspective"):
    if kind == "translation":
        return np.array([[1, 0, 9], [0, 1, 6], [0, 0, 1]], np.float64)
    if kind == "rotation":
        a = cv2.getRotationMatrix2D((WIDTH/2, HEIGHT/2), 5, 0.96)
        return np.vstack([a, [0, 0, 1]])
    if kind == "perspective":
        return np.array([[0.96, -0.018, 14], [0.012, 0.965, 9], [0.00005, -0.000035, 1]], np.float64)
    if kind == "identity":
        return np.eye(3)
    raise ValueError(kind)


def image_pair(kind="perspective", *, seed=1, invert_thermal=False, noise_sd=1.5):
    rng = np.random.default_rng(seed)
    target = marker_target()
    visible = np.clip(target.astype(float)+rng.normal(0, noise_sd, target.shape), 0, 255).astype(np.uint8)
    thermal = cv2.warpPerspective(target, transform(kind), (WIDTH, HEIGHT), borderValue=230)
    thermal = cv2.GaussianBlur(thermal, (3, 3), 0.6)
    thermal = np.clip(thermal.astype(float)+rng.normal(0, noise_sd, target.shape), 0, 255)
    if invert_thermal:
        thermal = 255-thermal
    raw = np.round(12000+thermal*25).astype(np.uint16)  # arbitrary counts, NOT degrees
    return cv2.cvtColor(visible, cv2.COLOR_GRAY2BGR), raw


def display(raw):
    normalized = cv2.normalize(raw, None, 0, 255, cv2.NORM_MINMAX).astype(np.uint8)
    return cv2.applyColorMap(normalized, cv2.COLORMAP_INFERNO)
