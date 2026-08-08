#!/usr/bin/env python3
"""
Splat Visualization Runner (VISUAL PURPOSES ONLY)

NOTE: As per the PHOENIX roadmap, the actual *measurements* come from the 
COLMAP sparse cloud (via colmap_runner.py and measure_tool.py). 
This script is strictly for generating a "wow" visual presentation (Gaussian Splatting)
and should never be the source of any certified measurement claims.

To run Gaussian Splatting, you need a machine with a powerful NVIDIA GPU and CUDA installed.
Because of these heavy hardware requirements, this script serves as a wrapper/guide 
for running Nerfstudio rather than automating the entire CUDA build process locally.
"""

import sys
import subprocess
from pathlib import Path

def main():
    print("="*60)
    print(" PHOENIX Splat Visualization Runner")
    print("="*60)
    print("\n⚠️  IMPORTANT: The resulting splat is for VISUAL PURPOSES ONLY.")
    print("Do not use the splat render to extract certified measurements.")
    print("Measurements MUST be derived from the COLMAP point cloud.")
    print("="*60)

    if len(sys.argv) < 2:
        print("\nUsage: python3 splat_runner.py <path_to_colmap_output_dir>")
        print("\nPrerequisites:")
        print("1. A machine with an NVIDIA GPU and CUDA toolkit.")
        print("2. Nerfstudio installed: pip install nerfstudio")
        return

    colmap_dir = Path(sys.argv[1]).resolve()
    
    if not (colmap_dir / "sparse").exists():
        print(f"\nError: Could not find COLMAP sparse model in {colmap_dir}")
        print("Please run colmap_runner.py first.")
        return

    print("\nTo generate the splat, run the following command in a CUDA-enabled terminal:")
    
    # nerfstudio accepts a colmap directory directly if formatted correctly
    print(f"\n   ns-train splatfacto --data {colmap_dir}")
    
    print("\nOnce training finishes, Nerfstudio will provide a Viewer URL (e.g. localhost:7007)")
    print("Open that URL in your browser to explore the 3D scene.")

if __name__ == "__main__":
    main()
