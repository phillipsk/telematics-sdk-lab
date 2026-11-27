window.WorkbenchCharts = (function () {
  'use strict';

  const C = {
    bg: '#0b1222',
    border: '#22314f',
    slate: '#94a3b8',
    cyan: '#38bdf8',
    teal: '#2dd4bf',
    green: '#4ade80',
    amber: '#fbbf24',
    red: '#f87171',
    purple: '#c084fc',
    white: '#f1f5f9',
    gridLine: 'rgba(34,49,79,0.45)',
    roadAsphalt: '#1e293b',
    roadCenter: 'rgba(148,163,184,0.4)',
    signalHousing: '#0f172a'
  };

  function syncTheme() {
    if (typeof window === 'undefined' || !window.getComputedStyle) return;
    const cs = window.getComputedStyle(document.documentElement);
    const g = (v, fb) => cs.getPropertyValue(v).trim() || fb;
    C.bg = g('--sub', C.bg);
    C.border = g('--border', C.border);
    C.slate = g('--muted', C.slate);
    C.cyan = g('--cyan', C.cyan);
    C.teal = g('--teal', C.teal);
    C.green = g('--green', C.green);
    C.amber = g('--amber', C.amber);
    C.red = g('--red', C.red);
    C.purple = g('--purple', C.purple);
    C.white = g('--text', C.white);
    const forced = document.documentElement.getAttribute('data-theme');
    const isLight = forced === 'light' || (forced !== 'dark' && window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches);
    C.gridLine = isLight ? 'rgba(148,163,184,0.32)' : 'rgba(34,49,79,0.45)';
    C.roadAsphalt = isLight ? '#475569' : '#1e293b';
    C.roadCenter = isLight ? 'rgba(248,250,252,0.75)' : 'rgba(148,163,184,0.4)';
    C.signalHousing = isLight ? '#1e293b' : '#0f172a';
  }

  function eventColor(type) {
    if (type === 'HARD_BRAKE' || type === 'POTENTIAL_IMPACT') return C.red;
    if (type === 'RAPID_ACCEL') return C.amber;
    if (type === 'HARSH_CORNER_LEFT' || type === 'HARSH_CORNER_RIGHT') return C.cyan;
    if (type === 'DISTRACTION_HANDHELD' || type === 'DISTRACTION_SCREEN') return C.purple;
    if (type === 'REJECTED_PHONE_DROP') return C.green;
    return C.teal;
  }

  function drawRoadCourse(canvas, sim) {
    syncTheme();
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    // Subtle grid
    ctx.strokeStyle = C.gridLine;
    ctx.lineWidth = 1;
    for (let x = 0; x < w; x += 35) {
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
    }
    for (let y = 0; y < h; y += 35) {
      ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
    }

    // Tunnel / Urban Canyon Dead Zone (top straightaway)
    ctx.fillStyle = 'rgba(251, 191, 36, 0.10)';
    ctx.strokeStyle = 'rgba(251, 191, 36, 0.45)';
    ctx.setLineDash([6, 4]);
    ctx.fillRect(220, 32, 240, 86);
    ctx.strokeRect(220, 32, 240, 86);
    ctx.setLineDash([]);
    ctx.fillStyle = C.amber;
    ctx.font = 'bold 11px monospace';
    ctx.fillText('URBAN CANYON / TUNNEL (GNSS DEAD ZONE)', 228, 48);

    // Road Loop Path
    const pts = sim.waypoints;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.strokeStyle = C.roadAsphalt;
    ctx.lineWidth = 42;
    ctx.beginPath();
    pts.forEach((p, i) => (i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y)));
    ctx.closePath();
    ctx.stroke();

    // Dashed centerline
    ctx.strokeStyle = C.roadCenter;
    ctx.lineWidth = 2;
    ctx.setLineDash([12, 10]);
    ctx.beginPath();
    pts.forEach((p, i) => (i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y)));
    ctx.closePath();
    ctx.stroke();
    ctx.setLineDash([]);

    // Traffic Light at bottom straightaway intersection
    const lx = 350, ly = 315;
    ctx.fillStyle = C.signalHousing;
    ctx.fillRect(lx - 10, ly - 32, 20, 26);
    const lightColor = sim.trafficLight === 'RED' ? C.red : sim.trafficLight === 'YELLOW' ? C.amber : C.green;
    ctx.fillStyle = lightColor;
    ctx.beginPath();
    ctx.arc(lx, ly - 19, 6, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = lightColor;
    ctx.font = 'bold 10px monospace';
    ctx.fillText(`SIGNAL: ${sim.trafficLight}`, lx - 36, ly + 36);

    // Tire skid marks from hard braking / harsh cornering
    if (sim.skidMarks && sim.skidMarks.length) {
      sim.skidMarks.forEach((sk) => {
        ctx.strokeStyle = `rgba(15, 23, 42, ${sk.alpha.toFixed(2)})`;
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.moveTo(sk.x0, sk.y0);
        ctx.lineTo(sk.x1, sk.y1);
        ctx.stroke();
      });
    }

    // Event pins on track
    sim.trackMarkers.forEach((m) => {
      ctx.fillStyle = eventColor(m.type);
      ctx.beginPath();
      ctx.arc(m.x, m.y, 5.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = 1.2;
      ctx.stroke();
    });

    // Draw Animated Vehicle Skin
    ctx.save();
    ctx.translate(sim.carX, sim.carY);
    ctx.rotate(sim.carAngleRad);

    // Headlight glow
    ctx.fillStyle = 'rgba(56, 189, 248, 0.14)';
    ctx.beginPath();
    ctx.moveTo(15, -8);
    ctx.lineTo(50, -23);
    ctx.lineTo(50, 23);
    ctx.lineTo(15, 8);
    ctx.closePath();
    ctx.fill();

    drawVehicleBody(ctx, sim);
    ctx.restore();
  }

  function drawVehicleBody(ctx, sim) {
    const skin = sim.vehicleSkin || 'ev_suv';
    const braking = (sim.currentLongG || 0) < -0.12;
    const turningLeft = (sim.currentLatG || 0) < -0.14;
    const turningRight = (sim.currentLatG || 0) > 0.14;
    const blinkOn = Math.floor(Date.now() / 220) % 2 === 0;

    let bodyColor = '#e2e8f0';
    let length = 34, width = 18;
    if (skin === 'coupe') { bodyColor = '#ef4444'; length = 34; width = 17; }
    else if (skin === 'sedan') { bodyColor = '#38bdf8'; length = 34; width = 17; }
    else if (skin === 'pickup') { bodyColor = '#d97706'; length = 38; width = 19; }
    else if (skin === 'fleet_van') { bodyColor = '#cbd5e1'; length = 40; width = 20; }
    if (sim.inTunnel) bodyColor = '#fbbf24';

    const hx = length / 2, hy = width / 2;

    // Shadow
    ctx.fillStyle = 'rgba(0,0,0,0.35)';
    ctx.beginPath();
    ctx.roundRect(-hx - 1, -hy + 2, length + 2, width, 5);
    ctx.fill();

    // Main body
    ctx.fillStyle = bodyColor;
    ctx.strokeStyle = '#061322';
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    ctx.roundRect(-hx, -hy, length, width, skin === 'fleet_van' ? 4 : 6);
    ctx.fill();
    ctx.stroke();

    // Skin-specific details
    if (skin === 'ev_suv') {
      // Panoramic glass roof + cyan EV accent
      ctx.fillStyle = '#0f172a';
      ctx.beginPath(); ctx.roundRect(-7, -6, 15, 12, 3); ctx.fill();
      ctx.fillStyle = 'rgba(56,189,248,0.45)';
      ctx.fillRect(hx - 2, -hy + 2, 2, width - 4);
    } else if (skin === 'coupe') {
      // Racing stripe + rear spoiler
      ctx.fillStyle = '#f8fafc';
      ctx.fillRect(-hx + 3, -1.8, length - 5, 3.6);
      ctx.fillStyle = '#0f172a';
      ctx.fillRect(2, -5.5, 8, 11);
      ctx.fillStyle = '#1e293b';
      ctx.fillRect(-hx - 1, -hy + 1, 3, width - 2);
    } else if (skin === 'sedan') {
      ctx.fillStyle = '#0b1222';
      ctx.fillRect(2, -6, 8, 12);
      ctx.fillRect(-9, -5.5, 5, 11);
    } else if (skin === 'pickup') {
      // Open dark cargo bed in rear
      ctx.fillStyle = '#1e293b';
      ctx.fillRect(-hx + 2, -hy + 2.5, 13, width - 5);
      ctx.fillStyle = '#0b1222';
      ctx.fillRect(3, -6, 7, 12);
    } else if (skin === 'fleet_van') {
      // Roof ribs + blinking amber fleet beacon
      ctx.fillStyle = '#0b1222';
      ctx.fillRect(hx - 10, -hy + 2.5, 6, width - 5);
      ctx.strokeStyle = '#64748b';
      ctx.lineWidth = 1;
      for (let rx = -14; rx <= 2; rx += 5) {
        ctx.beginPath(); ctx.moveTo(rx, -hy + 3); ctx.lineTo(rx, hy - 3); ctx.stroke();
      }
      if (blinkOn) {
        ctx.fillStyle = '#fbbf24';
        ctx.beginPath(); ctx.arc(-4, 0, 3, 0, Math.PI * 2); ctx.fill();
      }
    }

    // Headlights
    ctx.fillStyle = '#fef08a';
    ctx.fillRect(hx - 2, -hy + 2, 2, 3.5);
    ctx.fillRect(hx - 2, hy - 5.5, 2, 3.5);

    // Brake lights (glow bright red when decelerating)
    ctx.fillStyle = braking ? '#ff2a2a' : 'rgba(239,68,68,0.45)';
    ctx.fillRect(-hx, -hy + 2, 2.5, 4);
    ctx.fillRect(-hx, hy - 6, 2.5, 4);
    if (braking) {
      ctx.fillStyle = 'rgba(255, 42, 42, 0.35)';
      ctx.beginPath(); ctx.arc(-hx - 3, -hy + 4, 6, 0, Math.PI * 2); ctx.fill();
      ctx.beginPath(); ctx.arc(-hx - 3, hy - 4, 6, 0, Math.PI * 2); ctx.fill();
    }

    // Turn indicators
    if (blinkOn && turningLeft) {
      ctx.fillStyle = '#fbbf24';
      ctx.beginPath(); ctx.arc(hx - 1, -hy, 2.5, 0, Math.PI * 2); ctx.fill();
    }
    if (blinkOn && turningRight) {
      ctx.fillStyle = '#fbbf24';
      ctx.beginPath(); ctx.arc(hx - 1, hy, 2.5, 0, Math.PI * 2); ctx.fill();
    }
  }

  function draw3DPhone(canvas, pitchDeg, rollDeg, yawDeg) {
    syncTheme();
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const cx = w / 2, cy = h / 2;
    const p = (pitchDeg * Math.PI) / 180;
    const r = (rollDeg * Math.PI) / 180;
    const y = (yawDeg * Math.PI) / 180;

    // 3D box vertices (phone slab)
    const hw = 34, hh = 64, hd = 6;
    const verts = [
      [-hw, -hh, -hd], [hw, -hh, -hd], [hw, hh, -hd], [-hw, hh, -hd],
      [-hw, -hh, hd], [hw, -hh, hd], [hw, hh, hd], [-hw, hh, hd]
    ];

    function rot(v) {
      let [vx, vy, vz] = v;
      // Pitch around X
      let y1 = vy * Math.cos(p) - vz * Math.sin(p);
      let z1 = vy * Math.sin(p) + vz * Math.cos(p);
      vy = y1; vz = z1;
      // Roll around Y
      let x2 = vx * Math.cos(r) + vz * Math.sin(r);
      let z2 = -vx * Math.sin(r) + vz * Math.cos(r);
      vx = x2; vz = z2;
      // Yaw around Z
      let x3 = vx * Math.cos(y) - vy * Math.sin(y);
      let y3 = vx * Math.sin(y) + vy * Math.cos(y);
      vx = x3; vy = y3;

      const scale = 280 / (280 + vz);
      return [cx + vx * scale, cy + vy * scale];
    }

    const proj = verts.map(rot);
    const edges = [
      [0,1],[1,2],[2,3],[3,0],
      [4,5],[5,6],[6,7],[7,4],
      [0,4],[1,5],[2,6],[3,7]
    ];

    // Screen fill on front face
    ctx.fillStyle = 'rgba(56, 189, 248, 0.14)';
    ctx.beginPath();
    ctx.moveTo(proj[4][0], proj[4][1]);
    ctx.lineTo(proj[5][0], proj[5][1]);
    ctx.lineTo(proj[6][0], proj[6][1]);
    ctx.lineTo(proj[7][0], proj[7][1]);
    ctx.closePath();
    ctx.fill();

    ctx.strokeStyle = C.cyan;
    ctx.lineWidth = 2;
    edges.forEach(([a, b]) => {
      ctx.beginPath();
      ctx.moveTo(proj[a][0], proj[a][1]);
      ctx.lineTo(proj[b][0], proj[b][1]);
      ctx.stroke();
    });

    // Top notch indicator
    ctx.fillStyle = C.teal;
    const midTopX = (proj[4][0] + proj[5][0]) / 2;
    const midTopY = (proj[4][1] + proj[5][1]) / 2;
    ctx.beginPath();
    ctx.arc(midTopX, midTopY, 4, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawGGCircle(canvas, longG, latG, trail, thresholdG = 0.35) {
    syncTheme();
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const cx = w / 2, cy = h / 2;
    const radius = Math.min(w, h) * 0.40;
    const maxG = 0.7;

    ctx.font = '10px monospace';
    ctx.fillStyle = C.slate;
    [0.2, 0.4, 0.6].forEach((g) => {
      const r = (g / maxG) * radius;
      ctx.strokeStyle = C.border;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.stroke();
      ctx.fillText(`${g}g`, cx + 3, cy - r + 11);
    });

    // Threshold circle
    ctx.strokeStyle = 'rgba(251,191,36,0.55)';
    ctx.setLineDash([5, 4]);
    ctx.beginPath();
    ctx.arc(cx, cy, (thresholdG / maxG) * radius, 0, Math.PI * 2);
    ctx.stroke();
    ctx.setLineDash([]);

    // Axes
    ctx.strokeStyle = C.border;
    ctx.beginPath();
    ctx.moveTo(cx - radius, cy); ctx.lineTo(cx + radius, cy);
    ctx.moveTo(cx, cy - radius); ctx.lineTo(cx, cy + radius);
    ctx.stroke();

    ctx.fillText('ACCEL', cx - 16, cy - radius - 5);
    ctx.fillText('BRAKE', cx - 16, cy + radius + 13);
    ctx.fillText('L', cx - radius - 12, cy + 3);
    ctx.fillText('R', cx + radius + 5, cy + 3);

    // Fading trail
    const recent = trail.slice(-110);
    for (let i = 1; i < recent.length; i++) {
      const p0 = recent[i - 1], p1 = recent[i];
      const x0 = cx + Math.max(-1, Math.min(1, p0.latG / maxG)) * radius;
      const y0 = cy - Math.max(-1, Math.min(1, p0.longG / maxG)) * radius;
      const x1 = cx + Math.max(-1, Math.min(1, p1.latG / maxG)) * radius;
      const y1 = cy - Math.max(-1, Math.min(1, p1.longG / maxG)) * radius;
      ctx.strokeStyle = `rgba(45,212,191,${(i / recent.length).toFixed(2)})`;
      ctx.lineWidth = 2;
      ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x1, y1); ctx.stroke();
    }

    const curX = cx + Math.max(-1, Math.min(1, latG / maxG)) * radius;
    const curY = cy - Math.max(-1, Math.min(1, longG / maxG)) * radius;
    const mag = Math.sqrt(latG * latG + longG * longG);
    const col = mag >= thresholdG ? C.red : mag >= thresholdG * 0.65 ? C.amber : C.green;

    ctx.strokeStyle = col;
    ctx.lineWidth = 3;
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(curX, curY); ctx.stroke();
    ctx.fillStyle = col;
    ctx.beginPath(); ctx.arc(curX, curY, 6, 0, Math.PI * 2); ctx.fill();
  }

  function drawOscilloscope(canvas, samples, mode) {
    syncTheme();
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const padL = 38, plotW = w - padL - 8, cy = h / 2;
    const yRange = mode === 'raw' ? 2.0 : 0.8;

    ctx.font = '10px monospace';
    ctx.fillStyle = C.slate;
    [-yRange, 0, yRange].forEach((v) => {
      const y = cy - (v / yRange) * (h * 0.42);
      ctx.strokeStyle = v === 0 ? 'rgba(148,163,184,0.4)' : C.border;
      ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(padL + plotW, y); ctx.stroke();
      ctx.fillText(v.toFixed(1), 4, y + 3);
    });

    if (samples.length < 2) return;

    function series(color, width, fn) {
      ctx.strokeStyle = color;
      ctx.lineWidth = width;
      ctx.beginPath();
      samples.forEach((s, i) => {
        const x = padL + (i / (samples.length - 1)) * plotW;
        const val = Math.max(-yRange, Math.min(yRange, fn(s)));
        const y = cy - (val / yRange) * (h * 0.42);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }

    if (mode === 'raw') {
      series(C.red, 1.8, (s) => s.rawPhoneX || 0);
      series(C.green, 1.8, (s) => s.rawPhoneY || 0);
      series(C.cyan, 1.8, (s) => s.rawPhoneZ || 0);
    } else if (mode === 'compare') {
      series('rgba(148,163,184,0.55)', 1.5, (s) => s.rawLongG || 0);
      series(C.cyan, 2.6, (s) => s.longG || 0);
    } else {
      series(C.cyan, 2.2, (s) => s.longG || 0);
      series(C.amber, 2.0, (s) => s.latG || 0);
      series(C.purple, 1.4, (s) => s.vertG || 0);
    }
  }

  function drawReplayMap(canvas, track, events, activeIndex) {
    syncTheme();
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    const valid = track.filter((p) => p.lat !== 0 || p.lon !== 0);
    if (valid.length < 2) {
      ctx.fillStyle = C.slate;
      ctx.font = '12px sans-serif';
      ctx.fillText('Load a trip JSON/CSV with GPS track points to view route map.', 24, h / 2);
      return;
    }

    const refLat = valid[0].lat, refLon = valid[0].lon;
    const cosLat = Math.cos((refLat * Math.PI) / 180);
    const toM = (lat, lon) => [
      (lon - refLon) * 111111 * cosLat,
      (lat - refLat) * 111111
    ];

    const pts = valid.map((p) => ({ m: toM(p.lat, p.lon), spd: p.speedMph }));
    const minX = Math.min(...pts.map((p) => p.m[0]));
    const maxX = Math.max(...pts.map((p) => p.m[0]));
    const minY = Math.min(...pts.map((p) => p.m[1]));
    const maxY = Math.max(...pts.map((p) => p.m[1]));

    const spanX = Math.max(20, maxX - minX);
    const spanY = Math.max(20, maxY - minY);
    const pad = 26;
    const scale = Math.min((w - pad * 2) / spanX, (h - pad * 2) / spanY);
    const offX = (w - spanX * scale) / 2;
    const offY = (h - spanY * scale) / 2;
    const toScr = (e, n) => [offX + (e - minX) * scale, h - offY - (n - minY) * scale];

    for (let i = 1; i < pts.length; i++) {
      const a = toScr(pts[i - 1].m[0], pts[i - 1].m[1]);
      const b = toScr(pts[i].m[0], pts[i].m[1]);
      const spd = pts[i].spd;
      ctx.strokeStyle = spd >= 60 ? C.red : spd >= 40 ? C.amber : spd >= 20 ? C.teal : C.cyan;
      ctx.lineWidth = 4;
      ctx.lineCap = 'round';
      ctx.beginPath(); ctx.moveTo(a[0], a[1]); ctx.lineTo(b[0], b[1]); ctx.stroke();
    }

    const cur = valid[Math.min(activeIndex, valid.length - 1)];
    const [ce, cn] = toM(cur.lat, cur.lon);
    const [sx, sy] = toScr(ce, cn);
    ctx.fillStyle = C.white;
    ctx.beginPath(); ctx.arc(sx, sy, 7, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = C.cyan;
    ctx.beginPath(); ctx.arc(sx, sy, 4, 0, Math.PI * 2); ctx.fill();
  }

  function drawEventZoom(canvas, samples) {
    syncTheme();
    const ctx = canvas.getContext('2d');
    const w = canvas.width, h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    if (!samples || samples.length < 2) {
      ctx.fillStyle = C.slate;
      ctx.font = '11px monospace';
      ctx.fillText('No 5s IMU snippet for this event', 16, h / 2);
      return;
    }

    const padL = 36, plotW = w - padL - 8, cy = h / 2;
    const maxG = Math.max(0.6, ...samples.map((s) => Math.max(Math.abs(s.longG), Math.abs(s.latG))) ) * 1.15;

    ctx.strokeStyle = 'rgba(148,163,184,0.4)';
    ctx.beginPath(); ctx.moveTo(padL, cy); ctx.lineTo(padL + plotW, cy); ctx.stroke();
    ctx.fillStyle = C.slate; ctx.font = '10px monospace';
    ctx.fillText(`+${maxG.toFixed(1)}`, 3, 14);
    ctx.fillText(`-${maxG.toFixed(1)}`, 3, h - 6);

    function drawLine(col, fn) {
      ctx.strokeStyle = col; ctx.lineWidth = 2.2; ctx.beginPath();
      samples.forEach((s, i) => {
        const x = padL + (i / (samples.length - 1)) * plotW;
        const y = cy - (fn(s) / maxG) * (h * 0.42);
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();
    }
    drawLine(C.cyan, (s) => s.longG);
    drawLine(C.amber, (s) => s.latG);
  }

  return {
    drawRoadCourse,
    draw3DPhone,
    drawGGCircle,
    drawOscilloscope,
    drawReplayMap,
    drawEventZoom
  };
})();
