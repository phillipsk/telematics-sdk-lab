import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const DEFAULT_SHUTDOWN_AT_ISO = '2026-12-15T14:00:00Z';
const COOKIE_NAME = 'workbench_session';
const SESSION_MAX_AGE_SEC = 2592000;
const MAX_FORM_BYTES = 4096;
const MAX_CONFIG_JSON_BYTES = 16 * 1024;
const MAX_TRIP_JSON_BYTES = 12 * 1024 * 1024;

const DEFAULT_REMOTE_CONFIG = {
  version: 1,
  autoDetectEnabled: true,
  motionPromptsEnabled: true,
  autoStartVerifyMph: 10,
  autoStopMode: 'SMART_5M',
  stopHysteresisSec: 300,
  autoStopNonCar: false,
  filterEngineMode: 'STANDARD_LOW_PASS',
  lowPassAlpha: 0.22,
  hardBrakeG: 0.35,
  rapidAccelG: 0.30,
  harshCornerG: 0.35,
  speedingLimitMph: 70,
  impactThresholdG: 3.5,
  fifoBatchLatencyMs: 0,
  legacyWakeLockEnabled: false
};

function clampNum(val, fallback, min, max) {
  const n = Number(val);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
}

function sanitizeRemoteConfig(current, patch = {}) {
  const base = { ...DEFAULT_REMOTE_CONFIG, ...(current || {}) };
  const allowedFifo = [0, 500, 2000];
  const fifoRaw = Number(patch.fifoBatchLatencyMs ?? base.fifoBatchLatencyMs);
  const fifo = allowedFifo.includes(fifoRaw) ? fifoRaw : base.fifoBatchLatencyMs;
  const mode = patch.filterEngineMode === 'ADAPTIVE_DUAL_POLE' || (patch.filterEngineMode === undefined && base.filterEngineMode === 'ADAPTIVE_DUAL_POLE')
    ? 'ADAPTIVE_DUAL_POLE'
    : 'STANDARD_LOW_PASS';
  const rawStopMode = patch.autoStopMode ?? base.autoStopMode;
  const autoStopMode = ['MANUAL_ONLY', 'SMART_5M', 'QUICK_90S'].includes(rawStopMode) ? rawStopMode : 'SMART_5M';
  const stopHyst = autoStopMode === 'MANUAL_ONLY'
    ? 0
    : Math.round(clampNum(patch.stopHysteresisSec ?? base.stopHysteresisSec, base.stopHysteresisSec || 300, 30, 600));

  return {
    version: Math.max(1, Number(base.version || 1) + 1),
    updatedAtIso: new Date().toISOString(),
    autoDetectEnabled: typeof patch.autoDetectEnabled === 'boolean' ? patch.autoDetectEnabled : Boolean(base.autoDetectEnabled),
    motionPromptsEnabled: typeof patch.motionPromptsEnabled === 'boolean' ? patch.motionPromptsEnabled : Boolean(base.motionPromptsEnabled),
    autoStartVerifyMph: Math.round(clampNum(patch.autoStartVerifyMph ?? base.autoStartVerifyMph, base.autoStartVerifyMph, 5, 35)),
    autoStopMode,
    stopHysteresisSec: stopHyst,
    autoStopNonCar: typeof patch.autoStopNonCar === 'boolean' ? patch.autoStopNonCar : Boolean(base.autoStopNonCar),
    filterEngineMode: mode,
    lowPassAlpha: Number(clampNum(patch.lowPassAlpha ?? base.lowPassAlpha, base.lowPassAlpha, 0.08, 0.80).toFixed(2)),
    hardBrakeG: Number(clampNum(patch.hardBrakeG ?? base.hardBrakeG, base.hardBrakeG, 0.15, 0.80).toFixed(2)),
    rapidAccelG: Number(clampNum(patch.rapidAccelG ?? base.rapidAccelG, base.rapidAccelG, 0.15, 0.80).toFixed(2)),
    harshCornerG: Number(clampNum(patch.harshCornerG ?? base.harshCornerG, base.harshCornerG, 0.15, 0.80).toFixed(2)),
    speedingLimitMph: Math.round(clampNum(patch.speedingLimitMph ?? base.speedingLimitMph, base.speedingLimitMph, 35, 95)),
    impactThresholdG: Number(clampNum(patch.impactThresholdG ?? base.impactThresholdG, base.impactThresholdG, 2.0, 5.0).toFixed(2)),
    fifoBatchLatencyMs: fifo,
    legacyWakeLockEnabled: typeof patch.legacyWakeLockEnabled === 'boolean' ? patch.legacyWakeLockEnabled : Boolean(base.legacyWakeLockEnabled)
  };
}

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.csv': 'text/csv; charset=utf-8',
  '.gpx': 'application/gpx+xml; charset=utf-8'
};

