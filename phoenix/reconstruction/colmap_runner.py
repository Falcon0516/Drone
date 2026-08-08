#!/usr/bin/env python3
"""
COLMAP Automation Script for PHOENIX Reconstruction

Runs the Structure-from-Motion (SfM) pipeline on a directory of overlapping photos.
Outputs a sparse 3D point cloud and camera poses in human-readable text format
for downstream marker scale calibration.
"""

import os
import subprocess
import argparse
from pathlib import Path

def run_cmd(cmd: list):
    print(f"➜ Running: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)

def main():
    parser = argparse.ArgumentParser(description="Run COLMAP SfM pipeline")
    parser.add_argument("--image_dir", required=True, help="Path to directory containing input photos")
    parser.add_argument("--output_dir", required=True, help="Path to save COLMAP outputs")
    args = parser.parse_args()

    image_dir = Path(args.image_dir).resolve()
    output_dir = Path(args.output_dir).resolve()

    if not image_dir.exists():
        print(f"Error: Image directory {image_dir} not found.")
        return

    # Setup directories
    db_path = output_dir / "database.db"
    sparse_dir = output_dir / "sparse"
    text_dir = output_dir / "text_model"

    output_dir.mkdir(parents=True, exist_ok=True)
    sparse_dir.mkdir(exist_ok=True)
    text_dir.mkdir(exist_ok=True)

    if db_path.exists():
        db_path.unlink() # Start fresh

    try:
        # 1. Feature Extraction
        print("\n--- 1. FEATURE EXTRACTION ---")
        run_cmd([
            "colmap", "feature_extractor",
            "--database_path", str(db_path),
            "--image_path", str(image_dir),
            "--ImageReader.camera_model", "PINHOLE"
        ])

        # 2. Feature Matching
        print("\n--- 2. EXHAUSTIVE MATCHING ---")
        run_cmd([
            "colmap", "exhaustive_matcher",
            "--database_path", str(db_path)
        ])

        # 3. Sparse Mapping (SfM)
        print("\n--- 3. SPARSE MAPPING ---")
        run_cmd([
            "colmap", "mapper",
            "--database_path", str(db_path),
            "--image_path", str(image_dir),
            "--output_path", str(sparse_dir)
        ])

        # Find the actual output directory (mapper puts it in sparse/0/)
        model_0_dir = sparse_dir / "0"
        if not model_0_dir.exists():
            print("Error: COLMAP mapper failed to produce a model (sparse/0/ not found). Check image overlap/quality.")
            return

        # 4. Convert Binary Model to Text
        # The text format is significantly easier to parse in pure Python without complex pycolmap dependencies
        print("\n--- 4. CONVERTING TO TEXT FORMAT ---")
        run_cmd([
            "colmap", "model_converter",
            "--input_path", str(model_0_dir),
            "--output_path", str(text_dir),
            "--output_type", "TXT"
        ])

        print(f"\n✅ Pipeline Complete! Model exported to: {text_dir}")
        print("Next step: Run scale_calibrator.py to apply ArUco marker scale.")

    except subprocess.CalledProcessError as e:
        print(f"\n❌ COLMAP process failed: {e}")
        print("Please ensure COLMAP is installed and in your system PATH (e.g. `brew install colmap`).")

if __name__ == "__main__":
    main()
