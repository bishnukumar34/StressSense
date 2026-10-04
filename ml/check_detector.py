"""Sanity-check R-peak detection and HRV metrics against real WESAD ECG."""
import pickle
import sys
import traceback
import numpy as np

sys.path.insert(0, ".")
out = open(sys.argv[2], "w", encoding="utf-8")


def p(*a):
    print(*a, file=out)


try:
    from wesad_features import detect_r_peaks, FS_ECG, LABEL_BASELINE, LABEL_STRESS, LABEL_AMUSEMENT

    with open(sys.argv[1], "rb") as f:
        d = pickle.load(f, encoding="latin1")

    ecg = np.asarray(d["signal"]["chest"]["ECG"]).reshape(-1)
    lab = np.asarray(d["label"]).reshape(-1)
    p(f"signal: {len(ecg)/FS_ECG:.0f} s @ {FS_ECG} Hz")

    for name, lv in (("baseline", LABEL_BASELINE), ("stress", LABEL_STRESS),
                     ("amusement", LABEL_AMUSEMENT)):
        idx = np.flatnonzero(lab == lv)
        if idx.size == 0:
            continue
        # longest contiguous stretch of this label
        best_start, best_len, cur_start, cur_len = idx[0], 0, idx[0], 0
        for i in range(1, idx.size):
            if idx[i] != idx[i - 1] + 1:
                cur_start, cur_len = idx[i], 1
            else:
                cur_len += 1
            if cur_len > best_len:
                best_len, best_start = cur_len, cur_start
        start = best_start
        n = min(300 * FS_ECG, len(ecg) - start)
        seg = ecg[start:start + n]
        peaks = detect_r_peaks(seg, FS_ECG)
        if peaks.size < 3:
            p(f"{name:9s}: TOO FEW PEAKS ({peaks.size})")
            continue
        nn = np.diff(peaks) * 1000.0 / FS_ECG
        nn = nn[(nn >= 300) & (nn <= 2000)]
        hr = 60000.0 / nn.mean() if nn.size else float("nan")
        rmssd = float(np.sqrt(np.mean(np.diff(nn) ** 2))) if nn.size > 1 else float("nan")
        sdnn = float(np.std(nn, ddof=1)) if nn.size > 1 else float("nan")
        p(f"{name:9s}: {n/FS_ECG:.0f}s beats={peaks.size:4d} valid_nn={nn.size:4d} "
          f"HR={hr:6.1f} bpm  RMSSD={rmssd:7.2f} ms  SDNN={sdnn:7.2f} ms")
except Exception:
    traceback.print_exc(file=out)
finally:
    out.close()