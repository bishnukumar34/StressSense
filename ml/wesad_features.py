"""
Derive StressSense deployment features from the WESAD dataset.

Every feature produced here has the *same definition* as its counterpart in
`app/src/main/java/com/bishnu/stresswelfarebridge/ml/FeatureSchema.kt`. That correspondence is the
whole point of this module: the model may only be trained on quantities that StressSense can also
recreate at inference time (Phase 10). Anything WESAD has that the phone cannot measure - EDA, skin
temperature, EMG, the chest accelerometer - is deliberately not extracted here.

What is extracted, and what it stands in for on device:

  WESAD source              -> feature             deployment equivalent
  ---------------------------------------------------------------
  chest ECG (700 Hz)        -> hr, rmssd, sdnn,    camera PPG / Health Connect
                               mean_nn, sdsd, pnn50    HR + HRV
  chest respiration (700Hz) -> resp_rate_bpm       Health Connect respiratory rate
  wrist accelerometer (32Hz)-> activity_recent_norm Health Connect steps / active minutes
  wrist BVP (64 Hz)         -> ppg_quality,        camera PPG acquisition quality
                               valid_nn_ratio

NOT extracted, with reasons (recorded rather than silently dropped):
  EDA / TEMP / EMG / chest ACC - no phone equivalent; using them would train the model on signals
                                  that do not exist at inference time.
  sleep duration / quality    - WESAD is a daytime lab protocol and contains no sleep data at all.
  SpO2                        - WESAD carries no oximetry.
  height / weight / BMI       - WESAD carries no anthropometrics.

Labels (from `pickle['label']`, 700 Hz):
  0 = undefined, 1 = baseline, 2 = stress, 3 = amusement, 4 = meditation,
  5/6/7 = transition/ignore segments.

Binary task: stress (2) vs non-stress (1 baseline + 3 amusement), which is the standard WESAD
"stress vs non-stress" setup. Meditation (4) and every transition/undefined segment is excluded
rather than silently folded into the negative class.
"""

from __future__ import annotations

import pickle
from pathlib import Path

import numpy as np
from scipy.signal import butter, filtfilt, find_peaks, sosfiltfilt

# -- constants shared with the Kotlin side ------------------------------------------------------
FS_ECG = 700          # Hz, chest RespiBAN
FS_WRIST = 32         # Hz, wrist accelerometer
FS_BVP = 64           # Hz, wrist Empatica E4 BVP

WINDOW_S = 60         # analysis window; ~60 s matches a real camera HRV measurement

LABEL_BASELINE, LABEL_STRESS, LABEL_AMUSEMENT = 1, 2, 3
NEGATIVE_LABELS = (LABEL_BASELINE, LABEL_AMUSEMENT)

# Physiological acceptance ranges; identical to PersonalBaseline.ranges in Kotlin.
RANGES = {
    "hr_bpm": (30.0, 220.0),
    "rmssd_ms": (1.0, 300.0),
    "sdnn_ms": (1.0, 300.0),
    "mean_nn_ms": (200.0, 2400.0),
    "sdsd_ms": (0.0, 300.0),
    "pnn50_pct": (0.0, 100.0),
    "resp_rate_bpm": (4.0, 60.0),
    "activity_recent_norm": (0.0, 1.0),
    "duration_s": (1.0, 1800.0),
}

# z-score denominator floors; identical to PersonalBaseline.floors in Kotlin.
FLOORS = {
    "hr_bpm": 2.0,
    "rmssd_ms": 1.0,
    "sdnn_ms": 2.0,
    "mean_nn_ms": 5.0,
    "sdsd_ms": 1.0,
    "pnn50_pct": 1.0,
    "resp_rate_bpm": 0.5,
}

MIN_SAMPLES_FOR_BASELINE = 5   # matches PersonalBaseline.MIN_SAMPLES

# R-peak detector tuning.
T_ACCEPT_FRAC = 0.5     # a candidate must reach this fraction of the running R-peak amplitude
REFRACTORY_S = 0.30     # minimum beat-to-beat interval (caps detection at 200 bpm)

