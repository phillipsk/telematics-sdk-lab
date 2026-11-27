#!/usr/bin/env node
/**
 * Live E2E smoke test against the deployed Telematics Workbench + GCS backend.
 * Cleans up its synthetic smoke_<ts> object via DELETE /api/trips/:id when done.
 */
import assert from 'node:assert/strict';

const baseUrl = (process.env.WORKBENCH_URL || '').replace(/\/+$/, '');
const passcode = process.env.WORKBENCH_PASSCODE || '';

if (!baseUrl) {
  console.error('WORKBENCH_URL environment variable is required.');
  process.exit(2);
}

async function run() {
  const healthRes = await fetch(`${baseUrl}/health`);
  assert.equal(healthRes.status, 200, 'GET /health must return 200');
  const health = await healthRes.json();
  assert.equal(health.status, 'ok');
  console.log('[smoke] /health ok:', JSON.stringify(health));

  if (passcode) {
    const unauthRes = await fetch(`${baseUrl}/api/trips`);
    assert.equal(unauthRes.status, 401, 'Unauthenticated /api/trips must return 401');

    const badUnlock = await fetch(`${baseUrl}/?unlock=wrong-passcode-000`, { redirect: 'manual' });
    assert.equal(badUnlock.status, 401, 'Invalid ?unlock= must return 401');

    const unlockRes = await fetch(`${baseUrl}/?unlock=${encodeURIComponent(passcode)}`, { redirect: 'manual' });
    assert.equal(unlockRes.status, 302, 'Valid ?unlock= must redirect');
    const cookie = (unlockRes.headers.get('set-cookie') || '').split(';')[0];
    assert.ok(cookie.startsWith('workbench_session='), 'Session cookie must be issued');

    const pageRes = await fetch(`${baseUrl}/`, { headers: { Cookie: cookie } });
    assert.equal(pageRes.status, 200, 'Authenticated GET / must return 200');
    console.log('[smoke] auth + session cookie ok');

    const unauthCfg = await fetch(`${baseUrl}/api/config`);
    assert.equal(unauthCfg.status, 401, 'Unauthenticated /api/config must return 401');
  }

  const cfgAuth = passcode ? { 'X-Workbench-Passcode': passcode } : {};
  const cfgRes = await fetch(`${baseUrl}/api/config`, { headers: cfgAuth });
  assert.equal(cfgRes.status, 200, 'GET /api/config must return 200');
  const cfg = await cfgRes.json();
  assert.equal(typeof cfg.autoStartVerifyMph, 'number');
  assert.ok(cfg.version >= 1);
  console.log('[smoke] /api/config ok: v' + cfg.version + ' autoStartVerifyMph=' + cfg.autoStartVerifyMph);

  const tripId = `smoke_${Date.now()}`;
  const authHeaders = passcode ? { 'X-Workbench-Passcode': passcode } : {};
  const payload = {
    id: tripId,
    tripLabel: 'Automated Smoke Check',
    seatRole: 'DRIVER',
    transportMode: 'CAR',
    avgVertRmsG: 0.031,
    startTimeMs: Date.now() - 15_000,
    endTimeMs: Date.now(),
    durationSec: 15,
    distanceMiles: 0.12,
    maxSpeedMph: 28.4,
    score: { overall: 98, braking: 98, acceleration: 98, cornering: 98, speeding: 100, distraction: 100, smoothness: 98 },
    events: [
      { id: 'smoke-e1', type: 'HARD_BRAKE', displayName: 'Hard Brake', timestampMs: Date.now() - 5000, elapsedTripSec: 10, lat: 37.7755, lon: -122.4185, details: 'Smoke brake sample' }
    ],
    track: [
      { es: 0, ts: Date.now() - 15_000, lat: 37.7751, lon: -122.4188, sp: 12.0, br: 45, ac: 4, sat: 12, lg: 0.08, lt: 0.0, vt: 0.02, rt: 1.0 },
      { es: 15, ts: Date.now(), lat: 37.7755, lon: -122.4185, sp: 28.4, br: 46, ac: 4, sat: 13, lg: -0.35, lt: 0.02, vt: 0.03, rt: 1.06 }
    ]
  };

  try {
    const putRes = await fetch(`${baseUrl}/api/trips/${tripId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', ...authHeaders },
      body: JSON.stringify(payload)
    });
    assert.equal(putRes.status, 200, 'PUT /api/trips/:id must return 200');
    console.log('[smoke] PUT trip stored:', tripId);

    const listRes = await fetch(`${baseUrl}/api/trips`, { headers: authHeaders });
    assert.equal(listRes.status, 200);
    const list = await listRes.json();
    assert.ok(list.some((t) => t.id === tripId), 'GET /api/trips must include uploaded smoke trip');

    const getRes = await fetch(`${baseUrl}/api/trips/${tripId}`, { headers: authHeaders });
    assert.equal(getRes.status, 200);

    const csvRes = await fetch(`${baseUrl}/api/trips/${tripId}/download?format=csv`, { headers: authHeaders });
    assert.equal(csvRes.status, 200);
    const csvText = await csvRes.text();
    assert.ok(csvText.includes('37.775500,-122.418500'), 'CSV must include uploaded coordinates');
    assert.ok(csvText.includes('# Mode,CAR'), 'CSV must include transportMode');

    const gpxRes = await fetch(`${baseUrl}/api/trips/${tripId}/download?format=gpx`, { headers: authHeaders });
    assert.equal(gpxRes.status, 200);
    const gpxText = await gpxRes.text();
    assert.ok(gpxText.includes('<gpx version="1.1"'), 'GPX must be valid GPX 1.1');
    console.log('[smoke] JSON + CSV + GPX downloads ok');
  } finally {
    const delRes = await fetch(`${baseUrl}/api/trips/${tripId}`, {
      method: 'DELETE',
      headers: authHeaders
    });
    assert.equal(delRes.status, 200, 'DELETE /api/trips/:id cleanup must succeed');
    const verifyGone = await fetch(`${baseUrl}/api/trips/${tripId}`, { headers: authHeaders });
    assert.equal(verifyGone.status, 404, 'Deleted smoke trip must return 404');
    console.log('[smoke] DELETE cleanup verified (404)');
  }
}

run().catch((err) => {
  console.error('[smoke] FAILED:', err);
  process.exit(1);
});
