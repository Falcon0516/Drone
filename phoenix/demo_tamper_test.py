#!/usr/bin/env python3
"""
PHOENIX End-to-End Tamper Detection Demo Script

Runs the Trust Ledger tamper-detection demo automatically:
1. Anchors a realistic reconstruction payload
2. Verifies it (should PASS)
3. Tampers with one value
4. Re-verifies (should FAIL with TAMPERING DETECTED)
5. Restores the ledger to clean state
"""

import sys
import os
import json
import time

# Add trust-ledger to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "trust-ledger"))

from hasher import generate_payload_hash

LEDGER_PATH = os.path.join(os.path.dirname(__file__), "trust-ledger", "ledger.json")

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    CYAN = '\033[96m'
    BOLD = '\033[1m'
    RESET = '\033[0m'

def banner(text):
    print(f"\n{Colors.CYAN}{Colors.BOLD}{'='*60}")
    print(f"  {text}")
    print(f"{'='*60}{Colors.RESET}\n")

def step(num, text):
    print(f"{Colors.YELLOW}[Step {num}]{Colors.RESET} {text}")

def anchor_payload(payload, chain):
    prev_hash = "GENESIS_00000000000000000000000000000000000000000000000000000000"
    if chain:
        prev_hash = chain[-1]["block_hash"]
    
    payload["previous_hash"] = prev_hash
    block_hash = generate_payload_hash(payload)
    
    block = {
        "block_index": len(chain),
        "payload": payload,
        "block_hash": block_hash
    }
    chain.append(block)
    return chain

def verify_chain(chain):
    prev_hash = "GENESIS_00000000000000000000000000000000000000000000000000000000"
    for block in chain:
        payload = block["payload"]
        anchored_hash = block["block_hash"]
        
        if payload.get("previous_hash") != prev_hash:
            return False, f"Chain link broken at Block #{block['block_index']}"
        
        recomputed = generate_payload_hash(payload)
        if recomputed != anchored_hash:
            return False, f"Data integrity violated at Block #{block['block_index']}"
        
        prev_hash = anchored_hash
    return True, "All blocks verified"

def main():
    banner("PHOENIX — TAMPER DETECTION LIVE DEMO")
    
    # Step 1: Create a realistic reconstruction payload
    step(1, "Anchoring a certified reconstruction measurement to the ledger...")
    
    payload = {
        "measurement_cm": 147.3,
        "margin_of_error_cm": 0.4,
        "gps_lat": 12.9352,
        "gps_lon": 77.5339,
        "colmap_points_hash": "a1b2c3d4e5f6789012345678abcdef0123456789abcdef0123456789abcdef01",
        "timestamp": int(time.time()),
        "site_name": "Bellandur Lake Buffer Zone — Section A3"
    }
    
    chain = anchor_payload(dict(payload), [])
    
    with open(LEDGER_PATH, "w") as f:
        json.dump(chain, f, indent=4)
    
    print(f"  Measurement: {payload['measurement_cm']} cm ± {payload['margin_of_error_cm']} cm")
    print(f"  Location: {payload['gps_lat']}, {payload['gps_lon']}")
    print(f"  Block Hash: {chain[0]['block_hash'][:32]}...")
    print(f"  {Colors.GREEN}✔ Anchored successfully.{Colors.RESET}")
    
    time.sleep(1)
    
    # Step 2: Verify (should pass)
    step(2, "Running verification audit on untouched ledger...")
    
    ok, msg = verify_chain(chain)
    if ok:
        print(f"\n  {Colors.GREEN}{Colors.BOLD}✅ VERIFIED — Ledger integrity confirmed.{Colors.RESET}")
        print(f"  {msg}\n")
    else:
        print(f"\n  {Colors.RED}UNEXPECTED FAILURE: {msg}{Colors.RESET}\n")
        return
    
    time.sleep(1)
    
    # Step 3: Simulate tampering
    step(3, "Simulating a bad actor changing the measurement...")
    
    original_value = chain[0]["payload"]["measurement_cm"]
    tampered_value = original_value + 10.0  # Add 10cm — a significant fraud
    chain[0]["payload"]["measurement_cm"] = tampered_value
    
    with open(LEDGER_PATH, "w") as f:
        json.dump(chain, f, indent=4)
    
    print(f"  {Colors.RED}⚠ Measurement maliciously altered: {original_value} cm → {tampered_value} cm{Colors.RESET}")
    
    time.sleep(1)
    
    # Step 4: Re-verify (should FAIL)
    step(4, "Running verification audit on tampered ledger...")
    
    ok, msg = verify_chain(chain)
    if not ok:
        print(f"\n  {Colors.RED}{Colors.BOLD}❌ TAMPERING DETECTED — {msg}{Colors.RESET}")
        print(f"  {Colors.RED}The anchored hash no longer matches the data.{Colors.RESET}")
        print(f"  {Colors.RED}Any alteration, no matter how small, is instantly detectable.{Colors.RESET}\n")
    else:
        print(f"\n  {Colors.RED}UNEXPECTED: Verification passed on tampered data!{Colors.RESET}\n")
        return
    
    # Step 5: Restore
    step(5, "Restoring ledger to clean state...")
    chain[0]["payload"]["measurement_cm"] = original_value
    # Re-anchor properly
    clean_chain = anchor_payload(dict(chain[0]["payload"]), [])
    with open(LEDGER_PATH, "w") as f:
        json.dump(clean_chain, f, indent=4)
    print(f"  {Colors.GREEN}✔ Ledger restored.{Colors.RESET}")
    
    banner("DEMO COMPLETE — Tamper detection is working correctly.")

if __name__ == "__main__":
    main()