LABEL_PURITY = 0.99    # fraction of a window that must carry the same condition label
MIN_SLOPE_POINTS = 3   # matches ObservationHistory.MIN_SLOPE_POINTS


def _in_range(name: str, value: float) -> bool:
    lo, hi = RANGES[name]
    return lo <= value <= hi


def robust_center_scale(values: np.ndarray, name: str):
    """Median / (1.4826 * MAD), with the same floor as the Kotlin baseline."""
    med = float(np.median(values))
    mad = float(np.median(np.abs(values - med)))
    scale = 1.4826 * mad
    floor = FLOORS.get(name, 1.0)
    if not np.isfinite(scale) or scale < floor:
        scale = floor
    return med, scale


# -- ECG R-peak detection -----------------------------------------------------------------------

def detect_r_peaks(ecg: np.ndarray, fs: int = FS_ECG) -> np.ndarray:
    """
    R-peak locations via an adaptive-threshold Pan-Tompkins-style detector.

    Band-pass to the QRS band, differentiate, square, integrate over 150 ms, then walk the candidate
    peaks **in time order** maintaining a running estimate of the true R-peak amplitude
    (`running_pk`) and requiring every accepted beat to reach [T_ACCEPT_FRAC] of it.

    That requirement is the whole ballgame. Without it the T wave - which is a genuine local
    maximum roughly 300 ms after each R wave - is accepted as a beat, every RR interval is halved,
    and both HR and HRV come out wrong. With it, a T wave at ~40 % of the R amplitude fails the test
    while the next real R wave, at ~100 %, passes.

    A refractory period of [REFRACTORY_S] additionally caps the detector at 1/REFRACTORY_S.
    """
    x = np.asarray(ecg, dtype=np.float64).reshape(-1)
    if x.size < fs:
        return np.array([], dtype=int)

    nyq = fs / 2.0
    # SOS form throughout: transfer-coefficient `filtfilt` is numerically unstable at these narrow
    # bands and will silently produce ~1e26 samples.
    sos = butter(3, [5.0 / nyq, 20.0 / nyq], btype="band", output="sos")
    x = sosfiltfilt(sos, x)

    # Differentiate + square to emphasise QRS energy, then integrate over 150 ms.
    d = np.gradient(x)
    win = max(1, int(0.15 * fs))
    integ = np.convolve(d * d, np.ones(win) / win, mode="same")

    # Candidates with a deliberately permissive separation; the adaptive logic below does the real
    # discrimination, so the candidate list must not pre-filter anything.
    candidates, _ = find_peaks(integ, distance=max(1, int(0.10 * fs)))
    if candidates.size < 3:
        return np.array([], dtype=int)

    heights = integ[candidates]
    # Seed the running peak level from the tall half of the candidates, and the threshold at half
    # of that, so the first few beats are judged against the QRS rather than against noise.
    running_pk = float(np.median(heights[heights >= np.percentile(heights, 50.0)]))
    threshold = T_ACCEPT_FRAC * running_pk

    refractory = int(REFRACTORY_S * fs)
    accepted: list[int] = []
    last_accepted = -(10 ** 9)

    for idx, p in enumerate(candidates):
        h = float(heights[idx])
        if h < threshold:
            continue
        if p - last_accepted < refractory:
            # Inside the refractory window: if this candidate is clearly taller than the one already
            # taken, it was the better beat position. Otherwise keep the earlier one.
            if accepted and h > 1.25 * integ[accepted[-1]]:
                accepted[-1] = p
                running_pk = 0.25 * h + 0.75 * running_pk
                threshold = T_ACCEPT_FRAC * running_pk
            continue
        accepted.append(int(p))
        last_accepted = int(p)
        running_pk = 0.25 * h + 0.75 * running_pk
        threshold = T_ACCEPT_FRAC * running_pk

    if len(accepted) < 2:
        return np.array([], dtype=int)
    return np.array(accepted, dtype=int)


