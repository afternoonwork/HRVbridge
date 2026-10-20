#!/usr/bin/env python3
"""Generates core/src/test/resources/test-vectors.json.

This is an independent reference implementation of the parser, artefact
filter and metrics described in CLAUDE.md. The Kotlin (and later Swift) test
suites must reproduce its outputs. Arithmetic is written as plain sequential
loops so floating-point results match a straightforward implementation in
any IEEE 754 language.

Rounding rule (all values are positive): n = floor(x * 1e9 + 0.5) evaluated
in IEEE double (an exact integer), then n / 10^9 rounded half up (away from
zero) to the target number of decimals in decimal arithmetic. The first step
pins the double to 9 decimals so ties such as 1.15 (stored as 1.1499999...)
round up as written.

Usage: python3 -I tools/gen_test_vectors.py > core/src/test/resources/test-vectors.json
"""
import json
import math
from decimal import ROUND_HALF_UP, Decimal

MIN_MS, MAX_MS, MAX_REL, WINDOW = 300.0, 2000.0, 0.20, 5
MAX_REMOVED, MIN_VALID = 0.05, 40


def parse(hexstr):
    b = bytes.fromhex(hexstr)
    if len(b) < 2:
        return None
    flags = b[0]
    i = 1
    if flags & 0x01:
        if len(b) < 3:
            return None
        hr = b[1] | (b[2] << 8)
        i = 3
    else:
        hr = b[1]
        i = 2
    ee = None
    if flags & 0x08:
        if len(b) < i + 2:
            return None
        ee = b[i] | (b[i + 1] << 8)
        i += 2
    rr = []
    dropped = False
    if flags & 0x10:
        while i + 1 < len(b):
            rr.append((b[i] | (b[i + 1] << 8)) * 1000.0 / 1024.0)
            i += 2
        dropped = i < len(b)
    return {
        "heartRate": hr,
        "contactSupported": bool(flags & 0x04),
        "contactDetected": bool(flags & 0x02),
        "energyExpended": ee,
        "rrPresent": bool(flags & 0x10),
        "rrMs": rr,
        "droppedTrailingByte": dropped,
    }


def median(xs):
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2.0


def filt(rr):
    in_range = [x for x in rr if MIN_MS <= x <= MAX_MS]
    seed = median(in_range[:WINDOW]) if in_range else None
    accepted, reasons, acc_vals = [], [], []
    for x in rr:
        if not (MIN_MS <= x <= MAX_MS):
            accepted.append(False)
            reasons.append("OUT_OF_RANGE")
            continue
        ref = median(acc_vals[-WINDOW:]) if acc_vals else seed
        if abs(x - ref) / ref > MAX_REL:
            accepted.append(False)
            reasons.append("RELATIVE")
            continue
        accepted.append(True)
        reasons.append(None)
        acc_vals.append(x)
    return accepted, reasons


def half_up(x, decimals):
    assert x >= 0
    nano = math.floor(x * 1e9 + 0.5)
    q = (Decimal(nano).scaleb(-9)).quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_HALF_UP)
    return float(q)


# (value, decimals, expected): expected is written by hand, the generator
# asserts its own rule agrees before emitting.
ROUNDING = [
    (68.45, 1, 68.5),
    (1.15, 1, 1.2),
    (0.15, 1, 0.2),
    (2.65, 1, 2.7),
    (68.35, 1, 68.4),
    (0.05, 1, 0.1),
    (123.25, 1, 123.3),
    (68.44999, 1, 68.4),
    (68.4499999994, 1, 68.4),
    (68.4499999996, 1, 68.5),
    (0.0, 1, 0.0),
    (72.5, 0, 73.0),
    (71.5, 0, 72.0),
    (71.49999, 0, 71.0),
    (59.99999999995, 0, 60.0),
]


def metrics(rr, accepted):
    nn = [x for x, a in zip(rr, accepted) if a]
    sq, pairs = 0.0, 0
    for i in range(len(rr) - 1):
        if accepted[i] and accepted[i + 1]:
            d = rr[i + 1] - rr[i]
            sq += d * d
            pairs += 1
    if len(nn) < 2 or pairs == 0:
        return None
    total = 0.0
    for x in nn:
        total += x
    mean = total / len(nn)
    ss = 0.0
    for x in nn:
        ss += (x - mean) * (x - mean)
    rmssd = math.sqrt(sq / pairs)
    sdnn = math.sqrt(ss / (len(nn) - 1))
    removed = len(rr) - len(nn)
    reliable = removed / len(rr) <= MAX_REMOVED and len(nn) >= MIN_VALID
    return {
        "meanNnMs": mean,
        "restingHr": int(half_up(60000.0 / mean, 0)),
        "rmssdMs": rmssd,
        "sdnnMs": sdnn,
        "rmssdRounded": half_up(rmssd, 1),
        "sdnnRounded": half_up(sdnn, 1),
        "acceptedBeats": len(nn),
        "removedBeats": removed,
        "validPairs": pairs,
        "reliable": reliable,
    }


