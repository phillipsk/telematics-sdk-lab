(function () {
  'use strict';

  const E = window.TelematicsEngine;
  const Charts = window.WorkbenchCharts;
  const USB_BASE = 'http://127.0.0.1:8765';

  // App Mode State
  let activeMode = 'sim'; // 'sim' | 'usb' | 'replay'
  let waveMode = 'vehicle';
  let filterMode = 'STANDARD_LOW_PASS';
  let lowPassAlpha = 0.24;
  let fifoLatencyMs = 0;
  let legacyWakeLock = false;

  // Shared SDK Engines for Simulator
  const bridge = new E.SensorEngineBridge();
  const mapper = new E.CoordinateFrameMapper(bridge);
  const wakePolicy = new E.WakeLockPolicyEngine();
  const fsm = new E.TripStateMachine(10000);
  const ringBuffer = [];
  const detector = new E.DrivingEventDetector(ringBuffer);

  // Simulator World State
  const waypoints = [
    { x: 110, y: 75 },
    { x: 590, y: 75 },
    { x: 635, y: 120 },
    { x: 635, y: 270 },
    { x: 590, y: 315 },
    { x: 110, y: 315 },
    { x: 65, y: 270 },
    { x: 65, y: 120 }
  ];

  const sim = {
    waypoints,
    wpIndex: 0,
    carX: 110,
    carY: 75,
    carAngleRad: 0,
    speedMph: 22,
    distanceMiles: 0,
    autoCruise: true,
    pitchDeg: 55,
    rollDeg: 10,
    yawDeg: 25,
    trafficLight: 'GREEN',
    lightTimer: 0,
    inTunnel: false,
    handheldUntilMs: 0,
    overrideBrakeUntilMs: 0,
    overrideTurnUntilMs: 0,
    overrideTurnSign: -1,
    tunnelForceUntilMs: 0,
    stopSignalUntilMs: 0,
    vehicleSkin: 'ev_suv',
    currentLongG: 0,
    currentLatG: 0,
    prevCarX: 110,
    prevCarY: 75,
    skidMarks: [],
    trackMarkers: [],
    events: [],
    selectedEventId: null,
    manualLockMs: 0,
    queuedChunks: 0,
    lastBearingDeg: 90,
    tripStartMs: Date.now()
  };

  // Keyboard controls for manual driving
  const keys = {};
  window.addEventListener('keydown', (ev) => {
    if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Space'].includes(ev.code)) {
      keys[ev.code] = true;
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Space'].includes(ev.code)) {
        if (activeMode === 'sim' && document.activeElement.tagName !== 'INPUT') ev.preventDefault();
      }
    }
    if (['KeyW', 'KeyA', 'KeyS', 'KeyD'].includes(ev.code)) keys[ev.code] = true;
  });
  window.addEventListener('keyup', (ev) => { keys[ev.code] = false; });

  // USB Stream State
  let usbEventSource = null;
  let usbConnected = false;
  let usbLatestState = null;
  const usbWaveform = [];

  // Replay State
  let replayTrip = null;
  let replayPlaying = false;
  let replayIdx = 0;
  let replayTimer = null;

  // DOM helpers
  const $ = (id) => document.getElementById(id);

  // System Light / Dark Theme + Manual Auto/Light/Dark Cycle
  const THEME_MODES = ['auto', 'light', 'dark'];
  let themeMode = localStorage.getItem('wb_theme') || 'auto';
  if (!THEME_MODES.includes(themeMode)) themeMode = 'auto';

  function applyWorkbenchTheme() {
    const root = document.documentElement;
    if (themeMode === 'auto') {
      root.removeAttribute('data-theme');
    } else {
      root.setAttribute('data-theme', themeMode);
    }
    const label = themeMode === 'auto' ? 'Auto' : themeMode === 'light' ? 'Light' : 'Dark';
    const btn = $('themeToggleBtn');
    if (btn) btn.textContent = `Theme: ${label}`;
  }

  applyWorkbenchTheme();
  $('themeToggleBtn')?.addEventListener('click', () => {
    const idx = THEME_MODES.indexOf(themeMode);
    themeMode = THEME_MODES[(idx + 1) % THEME_MODES.length];
    localStorage.setItem('wb_theme', themeMode);
    applyWorkbenchTheme();
  });

  // Mode Switching
  document.querySelectorAll('.mode-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      activeMode = btn.dataset.mode;
      document.querySelectorAll('.mode-btn').forEach((b) => b.classList.toggle('active', b === btn));
      $('leftSimPanel').classList.toggle('hidden', activeMode !== 'sim');
      $('leftUsbPanel').classList.toggle('hidden', activeMode !== 'usb');
      $('leftReplayPanel').classList.toggle('hidden', activeMode !== 'replay');
      updateConnectionPill();
      if (activeMode === 'usb' && !usbConnected) connectUsbStream();
      if (activeMode === 'replay') fetchCloudTrips();
    });
  });

  function updateConnectionPill() {
    const pill = $('connectionPill');
    if (activeMode === 'sim') {
      pill.textContent = 'OFFLINE SIM READY';
      pill.className = 'pill pill-cyan';
    } else if (activeMode === 'usb') {
      pill.textContent = usbConnected ? 'USB STREAM LIVE' : 'USB DISCONNECTED';
      pill.className = usbConnected ? 'pill pill-green' : 'pill pill-amber';
    } else {
      pill.textContent = replayTrip ? `REPLAY: ${replayTrip.id}` : 'REPLAY MODE';
      pill.className = 'pill pill-teal';
    }
  }

  // Simulator Controls
  $('autoCruiseBtn').addEventListener('click', () => {
    sim.autoCruise = !sim.autoCruise;
    $('autoCruiseBtn').textContent = `Auto-Cruise: ${sim.autoCruise ? 'ON' : 'OFF (WASD)'}`;
  });

  $('resetSimBtn').addEventListener('click', () => {
    sim.carX = 110; sim.carY = 75; sim.prevCarX = 110; sim.prevCarY = 75; sim.wpIndex = 0; sim.carAngleRad = 0;
    sim.speedMph = 0; sim.distanceMiles = 0; sim.events = []; sim.trackMarkers = []; sim.skidMarks = [];
    sim.manualLockMs = 0; sim.queuedChunks = 0; sim.tripStartMs = Date.now();
    ringBuffer.length = 0;
    fsm.reset();
    mapper.reset();
    detector.reset(sim.tripStartMs);
    renderEventsFeed(sim.events);
  });

  document.querySelectorAll('.skin-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      sim.vehicleSkin = btn.dataset.skin;
      document.querySelectorAll('.skin-btn').forEach((b) => b.classList.toggle('active', b === btn));
    });
  });

  // Tilt Sliders & Presets
  function syncTiltLabels() {
    $('pitchReadout').textContent = `${sim.pitchDeg}°`;
    $('rollReadout').textContent = `${sim.rollDeg}°`;
    $('yawReadout').textContent = `${sim.yawDeg}°`;
  }
  $('pitchSlider').addEventListener('input', (e) => { sim.pitchDeg = +e.target.value; syncTiltLabels(); });
  $('rollSlider').addEventListener('input', (e) => { sim.rollDeg = +e.target.value; syncTiltLabels(); });
  $('yawSlider').addEventListener('input', (e) => { sim.yawDeg = +e.target.value; syncTiltLabels(); });

  document.querySelectorAll('.preset-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      const p = btn.dataset.preset;
      if (p === 'dash') { sim.pitchDeg = 65; sim.rollDeg = 0; sim.yawDeg = 0; }
      if (p === 'cup') { sim.pitchDeg = 42; sim.rollDeg = 18; sim.yawDeg = 35; }
      if (p === 'pocket') { sim.pitchDeg = -25; sim.rollDeg = 72; sim.yawDeg = 115; }
      $('pitchSlider').value = sim.pitchDeg;
      $('rollSlider').value = sim.rollDeg;
      $('yawSlider').value = sim.yawDeg;
      syncTiltLabels();
    });
  });

  // Edge Scenario Injectors
  document.querySelectorAll('[data-scenario]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const now = Date.now();
      const sc = btn.dataset.scenario;
      if (fsm.state === 'IDLE') fsm.forceRecording(now, 'Scenario started recording');

      if (sc === 'hard_brake') {
        sim.speedMph = Math.max(sim.speedMph, 36);
        sim.overrideBrakeUntilMs = now + 900;
      } else if (sc === 'harsh_turn') {
        sim.speedMph = Math.max(sim.speedMph, 28);
        sim.overrideTurnUntilMs = now + 900;
        sim.overrideTurnSign = -1;
      } else if (sc === 'phone_drop') {
        const res = detector.evaluateStage2Impact(now, 4.5, 8.9, sim.speedMph, sim.speedMph);
        pushSimEvent(res.event);
      } else if (sc === 'collision') {
        const pre = Math.max(32, sim.speedMph);
        sim.speedMph = 0;
        const res = detector.evaluateStage2Impact(now, 4.8, 2.1, pre, 0);
        pushSimEvent(res.event);
        if (wakePolicy.evaluateEventBurst(res.event.type)) {
          sim.manualLockMs += 15000;
          $('wakeLockDecisionText').textContent = 'EventSink Decorator: POTENTIAL_IMPACT -> 15s timed lock';
        }
      } else if (sc === 'tunnel') {
        sim.tunnelForceUntilMs = now + 5000;
      } else if (sc === 'red_light') {
        sim.stopSignalUntilMs = now + 5000;
      } else if (sc === 'handheld') {
        sim.speedMph = Math.max(sim.speedMph, 25);
        sim.handheldUntilMs = now + 2600;
      }
    });
  });

  function pushSimEvent(ev) {
    sim.events.unshift(ev);
    sim.selectedEventId = ev.id;
    sim.trackMarkers.push({ x: sim.carX, y: sim.carY, type: ev.type });
    if (sim.trackMarkers.length > 40) sim.trackMarkers.shift();
    renderEventsFeed(sim.events);
  }

  // Right Pane Controls
  document.querySelectorAll('[data-wmode]').forEach((btn) => {
    btn.addEventListener('click', () => {
      waveMode = btn.dataset.wmode;
      document.querySelectorAll('[data-wmode]').forEach((b) => b.classList.toggle('active', b === btn));
    });
  });

  document.querySelectorAll('.engine-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      filterMode = btn.dataset.engine;
      document.querySelectorAll('.engine-btn').forEach((b) => b.classList.toggle('active', b === btn));
    });
  });

  $('alphaSlider').addEventListener('input', (e) => {
    lowPassAlpha = parseFloat(e.target.value);
    $('alphaVal').textContent = lowPassAlpha.toFixed(2);
  });

  document.querySelectorAll('.fifo-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      fifoLatencyMs = parseInt(btn.dataset.fifo, 10);
      document.querySelectorAll('.fifo-btn').forEach((b) => b.classList.toggle('active', b === btn));
    });
  });

  $('legacyWakeLockToggle').addEventListener('change', (e) => {
    legacyWakeLock = e.target.checked;
  });

  // Live USB Bridge
  $('usbReconnectBtn').addEventListener('click', connectUsbStream);
  $('pullTripsBtn').addEventListener('click', fetchPhoneTrips);

  function connectUsbStream() {
    if (usbEventSource) {
      usbEventSource.close();
      usbEventSource = null;
    }
    const banner = $('usbBanner');
    banner.className = 'callout callout-info';
    banner.textContent = 'Connecting to http://127.0.0.1:8765/stream ...';

    try {
      const es = new EventSource(`${USB_BASE}/stream`);
      usbEventSource = es;

      es.onopen = () => {
        usbConnected = true;
        banner.className = 'callout callout-ok';
        banner.textContent = 'Connected to Android USB stream! Tilt or move your phone to inspect live rotation & telemetry.';
        updateConnectionPill();
        fetchPhoneTrips();
      };

      es.onmessage = (msg) => {
        try {
          const data = JSON.parse(msg.data);
          usbLatestState = data;
          usbWaveform.push({
            timestampMs: data.timestampMs,
            longG: data.frame.filteredLongG,
            latG: data.frame.filteredLatG,
            vertG: data.frame.filteredVertG,
            rawLongG: data.frame.rawLongG,
            rawPhoneX: (data.frame.phoneLinearX || 0) / E.GRAVITY_MS2,
            rawPhoneY: (data.frame.phoneLinearY || 0) / E.GRAVITY_MS2,
            rawPhoneZ: (data.frame.phoneLinearZ || 0) / E.GRAVITY_MS2
          });
          if (usbWaveform.length > 220) usbWaveform.shift();
        } catch (_) {}
      };

      es.onerror = () => {
        usbConnected = false;
        updateConnectionPill();
        banner.className = 'callout callout-warn';
        banner.textContent = 'USB stream unreachable at 127.0.0.1:8765. Run "adb forward tcp:8765 tcp:8765" with Telematics Lab open on your phone, or use Offline Simulator mode.';
      };
    } catch (_) {
      usbConnected = false;
      updateConnectionPill();
    }
  }

  async function fetchPhoneTrips() {
    const container = $('phoneTripsList');
    try {
      const res = await fetch(`${USB_BASE}/trips`);
      const trips = await res.json();
      if (!trips.length) {
        container.innerHTML = '<p class="muted">No saved trips on phone yet. Run the 34s Demo Drive or drive a trip first.</p>';
        return;
      }
      container.innerHTML = '';
      trips.forEach((t) => {
        const div = document.createElement('div');
        div.className = 'trip-item';
        div.innerHTML = `
          <div>
            <strong>${t.isSimulated ? 'Demo' : 'Car'} Trip #${t.id}</strong>
            <div class="muted">${t.distanceMiles.toFixed(2)} mi • ${t.durationSec}s • Score ${t.score} • ${t.eventsCount} events</div>
          </div>
          <button type="button" class="btn btn-xs btn-primary">Load Replay</button>
        `;
        div.querySelector('button').addEventListener('click', async () => {
          const tripRes = await fetch(`${USB_BASE}/trip?id=${encodeURIComponent(t.id)}`);
          const rawText = await tripRes.text();
          loadTripForReplay(rawText, `phone_${t.id}.json`);
          document.querySelector('[data-mode="replay"]').click();
        });
        container.appendChild(div);
      });
    } catch (_) {
      container.innerHTML = '<p class="muted">Cannot reach phone at 127.0.0.1:8765.</p>';
    }
  }

  // Real Drive Replay Controls
  const dropZone = $('dropZone');
  const fileInput = $('tripFileInput');
  dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
  dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
  dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropZone.classList.remove('dragover');
    const f = e.dataTransfer.files[0];
    if (f) readTripFile(f);
  });
  fileInput.addEventListener('change', (e) => {
    if (e.target.files[0]) readTripFile(e.target.files[0]);
  });

  function readTripFile(file) {
    const reader = new FileReader();
    reader.onload = () => loadTripForReplay(String(reader.result || ''), file.name);
    reader.readAsText(file);
  }

  $('loadSampleReplayBtn').addEventListener('click', () => {
    const sampleTrack = [];
    let lat = 37.7755, lon = -122.4185;
    for (let i = 0; i < 40; i++) {
      const spd = i < 6 ? i * 5 : i < 20 ? 30 + Math.sin(i * 0.4) * 5 : i < 28 ? Math.max(0, 32 - (i - 20) * 4) : i < 34 ? (i - 28) * 4 : Math.max(0, 24 - (i - 34) * 4);
      const longG = i >= 20 && i <= 23 ? -0.44 : i >= 2 && i <= 4 ? 0.34 : 0.02;
      const latG = i >= 12 && i <= 15 ? -0.41 : 0.01;
      lat += (spd * 0.447 / 111111) * Math.cos((i * 3 * Math.PI) / 180);
      lon += (spd * 0.447 / 82000) * Math.sin((i * 3 * Math.PI) / 180);
      sampleTrack.push({
        es: i, ts: 1700000000000 + i * 1000, lat, lon, sp: spd, br: (i * 3) % 360,
        ac: 4.2, sat: 14, lg: longG, lt: latG, vt: 0.01, rt: 1.0, hh: i >= 16 && i <= 18
      });
    }
    const sampleJson = JSON.stringify({
      id: 'sample-sf-demo',
      durationSec: 40,
      distanceMiles: 0.31,
      rawPayloadBytes: 24500,
      gzipPayloadBytes: 5800,
      idempotentChunkCount: 1,
      score: { overall: 84, braking: 86, acceleration: 88, cornering: 86, speeding: 100, distraction: 80 },
      track: sampleTrack,
      events: [
        { id: 'ev1', type: 'HARSH_CORNER_LEFT', displayName: 'Harsh Left Turn', elapsedTripSec: 14, details: '0.41g lateral cornering', waveformSnippet: sampleTrack.slice(10, 18).map((p) => ({ ts: p.ts, lg: p.lg, lt: p.lt })) },
        { id: 'ev2', type: 'HARD_BRAKE', displayName: 'Hard Brake', elapsedTripSec: 22, details: '-0.44g braking', waveformSnippet: sampleTrack.slice(18, 26).map((p) => ({ ts: p.ts, lg: p.lg, lt: p.lt })) }
      ]
    });
    loadTripForReplay(sampleJson, 'sample-sf-demo.json');
  });

  function loadTripForReplay(rawText, name) {
    replayTrip = E.parseImportedTrip(rawText, name);
    replayIdx = 0;
    replayPlaying = false;
    $('replayPlayBtn').textContent = 'Play';
    $('replayScrub').max = Math.max(0, replayTrip.track.length - 1);
    $('replayScrub').value = 0;
    const rawObj = rawText.trim().startsWith('{') ? JSON.parse(rawText) : {};
    const label = rawObj.tripLabel || replayTrip.id;
    const mode = rawObj.transportMode || 'CAR';
    const seat = rawObj.seatRole || 'N/A';
    const rmsG = Number(rawObj.avgVertRmsG || 0);
    const rmsText = rmsG > 0 ? ` • Vert RMS ${rmsG.toFixed(3)}g` : '';
    $('replayMetaVal').textContent = `${label} (${mode} / ${seat}${rmsText})`;
    $('replayScoreVal').textContent = `${replayTrip.score.overall}`;
    $('replayDistVal').textContent = `${replayTrip.distanceMiles.toFixed(2)} mi`;
    const saved = replayTrip.rawPayloadBytes > 0 ? Math.round(100 - (replayTrip.gzipPayloadBytes * 100 / replayTrip.rawPayloadBytes)) : 0;
    $('replayCodecVal').textContent = `${(replayTrip.gzipPayloadBytes / 1024).toFixed(1)} KB (-${saved}%)`;
    renderEventsFeed(replayTrip.events);
    updateConnectionPill();
  }

  $('refreshCloudTripsBtn').addEventListener('click', fetchCloudTrips);

  async function fetchCloudTrips() {
    const box = $('cloudTripsList');
    if (window.location.protocol === 'file:') {
      box.innerHTML = '<p class="muted">Open via the Cloud Run HTTPS URL (or local server) to browse GCS-archived trips.</p>';
      return;
    }
    try {
      const res = await fetch('/api/trips');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const list = await res.json();
      if (!list.length) {
        box.innerHTML = '<p class="muted">No cloud-synced trips yet. Record a trip on your phone and it will appear here automatically.</p>';
        return;
      }
      box.innerHTML = '';
      list.forEach((t) => {
        const div = document.createElement('div');
        div.className = 'trip-item';
        const when = t.startTimeMs ? new Date(t.startTimeMs).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
        const rmsTxt = Number(t.avgVertRmsG || 0) > 0 ? ` • RMS ${Number(t.avgVertRmsG).toFixed(3)}g` : '';
        div.innerHTML = `
          <div>
            <strong>${t.tripLabel || t.id}</strong> <span class="pill pill-cyan">${t.transportMode || 'CAR'}</span> <span class="pill pill-teal">${t.seatRole || 'ROLE TBD'}</span>
            <div class="muted">${when ? `${when} • ` : ''}${Number(t.distanceMiles || 0).toFixed(2)} mi • ${t.durationSec}s • Score ${t.score} • ${t.eventsCount} events${rmsTxt}</div>
          </div>
          <div class="row gap-xs">
            <a class="btn btn-xs btn-ghost" href="/api/trips/${encodeURIComponent(t.id)}/download?format=json">JSON</a>
            <a class="btn btn-xs btn-ghost" href="/api/trips/${encodeURIComponent(t.id)}/download?format=csv">CSV</a>
            <a class="btn btn-xs btn-ghost" href="/api/trips/${encodeURIComponent(t.id)}/download?format=gpx">GPX</a>
            <button type="button" class="btn btn-xs btn-primary">Replay</button>
          </div>
        `;
        div.querySelector('button').addEventListener('click', async () => {
          const tripRes = await fetch(`/api/trips/${encodeURIComponent(t.id)}`);
          const raw = await tripRes.text();
          loadTripForReplay(raw, `${t.id}.json`);
        });
        box.appendChild(div);
      });
    } catch (_) {
      box.innerHTML = '<p class="muted">Cloud archive unavailable in local file:// view.</p>';
    }
  }

  $('replayPlayBtn').addEventListener('click', () => {
    if (!replayTrip || !replayTrip.track.length) return;
    replayPlaying = !replayPlaying;
    $('replayPlayBtn').textContent = replayPlaying ? 'Pause' : 'Play';
  });

  $('replayScrub').addEventListener('input', (e) => {
    replayIdx = parseInt(e.target.value, 10) || 0;
  });

  setInterval(() => {
    if (activeMode === 'replay' && replayPlaying && replayTrip && replayTrip.track.length > 0) {
      const mult = parseInt($('replaySpeedSelect').value, 10) || 1;
      replayIdx = (replayIdx + mult) % replayTrip.track.length;
      $('replayScrub').value = replayIdx;
    }
  }, 250);

  // Event Feed Rendering
  function renderEventsFeed(events) {
    const feed = $('eventsFeed');
    if (!events || !events.length) {
      feed.innerHTML = '<p class="muted">No events recorded yet. Trigger a scenario or drive around the track.</p>';
      return;
    }
    feed.innerHTML = '';
    events.slice(0, 15).forEach((ev, idx) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      const isSelected = sim.selectedEventId === ev.id || (!sim.selectedEventId && idx === 0);
      btn.className = `event-row${isSelected ? ' selected' : ''}`;
      btn.innerHTML = `
        <div class="ev-title"><span>${ev.displayName}</span><span class="mono">@${Math.round(ev.elapsedTripSec || 0)}s</span></div>
        <div class="ev-sub">${ev.details || ''}</div>
      `;
      btn.addEventListener('click', () => {
        sim.selectedEventId = ev.id;
        feed.querySelectorAll('.event-row').forEach((r) => r.classList.remove('selected'));
        btn.classList.add('selected');
        $('zoomTitle').textContent = ev.displayName;
        $('zoomDetails').textContent = ev.details || '';
        Charts.drawEventZoom($('eventZoomCanvas'), ev.waveformSnippet || []);
      });
      feed.appendChild(btn);
      if (isSelected) {
        $('zoomTitle').textContent = ev.displayName;
        $('zoomDetails').textContent = ev.details || '';
        Charts.drawEventZoom($('eventZoomCanvas'), ev.waveformSnippet || []);
      }
    });
  }

  // Main 25 Hz Physics + Rendering Loop
  let lastTickMs = performance.now();
  function tick() {
    const nowPerf = performance.now();
    const dt = Math.min(0.08, (nowPerf - lastTickMs) / 1000);
    lastTickMs = nowPerf;
    const nowMs = Date.now();

    // Update Simulator Physics
    sim.lightTimer += dt;
    const cycle = sim.lightTimer % 16;
    sim.trafficLight = cycle < 9 ? 'GREEN' : cycle < 11.5 ? 'YELLOW' : 'RED';

    let targetLongG = 0;
    let targetLatG = 0;
    let steerRate = 0;

    if (sim.autoCruise) {
      const target = waypoints[sim.wpIndex];
      const dx = target.x - sim.carX;
      const dy = target.y - sim.carY;
      const dist = Math.hypot(dx, dy);
      if (dist < 16) {
        sim.wpIndex = (sim.wpIndex + 1) % waypoints.length;
      }
      const desiredAngle = Math.atan2(dy, dx);
      let diff = desiredAngle - sim.carAngleRad;
      while (diff > Math.PI) diff -= Math.PI * 2;
      while (diff < -Math.PI) diff += Math.PI * 2;
      steerRate = Math.max(-2.4, Math.min(2.4, diff * 4.5));
      sim.carAngleRad += steerRate * dt;

      const nearRedLight = sim.trafficLight === 'RED' && Math.hypot(sim.carX - 350, sim.carY - 315) < 75;
      if (nowMs < sim.overrideBrakeUntilMs) {
        targetLongG = -0.46;
      } else if (nowMs < sim.stopSignalUntilMs || nearRedLight) {
        targetLongG = sim.speedMph > 1 ? -0.26 : 0;
      } else if (sim.speedMph < 28) {
        targetLongG = 0.22;
      } else {
        targetLongG = 0.02 * Math.sin(nowPerf * 0.002);
      }

      if (nowMs < sim.overrideTurnUntilMs) {
        targetLatG = sim.overrideTurnSign * 0.45;
      } else {
        targetLatG = (sim.speedMph * 0.44704 * steerRate) / E.GRAVITY_MS2;
      }
    } else {
      const accelKey = keys.KeyW || keys.ArrowUp;
      const brakeKey = keys.KeyS || keys.ArrowDown || keys.Space;
      const leftKey = keys.KeyA || keys.ArrowLeft;
      const rightKey = keys.KeyD || keys.ArrowRight;

      targetLongG = keys.Space ? -0.50 : brakeKey ? -0.38 : accelKey ? 0.34 : (sim.speedMph > 1 ? -0.04 : 0);
      steerRate = (leftKey ? -1.8 : 0) + (rightKey ? 1.8 : 0);
      if (sim.speedMph > 1) sim.carAngleRad += steerRate * dt;
      targetLatG = (sim.speedMph * 0.44704 * steerRate) / E.GRAVITY_MS2;
    }

    const skinAccelMul = sim.vehicleSkin === 'coupe' ? 1.2 : sim.vehicleSkin === 'fleet_van' ? 0.85 : 1.0;
    const maxMps = sim.vehicleSkin === 'coupe' ? 38 : sim.vehicleSkin === 'fleet_van' ? 29 : 34;
    const nextMps = Math.max(0, Math.min(maxMps, sim.speedMph * 0.44704 + targetLongG * skinAccelMul * E.GRAVITY_MS2 * dt));
    sim.speedMph = nextMps / 0.44704;
    sim.distanceMiles += (nextMps * dt) / 1609.344;
    sim.prevCarX = sim.carX;
    sim.prevCarY = sim.carY;

    if (sim.autoCruise) {
      sim.carX += Math.cos(sim.carAngleRad) * (nextMps * 2.1 * dt);
      sim.carY += Math.sin(sim.carAngleRad) * (nextMps * 2.1 * dt);
    } else {
      sim.carX = Math.max(40, Math.min(660, sim.carX + Math.cos(sim.carAngleRad) * (nextMps * 2.4 * dt)));
      sim.carY = Math.max(40, Math.min(350, sim.carY + Math.sin(sim.carAngleRad) * (nextMps * 2.4 * dt)));
    }

    sim.currentLongG = targetLongG;
    sim.currentLatG = targetLatG;
    if ((targetLongG <= -0.32 || Math.abs(targetLatG) >= 0.34) && sim.speedMph >= 6) {
      sim.skidMarks.push({ x0: sim.prevCarX, y0: sim.prevCarY, x1: sim.carX, y1: sim.carY, alpha: 0.75 });
      if (sim.skidMarks.length > 90) sim.skidMarks.shift();
    }
    sim.skidMarks.forEach((sk) => { sk.alpha *= 0.992; });
    sim.skidMarks = sim.skidMarks.filter((sk) => sk.alpha > 0.08);

    sim.inTunnel = (sim.carX >= 220 && sim.carX <= 460 && sim.carY >= 32 && sim.carY <= 118) || (nowMs < sim.tunnelForceUntilMs);
    const bearingDeg = ((sim.carAngleRad * 180 / Math.PI) + 90 + 360) % 360;
    sim.lastBearingDeg = bearingDeg;

    const gnssAcc = sim.inTunnel ? 120 : 4.2;
    const gnssSats = sim.inTunnel ? 1 : 14;
    fsm.update(nowMs, sim.speedMph, gnssAcc);

    // Rotate desired vehicle G -> World ENU -> Phone Frame
    const th = bearingDeg * Math.PI / 180;
    const noiseLong = (Math.random() - 0.5) * 0.06;
    const noiseLat = (Math.random() - 0.5) * 0.06;
    const longMs2 = (targetLongG + noiseLong) * E.GRAVITY_MS2;
    const latMs2 = (targetLatG + noiseLat) * E.GRAVITY_MS2;
    const aEast = longMs2 * Math.sin(th) + latMs2 * Math.cos(th);
    const aNorth = longMs2 * Math.cos(th) - latMs2 * Math.sin(th);
    const aUp = (Math.random() - 0.5) * 0.05 * E.GRAVITY_MS2;

    const rotM = E.buildRotationMatrix(
      (bearingDeg + sim.yawDeg) * Math.PI / 180,
      sim.pitchDeg * Math.PI / 180,
      sim.rollDeg * Math.PI / 180
    );
    const pLin = [
      rotM[0] * aEast + rotM[3] * aNorth + rotM[6] * aUp,
      rotM[1] * aEast + rotM[4] * aNorth + rotM[7] * aUp,
      rotM[2] * aEast + rotM[5] * aNorth + rotM[8] * aUp
    ];
    const pRaw = [
      pLin[0] + rotM[6] * E.GRAVITY_MS2,
      pLin[1] + rotM[7] * E.GRAVITY_MS2,
      pLin[2] + rotM[8] * E.GRAVITY_MS2
    ];

    const mapped = mapper.map(pLin, pRaw, rotM, sim.inTunnel ? null : bearingDeg, nextMps, lowPassAlpha, filterMode);
    ringBuffer.push({
      timestampMs: nowMs,
      longG: mapped.filteredLongG,
      latG: mapped.filteredLatG,
      vertG: mapped.filteredVertG,
      rawLongG: mapped.rawLongG,
      rawPhoneX: mapped.rawPhoneG[0],
      rawPhoneY: mapped.rawPhoneG[1],
      rawPhoneZ: mapped.rawPhoneG[2]
    });
    if (ringBuffer.length > 1500) ringBuffer.shift();

    const handheld = nowMs < sim.handheldUntilMs;
    if (fsm.state === 'RECORDING' || fsm.state === 'STOP_PENDING') {
      const newlyDetected = detector.process(nowMs, mapped.filteredLongG, mapped.filteredLatG, sim.speedMph, handheld);
      newlyDetected.forEach(pushSimEvent);
    }

    const lockDecision = wakePolicy.evaluateState(fsm.state, legacyWakeLock);
    if (lockDecision.shouldHold) sim.manualLockMs += dt * 1000;
    if (sim.inTunnel && fsm.state === 'RECORDING') {
      sim.queuedChunks = Math.min(24, sim.queuedChunks + (Math.random() < 0.08 ? 1 : 0));
    } else if (!sim.inTunnel && sim.queuedChunks > 0) {
      sim.queuedChunks = Math.max(0, sim.queuedChunks - 1);
    }

    // Render Active Mode
    renderActiveMode(mapped, gnssAcc, gnssSats, lockDecision);
  }

  function renderActiveMode(simMapped, gnssAcc, gnssSats, lockDecision) {
    if (activeMode === 'sim') {
      Charts.drawRoadCourse($('roadCourseCanvas'), sim);
      Charts.draw3DPhone($('phone3dCanvasSim'), sim.pitchDeg, sim.rollDeg, sim.yawDeg);

      $('simSpeedVal').textContent = `${Math.round(sim.speedMph)} mph`;
      $('simDistVal').textContent = `${sim.distanceMiles.toFixed(2)} mi`;
      $('simGnssVal').textContent = sim.inTunnel ? '1 / ±120m (LOST)' : `${gnssSats} / ±${gnssAcc.toFixed(0)}m`;
      $('simLightVal').textContent = sim.trafficLight;
      $('rawPhoneReadout').textContent = simMapped.rawPhoneG.map((v) => (v >= 0 ? '+' : '') + v.toFixed(2)).join(', ');
      $('carFrameReadout').textContent = `Long ${(simMapped.filteredLongG >= 0 ? '+' : '')}${simMapped.filteredLongG.toFixed(2)} | Lat ${(simMapped.filteredLatG >= 0 ? '+' : '')}${simMapped.filteredLatG.toFixed(2)}`;

      drawRightPane(
        fsm.state,
        fsm.reason,
        simMapped.filteredLongG,
        simMapped.filteredLatG,
        simMapped.rawTotalG,
        simMapped.headingDeg,
        ringBuffer.slice(-200),
        ringBuffer.length,
        sim.manualLockMs,
        fifoLatencyMs === 0 ? 3000 : fifoLatencyMs === 500 ? 120 : 30,
        lockDecision.reason,
        sim.inTunnel ? 'OFFLINE BUFFERING' : 'ONLINE',
        sim.queuedChunks,
        E.computeSafetyScore(sim.events, sim.distanceMiles)
      );
    } else if (activeMode === 'usb' && usbLatestState) {
      const s = usbLatestState;
      Charts.draw3DPhone($('phone3dCanvasUsb'), s.frame.pitchDeg, s.frame.rollDeg, 0);
      $('usbDeviceVal').textContent = s.deviceModel || 'Android';
      $('usbSpeedVal').textContent = `${Math.round(s.gnss.speedMph)} mph`;
      $('usbGnssVal').textContent = `${s.gnss.satellitesUsed}/${s.gnss.satellitesVisible} (±${Math.round(s.gnss.accuracyMeters)}m)`;
      $('usbTiltVal').textContent = `${Math.round(s.frame.pitchDeg)}° / ${Math.round(s.frame.rollDeg)}°`;
      $('usbMountVal').textContent = `${s.isHandheld ? 'Handheld' : 'Mounted'} / ${s.screenOn ? 'On' : 'Off'}`;
      $('usbHzVal').textContent = `${Math.round(s.power.imuSampleHz)} Hz`;

      renderEventsFeed(s.events || []);
      $('diagnosticsLine').textContent = `Init ${s.diagnostics.startupInitMs}ms | Exit ${s.diagnostics.lastExitReason} | Faults ${s.diagnostics.caughtBoundaryFaults} | Quarantine ${s.diagnostics.quarantinedFiles}`;

      drawRightPane(
        s.tripState,
        `Phone FGS uptime ${s.power.fgsUptimeSec}s`,
        s.frame.filteredLongG,
        s.frame.filteredLatG,
        s.frame.rawTotalG,
        s.frame.headingDeg,
        usbWaveform,
        s.power.ringBufferFill,
        s.power.manualWakeLockHeldMs,
        s.power.fifoBatchLatencyMs === 0 ? 3000 : 120,
        s.diagnostics.latestWakeLockDecision || 'Remote-config denylist active',
        s.gnss.hasFix ? 'GNSS LOCKED' : 'NO FIX',
        0,
        s.score
      );
    } else if (activeMode === 'replay' && replayTrip) {
      const tr = replayTrip.track;
      const pt = tr[Math.min(replayIdx, tr.length - 1)] || { speedMph: 0, longG: 0, latG: 0, rawTotalG: 1, bearingDeg: 0, elapsedSec: 0 };
      Charts.drawReplayMap($('replayMapCanvas'), tr, replayTrip.events, replayIdx);
      $('replayTimeVal').textContent = `${Math.round(pt.elapsedSec)}s / ${replayTrip.durationSec}s`;

      const windowWave = tr.slice(Math.max(0, replayIdx - 60), replayIdx + 1).map((p) => ({
        longG: p.longG, latG: p.latG, vertG: p.vertG, rawLongG: p.longG, rawPhoneX: 0, rawPhoneY: 0, rawPhoneZ: 0
      }));
      drawRightPane(
        'RECORDING',
        `Replay @ ${Math.round(pt.elapsedSec)}s (${Math.round(pt.speedMph)} mph)`,
        pt.longG,
        pt.latG,
        pt.rawTotalG,
        pt.bearingDeg,
        windowWave,
        tr.length,
        0,
        120,
        'Recorded trip replay',
        'ARCHIVE REPLAY',
        replayTrip.idempotentChunkCount,
        replayTrip.score
      );
    }
  }

  function drawRightPane(
    fsmState,
    fsmReason,
    longG,
    latG,
    totalG,
    headingDeg,
    waveSamples,
    ringFill,
    manualLockMs,
    wakeupsPerMin,
    wakeDecision,
    syncText,
    queuedChunks,
    score
  ) {
    $('fsmHeaderPill').textContent = fsmState;
    $('fsmReasonBadge').textContent = fsmReason;
    document.querySelectorAll('#fsmPipeline .fsm-node').forEach((n) => {
      n.classList.toggle('active', n.dataset.state === fsmState);
    });

    $('ggHeadingBadge').textContent = `Heading ${Math.round(headingDeg)}°`;
    Charts.drawGGCircle($('ggCircleCanvas'), longG, latG, waveSamples, 0.35);
    $('longGVal').textContent = `${longG >= 0 ? '+' : ''}${longG.toFixed(2)}g`;
    $('latGVal').textContent = `${latG >= 0 ? '+' : ''}${latG.toFixed(2)}g`;
    $('totalGVal').textContent = `${totalG.toFixed(2)}g`;

    Charts.drawOscilloscope($('oscilloscopeCanvas'), waveSamples, waveMode);

    $('wakeLockVal').textContent = `${(manualLockMs / 1000).toFixed(1)}s`;
    $('wakeLockVal').className = manualLockMs === 0 ? 'text-green' : 'text-red';
    $('wakeupsVal').textContent = `${wakeupsPerMin}`;
    const highRisk = legacyWakeLock || manualLockMs > 5000;
    $('vitalsRiskVal').textContent = highRisk ? 'HIGH' : 'SAFE';
    $('vitalsRiskVal').className = highRisk ? 'text-red' : 'text-green';
    $('wakeLockDecisionText').textContent = wakeDecision;

    $('ringFillVal').textContent = `${ringFill} / 1500`;
    $('queuedChunksVal').textContent = `${queuedChunks}`;
    $('syncStatusPill').textContent = syncText;
    $('syncStatusPill').className = syncText.includes('OFFLINE') || syncText.includes('NO') ? 'pill pill-amber' : 'pill pill-green';

    if (score) {
      $('overallScoreBadge').textContent = score.overall;
      $('scoreBrake').textContent = score.braking;
      $('scoreAccel').textContent = score.acceleration;
      $('scoreCorner').textContent = score.cornering;
      $('scoreSpeed').textContent = score.speeding;
      $('scoreFocus').textContent = score.distraction;
    }
  }

  // Remote SDK Config & Feature Flags
  async function loadRemoteConfig() {
    if (window.location.protocol === 'file:') return;
    try {
      const res = await fetch('/api/config');
      if (!res.ok) return;
      const cfg = await res.json();
      if ($('remoteConfigVerPill')) $('remoteConfigVerPill').textContent = `v${cfg.version || 1}`;
      if ($('rcAutoStartMph')) $('rcAutoStartMph').value = cfg.autoStartVerifyMph ?? 10;
      if ($('rcAutoStopMode')) $('rcAutoStopMode').value = cfg.autoStopMode || 'SMART_5M';
      if ($('rcStopHystSec')) $('rcStopHystSec').value = cfg.stopHysteresisSec ?? 300;
      if ($('rcFifoSelect')) $('rcFifoSelect').value = String(cfg.fifoBatchLatencyMs ?? 0);
      if ($('rcFilterSelect')) $('rcFilterSelect').value = cfg.filterEngineMode || 'STANDARD_LOW_PASS';
      if ($('rcAutoDetect')) $('rcAutoDetect').checked = Boolean(cfg.autoDetectEnabled);
      if ($('rcMotionPrompts')) $('rcMotionPrompts').checked = Boolean(cfg.motionPromptsEnabled);
      if ($('rcAutoStopNonCar')) $('rcAutoStopNonCar').checked = Boolean(cfg.autoStopNonCar);
      if ($('rcLegacyWakeLock')) $('rcLegacyWakeLock').checked = Boolean(cfg.legacyWakeLockEnabled);
    } catch (_) {
      // Ignore offline/local errors
    }
  }

  $('publishRemoteConfigBtn')?.addEventListener('click', async () => {
    if (window.location.protocol === 'file:') {
      $('remoteConfigStatus').textContent = 'Open via Cloud Run HTTPS URL to publish.';
      return;
    }
    const patch = {
      autoStartVerifyMph: Number($('rcAutoStartMph')?.value || 10),
      autoStopMode: $('rcAutoStopMode')?.value || 'SMART_5M',
      stopHysteresisSec: Number($('rcStopHystSec')?.value ?? 300),
      fifoBatchLatencyMs: Number($('rcFifoSelect')?.value || 0),
      filterEngineMode: $('rcFilterSelect')?.value || 'STANDARD_LOW_PASS',
      autoDetectEnabled: Boolean($('rcAutoDetect')?.checked),
      motionPromptsEnabled: Boolean($('rcMotionPrompts')?.checked),
      autoStopNonCar: Boolean($('rcAutoStopNonCar')?.checked),
      legacyWakeLockEnabled: Boolean($('rcLegacyWakeLock')?.checked)
    };
    try {
      const res = await fetch('/api/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch)
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const saved = await res.json();
      $('remoteConfigVerPill').textContent = `v${saved.version}`;
      $('remoteConfigStatus').textContent = `Published v${saved.version} to Cloud Storage — swipe down on phone Drive tab to pull.`;
    } catch (err) {
      $('remoteConfigStatus').textContent = `Publish failed: ${err.message}`;
    }
  });

  // Start loop
  loadRemoteConfig();
  syncTiltLabels();
  setInterval(tick, 40);
})();
