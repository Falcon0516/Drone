/* ============================================================
   PHOENIX Ground Station — Dashboard Logic
   Connects to ground_station.py WebSocket and renders live data
   ============================================================ */

(function () {
    'use strict';

    const WS_URL = `ws://${location.hostname}:8765`;
    const RECONNECT_DELAY = 3000;

    // DOM refs
    const els = {
        connectionBadge: document.getElementById('connectionBadge'),
        connectionText: document.getElementById('connectionText'),
        clock: document.getElementById('clock'),
        coordsDisplay: document.getElementById('coordsDisplay'),
        telemetryPulse: document.getElementById('telemetryPulse'),
        altValue: document.getElementById('altValue'),
        hdgValue: document.getElementById('hdgValue'),
        spdValue: document.getElementById('spdValue'),
        batValue: document.getElementById('batValue'),
        batteryBar: document.getElementById('batteryBar'),
        pitchValue: document.getElementById('pitchValue'),
        rollValue: document.getElementById('rollValue'),
        faultPanel: document.getElementById('faultPanel'),
        faultCount: document.getElementById('faultCount'),
        faultList: document.getElementById('faultList'),
        detectionCount: document.getElementById('detectionCount'),
        detectionList: document.getElementById('detectionList'),
    };

    // ---- Clock ----
    function updateClock() {
        const now = new Date();
        els.clock.textContent = now.toLocaleTimeString('en-GB', { hour12: false });
    }
    setInterval(updateClock, 1000);
    updateClock();

    // ---- Map ----
    const map = L.map('map', {
        zoomControl: false,
        attributionControl: false,
    }).setView([12.9716, 77.5946], 15); // Default: Bengaluru

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
    }).addTo(map);

    // Drone marker
    const droneIcon = L.divIcon({
        className: 'drone-marker',
        html: `<div style="
            width: 18px; height: 18px; 
            background: #3b82f6; 
            border: 3px solid #fff;
            border-radius: 50%; 
            box-shadow: 0 0 12px rgba(59,130,246,0.6), 0 0 24px rgba(59,130,246,0.3);
        "></div>`,
        iconSize: [18, 18],
        iconAnchor: [9, 9],
    });

    let droneMarker = null;
    const trailCoords = [];
    const trailLine = L.polyline([], {
        color: '#3b82f6',
        weight: 2,
        opacity: 0.6,
        dashArray: '6, 8',
    }).addTo(map);

    function updateMapPosition(lat, lon) {
        if (lat === 0 && lon === 0) return;

        if (!droneMarker) {
            droneMarker = L.marker([lat, lon], { icon: droneIcon }).addTo(map);
            map.setView([lat, lon], 17);
        } else {
            droneMarker.setLatLng([lat, lon]);
        }

        trailCoords.push([lat, lon]);
        if (trailCoords.length > 500) trailCoords.shift();
        trailLine.setLatLngs(trailCoords);

        els.coordsDisplay.textContent = `${lat.toFixed(6)}, ${lon.toFixed(6)}`;
    }

    // ---- Telemetry ----
    let pulseTimeout = null;

    function flashPulse() {
        els.telemetryPulse.classList.add('active');
        clearTimeout(pulseTimeout);
        pulseTimeout = setTimeout(() => els.telemetryPulse.classList.remove('active'), 400);
    }

    function flashValue(el) {
        el.classList.add('flash');
        setTimeout(() => el.classList.remove('flash'), 500);
    }

    function updateTelemetry(data) {
        flashPulse();

        if (data.alt !== undefined) {
            els.altValue.textContent = data.alt.toFixed(1);
            flashValue(els.altValue);
        }
        if (data.heading !== undefined) {
            els.hdgValue.textContent = data.heading.toFixed(0);
            flashValue(els.hdgValue);
        }
        if (data.groundspeed !== undefined) {
            els.spdValue.textContent = data.groundspeed.toFixed(1);
        }
        if (data.pitch !== undefined) {
            els.pitchValue.textContent = data.pitch.toFixed(1);
        }
        if (data.roll !== undefined) {
            els.rollValue.textContent = data.roll.toFixed(1);
        }
        if (data.battery !== undefined && data.battery >= 0) {
            els.batValue.textContent = data.battery;
            els.batteryBar.style.width = data.battery + '%';
            if (data.battery > 50) {
                els.batteryBar.style.background = 'var(--accent-green)';
            } else if (data.battery > 20) {
                els.batteryBar.style.background = 'var(--accent-amber)';
            } else {
                els.batteryBar.style.background = 'var(--accent-red)';
            }
        }

        if (data.lat && data.lon) {
            updateMapPosition(data.lat, data.lon);
        }
    }

    // ---- Faults ----
    let faultTotal = 0;

    function addFault(fault) {
        faultTotal++;
        els.faultCount.textContent = faultTotal;
        els.faultPanel.classList.add('has-faults');

        // Remove empty state
        const empty = els.faultList.querySelector('.empty-state');
        if (empty) empty.remove();

        const isGPS = (fault.type || '').includes('GPS');
        const time = new Date(fault.timestamp * 1000).toLocaleTimeString('en-GB', { hour12: false });
        const detailStr = fault.details
            ? Object.entries(fault.details).map(([k, v]) => `${k}=${v}`).join(' ')
            : '';

        const div = document.createElement('div');
        div.className = 'fault-item';
        div.innerHTML = `
            <div class="fault-icon ${isGPS ? 'gps' : 'attitude'}">${isGPS ? '📡' : '🔄'}</div>
            <div class="fault-info">
                <div class="fault-type">${fault.type || 'UNKNOWN'}</div>
                <div class="fault-detail">${detailStr}</div>
            </div>
            <div class="fault-time">${time}</div>
        `;
        els.faultList.prepend(div);

        // Keep max 50 in DOM
        while (els.faultList.children.length > 50) {
            els.faultList.removeChild(els.faultList.lastChild);
        }
    }

    // ---- AI Detections ----
    let detectionTotal = 0;

    function addDetection(det) {
        detectionTotal++;
        els.detectionCount.textContent = detectionTotal;

        // Remove empty state
        const empty = els.detectionList.querySelector('.empty-state');
        if (empty) empty.remove();

        const time = new Date(det.timestamp * 1000).toLocaleTimeString('en-GB', { hour12: false });
        const conf = det.details?.conf || '?';
        const loc = det.details?.lat && det.details?.lon
            ? `${parseFloat(det.details.lat).toFixed(4)}, ${parseFloat(det.details.lon).toFixed(4)}`
            : '—';

        const div = document.createElement('div');
        div.className = 'detection-item';
        div.innerHTML = `
            <div class="detection-icon">🎯</div>
            <div class="detection-info">
                <div class="detection-label">${det.label || 'unknown'}</div>
                <div class="detection-meta">conf: ${conf} · ${loc}</div>
            </div>
            <div class="detection-time">${time}</div>
        `;
        els.detectionList.prepend(div);

        while (els.detectionList.children.length > 50) {
            els.detectionList.removeChild(els.detectionList.lastChild);
        }
    }

    // ---- Connection Status ----
    function setConnectionStatus(connected) {
        if (connected) {
            els.connectionBadge.className = 'badge badge-connected';
            els.connectionText.textContent = 'CONNECTED';
        } else {
            els.connectionBadge.className = 'badge badge-disconnected';
            els.connectionText.textContent = 'DISCONNECTED';
        }
    }

    // ---- WebSocket ----
    function connectWS() {
        const ws = new WebSocket(WS_URL);

        ws.onopen = () => {
            console.log('[WS] Connected to Ground Station');
        };

        ws.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);

                switch (msg.type) {
                    case 'init':
                        setConnectionStatus(msg.telemetry?.connected || false);
                        updateTelemetry(msg.telemetry || {});
                        (msg.faults || []).forEach(f => addFault(f));
                        (msg.detections || []).forEach(d => addDetection(d));
                        break;

                    case 'telemetry':
                        setConnectionStatus(true);
                        updateTelemetry(msg.data);
                        break;

                    case 'fault':
                        addFault(msg.data);
                        break;

                    case 'detection':
                        addDetection(msg.data);
                        break;

                    case 'status':
                        setConnectionStatus(msg.connected);
                        break;
                }
            } catch (e) {
                console.error('[WS] Parse error:', e);
            }
        };

        ws.onclose = () => {
            console.log('[WS] Disconnected. Reconnecting...');
            setConnectionStatus(false);
            setTimeout(connectWS, RECONNECT_DELAY);
        };

        ws.onerror = () => {
            ws.close();
        };
    }

    connectWS();

})();
