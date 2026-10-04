"""Cross-check peak-detected HR against an independent autocorrelation estimate."""
import pickle
import sys
import numpy as np
from scipy.signal import butter, filtfilt, detrend
sys.path.insert(0, ".")
from wesad_features import nn_intervals, FS_ECG

path = sys.argv[1]
out = open(sys.argv[2], "w", encoding="utf-8")


def p(*a):
    print(*a, file=out)


def ac_hr(seg, fs=FS_ECG, lo=40.0, hi=180.0):
    """Dominant rate from the autocorrelation of the band-passed signal's envelope (FFT-based)."""
    x = detrend(seg)
    b, a = butter(3, [5.0 / (fs / 2), 20.0 / (fs / 2)], btype="band")
    x = filtfilt(b, a, x)
    env = np.abs(x)
    win = int(0.15 * fs)
    env = np.convolve(env, np.ones(win) / win, mode="same")
    env = env - env.mean()
    # FFT autocorrelation: O(n log n) instead of O(n^2), which matters on multi-minute stretches.
    n = 1 << int(np.ceil(np.log2(2 * env.size)))
    f = np.fft.rfft(env, n)
    ac = np.fft.irfft(f * np.conj(f), n)[: env.size]
    ac /= ac[0] if ac[0] > 0 else 1.0
    # Lag for a rate of H bpm is fs*60/H samples.
    lo_i = max(1, int(fs * 60.0 / hi))
    hi_i = min(ac.size - 1, int(fs * 60.0 / lo))
    if hi_i <= lo_i:
        return float("nan")
    lag = lo_i + int(np.argmax(ac[lo_i:hi_i]))
    return 60.0 * fs / lag


with open(path, "rb") as f:
    d = pickle.load(f, encoding="latin1")
ecg = np.asarray(d["signal"]["chest"]["ECG"]).reshape(-1)
lab = np.asarray(d["label"]).reshape(-1)

NAMES = {1: "baseline", 2: "stress", 3: "amusement", 4: "meditation"}
runs = []
start = 0
for i in range(1, len(lab)):
    if lab[i] != lab[i - 1]:
        runs.append((start, i, lab[i - 1]))
        start = i
runs.append((start, len(lab), lab[-1]))

p(f"{'cond':<11s} {'dur_s':>6s} {'peakHR':>7s} {'acHR':>7s}  NN histogram (100ms bins, 0.25-1.60s)")
for s, e, lv in runs:
    if e - s < 60 * FS_ECG or int(lv) not in NAMES:
        continue
    seg = ecg[s:e]
    nn = nn_intervals(seg, FS_ECG)
    if nn.size < 10:
        continue
    hr = 60000.0 / nn.mean()
    ac = ac_hr(seg)
    hist, edges = np.histogram(nn, bins=np.arange(250, 1650, 100))
    top = " ".join(f"{int(edges[i])}:{hist[i]}" for i in np.argsort(hist)[::-1][:6])
    p(f"{NAMES[int(lv)]:<11s} {(e-s)/FS_ECG:6.0f} {hr:7.1f} {ac:7.1f}  {top}")
out.close()