def clean(n, base=1000.0, start=0):
    # Deterministic resting-like rhythm: respiratory sinus arrhythmia plus a
    # small pseudo-random term (LCG), quantised to 1/1024 s like real sensors.
    out, state = [], 12345 + start
    for i in range(n):
        state = (1103515245 * state + 12345) % (2 ** 31)
        noise = (state / 2 ** 31 - 0.5) * 30.0
        ms = base + 45.0 * math.sin(2 * math.pi * (i + start) / 4.5) + noise
        raw = round(ms * 1024.0 / 1000.0)
        out.append(raw * 1000.0 / 1024.0)
    return out


PACKETS = [
    ("uint8 HR, no RR", "0048"),
    ("uint8 HR, contact detected, one RR", "163C0004"),
    ("two RR values", "1040C003D403"),
    ("uint16 HR, one RR", "114B003303"),
    ("energy expended present, one RR", "18500201E803"),
    ("poor contact (supported, not detected)", "0400"),
    ("odd trailing byte dropped", "103C000405"),
    ("RR flag set but no RR values", "103C"),
    ("all fields", "1F5000E80300040002"),
    ("empty packet", ""),
    ("flags only", "10"),
    ("uint16 HR truncated", "0148"),
    ("energy expended truncated", "084801"),
]


def series_cases():
    c = []
    c.append(("clean 70 beats", clean(70)))
    s = clean(70)
    s = s[:30] + [600.0, 1400.0] + s[30:]
    c.append(("ectopic beat with compensatory pause", s))
    s = clean(70)
    s = s[:10] + [250.0] + s[10:40] + [2100.0] + s[40:]
    c.append(("out of range values", s))
    s = clean(60)
    s[5] = s[20] = s[40] = 250.0
    c.append(("exactly 5 percent removed", s))
    s = clean(60)
    s[5] = s[20] = s[40] = s[50] = 250.0
    c.append(("more than 5 percent removed", s))
    c.append(("too few beats", clean(35, base=1500.0)))
    c.append(("first beat artefact", [1800.0] + clean(60)))
    c.append(("first beat artefact, short", [600.0] + clean(60)))
    c.append(("first two beats artefacts", [1800.0, 450.0] + clean(60)))
    c.append(("relative boundary exactly 20 percent accepted",
              [1000.0] * 5 + [1200.0] + [1000.0] * 44))
    c.append(("relative just over 20 percent rejected",
              [1000.0] * 5 + [1200.5] + [1000.0] * 44))
    c.append(("minimum range boundary inclusive", [300.0] * 45))
    c.append(("maximum range boundary inclusive", [2000.0] * 45))
    c.append(("just outside range", [299.9] * 3 + [2000.1] * 3))
    c.append(("single beat", [1000.0]))
    c.append(("empty", []))
    return c


def main():
    packets = []
    for name, hx in PACKETS:
        packets.append({"name": name, "hex": hx, "expected": parse(hx)})
    rounding = []
    for x, d, want in ROUNDING:
        got = half_up(x, d)
        assert got == want, (x, d, got, want)
        rounding.append({"value": x, "decimals": d, "expected": want})
    series = []
    for name, rr in series_cases():
        acc, reasons = filt(rr)
        series.append({
            "name": name,
            "rrMs": rr,
            "expected": {
                "accepted": acc,
                "rejectReasons": reasons,
                "metrics": metrics(rr, acc),
            },
        })
    doc = {
        "version": 1,
        "description": "Shared contract for HRV Bridge core logic. Generated by tools/gen_test_vectors.py.",
        "roundingRule": "n = floor(x * 1e9 + 0.5) in IEEE double, then n / 10^9 rounded half away from zero in decimal arithmetic. All values are positive.",
        "config": {
            "minRrMs": MIN_MS, "maxRrMs": MAX_MS, "maxRelativeDeviation": MAX_REL,
            "medianWindow": WINDOW, "maxRemovedFraction": MAX_REMOVED, "minValidBeats": MIN_VALID,
        },
        "rounding": rounding,
        "packets": packets,
        "series": series,
    }
    print(json.dumps(doc, indent=1))


if __name__ == "__main__":
    main()