def nn_intervals(ecg: np.ndarray, fs: int = FS_ECG) -> np.ndarray:
    """Successive R-peak differences in ms, with physiologically impossible steps removed."""
    peaks = detect_r_peaks(ecg, fs)
    if peaks.size < 2:
        return np.array([], dtype=np.float64)
    nn = np.diff(peaks).astype(np.float64) * (1000.0 / fs)
    # A beat interval below 300 ms or above 2000 ms is a detection artefact, not physiology.
    return nn[(nn >= 300.0) & (nn <= 2000.0)]


def hrv_metrics(nn: np.ndarray) -> dict:
    """
    Time-domain HRV metrics, with exactly the definitions used on device in `HrvCalculator`.

      RMSSD = sqrt( mean( (NN[i+1] - NN[i])^2 ) )            successive differences
      SDNN  = std(NN, ddof=1)
      SDSD  = std( NN[i+1] - NN[i], ddof=1 )
      pNN50 = 100 * share of |NN[i+1] - NN[i]| > 50 ms
      meanNN = mean(NN)

    A window with fewer than MIN_NN valid intervals yields no metrics at all, matching the
    on-device rule that HRV is never reported from a handful of beats.
    """
    MIN_NN = 20
    if nn.size < MIN_NN:
        return {}
    diff = np.diff(nn)
    return {
        "hr_bpm": 60000.0 / float(nn.mean()),
        "mean_nn_ms": float(nn.mean()),
        "rmssd_ms": float(np.sqrt(np.mean(diff ** 2))) if diff.size else None,
        "sdnn_ms": float(np.std(nn, ddof=1)) if nn.size > 1 else None,
        "sdsd_ms": float(np.std(diff, ddof=1)) if diff.size > 1 else None,
        "pnn50_pct": float(100.0 * np.mean(np.abs(diff) > 50.0)) if diff.size else None,
        "nn_count": int(nn.size),
    }


def respiration_rate(resp: np.ndarray, fs: int = FS_ECG) -> float | None:
    """
    Breaths per minute, from negative-going zero crossings of the band-passed respiratory trace.

    Implemented with second-order sections (`sos` + `sosfiltfilt`) rather than transfer
    coefficients. At these cut-offs (0.1 Hz against a 700 Hz sample rate) a plain
    `butter(...) + filtfilt(...)` is numerically catastrophic - it returns values around 1e26 and
    therefore zero crossings. SOS form factors each biquad separately and stays stable, which is
    why the filter band here is safe to use directly.
    """
    if resp.size < 10 * fs:
        return None
    x = np.asarray(resp, dtype=np.float64).reshape(-1)
    nyq = fs / 2.0
    # 0.1-0.5 Hz spans roughly 6-30 breaths/min, the plausible resting/exercised range.
    sos = butter(3, [0.1 / nyq, 0.5 / nyq], btype="band", output="sos")
    x = sosfiltfilt(sos, x)
    sign = np.sign(x)
    crossings = np.flatnonzero(np.diff(sign) < 0)
    if crossings.size < 2:
        return None
    seconds = (crossings[-1] - crossings[0]) / fs
    if seconds <= 0:
        return None
    rate = 60.0 * (crossings.size - 1) / seconds
    return float(rate) if _in_range("resp_rate_bpm", rate) else None


def activity_index(acc_window: np.ndarray, scale: float) -> float | None:
    """
    Recent physical load, normalised to 0..1, from wrist accelerometer magnitude.

    Stands in for Health Connect steps / active minutes. `scale` is a per-subject robust reference
    (see FeatureSchema.ACTIVITY_NOTE for why this is a proxy rather than the same quantity).
    """
    if acc_window.size == 0 or scale <= 0:
        return None
    mag = np.linalg.norm(acc_window, axis=1)
    # Deviation from the subject's own gravity-inclusive mean captures movement, not orientation.
    deviation = float(np.mean(np.abs(mag - np.median(mag))))
    v = 1.0 - np.exp(-deviation / (scale + 1e-9))
    v = float(np.clip(v, 0.0, 1.0))
    return v if _in_range("activity_recent_norm", v) else None


