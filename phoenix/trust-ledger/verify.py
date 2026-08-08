#!/usr/bin/env python3
"""
Verification & Tamper Detection Tool for PHOENIX Trust Ledger

Reads the local hash-chained ledger and verifies every block.
If any data has been tampered with, the computed hash will not match 
the anchored block_hash, and the chain will break.
"""

import sys
import json
from pathlib import Path
from hasher import generate_payload_hash

LEDGER_PATH = Path(__file__).parent / "ledger.json"

class Colors:
    GREEN = '\033[92m'
    RED = '\033[91m'
    YELLOW = '\033[93m'
    RESET = '\033[0m'
    BOLD = '\033[1m'

def load_ledger() -> list:
    if not LEDGER_PATH.exists():
        return []
    with open(LEDGER_PATH, "r") as f:
        return json.load(f)

def verify_chain():
    chain = load_ledger()
    if not chain:
        print("Ledger is empty.")
        return

    print("="*60)
    print(" PHOENIX TRUST LEDGER — VERIFICATION AUDIT")
    print("="*60)
    print(f"Auditing {len(chain)} blocks...\n")

    prev_hash = "GENESIS_00000000000000000000000000000000000000000000000000000000"
    
    tampered = False

    for i, block in enumerate(chain):
        block_idx = block.get("block_index")
        payload = block.get("payload")
        anchored_hash = block.get("block_hash")
        
        # 1. Check Link: Does the payload's previous_hash match the actual previous block's hash?
        if payload.get("previous_hash") != prev_hash:
            print(f"{Colors.RED}{Colors.BOLD}❌ TAMPERING DETECTED in Block #{block_idx}{Colors.RESET}")
            print(f"{Colors.YELLOW}Chain broken! Expected previous_hash: {prev_hash[:16]}... but payload claims: {payload.get('previous_hash')[:16]}...{Colors.RESET}\n")
            tampered = True
            break
            
        # 2. Check Data Integrity: Re-hash the payload and see if it matches the anchored hash
        recomputed_hash = generate_payload_hash(payload)
        if recomputed_hash != anchored_hash:
            print(f"{Colors.RED}{Colors.BOLD}❌ TAMPERING DETECTED in Block #{block_idx} — DATA ALTERED{Colors.RESET}")
            print(f"Anchored Hash:   {anchored_hash}")
            print(f"Recomputed Hash: {recomputed_hash}")
            print(f"{Colors.RED}The payload data has been modified since it was anchored!{Colors.RESET}\n")
            tampered = True
            break
            
        print(f"{Colors.GREEN}✔ Block #{block_idx} VERIFIED{Colors.RESET} (Hash: {anchored_hash[:16]}...)")
        prev_hash = anchored_hash

    print("="*60)
    if tampered:
        print(f"{Colors.RED}{Colors.BOLD}AUDIT FAILED: The ledger has been compromised.{Colors.RESET}")
    else:
        print(f"{Colors.GREEN}{Colors.BOLD}AUDIT PASSED: The ledger is cryptographically secure.{Colors.RESET}")
    print("="*60)

def simulate_tampering():
    """Helper function for the demo to deliberately alter a measurement."""
    chain = load_ledger()
    if not chain:
        print("Cannot simulate tampering on an empty ledger. Run ledger.py first.")
        return
        
    print("\n[DEMO] Simulating a bad actor changing the measurement in Block #0...")
    # Change the measurement slightly
    old_val = chain[0]["payload"]["measurement_cm"]
    chain[0]["payload"]["measurement_cm"] = old_val + 0.1 
    
    with open(LEDGER_PATH, "w") as f:
        json.dump(chain, f, indent=4)
        
    print(f"[DEMO] Measurement in Block #0 maliciously altered from {old_val} to {old_val + 0.1}.")
    print("[DEMO] Running verification audit again...\n")
    
    verify_chain()

if __name__ == "__main__":
    if "--tamper" in sys.argv:
        simulate_tampering()
    else:
        verify_chain()
        print("\n(Tip: Run 'python3 verify.py --tamper' to simulate a bad actor editing the data)")
