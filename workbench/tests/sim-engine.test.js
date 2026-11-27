const test = require('node:test');
const assert = require('node:assert/strict');
const E = require('../js/sim-engine.js');

test('coordinate frame rotation stays invariant across tilted phone mounts', () => {
  const mapperA = new E.CoordinateFrameMapper();
  const mapperB = new E.CoordinateFrameMapper();

  // Car moving East (bearing 90 deg) accelerating +0.4g East
  const aEast = 0.4 * E.GRAVITY_MS2;
  const rotA = E.buildRotationMatrix(0, 0, 0);
  const pLinA = [rotA[0] * aEast, rotA[1] * aEast, rotA[2] * aEast];
  const resA = mapperA.map(pLinA, pLinA, rotA, 90, 12, 1.0, 'STANDARD_LOW_PASS');

  const rotB = E.buildRotationMatrix(0.9, 0.95, 0.4);
  const pLinB = [rotB[0] * aEast, rotB[1] * aEast, rotB[2] * aEast];
  const resB = mapperB.map(pLinB, pLinB, rotB, 90, 12, 1.0, 'STANDARD_LOW_PASS');

  assert.ok(Math.abs(resA.rawLongG - 0.4) < 0.01);
  assert.ok(Math.abs(resB.rawLongG - 0.4) < 0.01);
  assert.ok(Math.abs(resB.rawLatG) < 0.01);
});

test('dual-pole filter bridge attenuates road-chatter spike more smoothly than single-pole', () => {
  const bridge = new E.SensorEngineBridge();
  bridge.filter('STANDARD_LOW_PASS', 0, 0, 0, 0.24);
  const std = bridge.filter('STANDARD_LOW_PASS', 1.0, 0, 0, 0.24);

  bridge.filter('ADAPTIVE_DUAL_POLE', 0, 0, 0, 0.24);
  const adp = bridge.filter('ADAPTIVE_DUAL_POLE', 1.0, 0, 0, 0.24);

  assert.ok(adp.longG < std.longG);
  assert.ok(adp.longG > 0.01 && adp.longG < 0.15);
});

test('trip FSM bridges red-light stop and rejects noisy GPS fixes', () => {
  const fsm = new E.TripStateMachine(10000);
  const t0 = 100000;

  assert.equal(fsm.update(t0, 40, 120), 'IDLE'); // noisy GPS ignored
  assert.equal(fsm.update(t0 + 1000, 18, 5), 'VERIFYING');
  assert.equal(fsm.update(t0 + 4000, 22, 5), 'RECORDING');
  assert.equal(fsm.update(t0 + 8000, 0.5, 5), 'STOP_PENDING');
  assert.equal(fsm.update(t0 + 12000, 14, 5), 'RECORDING'); // bridged!
  assert.equal(fsm.update(t0 + 15000, 0, 5), 'STOP_PENDING');
  assert.equal(fsm.update(t0 + 26000, 0, 5), 'FINALIZING');
});

test('stage 2 impact classifier separates phone drop from vehicle collision', () => {
  const detector = new E.DrivingEventDetector([]);
  detector.reset(0);

  const drop = detector.evaluateStage2Impact(5000, 4.5, 8.9, 28, 28);
  assert.equal(drop.verdict, 'REJECTED_PHONE_DROP');

  const crash = detector.evaluateStage2Impact(8000, 4.8, 2.0, 38, 0);
  assert.equal(crash.verdict, 'POTENTIAL_IMPACT');
});

test('wake lock policy denylist and event burst gating work as designed', () => {
  const policy = new E.WakeLockPolicyEngine();
  assert.equal(policy.evaluateState('RECORDING', false).shouldHold, false);
  assert.equal(policy.evaluateState('RECORDING', true).shouldHold, true);
  assert.equal(policy.evaluateEventBurst('HARD_BRAKE'), false);
  assert.equal(policy.evaluateEventBurst('POTENTIAL_IMPACT'), true);
});

test('parseImportedTrip parses exported CSV and JSON trips cleanly', () => {
  const csv = [
    '# Trip ID,abc123',
    'elapsed_sec,timestamp_ms,lat,lon,speed_mph,bearing_deg,accuracy_m,satellites_used,long_g,lat_g,vert_g,raw_total_g,yaw_rad_s,screen_on,handheld',
    '0.00,1700000000000,37.775500,-122.418500,10.00,90.0,4.0,12,0.1000,0.0000,0.0000,1.0000,0.0000,false,false',
    '1.00,1700000001000,37.775550,-122.418450,25.00,90.0,4.0,12,0.2000,0.0500,0.0000,1.0200,0.0100,false,false'
  ].join('\n');

  const parsed = E.parseImportedTrip(csv, 'drive.csv');
  assert.equal(parsed.track.length, 2);
  assert.equal(parsed.track[1].speedMph, 25);
});

test('parseImportedTrip preserves full JSON trip events, waveform snippets, and Gzip metadata', () => {
  const payload = JSON.stringify({
    id: 'bike01',
    tripLabel: 'Office Bike Commute',
    transportMode: 'BICYCLE',
    durationSec: 640,
    distanceMiles: 3.1,
    score: { overall: 97, braking: 96, acceleration: 98, cornering: 97, speeding: 100, distraction: 95 },
    rawPayloadBytes: 24500,
    gzipPayloadBytes: 6125,
    idempotentChunkCount: 1,
    track: [
      { es: 0, ts: 1795730000000, lat: 37.7755, lon: -122.4185, sp: 11.5, br: 88, ac: 3.5, sat: 13, lg: 0.08, lt: 0.02, vt: 0.09, rt: 1.01, hh: false }
    ],
    events: [
      {
        id: 'ev-1',
        type: 'HARD_BRAKE',
        displayName: 'Hard Brake',
        elapsedTripSec: 120,
        details: 'Crosswalk stop',
        waveformSnippet: [
          { ts: 1795730120000, lg: -0.34, lt: 0.01 }
        ]
      }
    ]
  });

  const parsed = E.parseImportedTrip(payload, 'bike01.json');
  assert.equal(parsed.id, 'bike01');
  assert.equal(parsed.track.length, 1);
  assert.equal(parsed.track[0].speedMph, 11.5);
  assert.equal(parsed.events.length, 1);
  assert.equal(parsed.events[0].waveformSnippet[0].longG, -0.34);
  assert.equal(parsed.gzipPayloadBytes, 6125);
});
