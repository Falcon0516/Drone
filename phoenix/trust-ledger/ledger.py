#!/usr/bin/env python3
"""
Append-Only Hash-Chained Local Ledger for PHOENIX

This acts as the fallback POC for AXIOM's blockchain anchoring.
It maintains a JSON file where each new entry includes the hash of the
previous entry, creating an unbreakable chain.
"""

import os
import json
import time
from pathlib import Path
from hasher import generate_payload_hash

LEDGER_PATH = Path(__file__).parent / "ledger.json"

def load_ledger() -> list:
    if not LEDGER_PATH.exists():
        return []
    with open(LEDGER_PATH, "r") as f:
        return json.load(f)

def save_ledger(chain: list):
    with open(LEDGER_PATH, "w") as f:
        json.dump(chain, f, indent=4)

def anchor_measurement(measurement_cm: float, margin_cm: float, lat: float, lon: float, colmap_hash: str):
    """
    Create a new payload, hash it (including the previous block's hash),
    and append it to the ledger.
    """
    chain = load_ledger()
    
    # Get previous block's hash, or use Genesis hash if empty
    prev_hash = "GENESIS_00000000000000000000000000000000000000000000000000000000"
    if chain:
        prev_hash = chain[-1]["block_hash"]
        
    payload = {
        "measurement_cm": measurement_cm,
        "margin_of_error_cm": margin_cm,
        "gps_lat": lat,
        "gps_lon": lon,
        "colmap_points_hash": colmap_hash,
        "timestamp": int(time.time()),
        "previous_hash": prev_hash
    }
    
    block_hash = generate_payload_hash(payload)
    
    block = {
        "block_index": len(chain),
        "payload": payload,
        "block_hash": block_hash
    }
    
    chain.append(block)
    save_ledger(chain)
    
    print(f"✅ Anchored Block #{block['block_index']} to Ledger.")
    print(f"Hash: {block_hash}")
    return block_hash

if __name__ == "__main__":
    print("Anchoring a test measurement...")
    anchor_measurement(
        measurement_cm=145.2,
        margin_cm=0.5,
        lat=12.9,
        lon=77.5,
        colmap_hash="dummy_colmap_hash"
    )