const ALLOWED_EXACT_PATHS = new Set(['/index.html', '/styles.css']);
const ALLOWED_PREFIXES = ['/js/'];

function sha256Digest(value) {
  return crypto.createHash('sha256').update(String(value), 'utf8').digest();
}

function timingSafeStringEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  return crypto.timingSafeEqual(sha256Digest(a), sha256Digest(b));
}

function signSessionExpiry(expiresSec, secret) {
  const payload = String(expiresSec);
  const sigHex = crypto.createHmac('sha256', secret).update(payload, 'utf8').digest('hex');
  return `${payload}.${sigHex}`;
}

function verifySessionCookie(cookieHeader, secret, nowMs) {
  if (!cookieHeader || typeof cookieHeader !== 'string') return false;
  const pairs = cookieHeader.split(';');
  let rawToken = null;
  for (const pair of pairs) {
    const idx = pair.indexOf('=');
    if (idx === -1) continue;
    if (pair.slice(0, idx).trim() === COOKIE_NAME) {
      rawToken = pair.slice(idx + 1).trim();
      break;
    }
  }
  if (!rawToken) return false;
  const match = /^(\d+)\.([0-9a-f]{64})$/.exec(rawToken);
  if (!match) return false;
  const expiresSec = Number(match[1]);
  if (!Number.isFinite(expiresSec) || expiresSec * 1000 <= nowMs) return false;

  const expectedHex = crypto.createHmac('sha256', secret).update(String(expiresSec), 'utf8').digest('hex');
  const sigBuf = Buffer.from(match[2], 'hex');
  const expectedBuf = Buffer.from(expectedHex, 'hex');
  if (sigBuf.length !== expectedBuf.length) return false;
  return crypto.timingSafeEqual(sigBuf, expectedBuf);
}

function isSecureRequest(req) {
  const proto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim().toLowerCase();
  return proto === 'https' || process.env.NODE_ENV === 'production';
}

function buildSessionCookieHeader(value, maxAgeSec, req) {
  const parts = [
    `${COOKIE_NAME}=${value}`,
    'HttpOnly',
    'SameSite=Lax',
    'Path=/',
    `Max-Age=${maxAgeSec}`
  ];
  if (isSecureRequest(req)) parts.push('Secure');
  return parts.join('; ');
}

function applySecurityHeaders(res) {
  res.setHeader('X-Robots-Tag', 'noindex, nofollow, noarchive');
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
}