def bvp_quality(bvp_window: np.ndarray) -> tuple[float, float]:
    """
    (quality 0..1, valid_nn_ratio 0..1) from the wrist BVP window.

    Quality blends the two properties that most reliably indicate a usable peripheral pulse: the
    modulation depth (AC/DC) and the beat-to-beat consistency of the pulse amplitudes. The valid
    ratio reflects the share of candidate intervals that were physiologically sized.

    This is the *training-side proxy* for the camera's acquisition quality. It is not the same
    algorithm as `SignalQualityEvaluator` on the device, and that difference is documented rather
    than hidden.
    """
    x = np.asarray(bvp_window, dtype=np.float64).reshape(-1)
    if x.size < 100:
        return 0.0, 0.0
    baseline = float(np.median(x))
    ac = float(np.std(x))
    if ac <= 0:
        return 0.0, 0.0
    ac_dc = ac / (abs(baseline) + 1e-9)

    peaks, _ = find_peaks(x, distance=int(0.30 * FS_BVP))
    amps = x[peaks]
    if amps.size < 5:
        return 0.0, 0.0
    amp_cv = float(np.std(amps) / (np.mean(np.abs(amps)) + 1e-9))
    amp_stability = float(np.clip(1.0 - amp_cv, 0.0, 1.0))
    depth = float(np.clip(ac_dc / 0.05, 0.0, 1.0))

    nn = np.diff(peaks) * (1000.0 / FS_BVP)
    plausible = np.sum((nn >= 300.0) & (nn <= 2000.0))
    valid_ratio = float(plausible / nn.size) if nn.size else 0.0

    quality = float(np.clip(0.5 * depth + 0.5 * amp_stability, 0.0, 1.0))
    return quality, valid_ratio


# -- per-subject extraction ----------------------------------------------------------------------

def extract_subject(path: Path) -> list[dict]:
    """
    Extract one subject's windows.

    A window is used only when it is almost entirely a single condition (>= [LABEL_PURITY] of the
    same non-transition label). Partly-labelled windows are dropped rather than assigned to the
    majority label, because a window straddling the baseline->stress transition carries no
    trustworthy target.
    """
    with open(path, "rb") as f:
        data = pickle.load(f, encoding="latin1")

    subject = str(data.get("subject", path.parent.name))
    chest = data["signal"]["chest"]
    wrist = data["signal"]["wrist"]

    ecg = np.asarray(chest["ECG"]).reshape(-1)
    resp = np.asarray(chest["Resp"]).reshape(-1)
    labels = np.asarray(data["label"]).reshape(-1)
    acc = np.asarray(wrist["ACC"], dtype=np.float64)      # (N,3) @ 32 Hz
    bvp = np.asarray(wrist["BVP"]).reshape(-1)           # @ 64 Hz

    # Robust per-subject movement reference for the activity normalisation.
    mag_all = np.linalg.norm(acc, axis=1)
    acc_scale = float(np.median(np.abs(mag_all - np.median(mag_all)))) or 1.0

    win = WINDOW_S * FS_ECG
    rows: list[dict] = []

    for start in range(0, len(labels) - win + 1, win):
        stop = start + win
        seg_labels = labels[start:stop]
        values, counts = np.unique(seg_labels, return_counts=True)
        dominant = int(values[np.argmax(counts)])
        purity = counts.max() / win
        if purity < LABEL_PURITY:
            continue
        if dominant not in (LABEL_BASELINE, LABEL_STRESS, LABEL_AMUSEMENT):
            continue

        row: dict = {"subject": subject, "start_s": start / FS_ECG, "label": dominant}

        nn = nn_intervals(ecg[start:stop], FS_ECG)
        row.update(hrv_metrics(nn))
        row["artifact_ratio"] = 0.0 if nn.size else 1.0

        row["resp_rate_bpm"] = respiration_rate(resp[start:stop], FS_ECG)

        # Wrist streams are at different rates; slice them with the same time span.
        a0 = int(start / FS_ECG * FS_WRIST)
        a1 = int(stop / FS_ECG * FS_WRIST)
        row["activity_recent_norm"] = activity_index(acc[a0:a1], acc_scale)

        b0 = int(start / FS_ECG * FS_BVP)
        b1 = int(stop / FS_ECG * FS_BVP)
        quality, valid_ratio = bvp_quality(bvp[b0:b1])
        row["ppg_quality"] = quality
        row["valid_nn_ratio"] = valid_ratio
        row["confidence"] = quality
        row["duration_s"] = float(WINDOW_S)

        rows.append(row)

    return rows


