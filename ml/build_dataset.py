"""Build the WESAD feature table, caching to CSV so training is not repeated."""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from wesad_features import extract_subject, add_baseline_and_temporal

# Column order: the schema features, then bookkeeping. Kept explicit so the CSV is stable.
FEATURE_COLUMNS = [
    "hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "sdsd_ms", "pnn50_pct",
    "hr_z", "rmssd_z", "sdnn_z", "mean_nn_z",
    "resp_rate_bpm", "resp_rate_z",
    "activity_recent_norm",
    "hr_delta_recent", "rmssd_delta_recent", "hr_slope_recent", "rmssd_slope_recent",
    "ppg_quality", "confidence", "valid_nn_ratio", "artifact_ratio", "duration_s",
]
BOOKKEEPING = ["subject", "start_s", "label", "nn_count"]

LABEL_STRESS = 2


def build(root: Path, out_csv: Path, limit: int | None = None) -> int:
    subjects = sorted(
        p for p in root.iterdir()
        if p.is_dir() and (p / f"{p.name}.pkl").exists()
    )
    if limit:
        subjects = subjects[:limit]

    all_rows: list[dict] = []
    for i, sdir in enumerate(subjects, 1):
        print(f"[{i}/{len(subjects)}] {sdir.name} ...", flush=True)
        rows = extract_subject(sdir / f"{sdir.name}.pkl")
        print(f"    windows: {len(rows)}", flush=True)
        all_rows.extend(rows)
        del rows

    all_rows = add_baseline_and_temporal(all_rows)

    # Binary target: stress vs non-stress (baseline + amusement).
    for r in all_rows:
        r["target"] = 1 if r["label"] == LABEL_STRESS else 0

    out_csv.parent.mkdir(parents=True, exist_ok=True)
    with open(out_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=BOOKKEEPING + FEATURE_COLUMNS + ["target"])
        w.writeheader()
        for r in all_rows:
            row = {k: r.get(k) for k in w.fieldnames}
            w.writerow(row)

    n = len(all_rows)
    pos = sum(1 for r in all_rows if r["target"] == 1)
    print(f"\nwrote {out_csv} : {n} windows, {pos} stress / {n - pos} non-stress, "
          f"{len(subjects)} subjects")
    return n


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="directory containing S2, S3, ... folders")
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=None)
    args = ap.parse_args()
    build(Path(args.root), Path(args.out), args.limit)


if __name__ == "__main__":
    main()