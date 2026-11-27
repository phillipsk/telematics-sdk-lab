const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

async function startTestServer() {
  const { createWorkbenchServer } = await import('../server.mjs');
  const tmpData = await fs.mkdtemp(path.join(os.tmpdir(), 'wb-test-'));
  const server = createWorkbenchServer({
    rootDir: path.resolve(__dirname, '..'),
    dataDir: tmpData,
    gcsBucket: '',
    passcode: 'TestPass-123',
    sessionSecret: 'a'.repeat(64),
    shutdownAtIso: '2030-01-01T00:00:00Z'
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const addr = server.address();
  return { server, baseUrl: `http://127.0.0.1:${addr.port}`, tmpData };
}

test('passcode gate, static allowlist, and trip JSON/CSV/GPX cloud API work end-to-end', async () => {
  const { server, baseUrl, tmpData } = await startTestServer();
  try {
    // 1. Unauthenticated GET / returns 401 login page
    const unauth = await fetch(`${baseUrl}/`);
    assert.equal(unauth.status, 401);
    const loginHtml = await unauth.text();
    assert.ok(loginHtml.includes('Passcode'));

    // 2. Login POST sets HttpOnly cookie
    const loginRes = await fetch(`${baseUrl}/__login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'passcode=TestPass-123',
      redirect: 'manual'
    });
    assert.equal(loginRes.status, 302);
    const cookie = loginRes.headers.get('set-cookie').split(';')[0];
    assert.ok(cookie.startsWith('workbench_session='));

    // 3. Authenticated GET / returns 200 HTML
    const homeRes = await fetch(`${baseUrl}/`, { headers: { Cookie: cookie } });
    assert.equal(homeRes.status, 200);
    const homeHtml = await homeRes.text();
    assert.ok(homeHtml.includes('Telematics SDK Workbench'));

    // 4. Blocked internal paths return 404 even when authenticated
    const blocked = await fetch(`${baseUrl}/server.mjs`, { headers: { Cookie: cookie } });
    assert.equal(blocked.status, 404);

    // 5. Phone trip upload via X-Workbench-Passcode header
    const tripPayload = {
      id: 'commute01',
      tripLabel: 'Outbound Commute',
      seatRole: 'BACKSEAT_PASSENGER',
      transportMode: 'CAR',
      avgVertRmsG: 0.042,
      startTimeMs: 1795710000000,
      endTimeMs: 1795711800000,
      durationSec: 1800,
      distanceMiles: 14.6,
      maxSpeedMph: 64.5,
      score: { overall: 94 },
      events: [
        { id: 'e1', type: 'HARD_BRAKE', timestampMs: 1795710500000, elapsedTripSec: 500, lat: 37.7851, lon: -122.4090, details: 'Exit ramp stop' }
      ],
      track: [
        { es: 0, ts: 1795710000000, lat: 37.7755, lon: -122.4185, sp: 15, br: 350, ac: 4, sat: 12, lg: 0.1, lt: 0.0, vt: 0.0, rt: 1.0 },
        { es: 10, ts: 1795710010000, lat: 37.7851, lon: -122.4090, sp: 55, br: 355, ac: 4, sat: 14, lg: -0.2, lt: 0.1, vt: 0.0, rt: 1.02 }
      ]
    };

    const putRes = await fetch(`${baseUrl}/api/trips/commute01`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'X-Workbench-Passcode': 'TestPass-123'
      },
      body: JSON.stringify(tripPayload)
    });
    assert.equal(putRes.status, 200);

    const listRes = await fetch(`${baseUrl}/api/trips`, { headers: { Cookie: cookie } });
    assert.equal(listRes.status, 200);
    const list = await listRes.json();
    assert.equal(list.length, 1);
    assert.equal(list[0].tripLabel, 'Outbound Commute');
    assert.equal(list[0].seatRole, 'BACKSEAT_PASSENGER');
    assert.equal(list[0].transportMode, 'CAR');

    const csvRes = await fetch(`${baseUrl}/api/trips/commute01/download?format=csv`, { headers: { Cookie: cookie } });
    assert.equal(csvRes.status, 200);
    const csvText = await csvRes.text();
    assert.ok(csvText.includes('Outbound Commute'));
    assert.ok(csvText.includes('37.785100,-122.409000'));

    const gpxRes = await fetch(`${baseUrl}/api/trips/commute01/download?format=gpx`, { headers: { Cookie: cookie } });
    assert.equal(gpxRes.status, 200);
    const gpxText = await gpxRes.text();
    assert.ok(gpxText.includes('<gpx version="1.1"'));
    assert.ok(gpxText.includes('lat="37.785100" lon="-122.409000"'));
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(tmpData, { recursive: true, force: true });
  }
});

test('auth guards reject bad passcodes, unauthenticated API calls, and expired workbench', async () => {
  const { server, baseUrl, tmpData } = await startTestServer();
  try {
    const unauthApi = await fetch(`${baseUrl}/api/trips`);
    assert.equal(unauthApi.status, 401);

    const healthRes = await fetch(`${baseUrl}/health`);
    assert.equal(healthRes.status, 200);
    assert.deepEqual(await healthRes.json(), { status: 'ok', gcs: false });

    const badLogin = await fetch(`${baseUrl}/__login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'passcode=WrongPassword',
      redirect: 'manual'
    });
    assert.equal(badLogin.status, 401);

    const badUnlock = await fetch(`${baseUrl}/?unlock=WrongPassword`, { redirect: 'manual' });
    assert.equal(badUnlock.status, 401);

    const goodUnlock = await fetch(`${baseUrl}/?unlock=TestPass-123`, { redirect: 'manual' });
    assert.equal(goodUnlock.status, 302);
    const cookie = goodUnlock.headers.get('set-cookie').split(';')[0];
    assert.ok(cookie.startsWith('workbench_session='));

    const missingTrip = await fetch(`${baseUrl}/api/trips/does_not_exist`, { headers: { Cookie: cookie } });
    assert.equal(missingTrip.status, 404);

    const badJson = await fetch(`${baseUrl}/api/trips/bad01`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Workbench-Passcode': 'TestPass-123' },
      body: '{not-valid-json'
    });
    assert.equal(badJson.status, 400);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(tmpData, { recursive: true, force: true });
  }

  const { createWorkbenchServer } = await import('../server.mjs');
  const tmpExpired = await fs.mkdtemp(path.join(os.tmpdir(), 'wb-expired-'));
  const expiredServer = createWorkbenchServer({
    rootDir: path.resolve(__dirname, '..'),
    dataDir: tmpExpired,
    passcode: 'TestPass-123',
    sessionSecret: 'b'.repeat(64),
    shutdownAtIso: '2020-01-01T00:00:00Z'
  });
  await new Promise((resolve) => expiredServer.listen(0, '127.0.0.1', resolve));
  try {
    const expiredUrl = `http://127.0.0.1:${expiredServer.address().port}`;
    const res = await fetch(`${expiredUrl}/`);
    assert.equal(res.status, 410);
  } finally {
    await new Promise((resolve) => expiredServer.close(resolve));
    await fs.rm(tmpExpired, { recursive: true, force: true });
  }
});