function escapeHtml(text) {
  return String(text)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function renderLoginHtml(errorMessage = '') {
  const errHtml = errorMessage ? `<p class="err" role="alert">${escapeHtml(errorMessage)}</p>` : '';
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="robots" content="noindex, nofollow, noarchive" />
  <title>Telematics SDK Workbench — Passcode Required</title>
  <style>
    * { box-sizing: border-box; }
    :root {
      color-scheme: dark light;
      --bg: #080d19; --card: #111a2e; --border: #22314f; --text: #f1f5f9;
      --muted: #94a3b8; --input-bg: #0b1222; --accent: #38bdf8; --on-accent: #061322;
      --err-text: #fca5a5;
    }
    @media (prefers-color-scheme: light) {
      :root {
        --bg: #f1f5f9; --card: #ffffff; --border: #cbd5e1; --text: #0f172a;
        --muted: #475569; --input-bg: #f8fafc; --accent: #0284c7; --on-accent: #ffffff;
        --err-text: #b91c1c;
      }
    }
    body {
      margin: 0; min-height: 100dvh; display: grid; place-items: center; padding: 1.25rem;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: var(--bg); color: var(--text);
    }
    main {
      width: 100%; max-width: 410px; background: var(--card);
      border: 1px solid var(--border); border-radius: 16px; padding: 1.75rem;
      box-shadow: 0 20px 50px rgba(0,0,0,0.22);
    }
    .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 10px var(--accent); display: inline-block; margin-right: 8px; }
    h1 { margin: 0 0 6px; font-size: 1.25rem; display: flex; align-items: center; }
    p { margin: 0 0 1rem; color: var(--muted); font-size: 0.9rem; line-height: 1.45; }
    label { display: block; font-size: 0.8rem; color: var(--muted); margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.5px; }
    input[type="password"] {
      width: 100%; padding: 11px 12px; border-radius: 10px; border: 1px solid var(--border);
      background: var(--input-bg); color: var(--text); font-size: 1rem; margin-bottom: 12px;
    }
    button {
      width: 100%; padding: 11px; border-radius: 10px; border: none;
      background: var(--accent); color: var(--on-accent); font-weight: 700; font-size: 0.95rem; cursor: pointer;
    }
    .err {
      background: rgba(248,113,113,0.14); border: 1px solid rgba(248,113,113,0.45);
      color: var(--err-text); padding: 9px 11px; border-radius: 9px; margin-bottom: 12px; font-size: 0.85rem;
    }
  </style>
</head>
<body>
  <main>
    <h1><span class="dot"></span>Telematics SDK Workbench</h1>
    <p>Enter your passcode to access the interactive simulator, live telemetry graphs, and cloud-synced drives.</p>
    ${errHtml}
    <form method="POST" action="/__login">
      <label for="passcode">Passcode</label>
      <input id="passcode" name="passcode" type="password" autocomplete="current-password" autofocus required />
      <button type="submit">Unlock Workbench</button>
    </form>
  </main>
</body>
</html>`;
}

function renderExpiredHtml(shutdownIso) {
  return `<!doctype html><html><head><meta charset="utf-8" /><style>
    :root { color-scheme: dark light; --bg:#080d19; --card:#111a2e; --border:#22314f; --text:#f1f5f9; --muted:#94a3b8; }
    @media (prefers-color-scheme: light) { :root { --bg:#f1f5f9; --card:#ffffff; --border:#cbd5e1; --text:#0f172a; --muted:#475569; } }
    body { margin:0; background:var(--bg); color:var(--text); font-family:sans-serif; display:grid; place-items:center; min-height:100vh; }
    div { background:var(--card); border:1px solid var(--border); padding:24px; border-radius:14px; max-width:420px; }
    p { color:var(--muted); }
  </style></head><body><div>
    <h2>Workbench Archived</h2>
    <p>This demo instance reached its scheduled sunset (${escapeHtml(shutdownIso)}).</p>
  </div></body></html>`;
}

function readRequestBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    let total = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      total += chunk.length;
      if (total > maxBytes) {
        const err = new Error('Payload Too Large');
        err.statusCode = 413;
        req.destroy();
        reject(err);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function isAllowedStaticPath(normalizedPath) {
  if (ALLOWED_EXACT_PATHS.has(normalizedPath)) return true;
  return ALLOWED_PREFIXES.some((prefix) => normalizedPath.startsWith(prefix));
}

async function serveStaticAsset(rootDir, normalizedPath, res, cacheControl, method) {
  const safePath = normalizedPath === '/' ? '/index.html' : normalizedPath;
  if (!isAllowedStaticPath(safePath)) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end('Not Found');
    return;
  }
  const rel = safePath.replace(/^\/+/, '');
  const fullPath = path.resolve(rootDir, rel);
  if (!fullPath.startsWith(path.resolve(rootDir) + path.sep)) {
    res.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Forbidden');
    return;
  }
  try {
    const data = await fs.readFile(fullPath);
    const ext = path.extname(fullPath).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME_TYPES[ext] || 'application/octet-stream',
      'Content-Length': String(data.length),
      'Cache-Control': cacheControl
    });
    if (method === 'HEAD') {
      res.end();
      return;
    }
    res.end(data);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not Found');
  }
}

export function buildCsvFromTrip(trip) {
  const lines = [];
  const label = trip.tripLabel || `Trip ${trip.id}`;
  const seatRole = trip.seatRole || 'UNASSIGNED';
  const mode = trip.transportMode || 'CAR';
  lines.push(`# Trip ID,${trip.id}`);
  lines.push(`# Label,${String(label).replaceAll(',', ' ')}`);
  lines.push(`# Mode,${mode}`);
  lines.push(`# Seat Role,${seatRole}`);
  lines.push(`# Vert RMS G,${Number(trip.avgVertRmsG || 0).toFixed(4)}`);
  lines.push(`# Start UTC,${new Date(trip.startTimeMs || Date.now()).toISOString()}`);
  lines.push(`# End UTC,${new Date(trip.endTimeMs || Date.now()).toISOString()}`);
  lines.push(`# Overall Score,${trip.score?.overall ?? 100},Distance Mi,${Number(trip.distanceMiles || 0).toFixed(3)},Duration Sec,${trip.durationSec || 0}`);
  lines.push('elapsed_sec,timestamp_ms,iso_utc,lat,lon,speed_mph,bearing_deg,accuracy_m,satellites_used,long_g,lat_g,vert_g,raw_total_g,yaw_rad_s,screen_on,handheld');

  for (const p of trip.track || []) {
    const ts = p.ts ?? p.timestampMs ?? 0;
    const iso = ts ? new Date(ts).toISOString() : '';
    lines.push([
      Number(p.es ?? p.elapsedSec ?? 0).toFixed(2),
      ts,
      iso,
      Number(p.lat ?? 0).toFixed(6),
      Number(p.lon ?? 0).toFixed(6),
      Number(p.sp ?? p.speedMph ?? 0).toFixed(2),
      Number(p.br ?? p.bearingDeg ?? 0).toFixed(1),
      Number(p.ac ?? p.accuracyM ?? 0).toFixed(1),
      p.sat ?? p.satellitesUsed ?? 0,
      Number(p.lg ?? p.longG ?? 0).toFixed(4),
      Number(p.lt ?? p.latG ?? 0).toFixed(4),
      Number(p.vt ?? p.vertG ?? 0).toFixed(4),
      Number(p.rt ?? p.rawTotalG ?? 1).toFixed(4),
      Number(p.yw ?? p.yawRadS ?? 0).toFixed(4),
      Boolean(p.sc ?? p.screenOn ?? false),
      Boolean(p.hh ?? p.handheld ?? false)
    ].join(','));
  }
  return lines.join('\n') + '\n';
}

export function buildGpxFromTrip(trip) {
  const esc = (s) => escapeHtml(String(s ?? ''));
  const name = trip.tripLabel || `Trip ${trip.id}`;
  const seat = trip.seatRole || 'UNASSIGNED';
  const mode = trip.transportMode || 'CAR';
  const track = trip.track || [];
  const events = trip.events || [];

  const wpts = events
    .filter((e) => (e.lat || 0) !== 0 || (e.lon || 0) !== 0)
    .map((e) => {
      const tIso = new Date(e.timestampMs || Date.now()).toISOString();
      return `  <wpt lat="${Number(e.lat).toFixed(6)}" lon="${Number(e.lon).toFixed(6)}">\n` +
        `    <time>${tIso}</time>\n` +
        `    <name>${esc(e.type)}</name>\n` +
        `    <desc>${esc(e.details)}</desc>\n` +
        `  </wpt>`;
    }).join('\n');

  const trkpts = track
    .filter((p) => (p.lat || 0) !== 0 || (p.lon || 0) !== 0)
    .map((p) => {
      const ts = p.ts ?? p.timestampMs ?? Date.now();
      const iso = new Date(ts).toISOString();
      const ele = Number(p.alt ?? p.altitudeM ?? 0).toFixed(1);
      return `      <trkpt lat="${Number(p.lat).toFixed(6)}" lon="${Number(p.lon).toFixed(6)}"><ele>${ele}</ele><time>${iso}</time></trkpt>`;
    }).join('\n');

  return `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<gpx version="1.1" creator="Telematics Lab" xmlns="http://www.topografix.com/GPX/1/1">\n` +
    `  <metadata>\n` +
    `    <name>${esc(name)} (${esc(mode)} / ${esc(seat)})</name>\n` +
    `    <time>${new Date(trip.startTimeMs || Date.now()).toISOString()}</time>\n` +
    `  </metadata>\n` +
    (wpts ? `${wpts}\n` : '') +
    `  <trk>\n` +
    `    <name>${esc(name)}</name>\n` +
    `    <trkseg>\n` +
    (trkpts ? `${trkpts}\n` : '') +
    `    </trkseg>\n` +
    `  </trk>\n` +
    `</gpx>\n`;
}

function summarizeTrip(trip) {
  return {
    id: trip.id,
    tripLabel: trip.tripLabel || `Trip ${trip.id}`,
    seatRole: trip.seatRole || 'UNASSIGNED',
    transportMode: trip.transportMode || 'CAR',
    avgVertRmsG: Number(trip.avgVertRmsG || 0),
    startTimeMs: trip.startTimeMs || 0,
    endTimeMs: trip.endTimeMs || 0,
    durationSec: trip.durationSec || 0,
    distanceMiles: Number(trip.distanceMiles || 0),
    maxSpeedMph: Number(trip.maxSpeedMph || 0),
    score: trip.score?.overall ?? 100,
    eventsCount: Array.isArray(trip.events) ? trip.events.length : 0,
    trackCount: Array.isArray(trip.track) ? trip.track.length : 0,
    isSimulated: Boolean(trip.isSimulated),
    rawPayloadBytes: trip.rawPayloadBytes || 0,
    gzipPayloadBytes: trip.gzipPayloadBytes || 0,
    idempotentChunkCount: trip.idempotentChunkCount || 1
  };
}

function createTripStore({ gcsBucket, dataDir }) {
  let cachedToken = null;
  let cachedTokenExpiresMs = 0;

  async function getGcsToken() {
    const now = Date.now();
    if (cachedToken && now < cachedTokenExpiresMs - 60_000) return cachedToken;
    const res = await fetch('http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token', {
      headers: { 'Metadata-Flavor': 'Google' }
    });
    if (!res.ok) throw new Error(`Metadata token error ${res.status}`);
    const data = await res.json();
    cachedToken = data.access_token;
    cachedTokenExpiresMs = now + (data.expires_in || 3600) * 1000;
    return cachedToken;
  }

  async function gcsPutObject(objectName, contentType, bodyText) {
    const token = await getGcsToken();
    const url = `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(gcsBucket)}/o?uploadType=media&name=${encodeURIComponent(objectName)}`;
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': contentType
      },
      body: bodyText
    });
    if (!res.ok) throw new Error(`GCS upload failed (${res.status}) for ${objectName}`);
  }

  async function gcsGetObject(objectName) {
    const token = await getGcsToken();
    const url = `https://storage.googleapis.com/storage/v1/b/${encodeURIComponent(gcsBucket)}/o/${encodeURIComponent(objectName)}?alt=media`;
    const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    if (res.status === 404) return null;
    if (!res.ok) throw new Error(`GCS read failed (${res.status}) for ${objectName}`);
    return await res.text();
  }

  async function gcsListTripJsons() {
    const token = await getGcsToken();
    const url = `https://storage.googleapis.com/storage/v1/b/${encodeURIComponent(gcsBucket)}/o?prefix=${encodeURIComponent('trips/')}`;
    const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
    if (!res.ok) throw new Error(`GCS list failed (${res.status})`);
    const data = await res.json();
    return (data.items || [])
      .map((i) => i.name)
      .filter((name) => name.startsWith('trips/') && name.endsWith('.json') && name !== 'trips/index.json');
  }

  async function gcsDeleteObject(objectName) {
    const token = await getGcsToken();
    const url = `https://storage.googleapis.com/storage/v1/b/${encodeURIComponent(gcsBucket)}/o/${encodeURIComponent(objectName)}`;
    const res = await fetch(url, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` }
    });
    if (res.status === 404) return false;
    if (!res.ok) throw new Error(`GCS delete failed (${res.status}) for ${objectName}`);
    return true;
  }

  async function saveTrip(trip) {
    const id = trip.id;
    const jsonText = JSON.stringify(trip, null, 2);
    const csvText = buildCsvFromTrip(trip);
    const gpxText = buildGpxFromTrip(trip);

    if (gcsBucket) {
      await Promise.all([
        gcsPutObject(`trips/${id}.json`, 'application/json; charset=utf-8', jsonText),
        gcsPutObject(`trips/${id}.csv`, 'text/csv; charset=utf-8', csvText),
        gcsPutObject(`trips/${id}.gpx`, 'application/gpx+xml; charset=utf-8', gpxText)
      ]);
      const indexRaw = await gcsGetObject('trips/index.json');
      const indexList = indexRaw ? JSON.parse(indexRaw) : [];
      const summary = summarizeTrip(trip);
      const next = [summary, ...indexList.filter((item) => item.id !== id)]
        .sort((a, b) => (b.startTimeMs || 0) - (a.startTimeMs || 0));
      await gcsPutObject('trips/index.json', 'application/json; charset=utf-8', JSON.stringify(next, null, 2));
      return summary;
    }

    const dir = path.join(dataDir, 'trips');
    await fs.mkdir(dir, { recursive: true });
    await Promise.all([
      fs.writeFile(path.join(dir, `${id}.json`), jsonText, 'utf8'),
      fs.writeFile(path.join(dir, `${id}.csv`), csvText, 'utf8'),
      fs.writeFile(path.join(dir, `${id}.gpx`), gpxText, 'utf8')
    ]);
    return summarizeTrip(trip);
  }

  async function listTrips() {
    if (gcsBucket) {
      const indexRaw = await gcsGetObject('trips/index.json');
      if (indexRaw) return JSON.parse(indexRaw);
      const names = await gcsListTripJsons();
      const summaries = [];
      for (const name of names) {
        const raw = await gcsGetObject(name);
        if (raw) summaries.push(summarizeTrip(JSON.parse(raw)));
      }
      return summaries.sort((a, b) => (b.startTimeMs || 0) - (a.startTimeMs || 0));
    }

    const dir = path.join(dataDir, 'trips');
    try {
      const entries = await fs.readdir(dir);
      const summaries = [];
      for (const file of entries) {
        if (!file.endsWith('.json')) continue;
        const raw = await fs.readFile(path.join(dir, file), 'utf8');
        summaries.push(summarizeTrip(JSON.parse(raw)));
      }
      return summaries.sort((a, b) => (b.startTimeMs || 0) - (a.startTimeMs || 0));
    } catch {
      return [];
    }
  }

  async function getTripFile(id, format = 'json') {
    const ext = format === 'csv' ? 'csv' : format === 'gpx' ? 'gpx' : 'json';
    if (gcsBucket) {
      return await gcsGetObject(`trips/${id}.${ext}`);
    }
    try {
      return await fs.readFile(path.join(dataDir, 'trips', `${id}.${ext}`), 'utf8');
    } catch {
      return null;
    }
  }

  async function deleteTrip(id) {
    if (gcsBucket) {
      const results = await Promise.all([
        gcsDeleteObject(`trips/${id}.json`),
        gcsDeleteObject(`trips/${id}.csv`),
        gcsDeleteObject(`trips/${id}.gpx`)
      ]);
      const indexRaw = await gcsGetObject('trips/index.json');
      if (indexRaw) {
        const indexList = JSON.parse(indexRaw);
        const next = indexList.filter((item) => item.id !== id);
        await gcsPutObject('trips/index.json', 'application/json; charset=utf-8', JSON.stringify(next, null, 2));
      }
      return results.some(Boolean);
    }

    const dir = path.join(dataDir, 'trips');
    let removedAny = false;
    for (const ext of ['json', 'csv', 'gpx']) {
      try {
        await fs.unlink(path.join(dir, `${id}.${ext}`));
        removedAny = true;
      } catch (err) {
        if (err?.code !== 'ENOENT') throw err;
      }
    }
    return removedAny;
  }

  async function getRemoteConfig() {
    if (gcsBucket) {
      const raw = await gcsGetObject('config/remote_config.json');
      if (raw) return { ...DEFAULT_REMOTE_CONFIG, ...JSON.parse(raw) };
      return { ...DEFAULT_REMOTE_CONFIG };
    }
    try {
      const raw = await fs.readFile(path.join(dataDir, 'config', 'remote_config.json'), 'utf8');
      return { ...DEFAULT_REMOTE_CONFIG, ...JSON.parse(raw) };
    } catch {
      return { ...DEFAULT_REMOTE_CONFIG };
    }
  }

  async function saveRemoteConfig(patch) {
    const current = await getRemoteConfig();
    const next = sanitizeRemoteConfig(current, patch);
    const jsonText = JSON.stringify(next, null, 2);
    if (gcsBucket) {
      await gcsPutObject('config/remote_config.json', 'application/json; charset=utf-8', jsonText);
      return next;
    }
    const dir = path.join(dataDir, 'config');
    await fs.mkdir(dir, { recursive: true });
    await fs.writeFile(path.join(dir, 'remote_config.json'), jsonText, 'utf8');
    return next;
  }

  return { saveTrip, listTrips, getTripFile, deleteTrip, getRemoteConfig, saveRemoteConfig };
}

export function createWorkbenchServer({
  rootDir = process.cwd(),
  dataDir = path.join(rootDir, 'data'),
  gcsBucket = process.env.GCS_BUCKET || '',
  passcode = process.env.WORKBENCH_PASSCODE || '',
  sessionSecret = process.env.WORKBENCH_SESSION_SECRET || '',
  shutdownAtIso = process.env.SHUTDOWN_AT_ISO || DEFAULT_SHUTDOWN_AT_ISO
} = {}) {
  const effectiveSecret = sessionSecret || crypto.randomBytes(32).toString('hex');
  const shutdownMs = Date.parse(shutdownAtIso);
  const store = createTripStore({ gcsBucket, dataDir });

  function isAuthorized(req, nowMs) {
    if (!passcode) return true;
    const headerCode = String(req.headers['x-workbench-passcode'] || '');
    if (headerCode && timingSafeStringEqual(headerCode, passcode)) return true;
    return verifySessionCookie(req.headers.cookie, effectiveSecret, nowMs);
  }

  return http.createServer(async (req, res) => {
    applySecurityHeaders(res);
    const nowMs = Date.now();

    if (Number.isFinite(shutdownMs) && nowMs >= shutdownMs) {
      res.writeHead(410, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(renderExpiredHtml(shutdownAtIso));
      return;
    }

    const url = new URL(req.url || '/', 'http://localhost');
    const normalizedPath = path.posix.normalize(url.pathname);
    const method = (req.method || 'GET').toUpperCase();

    if (normalizedPath === '/healthz' || normalizedPath === '/health') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ status: 'ok', gcs: Boolean(gcsBucket) }));
      return;
    }

    if (normalizedPath === '/__login' && method === 'POST') {
      try {
        const body = await readRequestBody(req, MAX_FORM_BYTES);
        const params = new URLSearchParams(body);
        const submitted = params.get('passcode') || '';
        if (passcode && timingSafeStringEqual(submitted, passcode)) {
          const expiresSec = Math.floor(nowMs / 1000) + SESSION_MAX_AGE_SEC;
          const token = signSessionExpiry(expiresSec, effectiveSecret);
          res.writeHead(302, {
            Location: '/',
            'Set-Cookie': buildSessionCookieHeader(token, SESSION_MAX_AGE_SEC, req),
            'Cache-Control': 'no-store'
          });
          res.end();
          return;
        }
      } catch {
        // fall through to 401
      }
      res.writeHead(401, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(renderLoginHtml('Incorrect passcode. Please try again.'));
      return;
    }

    if (normalizedPath === '/__logout' && method === 'POST') {
      res.writeHead(302, {
        Location: '/',
        'Set-Cookie': buildSessionCookieHeader('', 0, req),
        'Cache-Control': 'no-store'
      });
      res.end();
      return;
    }

    // Remote SDK Feature Flags & Config API
    if (normalizedPath === '/api/config') {
      if (!isAuthorized(req, nowMs)) {
        res.writeHead(401, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }
      if (method === 'GET') {
        try {
          const cfg = await store.getRemoteConfig();
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
          res.end(JSON.stringify(cfg));
        } catch (err) {
          res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: err.message }));
        }
        return;
      }
      if (method === 'PUT') {
        try {
          const body = await readRequestBody(req, MAX_CONFIG_JSON_BYTES);
          const patch = JSON.parse(body);
          const saved = await store.saveRemoteConfig(patch);
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
          res.end(JSON.stringify(saved));
        } catch (err) {
          const code = err?.statusCode === 413 ? 413 : 400;
          res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: err.message }));
        }
        return;
      }
      res.writeHead(405, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'Method Not Allowed' }));
      return;
    }

    // Cloud Trip API routes
    if (normalizedPath.startsWith('/api/trips')) {
      if (!isAuthorized(req, nowMs)) {
        res.writeHead(401, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ error: 'Unauthorized' }));
        return;
      }

      const safeIdMatch = /^\/api\/trips\/([A-Za-z0-9_-]{1,40})(?:\/(download))?$/.exec(normalizedPath);
      if (normalizedPath === '/api/trips' && method === 'GET') {
        try {
          const list = await store.listTrips();
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
          res.end(JSON.stringify(list));
        } catch (err) {
          res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: err.message }));
        }
        return;
      }

      if (safeIdMatch && !safeIdMatch[2] && method === 'PUT') {
        const tripId = safeIdMatch[1];
        try {
          const body = await readRequestBody(req, MAX_TRIP_JSON_BYTES);
          const trip = JSON.parse(body);
          trip.id = tripId;
          const summary = await store.saveTrip(trip);
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ status: 'stored', id: tripId, formats: ['json', 'csv', 'gpx'], summary }));
        } catch (err) {
          const code = err?.statusCode === 413 ? 413 : 400;
          res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: err.message }));
        }
        return;
      }

      if (safeIdMatch && !safeIdMatch[2] && method === 'GET') {
        const raw = await store.getTripFile(safeIdMatch[1], 'json');
        if (!raw) {
          res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: 'Trip not found' }));
          return;
        }
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(raw);
        return;
      }

      if (safeIdMatch && !safeIdMatch[2] && method === 'DELETE') {
        try {
          const deleted = await store.deleteTrip(safeIdMatch[1]);
          if (!deleted) {
            res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
            res.end(JSON.stringify({ error: 'Trip not found' }));
            return;
          }
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
          res.end(JSON.stringify({ status: 'deleted', id: safeIdMatch[1] }));
        } catch (err) {
          res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: err.message }));
        }
        return;
      }

      if (safeIdMatch && safeIdMatch[2] === 'download' && method === 'GET') {
        const fmt = (url.searchParams.get('format') || 'json').toLowerCase();
        const ext = fmt === 'csv' ? 'csv' : fmt === 'gpx' ? 'gpx' : 'json';
        const content = await store.getTripFile(safeIdMatch[1], ext);
        if (!content) {
          res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end(JSON.stringify({ error: 'Trip not found' }));
          return;
        }
        res.writeHead(200, {
          'Content-Type': MIME_TYPES[`.${ext}`],
          'Content-Disposition': `attachment; filename="trip_${safeIdMatch[1]}.${ext}"`,
          'Cache-Control': 'no-store'
        });
        res.end(content);
        return;
      }

      res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ error: 'Not found' }));
      return;
    }

    if (method !== 'GET' && method !== 'HEAD') {
      res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('Method Not Allowed');
      return;
    }

    const unlockParam = url.searchParams.get('unlock');
    if (unlockParam !== null && passcode) {
      if (timingSafeStringEqual(unlockParam, passcode)) {
        const expiresSec = Math.floor(nowMs / 1000) + SESSION_MAX_AGE_SEC;
        const token = signSessionExpiry(expiresSec, effectiveSecret);
        res.writeHead(302, {
          Location: '/',
          'Set-Cookie': buildSessionCookieHeader(token, SESSION_MAX_AGE_SEC, req),
          'Cache-Control': 'no-store'
        });
        res.end();
        return;
      }
      res.writeHead(401, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(renderLoginHtml('Invalid unlock link or passcode.'));
      return;
    }

    if (!isAuthorized(req, nowMs)) {
      res.writeHead(401, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
      if (method === 'HEAD') {
        res.end();
        return;
      }
      res.end(renderLoginHtml());
      return;
    }

    await serveStaticAsset(rootDir, normalizedPath, res, 'private, no-cache', method);
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT || 8080);
  const server = createWorkbenchServer({
    rootDir: process.cwd(),
    gcsBucket: process.env.GCS_BUCKET || '',
    passcode: process.env.WORKBENCH_PASSCODE || '',
    sessionSecret: process.env.WORKBENCH_SESSION_SECRET || '',
    shutdownAtIso: process.env.SHUTDOWN_AT_ISO || DEFAULT_SHUTDOWN_AT_ISO
  });
  server.listen(port, '0.0.0.0', () => {
    console.log(`Telematics Workbench listening on port ${port}`);
  });
}
