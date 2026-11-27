(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.TelematicsEngine = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const GRAVITY_MS2 = 9.80665;
  const DEG2RAD = Math.PI / 180;

  function buildRotationMatrix(yawRad, pitchRad, rollRad) {
    const cy = Math.cos(yawRad), sy = Math.sin(yawRad);
    const cp = Math.cos(pitchRad), sp = Math.sin(pitchRad);
    const cr = Math.cos(rollRad), sr = Math.sin(rollRad);
    return [
      cy * cr + sy * sp * sr, sy * cp, cy * sr - sy * sp * cr,
      -sy * cr + cy * sp * sr, cy * cp, -sy * sr - cy * sp * cr,
      -cp * sr, sp, cp * cr
    ];
  }

  class SensorEngineBridge {
    constructor() {
      this.reset();
    }
    reset() {
      this.stdSeeded = false;
      this.stdLong = 0; this.stdLat = 0; this.stdVert = 0;
      this.adpSeeded = false;
      this.s1Long = 0; this.s1Lat = 0; this.s1Vert = 0;
      this.s2Long = 0; this.s2Lat = 0; this.s2Vert = 0;
    }
    filter(mode, rawLongG, rawLatG, rawVertG, alpha) {
      const a = Math.min(1, Math.max(0.05, alpha));
      if (mode === 'ADAPTIVE_DUAL_POLE') {
        const a1 = Math.min(0.90, Math.max(0.06, a * 1.15));
        const a2 = Math.min(0.85, Math.max(0.05, a * 0.85));
        if (!this.adpSeeded) {
          this.s1Long = this.s2Long = rawLongG;
          this.s1Lat = this.s2Lat = rawLatG;
          this.s1Vert = this.s2Vert = rawVertG;
          this.adpSeeded = true;
        } else {
          this.s1Long = a1 * rawLongG + (1 - a1) * this.s1Long;
          this.s1Lat = a1 * rawLatG + (1 - a1) * this.s1Lat;
          this.s1Vert = a1 * rawVertG + (1 - a1) * this.s1Vert;
          this.s2Long = a2 * this.s1Long + (1 - a2) * this.s2Long;
          this.s2Lat = a2 * this.s1Lat + (1 - a2) * this.s2Lat;
          this.s2Vert = a2 * this.s1Vert + (1 - a2) * this.s2Vert;
        }
        return { longG: this.s2Long, latG: this.s2Lat, vertG: this.s2Vert };
      }

      if (!this.stdSeeded) {
        this.stdLong = rawLongG; this.stdLat = rawLatG; this.stdVert = rawVertG;
        this.stdSeeded = true;
      } else {
        this.stdLong = a * rawLongG + (1 - a) * this.stdLong;
        this.stdLat = a * rawLatG + (1 - a) * this.stdLat;
        this.stdVert = a * rawVertG + (1 - a) * this.stdVert;
      }
      return { longG: this.stdLong, latG: this.stdLat, vertG: this.stdVert };
    }
  }

  class CoordinateFrameMapper {
    constructor(bridge = new SensorEngineBridge()) {
      this.bridge = bridge;
      this.lastHeadingDeg = null;
    }
    reset() {
      this.lastHeadingDeg = null;
      this.bridge.reset();
    }
    map(phoneLinear, phoneRaw, rotMatrix9, gpsBearingDeg, gpsSpeedMps, alpha, filterMode) {
      const [px, py, pz] = phoneLinear;
      const [rx, ry, rz] = phoneRaw;
      const rawTotalG = Math.sqrt(rx * rx + ry * ry + rz * rz) / GRAVITY_MS2;

      const aEast = rotMatrix9[0] * px + rotMatrix9[1] * py + rotMatrix9[2] * pz;
      const aNorth = rotMatrix9[3] * px + rotMatrix9[4] * py + rotMatrix9[5] * pz;
      const aUp = rotMatrix9[6] * px + rotMatrix9[7] * py + rotMatrix9[8] * pz;

      let headingDeg = 0;
      let headingLocked = false;
      if (gpsBearingDeg != null && gpsSpeedMps >= 2.0) {
        headingDeg = ((gpsBearingDeg % 360) + 360) % 360;
        this.lastHeadingDeg = headingDeg;
        headingLocked = true;
      } else if (this.lastHeadingDeg != null) {
        headingDeg = this.lastHeadingDeg;
        headingLocked = true;
      }

      const th = headingDeg * DEG2RAD;
      const sinT = Math.sin(th), cosT = Math.cos(th);
      const rawLongG = (aEast * sinT + aNorth * cosT) / GRAVITY_MS2;
      const rawLatG = (aEast * cosT - aNorth * sinT) / GRAVITY_MS2;
      const rawVertG = aUp / GRAVITY_MS2;

      const filtered = this.bridge.filter(filterMode, rawLongG, rawLatG, rawVertG, alpha);
      return {
        rawPhoneG: [rx / GRAVITY_MS2, ry / GRAVITY_MS2, rz / GRAVITY_MS2],
        rawLongG, rawLatG, rawVertG,
        filteredLongG: filtered.longG,
        filteredLatG: filtered.latG,
        filteredVertG: filtered.vertG,
        rawTotalG,
        headingDeg,
        headingLocked
      };
    }
  }

  class WakeLockPolicyEngine {
    constructor() {
      this.denylist = new Set(['RECORDING', 'VERIFYING', 'STOP_PENDING', 'FINALIZING']);
      this.eventAllowlist = new Set(['POTENTIAL_IMPACT']);
    }
    evaluateState(tripState, legacyContinuousEnabled) {
      const wakeful = tripState !== 'IDLE';
      if (legacyContinuousEnabled && wakeful) {
        return { shouldHold: true, reason: `${tripState}: legacy continuous override ON` };
      }
      if (wakeful && this.denylist.has(String(tripState).toUpperCase())) {
        return { shouldHold: false, reason: `${tripState}: denied by remote-config denylist -> 0ms lock` };
      }
      return { shouldHold: false, reason: `${tripState}: non-wakeful -> released` };
    }
    evaluateEventBurst(eventType) {
      return this.eventAllowlist.has(eventType);
    }
  }

  class TripStateMachine {
    constructor(stopHysteresisMs = 12000) {
      this.state = 'IDLE';
      this.reason = 'Waiting for movement >= 10 mph';
      this.verifyingSinceMs = 0;
      this.stopPendingSinceMs = 0;
      this.stopHysteresisMs = stopHysteresisMs;
    }
    forceRecording(nowMs, msg = 'Trip started') {
      this.state = 'RECORDING';
      this.reason = msg;
      this.stopPendingSinceMs = 0;
      this.verifyingSinceMs = nowMs;
    }
    reset() {
      this.state = 'IDLE';
      this.reason = 'Waiting for movement >= 10 mph';
      this.verifyingSinceMs = 0;
      this.stopPendingSinceMs = 0;
    }
    update(nowMs, speedMph, accuracyM) {
      const goodFix = accuracyM > 0 && accuracyM <= 65;
      if (this.state === 'IDLE') {
        if (goodFix && speedMph >= 10) {
          this.state = 'VERIFYING';
          this.verifyingSinceMs = nowMs;
          this.reason = `Speed ${Math.round(speedMph)} mph >= 10 mph (verifying 5s)`;
        }
      } else if (this.state === 'VERIFYING') {
        if (goodFix && speedMph < 4) {
          this.state = 'IDLE';
          this.reason = 'Speed dropped < 4 mph; aborted';
        } else if (goodFix && speedMph >= 8 && nowMs - this.verifyingSinceMs >= 2500) {
          this.state = 'RECORDING';
          this.reason = 'Sustained driving verified';
        }
      } else if (this.state === 'RECORDING') {
        if (goodFix && speedMph < 2) {
          this.state = 'STOP_PENDING';
          this.stopPendingSinceMs = nowMs;
          this.reason = `Stopped (< 2 mph); hysteresis ${Math.round(this.stopHysteresisMs / 1000)}s`;
        }
      } else if (this.state === 'STOP_PENDING') {
        if (goodFix && speedMph >= 4) {
          this.state = 'RECORDING';
          this.stopPendingSinceMs = 0;
          this.reason = `Resumed (${Math.round(speedMph)} mph); bridged stop`;
        } else if (nowMs - this.stopPendingSinceMs >= this.stopHysteresisMs) {
          this.state = 'FINALIZING';
          this.reason = 'Stop hysteresis elapsed -> finalizing';
        }
      } else if (this.state === 'FINALIZING') {
        this.state = 'IDLE';
        this.reason = 'Saved & ready for next trip';
      }
      return this.state;
    }
  }

  class DrivingEventDetector {
    constructor(ringBuffer) {
      this.ringBuffer = ringBuffer;
      this.reset(0);
    }
    reset(tripStartMs) {
      this.tripStartMs = tripStartMs;
      this.brakeStart = 0; this.brakePeak = 0; this.brakeLast = 0;
      this.accelStart = 0; this.accelPeak = 0; this.accelLast = 0;
      this.turnStart = 0; this.turnPeak = 0; this.turnSign = 1; this.turnLast = 0;
      this.handheldStart = 0; this.handheldLast = 0;
    }
    process(nowMs, longG, latG, speedMph, handheld) {
      const emitted = [];
      const elapsed = Math.max(0, (nowMs - this.tripStartMs) / 1000);

      // Hard Brake
      if (longG <= -0.35 && speedMph >= 5) {
        if (!this.brakeStart) { this.brakeStart = nowMs; this.brakePeak = -longG; }
        else this.brakePeak = Math.max(this.brakePeak, -longG);
      } else if (this.brakeStart) {
        const dur = nowMs - this.brakeStart;
        if (dur >= 300 && nowMs - this.brakeLast >= 3500) {
          this.brakeLast = nowMs;
          emitted.push(this._makeEvent('HARD_BRAKE', 'Hard Brake', nowMs, elapsed, `-${this.brakePeak.toFixed(2)}g (${dur}ms)`));
        }
        this.brakeStart = 0;
      }

      // Rapid Accel
      if (longG >= 0.30 && speedMph >= 3) {
        if (!this.accelStart) { this.accelStart = nowMs; this.accelPeak = longG; }
        else this.accelPeak = Math.max(this.accelPeak, longG);
      } else if (this.accelStart) {
        const dur = nowMs - this.accelStart;
        if (dur >= 350 && nowMs - this.accelLast >= 3500) {
          this.accelLast = nowMs;
          emitted.push(this._makeEvent('RAPID_ACCEL', 'Rapid Accel', nowMs, elapsed, `+${this.accelPeak.toFixed(2)}g (${dur}ms)`));
        }
        this.accelStart = 0;
      }

      // Harsh Corner
      if (Math.abs(latG) >= 0.35 && speedMph >= 8) {
        if (!this.turnStart) { this.turnStart = nowMs; this.turnPeak = Math.abs(latG); this.turnSign = latG < 0 ? -1 : 1; }
        else this.turnPeak = Math.max(this.turnPeak, Math.abs(latG));
      } else if (this.turnStart) {
        const dur = nowMs - this.turnStart;
        if (dur >= 350 && nowMs - this.turnLast >= 3500) {
          this.turnLast = nowMs;
          const type = this.turnSign < 0 ? 'HARSH_CORNER_LEFT' : 'HARSH_CORNER_RIGHT';
          const name = this.turnSign < 0 ? 'Harsh Left Turn' : 'Harsh Right Turn';
          emitted.push(this._makeEvent(type, name, nowMs, elapsed, `${this.turnPeak.toFixed(2)}g (${dur}ms)`));
        }
        this.turnStart = 0;
      }

      // Handheld
      if (handheld && speedMph >= 10) {
        if (!this.handheldStart) this.handheldStart = nowMs;
      } else if (this.handheldStart) {
        const dur = nowMs - this.handheldStart;
        if (dur >= 1800 && nowMs - this.handheldLast >= 8000) {
          this.handheldLast = nowMs;
          emitted.push(this._makeEvent('DISTRACTION_HANDHELD', 'Handheld Phone Use', nowMs, elapsed, `${(dur / 1000).toFixed(1)}s while moving`));
        }
        this.handheldStart = 0;
      }

      return emitted;
    }

    evaluateStage2Impact(nowMs, peakG, peakGyroRadS, preSpeedMph, postSpeedMph) {
      const elapsed = Math.max(0, (nowMs - this.tripStartMs) / 1000);
      const deltaV = Math.max(0, preSpeedMph - postSpeedMph);
      const isCollision = preSpeedMph >= 12 && deltaV >= 8;
      if (isCollision) {
        return {
          verdict: 'POTENTIAL_IMPACT',
          event: this._makeEvent(
            'POTENTIAL_IMPACT',
            'Potential Impact (Stage 2 Confirmed)',
            nowMs,
            elapsed,
            `Peak ${peakG.toFixed(1)}g | Δv=${Math.round(deltaV)} mph (${Math.round(preSpeedMph)}->${Math.round(postSpeedMph)})`
          )
        };
      }
      return {
        verdict: 'REJECTED_PHONE_DROP',
        event: this._makeEvent(
          'REJECTED_PHONE_DROP',
          'Phone Drop Rejected (Stage 2)',
          nowMs,
          elapsed,
          `Peak ${peakG.toFixed(1)}g, gyro ${peakGyroRadS.toFixed(1)} rad/s, Δv=${Math.round(deltaV)} mph (< 8)`
        )
      };
    }

    _makeEvent(type, displayName, nowMs, elapsedSec, details) {
      const snippet = this.ringBuffer.filter((s) => s.timestampMs >= nowMs - 2500 && s.timestampMs <= nowMs + 2500);
      return {
        id: `${type}_${Math.round(nowMs)}`,
        type,
        displayName,
        timestampMs: nowMs,
        elapsedTripSec: elapsedSec,
        details,
        waveformSnippet: snippet.slice(-150)
      };
    }
  }

  function computeSafetyScore(events, distanceMiles) {
    const norm = Math.max(0.25, distanceMiles);
    const count = (t) => events.filter((e) => e.type === t).length;
    const braking = Math.max(20, Math.min(100, Math.round(100 - (count('HARD_BRAKE') / norm) * 14)));
    const acceleration = Math.max(20, Math.min(100, Math.round(100 - (count('RAPID_ACCEL') / norm) * 12)));
    const cornering = Math.max(20, Math.min(100, Math.round(100 - ((count('HARSH_CORNER_LEFT') + count('HARSH_CORNER_RIGHT')) / norm) * 14)));
    const speeding = Math.max(20, Math.min(100, Math.round(100 - (count('SPEEDING') / norm) * 18)));
    const distraction = Math.max(15, Math.min(100, Math.round(100 - (count('DISTRACTION_HANDHELD') * 16 + count('DISTRACTION_SCREEN') * 8) / norm)));
    const impacts = count('POTENTIAL_IMPACT');
    const overall = Math.max(0, Math.min(100, Math.round(
      braking * 0.25 + acceleration * 0.15 + cornering * 0.20 + speeding * 0.20 + distraction * 0.20
    ) - impacts * 20));
    return { overall, braking, acceleration, cornering, speeding, distraction };
  }

  function parseImportedTrip(text, filename = '') {
    const trimmed = text.trim();
    if (trimmed.startsWith('{')) {
      const obj = JSON.parse(trimmed);
      return {
        id: obj.id || 'imported',
        durationSec: obj.durationSec || 0,
        distanceMiles: obj.distanceMiles || 0,
        score: obj.score || { overall: 100, braking: 100, acceleration: 100, cornering: 100, speeding: 100, distraction: 100 },
        rawPayloadBytes: obj.rawPayloadBytes || trimmed.length,
        gzipPayloadBytes: obj.gzipPayloadBytes || Math.round(trimmed.length * 0.26),
        idempotentChunkCount: obj.idempotentChunkCount || 1,
        track: (obj.track || []).map((p) => ({
          elapsedSec: p.es ?? p.elapsedSec ?? 0,
          timestampMs: p.ts ?? p.timestampMs ?? 0,
          lat: p.lat ?? 0,
          lon: p.lon ?? 0,
          speedMph: p.sp ?? p.speedMph ?? 0,
          bearingDeg: p.br ?? p.bearingDeg ?? 0,
          accuracyM: p.ac ?? p.accuracyM ?? 5,
          satellitesUsed: p.sat ?? p.satellitesUsed ?? 12,
          longG: p.lg ?? p.longG ?? 0,
          latG: p.lt ?? p.latG ?? 0,
          vertG: p.vt ?? p.vertG ?? 0,
          rawTotalG: p.rt ?? p.rawTotalG ?? 1,
          handheld: p.hh ?? p.handheld ?? false
        })),
        events: (obj.events || []).map((e) => ({
          id: e.id || String(e.timestampMs),
          type: e.type,
          displayName: e.displayName || e.type,
          elapsedTripSec: e.elapsedTripSec || 0,
          details: e.details || '',
          waveformSnippet: (e.waveformSnippet || []).map((s) => ({
            timestampMs: s.ts ?? s.timestampMs ?? 0,
            longG: s.lg ?? s.longG ?? 0,
            latG: s.lt ?? s.latG ?? 0
          }))
        }))
      };
    }

    // CSV format exported by Telematics Lab
    const lines = trimmed.split(/\r?\n/);
    const track = [];
    for (const line of lines) {
      if (!line || line.startsWith('#') || line.startsWith('elapsed_sec')) continue;
      const cols = line.split(',');
      if (cols.length < 12) continue;
      track.push({
        elapsedSec: parseFloat(cols[0]) || 0,
        timestampMs: parseInt(cols[1], 10) || 0,
        lat: parseFloat(cols[2]) || 0,
        lon: parseFloat(cols[3]) || 0,
        speedMph: parseFloat(cols[4]) || 0,
        bearingDeg: parseFloat(cols[5]) || 0,
        accuracyM: parseFloat(cols[6]) || 5,
        satellitesUsed: parseInt(cols[7], 10) || 10,
        longG: parseFloat(cols[8]) || 0,
        latG: parseFloat(cols[9]) || 0,
        vertG: parseFloat(cols[10]) || 0,
        rawTotalG: parseFloat(cols[11]) || 1,
        handheld: cols[14] === 'true'
      });
    }
    const durationSec = track.length ? Math.round(track[track.length - 1].elapsedSec) : 0;
    return {
      id: filename.replace(/\.\w+$/, '') || 'csv-trip',
      durationSec,
      distanceMiles: track.reduce((acc, p, idx) => idx === 0 ? 0 : acc + (p.speedMph / 3600) * Math.max(0, p.elapsedSec - track[idx - 1].elapsedSec), 0),
      score: { overall: 95, braking: 95, acceleration: 95, cornering: 95, speeding: 95, distraction: 95 },
      rawPayloadBytes: trimmed.length,
      gzipPayloadBytes: Math.round(trimmed.length * 0.28),
      idempotentChunkCount: Math.max(1, Math.ceil(trimmed.length * 0.28 / 16384)),
      track,
      events: []
    };
  }

  return {
    GRAVITY_MS2,
    buildRotationMatrix,
    SensorEngineBridge,
    CoordinateFrameMapper,
    WakeLockPolicyEngine,
    TripStateMachine,
    DrivingEventDetector,
    computeSafetyScore,
    parseImportedTrip
  };
}));
