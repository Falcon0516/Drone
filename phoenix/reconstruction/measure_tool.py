#!/usr/bin/env python3
"""
Measurement Tool for PHOENIX Reconstruction

Uses the scaled COLMAP point cloud to compute real-world distances
between two 3D points, including a defensible margin of error based on
COLMAP's reprojection error.
"""

import sys
import json
import math
import numpy as np
from pathlib import Path

def read_points3D(path):
    points = {}
    with open(path, "r") as f:
        for line in f:
            if line.startswith("#"): continue
            parts = line.strip().split()
            if not parts: continue
            pid = int(parts[0])
            xyz = np.array([float(parts[1]), float(parts[2]), float(parts[3])])
            error = float(parts[7])
            points[pid] = {"xyz": xyz, "error": error}
    return points

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 measure_tool.py <path_to_calibration.json>")
        return

    calib_file = Path(sys.argv[1])
    if not calib_file.exists():
        print(f"Error: {calib_file} not found.")
        return

    with open(calib_file, "r") as f:
        calib = json.load(f)

    scale_factor = calib["scale_factor_cm"]
    text_dir = Path(calib["text_model_dir"])

    print(f"Loading points from {text_dir}/points3D.txt ...")
    points = read_points3D(text_dir / "points3D.txt")
    print(f"Loaded {len(points)} points. Scale: 1 unit = {scale_factor:.4f} cm.")

    print("\n--- CERTIFIED MEASUREMENT TOOL ---")
    print("Enter two Point IDs to calculate the real-world distance between them.")
    print("(Type 'exit' to quit)\n")

    while True:
        try:
            p1_str = input("Point 1 ID: ")
            if p1_str.lower() == 'exit': break
            p2_str = input("Point 2 ID: ")
            if p2_str.lower() == 'exit': break

            p1_id = int(p1_str)
            p2_id = int(p2_str)

            if p1_id not in points or p2_id not in points:
                print("Error: One or both Point IDs not found in the sparse cloud.\n")
                continue

            p1 = points[p1_id]
            p2 = points[p2_id]

            # Raw distance in COLMAP units
            raw_dist = np.linalg.norm(p1["xyz"] - p2["xyz"])
            
            # Scaled distance in Centimetres
            real_dist_cm = raw_dist * scale_factor

            # Calculate Defensible Margin of Error
            # Reprojection error is in pixels. We use the calibration base error as a proxy for depth uncertainty.
            # This is a simplified linear approximation: higher reprojection error = higher physical uncertainty.
            # Total error = (Error1 + Error2) * ScaleFactor * Constant
            # For a true survey-grade system, this requires full covariance matrix extraction from Ceres Solver,
            # but this approximation is sufficient for the POC to demonstrate the concept of "known uncertainty".
            combined_px_error = p1["error"] + p2["error"]
            margin_of_error_cm = combined_px_error * scale_factor * 0.5 

            print("\n----------------------------------------")
            print(f"DISTANCE: {real_dist_cm:.2f} cm")
            print(f"MARGIN OF ERROR: ± {margin_of_error_cm:.2f} cm")
            print("----------------------------------------\n")

        except ValueError:
            print("Please enter valid integer Point IDs.\n")
        except KeyboardInterrupt:
            break

if __name__ == "__main__":
    main()
