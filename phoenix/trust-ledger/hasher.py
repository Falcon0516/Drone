#!/usr/bin/env python3
"""
Deterministic Hasher for PHOENIX Trust Ledger

Generates a strict SHA-256 hash of a reconstruction payload.
This mimics the AXIOM hashing behavior.
"""

import hashlib
import json

def generate_payload_hash(payload: dict) -> str:
    """
    Deterministically hash a dictionary payload.
    Sorts keys to ensure identical data always produces the same hash.
    """
    # Convert dict to a strictly formatted JSON string
    serialized = json.dumps(payload, sort_keys=True, separators=(',', ':'))
    
    # Generate SHA-256 hash
    hasher = hashlib.sha256()
    hasher.update(serialized.encode('utf-8'))
    
    return hasher.hexdigest()

if __name__ == "__main__":
    # Quick test
    sample = {
        "measurement_cm": 150.5,
        "margin_of_error_cm": 0.2,
        "timestamp": 1691456000,
        "gps_lat": 12.9716,
        "gps_lon": 77.5946,
        "colmap_points_hash": "abc123def456..."
    }
    print(f"Sample Payload Hash: {generate_payload_hash(sample)}")