test('multi-modal WALK trip metadata and DELETE /api/trips/:id cleanup work', async () => {
  const { server, baseUrl, tmpData } = await startTestServer();
  try {
    const walkTrip = {
      id: 'walk01',
      tripLabel: 'Morning Walk',
      seatRole: 'DRIVER',
      transportMode: 'WALK',
      avgVertRmsG: 0.184,
      startTimeMs: 1795720000000,
      endTimeMs: 1795720900000,
      durationSec: 900,
      distanceMiles: 0.78,
      maxSpeedMph: 3.4,
      score: { overall: 100 },
      events: [],
      track: [
        { es: 0, ts: 1795720000000, lat: 37.7749, lon: -122.4194, sp: 3.1, br: 120, ac: 4, sat: 11, lg: 0.02, lt: 0.01, vt: 0.18, rt: 1.05 }
      ]
    };

    const putRes = await fetch(`${baseUrl}/api/trips/walk01`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Workbench-Passcode': 'TestPass-123' },
      body: JSON.stringify(walkTrip)
    });
    assert.equal(putRes.status, 200);
    const putJson = await putRes.json();
    assert.equal(putJson.summary.transportMode, 'WALK');
    assert.equal(putJson.summary.avgVertRmsG, 0.184);

    const csvRes = await fetch(`${baseUrl}/api/trips/walk01/download?format=csv`, {
      headers: { 'X-Workbench-Passcode': 'TestPass-123' }
    });
    assert.equal(csvRes.status, 200);
    const csvText = await csvRes.text();
    assert.ok(csvText.includes('# Mode,WALK'));
    assert.ok(csvText.includes('# Vert RMS G,0.1840'));

    const delRes = await fetch(`${baseUrl}/api/trips/walk01`, {
      method: 'DELETE',
      headers: { 'X-Workbench-Passcode': 'TestPass-123' }
    });
    assert.equal(delRes.status, 200);

    const getAfter = await fetch(`${baseUrl}/api/trips/walk01`, {
      headers: { 'X-Workbench-Passcode': 'TestPass-123' }
    });
    assert.equal(getAfter.status, 404);

    const listAfter = await fetch(`${baseUrl}/api/trips`, {
      headers: { 'X-Workbench-Passcode': 'TestPass-123' }
    });
    assert.deepEqual(await listAfter.json(), []);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(tmpData, { recursive: true, force: true });
  }
});

test('remote config GET/PUT enforces auth and persists validated feature flags', async () => {
  const { server, baseUrl, tmpData } = await startTestServer();
  try {
    const unauth = await fetch(`${baseUrl}/api/config`);
    assert.equal(unauth.status, 401);

    const getDef = await fetch(`${baseUrl}/api/config`, {
      headers: { 'X-Workbench-Passcode': 'TestPass-123' }
    });
    assert.equal(getDef.status, 200);
    const defCfg = await getDef.json();
    assert.equal(defCfg.version, 1);
    assert.equal(defCfg.autoStartVerifyMph, 10);
    assert.equal(defCfg.autoStopNonCar, false);

    const putRes = await fetch(`${baseUrl}/api/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Workbench-Passcode': 'TestPass-123' },
      body: JSON.stringify({
        autoStartVerifyMph: 15,
        autoStopMode: 'MANUAL_ONLY',
        fifoBatchLatencyMs: 500,
        filterEngineMode: 'ADAPTIVE_DUAL_POLE',
        autoStopNonCar: true
      })
    });
    assert.equal(putRes.status, 200);
    const updated = await putRes.json();
    assert.equal(updated.version, 2);
    assert.equal(updated.autoStartVerifyMph, 15);
    assert.equal(updated.autoStopMode, 'MANUAL_ONLY');
    assert.equal(updated.stopHysteresisSec, 0);
    assert.equal(updated.fifoBatchLatencyMs, 500);
    assert.equal(updated.filterEngineMode, 'ADAPTIVE_DUAL_POLE');
    assert.equal(updated.autoStopNonCar, true);

    const refetch = await fetch(`${baseUrl}/api/config`, {
      headers: { 'X-Workbench-Passcode': 'TestPass-123' }
    });
    const persisted = await refetch.json();
    assert.equal(persisted.version, 2);
    assert.equal(persisted.autoStartVerifyMph, 15);
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(tmpData, { recursive: true, force: true });
  }
});
