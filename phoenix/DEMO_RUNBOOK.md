# PHOENIX — DEMO RUNBOOK
> The authoritative script for the Monday presentation.
> Follow this order exactly. Total demo time: ~8-10 minutes.

---

## 🔴 LIVE DEMO #1: Tamper-Proof Measurement Verification (Phase 6)
**Why live**: 100% reliable, zero hardware dependencies, extremely visual.

### Setup
- Terminal open in `phoenix/` directory
- Ledger file (`trust-ledger/ledger.json`) deleted or empty

### Script
1. Run: `python3 demo_tamper_test.py`
2. Narrate while it runs:
   - **Step 1**: "We've just taken an aerial survey measurement — 147.3 cm — and sealed it with a cryptographic hash."
   - **Step 2**: "The system confirms the data is untouched. Green checkmark. This is the baseline."
   - **Step 3**: "Now I'm going to simulate someone — a corrupt contractor, a bad actor — quietly changing that measurement by 10 centimetres."
   - **Step 4**: "Instant detection. The hash no longer matches. You cannot silently alter PHOENIX data."
3. **Key line to audience**: "This is what makes our data *legally defensible*. Every measurement has a cryptographic seal. Change one digit, the system catches it."

**Estimated time: 2 minutes**

---

## 🔴 LIVE DEMO #2: Mission Planner — Autonomous Survey Path (Phase 7)
**Why live**: Visually impressive, interactive, zero risk of failure.

### Setup
- Open `mission-planner/index.html` in Chrome (serve via `python3 -m http.server 8080` from `mission-planner/`)

### Script
1. Zoom the map to a lake in Bengaluru (e.g., Bellandur Lake)
2. Use the polygon tool to draw a survey boundary around the lake's buffer zone
3. The lawnmower path instantly generates with colored sortie segments
4. Point out:
   - "Each color is one battery's worth of flight"
   - "The system calculated 70% photo overlap for reconstruction accuracy"
   - "It split a 45-minute survey into three 15-minute sorties automatically"
5. Click "Export Waypoints" — show that each sortie downloads as a MAVLink-compatible waypoint file
6. Change the Battery Time slider from 15 to 10 minutes — watch it re-split into more sorties

**Key line to audience**: "A local volunteer draws the boundary. PHOENIX does everything else — flight path, photo spacing, battery management. No pilot skill needed."

**Estimated time: 3 minutes**

---

## 🟡 PRE-RECORDED VIDEO: Telemetry Pipeline (Phases 1-4)
**Why recorded**: Requires Docker (SITL), Android device/emulator, and Tailscale — too many moving parts for a live demo.

### What to record (before Monday)
1. Start SITL: `docker run --rm -p 5760:5760 radarku/ardupilot-sitl -v ArduCopter`
2. Start Ground Station: `python3 dashboard/ground_station.py`
3. Open dashboard in browser: `http://localhost:3000`
4. Show telemetry flowing: altitude, heading, GPS position updating on the map
5. (If possible) Trigger a debug fault injection from the Android app and show the FAULT alert appear on the dashboard

### Recording guide
```bash
# Use macOS built-in screen recording (Cmd+Shift+5)
# Or use QuickTime Player > File > New Screen Recording
# Record for ~90 seconds showing the full data flow
# Save as phoenix_telemetry_demo.mov
```

**Narration points during playback**:
- "This is a simulated flight controller running ArduPilot SITL"
- "Telemetry flows: Flight Controller → Phone (USB) → Tailscale VPN → Ground Station"
- "The phone independently cross-checks GPS and attitude against its own sensors"
- "Any mismatch triggers a FAULT alert that reaches the operator in real-time"

**Estimated time: 2 minutes**

---

## 🟢 SLIDES ONLY: Architecture & Business Model
**Why slides**: These are strategic narrative points, not technical demos.

### Slides to prepare
1. **The Problem**: Bengaluru's lakes are being encroached. Manual surveys are expensive, slow, and corruptible.
2. **PHOENIX Architecture Diagram**: Phone → Flight Controller → Tailscale → Dashboard → Reconstruction → Trust Ledger
3. **The Phone Advantage**: ₹2,000 discarded smartphone vs. ₹50,000+ survey-grade sensor pod
4. **Tiered Fleet Strategy**: Different phone tiers for different mission types
5. **Reconstruction Pipeline**: COLMAP → ArUco scale → Certified measurement (explain Phase 5 briefly, show pre-run output)
6. **Monetization**: SaaS model for municipalities, NGOs, and environmental agencies
7. **Roadmap**: Multi-drone fleet, DGCA compliance, partnerships with BBMP/Lake Authority

**Estimated time: 3 minutes**

---

## Contingency Plan

| If this fails... | Do this instead |
|---|---|
| Tamper demo crashes | Open `trust-ledger/ledger.json` manually, edit a number, run `python3 trust-ledger/verify.py` |
| Mission planner won't load | Show pre-captured screenshots of the generated flight path |
| Pre-recorded video won't play | Walk through the Dashboard HTML/code and explain the architecture verbally with the architecture diagram slide |
| Everything fails | Focus entirely on the slides + the tamper demo (it's pure Python, it will work) |

---

## Pre-Presentation Checklist

- [ ] `python3 demo_tamper_test.py` runs cleanly (test 3 times)
- [ ] Mission planner loads in browser and generates paths
- [ ] Demo video recorded and plays smoothly
- [ ] Slides exported to PDF as backup
- [ ] Laptop charged, charger accessible
- [ ] Browser bookmarks set for dashboard and mission planner URLs
