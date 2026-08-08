/* ============================================================
   PHOENIX Mission Planner — Core Logic
   Generates lawnmower survey paths and battery-split sorties
   ============================================================ */

(function () {
    'use strict';

    // ---- Color palette for sortie chunks ----
    const SORTIE_COLORS = [
        '#3b82f6', '#10b981', '#f59e0b', '#ef4444',
        '#8b5cf6', '#06b6d4', '#ec4899', '#14b8a6',
        '#f97316', '#6366f1',
    ];

    // ---- Map Setup ----
    const map = L.map('map', { zoomControl: true }).setView([12.9716, 77.5946], 15);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);

    const drawnItems = new L.FeatureGroup();
    map.addLayer(drawnItems);

    const drawControl = new L.Control.Draw({
        draw: {
            polygon: { allowIntersection: false, shapeOptions: { color: '#3b82f6', weight: 2 } },
            polyline: false, rectangle: false, circle: false, marker: false, circlemarker: false,
        },
        edit: { featureGroup: drawnItems },
    });
    map.addControl(drawControl);

    let pathLayers = [];

    // ---- DOM Refs ----
    const els = {
        instructions: document.getElementById('mapInstructions'),
        resultsPanel: document.getElementById('resultsPanel'),
        sortieList: document.getElementById('sortieList'),
        resArea: document.getElementById('resArea'),
        resLength: document.getElementById('resLength'),
        resTime: document.getElementById('resTime'),
        resPhotos: document.getElementById('resPhotos'),
        resSorties: document.getElementById('resSorties'),
        clearBtn: document.getElementById('clearBtn'),
        exportBtn: document.getElementById('exportBtn'),
    };

    // ---- Config Readers ----
    function getConfig() {
        return {
            altitude: parseFloat(document.getElementById('altitude').value),
            fov: parseFloat(document.getElementById('fov').value),
            overlap: parseFloat(document.getElementById('overlap').value) / 100,
            speed: parseFloat(document.getElementById('speed').value),
            batteryTime: parseFloat(document.getElementById('batteryTime').value) * 60, // seconds
        };
    }

    // ---- Geometry Helpers ----
    const DEG2RAD = Math.PI / 180;
    const EARTH_R = 6371000; // metres

    function haversine(lat1, lon1, lat2, lon2) {
        const dLat = (lat2 - lat1) * DEG2RAD;
        const dLon = (lon2 - lon1) * DEG2RAD;
        const a = Math.sin(dLat / 2) ** 2 +
                  Math.cos(lat1 * DEG2RAD) * Math.cos(lat2 * DEG2RAD) * Math.sin(dLon / 2) ** 2;
        return EARTH_R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    function polygonArea(latlngs) {
        // Shoelace formula on projected coords (rough but good enough for small areas)
        let area = 0;
        const n = latlngs.length;
        for (let i = 0; i < n; i++) {
            const j = (i + 1) % n;
            const xi = latlngs[i].lng * DEG2RAD * EARTH_R * Math.cos(latlngs[i].lat * DEG2RAD);
            const yi = latlngs[i].lat * DEG2RAD * EARTH_R;
            const xj = latlngs[j].lng * DEG2RAD * EARTH_R * Math.cos(latlngs[j].lat * DEG2RAD);
            const yj = latlngs[j].lat * DEG2RAD * EARTH_R;
            area += xi * yj - xj * yi;
        }
        return Math.abs(area / 2);
    }

    // ---- Lawnmower Path Generation ----
    /**
     * Generate a lawnmower (boustrophedon) survey path.
     *
     * SPACING MATH (shown here so it can be explained if asked):
     * ─────────────────────────────────────────────────────────
     * Ground footprint width at altitude h with camera FOV θ:
     *   W = 2 × h × tan(θ/2)
     *
     * For X% sidelap (overlap between adjacent strips):
     *   Line spacing S = W × (1 - overlap)
     *
     * Example: h=50m, FOV=73°, overlap=70%
     *   W = 2 × 50 × tan(36.5°) = 74.0 m
     *   S = 74.0 × 0.30 = 22.2 m
     *
     * The function generates horizontal scan lines spaced by S,
     * clipped to the polygon boundary, alternating direction
     * (left→right, then right→left) to minimize turning.
     */
    function generateLawnmowerPath(latlngs, config) {
        const { altitude, fov, overlap } = config;

        // Ground footprint width
        const W = 2 * altitude * Math.tan((fov / 2) * DEG2RAD);
        // Line spacing
        const S = W * (1 - overlap);

        // Convert spacing from metres to degrees latitude (approximate)
        const spacingDeg = S / 111320;

        // Get bounding box
        let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity;
        for (const p of latlngs) {
            if (p.lat < minLat) minLat = p.lat;
            if (p.lat > maxLat) maxLat = p.lat;
            if (p.lng < minLon) minLon = p.lng;
            if (p.lng > maxLon) maxLon = p.lng;
        }

        // Generate horizontal scan lines
        const waypoints = [];
        let leftToRight = true;

        for (let lat = minLat + spacingDeg / 2; lat < maxLat; lat += spacingDeg) {
            // Find intersections of this latitude line with the polygon edges
            const intersections = [];
            const n = latlngs.length;
            for (let i = 0; i < n; i++) {
                const j = (i + 1) % n;
                const p1 = latlngs[i], p2 = latlngs[j];
                if ((p1.lat <= lat && p2.lat > lat) || (p2.lat <= lat && p1.lat > lat)) {
                    const t = (lat - p1.lat) / (p2.lat - p1.lat);
                    const lon = p1.lng + t * (p2.lng - p1.lng);
                    intersections.push(lon);
                }
            }
            intersections.sort((a, b) => a - b);

            // Take pairs of intersections as line segments inside the polygon
            for (let k = 0; k < intersections.length - 1; k += 2) {
                if (leftToRight) {
                    waypoints.push({ lat, lng: intersections[k] });
                    waypoints.push({ lat, lng: intersections[k + 1] });
                } else {
                    waypoints.push({ lat, lng: intersections[k + 1] });
                    waypoints.push({ lat, lng: intersections[k] });
                }
            }
            leftToRight = !leftToRight;
        }

        return { waypoints, lineSpacingM: S, footprintM: W };
    }

    // ---- Battery Split ----
    function splitIntoBatterySorties(waypoints, config) {
        const { speed, batteryTime } = config;
        const maxDist = speed * batteryTime; // max metres per battery

        const sorties = [];
        let currentSortie = [waypoints[0]];
        let currentDist = 0;

        for (let i = 1; i < waypoints.length; i++) {
            const prev = waypoints[i - 1];
            const curr = waypoints[i];
            const segDist = haversine(prev.lat, prev.lng, curr.lat, curr.lng);

            if (currentDist + segDist > maxDist && currentSortie.length > 1) {
                sorties.push({ waypoints: [...currentSortie], distance: currentDist });
                currentSortie = [prev]; // Overlap last point so path is continuous
                currentDist = 0;
            }
            currentSortie.push(curr);
            currentDist += segDist;
        }
        if (currentSortie.length > 1) {
            sorties.push({ waypoints: currentSortie, distance: currentDist });
        }

        return sorties;
    }

    // ---- Rendering ----
    function clearPaths() {
        pathLayers.forEach(l => map.removeLayer(l));
        pathLayers = [];
        els.sortieList.innerHTML = '';
    }

    function renderSorties(sorties, config) {
        clearPaths();
        els.resultsPanel.style.display = 'block';

        let totalDist = 0;
        let totalPhotos = 0;

        sorties.forEach((sortie, idx) => {
            const color = SORTIE_COLORS[idx % SORTIE_COLORS.length];
            const coords = sortie.waypoints.map(w => [w.lat, w.lng]);

            const line = L.polyline(coords, {
                color, weight: 3, opacity: 0.85, dashArray: idx > 0 ? '8, 6' : null,
            }).addTo(map);
            pathLayers.push(line);

            // Start marker
            const startMarker = L.circleMarker(coords[0], {
                radius: 6, fillColor: color, color: '#fff', weight: 2, fillOpacity: 1,
            }).addTo(map).bindTooltip(`Sortie ${idx + 1} Start`, { permanent: false });
            pathLayers.push(startMarker);

            const flightTimeSec = sortie.distance / config.speed;
            const flightTimeMin = flightTimeSec / 60;
            // Photo count: one photo every (footprint * (1 - overlap)) metres along the path
            const photoInterval = config.speed * 2; // roughly every 2 seconds
            const photos = Math.ceil(sortie.distance / photoInterval);

            totalDist += sortie.distance;
            totalPhotos += photos;

            // Sortie card in sidebar
            const card = document.createElement('div');
            card.className = 'sortie-card';
            card.innerHTML = `
                <div class="sortie-header">
                    <span class="sortie-name" style="color:${color}">Sortie ${idx + 1}</span>
                    <span class="sortie-badge" style="background:${color}22; color:${color}; border:1px solid ${color}44">
                        ${flightTimeMin.toFixed(1)} min
                    </span>
                </div>
                <div class="sortie-meta">
                    ${(sortie.distance / 1000).toFixed(2)} km · ~${photos} photos · ${sortie.waypoints.length} waypoints
                </div>
            `;
            card.addEventListener('mouseenter', () => { line.setStyle({ weight: 6 }); });
            card.addEventListener('mouseleave', () => { line.setStyle({ weight: 3 }); });
            els.sortieList.appendChild(card);
        });

        // Summary
        els.resArea.textContent = `—`; // Will be set by caller
        els.resLength.textContent = `${(totalDist / 1000).toFixed(2)} km`;
        els.resTime.textContent = `${(totalDist / config.speed / 60).toFixed(1)} min`;
        els.resPhotos.textContent = `~${totalPhotos}`;
        els.resSorties.textContent = sorties.length;
    }

    // ---- Main Pipeline ----
    function processBoundary(layer) {
        els.instructions.classList.add('hidden');
        const latlngs = layer.getLatLngs()[0];
        const config = getConfig();

        const area = polygonArea(latlngs);
        const { waypoints, lineSpacingM, footprintM } = generateLawnmowerPath(latlngs, config);

        if (waypoints.length < 2) {
            alert('Polygon too small or parameters too wide. Try a larger area or narrower spacing.');
            return;
        }

        const sorties = splitIntoBatterySorties(waypoints, config);
        renderSorties(sorties, config);

        // Set area
        if (area > 10000) {
            els.resArea.textContent = `${(area / 10000).toFixed(2)} ha`;
        } else {
            els.resArea.textContent = `${area.toFixed(0)} m²`;
        }

        // Store for export
        window._phoenixSorties = sorties;
        window._phoenixConfig = config;
    }

    // ---- Events ----
    map.on(L.Draw.Event.CREATED, function (e) {
        drawnItems.addLayer(e.layer);
        processBoundary(e.layer);
    });

    map.on(L.Draw.Event.EDITED, function () {
        drawnItems.eachLayer(l => processBoundary(l));
    });

    // Re-calculate when config changes
    ['altitude', 'fov', 'overlap', 'speed', 'batteryTime'].forEach(id => {
        document.getElementById(id).addEventListener('change', () => {
            drawnItems.eachLayer(l => processBoundary(l));
        });
    });

    els.clearBtn.addEventListener('click', () => {
        drawnItems.clearLayers();
        clearPaths();
        els.resultsPanel.style.display = 'none';
        els.instructions.classList.remove('hidden');
    });

    // ---- Export Waypoints (MAVLink-compatible QGC WPL 110 format) ----
    els.exportBtn.addEventListener('click', () => {
        const sorties = window._phoenixSorties;
        const config = window._phoenixConfig;
        if (!sorties || !sorties.length) {
            alert('Draw a boundary first to generate waypoints.');
            return;
        }

        sorties.forEach((sortie, idx) => {
            // QGC WPL 110 format — standard MAVLink waypoint file
            // NOTE: This is formatted for ArduPilot AUTO mode but is UNTESTED
            // against a real flight controller. Validate before real flight.
            let wpl = 'QGC WPL 110\n';

            // Home waypoint (index 0)
            const home = sortie.waypoints[0];
            wpl += `0\t1\t0\t16\t0\t0\t0\t0\t${home.lat.toFixed(8)}\t${home.lng.toFixed(8)}\t${config.altitude.toFixed(1)}\t1\n`;

            sortie.waypoints.forEach((wp, i) => {
                // MAV_CMD_NAV_WAYPOINT = 16
                wpl += `${i + 1}\t0\t3\t16\t0\t0\t0\t0\t${wp.lat.toFixed(8)}\t${wp.lng.toFixed(8)}\t${config.altitude.toFixed(1)}\t1\n`;
            });

            // Add RTL at the end (MAV_CMD_NAV_RETURN_TO_LAUNCH = 20)
            wpl += `${sortie.waypoints.length + 1}\t0\t3\t20\t0\t0\t0\t0\t0\t0\t0\t1\n`;

            const blob = new Blob([wpl], { type: 'text/plain' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `phoenix_sortie_${idx + 1}.waypoints`;
            a.click();
            URL.revokeObjectURL(a.href);
        });
    });

})();
