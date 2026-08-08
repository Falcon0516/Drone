#!/usr/bin/env python3
"""
Scale Calibrator for PHOENIX Reconstruction

Reads COLMAP text outputs, detects an ArUco marker in the source photos,
matches the 2D marker corners to 3D points in the sparse cloud, and
calculates the strict scale factor to convert COLMAP units to real-world metric units.
"""

import os
import math
import json
import argparse
from pathlib import Path
import numpy as np
import cv2

def read_points3D(path):
    """Parse COLMAP points3D.txt. Returns dict: point3D_id -> {'xyz': [x,y,z], 'error': e}"""
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

def read_images(path):
    """Parse COLMAP images.txt. Returns dict of images and their 2D-3D feature mappings."""
    images = {}
    with open(path, "r") as f:
        lines = f.readlines()
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            if line.startswith("#") or not line:
                i += 1
                continue
            # Image info line: IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME
            parts = line.split()
            img_id = int(parts[0])
            name = parts[9]
            
            # Next line contains points: X, Y, POINT3D_ID, ...
            i += 1
            point_line = lines[i].strip().split()
            features2d = []
            for p in range(0, len(point_line), 3):
                x = float(point_line[p])
                y = float(point_line[p+1])
                p3d_id = int(point_line[p+2])
                features2d.append((x, y, p3d_id))
                
            images[img_id] = {"name": name, "features": features2d}
            i += 1
    return images

def find_nearest_3d_point(corner_x, corner_y, features, points3d_dict, max_dist_px=5.0):
    """Find the 3D point associated with the closest 2D feature to a marker corner."""
    best_dist = float('inf')
    best_p3d = None
    
    for (fx, fy, p3d_id) in features:
        if p3d_id == -1: continue # No 3D point for this feature
        
        dist = math.hypot(fx - corner_x, fy - corner_y)
        if dist < best_dist and dist < max_dist_px:
            best_dist = dist
            best_p3d = points3d_dict.get(p3d_id)
            
    return best_p3d

def main():
    parser = argparse.ArgumentParser(description="Calibrate COLMAP scale using ArUco marker")
    parser.add_argument("--image_dir", required=True, help="Original photos folder")
    parser.add_argument("--text_model_dir", required=True, help="COLMAP text outputs folder")
    parser.add_argument("--marker_size_mm", type=float, default=150.0, help="Physical size of ArUco marker in mm")
    args = parser.parse_args()

    model_dir = Path(args.text_model_dir)
    img_dir = Path(args.image_dir)
    
    if not (model_dir / "points3D.txt").exists():
        print("Error: points3D.txt not found. Did you run colmap_runner.py first?")
        return

    print("Loading COLMAP geometry...")
    points3d = read_points3D(model_dir / "points3D.txt")
    images = read_images(model_dir / "images.txt")
    print(f"Loaded {len(points3d)} 3D points and {len(images)} registered images.")

    # Setup ArUco detector (using standard 4x4 dictionary, commonly used for calibration)
    # Handle API differences in OpenCV versions
    try:
        aruco_dict = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
        aruco_params = cv2.aruco.DetectorParameters()
        detector = cv2.aruco.ArucoDetector(aruco_dict, aruco_params)
        detect_func = lambda img: detector.detectMarkers(img)
    except AttributeError:
        # Fallback for older OpenCV versions
        aruco_dict = cv2.aruco.Dictionary_get(cv2.aruco.DICT_4X4_50)
        aruco_params = cv2.aruco.DetectorParameters_create()
        detect_func = lambda img: cv2.aruco.detectMarkers(img, aruco_dict, parameters=aruco_params)

    scale_factors = []
    base_errors = []

    print("\nScanning images for ArUco markers...")
    for img_id, img_data in images.items():
        img_path = img_dir / img_data["name"]
        if not img_path.exists(): continue
        
        cv_img = cv2.imread(str(img_path))
        if cv_img is None: continue
        
        gray = cv2.cvtColor(cv_img, cv2.COLOR_BGR2GRAY)
        corners, ids, rejected = detect_func(gray)
        
        if ids is not None and len(ids) > 0:
            print(f"  Found marker in {img_data['name']}")
            # corners[0] is array of 4 corners: top-left, top-right, bottom-right, bottom-left
            c2d = corners[0][0] 
            
            p3ds = []
            for corner in c2d:
                p3d = find_nearest_3d_point(corner[0], corner[1], img_data["features"], points3d)
                if p3d:
                    p3ds.append(p3d)
            
            # Need at least 2 corners with valid 3D points to compute a distance
            if len(p3ds) >= 2:
                # Compute distance between first two valid corners
                d3d = np.linalg.norm(p3ds[0]["xyz"] - p3ds[1]["xyz"])
                if d3d > 0:
                    # Scale factor = Real World / COLMAP World
                    # If marker size is 150mm, and distance in COLMAP is 2.5
                    # Scale factor = 150 / 2.5 = 60
                    # This means 1 COLMAP unit = 60 mm
                    factor_cm = (args.marker_size_mm / 10.0) / d3d 
                    scale_factors.append(factor_cm)
                    
                    avg_err = (p3ds[0]["error"] + p3ds[1]["error"]) / 2.0
                    base_errors.append(avg_err)
                    
            print(f"    Mapped {len(p3ds)}/4 corners to 3D space.")

    if not scale_factors:
        print("\n❌ Failed to compute scale. The ArUco marker was either not found in the photos, or its corners failed to reconstruct in COLMAP.")
        return

    # Average the computed scale factors
    final_scale = float(np.median(scale_factors))
    final_error = float(np.median(base_errors))
    
    print("\n--- CALIBRATION RESULTS ---")
    print(f"Median Scale Factor: {final_scale:.4f} (Multiply COLMAP distances by this to get Centimetres)")
    print(f"Base Reprojection Error: {final_error:.4f} pixels")
    
    # Save to JSON for the measurement tool
    out_file = model_dir.parent / "calibration.json"
    calib_data = {
        "scale_factor_cm": final_scale,
        "base_reprojection_error_px": final_error,
        "marker_size_mm": args.marker_size_mm,
        "text_model_dir": str(model_dir.absolute())
    }
    with open(out_file, "w") as f:
        json.dump(calib_data, f, indent=4)
        
    print(f"\n✅ Calibration saved to {out_file}")
    print("Next step: Use measure_tool.py to compute real-world distances.")

if __name__ == "__main__":
    main()