def add_baseline_and_temporal(rows: list[dict]) -> list[dict]:
    """
    Attach per-subject baseline z-scores and temporal features, in place.

    The personal baseline is built from that subject's own **baseline-condition** windows, which is
    the training analogue of the on-device rolling baseline. In leave-one-subject-out validation the
    held-out subject still supplies their own baseline from their own data — exactly as they would on
    the phone — while their *labels* are never used for fitting the classifier.

    Temporal features use only genuinely preceding windows. A window with no valid predecessor gets
    no delta and no slope, which is recorded as missing rather than filled.
    """
    by_subject: dict[str, list[dict]] = {}
    for r in rows:
        by_subject.setdefault(r["subject"], []).append(r)

    for subject, srows in by_subject.items():
        srows.sort(key=lambda r: r["start_s"])

        base_windows = [r for r in srows
                        if r["label"] == LABEL_BASELINE and r.get("hr_bpm") is not None]
        stats: dict[str, tuple[float, float]] = {}
        for key in ("hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "resp_rate_bpm"):
            vals = np.array([r[key] for r in base_windows if r.get(key) is not None],
                            dtype=np.float64)
            if vals.size >= MIN_SAMPLES_FOR_BASELINE:
                stats[key] = robust_center_scale(vals, key)

        for r in srows:
            for key, zname in (("hr_bpm", "hr_z"), ("rmssd_ms", "rmssd_z"),
                               ("sdnn_ms", "sdnn_z"), ("mean_nn_ms", "mean_nn_z"),
                               ("resp_rate_bpm", "resp_rate_z")):
                r[zname] = None
                if key in stats and r.get(key) is not None:
                    mu, scale = stats[key]
                    r[zname] = (r[key] - mu) / scale

        # Temporal features over the immediately preceding windows.
        for i, r in enumerate(srows):
            r["hr_delta_recent"] = None
            r["rmssd_delta_recent"] = None
            r["hr_slope_recent"] = None
            r["rmssd_slope_recent"] = None

            prev = srows[i - 1] if i >= 1 else None
            if prev is not None:
                if r.get("hr_bpm") is not None and prev.get("hr_bpm") is not None:
                    r["hr_delta_recent"] = r["hr_bpm"] - prev["hr_bpm"]
                if r.get("rmssd_ms") is not None and prev.get("rmssd_ms") is not None:
                    r["rmssd_delta_recent"] = r["rmssd_ms"] - prev["rmssd_ms"]

            for key, zname in (("hr_bpm", "hr_slope_recent"),
                               ("rmssd_ms", "rmssd_slope_recent")):
                window = [srows[j] for j in range(max(0, i - 5), i)
                          if srows[j].get(key) is not None]
                if r.get(key) is None or len(window) < MIN_SLOPE_POINTS:
                    continue
                ys = np.array([w[key] for w in window] + [r[key]], dtype=np.float64)
                xs = np.arange(len(ys), dtype=np.float64)
                slope = np.polyfit(xs, ys, 1)[0]
                r[zname] = float(slope)

    return rows
    quality = float(np.clip(0.5 * depth + 0.5 * amp_stability, 0.0, 1.0))
    return quality, valid_ratio