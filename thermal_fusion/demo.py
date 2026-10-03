"""python -m thermal_fusion.demo --output outputs"""
from pathlib import Path
import argparse
import json
import cv2
import numpy as np
from .core import FramePair, calibrate, fuse, parallax_residual_px, project
from .detection import match_markers
from .synthetic import geometry, image_pair, display, transform


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("outputs"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    reports = {}
    for kind in ("translation", "rotation", "perspective"):
        visible, raw = image_pair(kind, seed=5)
        v2, t2 = image_pair(kind, seed=23, invert_thermal=True)
        fit = match_markers(visible, raw, capture_id=f"synthetic-{kind}-fit", evidence="synthetic")
        check = match_markers(v2, t2, capture_id=f"synthetic-{kind}-independent-noise", evidence="synthetic")
        c = calibrate(fit, check, geometry(), created_unix_s=0, valid_until_unix_s=86400)
        gt_err = np.linalg.norm(project(check.visible, c.homography)-project(check.visible, transform(kind)), axis=1)
        reports[kind] = {**c.metrics, "ground_truth_p95_px": float(np.percentile(gt_err, 95)),
                         "scope": "synthetic images only"}
        c.save(args.output / f"calibration_{kind}_SYNTHETIC_ONLY.json")
        if kind == "perspective":
            pair = FramePair(visible, display(raw), raw, None, 1_000_000_000, 1_005_000_000, True)
            panels = []
            for mode, title in (("ir", "Pure IR"), ("alpha", "Visible alpha"), ("edges", "Visible edges")):
                result = fuse(pair, c, geometry(), mode=mode, alpha=0.65,
                              now_unix_s=1, now_monotonic_ns=1_015_000_000, live=False)
                im = result.display_bgr
                cv2.putText(im, f"SYNTHETIC ONLY - {title}", (14, 25), cv2.FONT_HERSHEY_SIMPLEX, .55, (255, 255, 255), 1)
                panels.append(im)
                cv2.imwrite(str(args.output/f"{mode}_synthetic.png"), im)
            cv2.imwrite(str(args.output/"preview_synthetic.png"), np.hstack(panels))
            cv2.imwrite(str(args.output/"visible_target_synthetic.png"), visible)
            cv2.imwrite(str(args.output/"thermal_target_synthetic_u16.png"), raw)
    reports["parallax_illustration"] = {
        "assumptions": "Parallel pinhole cameras; Z=330mm; thermal plane FOV width=104mm; width=640px. Not hardware measurements.",
        "cases": [{"baseline_mm": b, "height_mm": h,
                   "residual_px": parallax_residual_px(baseline_mm=b, plane_distance_mm=330,
                      component_height_mm=h, thermal_width_px=640, plane_fov_width_mm=104)}
                  for b in (30, 70) for h in (0, 1, 3, 5)]}
    (args.output/"verification_metrics.json").write_text(json.dumps(reports, indent=2), encoding="utf-8")
    print(json.dumps(reports, indent=2))

if __name__ == "__main__":
    main()
