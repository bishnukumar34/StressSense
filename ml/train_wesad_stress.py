"""
Train the StressSense stress model on WESAD and export it for Android.

Protocol, in order:

  1. **Leave-one-subject-out (LOSO)** is the *only* split used for any reported number. A random
     window split would leak: consecutive windows from one person are nearly identical, so a random
     split reports a far better score than the model will ever achieve on a person it has not seen.
     (Phase 12.)
  2. **Feature-group ablation** (Phase 9) re-runs the same LOSO protocol for progressively richer
     feature sets, so the value of each data category is measured rather than assumed.
  3. **Model comparison** (Phase 11) fits logistic regression, random forest and gradient boosting
     under identical folds, and selects on balanced accuracy and subject-to-subject stability.
  4. **Calibration** (Phase 13) fits Platt scaling on out-of-fold predictions.
  5. **Export** writes the chosen model plus its feature order, imputation medians and calibration
     constants to a single JSON the Android app reads at runtime, along with a set of
     parity-test vectors (Phase 21).

Labels are WESAD's own condition annotations. Nothing here invents a label from an HRV value: the
target is "the protocol was in the stress condition", which the dataset states directly.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import GradientBoostingClassifier, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (accuracy_score, balanced_accuracy_score, brier_score_loss,
                             f1_score, precision_score, recall_score, roc_auc_score)
from sklearn.model_selection import LeaveOneGroupOut
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

# Canonical order - MUST equal FeatureSchema.NAMES in Kotlin.
FEATURES = [
    "hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "sdsd_ms", "pnn50_pct",
    "hr_z", "rmssd_z", "sdnn_z", "mean_nn_z",
    "resp_rate_bpm", "resp_rate_z",
    "activity_recent_norm",
    "hr_delta_recent", "rmssd_delta_recent", "hr_slope_recent", "rmssd_slope_recent",
    "ppg_quality", "confidence", "valid_nn_ratio", "artifact_ratio", "duration_s",
]

# Ablation ladder (Phase 9 models A-F).
ABLATION_SETS: dict[str, list[str]] = {
    "A_hr_hrv": [
        "hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "sdsd_ms", "pnn50_pct",
    ],
    "B_plus_baseline": [
        "hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "sdsd_ms", "pnn50_pct",
        "hr_z", "rmssd_z", "sdnn_z", "mean_nn_z",
    ],
    "C_plus_activity": [
        "hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "sdsd_ms", "pnn50_pct",
        "hr_z", "rmssd_z", "sdnn_z", "mean_nn_z",
        "activity_recent_norm",
    ],
    "D_plus_temporal": [
        "hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "sdsd_ms", "pnn50_pct",
        "hr_z", "rmssd_z", "sdnn_z", "mean_nn_z",
        "activity_recent_norm",
        "hr_delta_recent", "rmssd_delta_recent", "hr_slope_recent", "rmssd_slope_recent",
    ],
    "E_plus_respiration": [
        "hr_bpm", "rmssd_ms", "sdnn_ms", "mean_nn_ms", "sdsd_ms", "pnn50_pct",
        "hr_z", "rmssd_z", "sdnn_z", "mean_nn_z",
        "activity_recent_norm",
        "hr_delta_recent", "rmssd_delta_recent", "hr_slope_recent", "rmssd_slope_recent",
        "resp_rate_bpm", "resp_rate_z",
    ],
    "F_all_candidates": list(FEATURES),
}


def load(csv_path: Path) -> pd.DataFrame:
    df = pd.read_csv(csv_path)
    # A feature that is absent for every window cannot be used; make that explicit rather than
    # silently imputing a constant column.
    keep = []
    for c in FEATURES:
        if c in df.columns and df[c].notna().any():
            keep.append(c)
    if len(keep) != len(FEATURES):
        dropped = [c for c in FEATURES if c not in keep]
        print(f"  features with no data in this build: {dropped}")
    df = df.dropna(subset=["target"])
    return df, keep


def design(df: pd.DataFrame, features: list[str], medians: np.ndarray | None = None):
    """
    Build the model input: imputed values followed by per-feature missingness flags.

    Mirrors `StressModel.designMatrix` exactly. Missing entries become the training-set median and
    raise a flag, so "not measured" stays distinguishable from "measured as zero".
    """
    raw = df[features].to_numpy(dtype=float)
    mask = np.isnan(raw)
    if medians is None:
        medians = np.nanmedian(raw, axis=0)
        medians = np.where(np.isnan(medians), 0.0, medians)
    filled = np.where(mask, medians, raw)
    return np.hstack([filled, mask.astype(float)]), medians


def metrics(y: np.ndarray, p: np.ndarray, prob: np.ndarray) -> dict:
    pred = (p >= 0.5).astype(int)
    out = {
        "accuracy": accuracy_score(y, pred),
        "balanced_accuracy": balanced_accuracy_score(y, pred),
        "precision": precision_score(y, pred, zero_division=0),
        "recall": recall_score(y, pred, zero_division=0),
        "f1": f1_score(y, pred, zero_division=0),
        "brier": brier_score_loss(y, prob) if len(np.unique(y)) > 1 else float("nan"),
    }
    out["roc_auc"] = roc_auc_score(y, prob) if len(np.unique(y)) > 1 else float("nan")
    # Confusion matrix as a 2x2 [[TN, FP], [FN, TP]].
    out["confusion"] = [[int(((p == 0) & (y == 0)).sum()), int(((p == 1) & (y == 0)).sum())],
                        [int(((p == 0) & (y == 1)).sum()), int(((p == 1) & (y == 1)).sum())]]
    return out


def make_model(kind: str, seed: int = 42):
    """The three candidate families from Phase 11, with balanced class weights throughout."""
    if kind == "logreg":
        return make_pipeline(
            StandardScaler(),
            LogisticRegression(max_iter=5000, class_weight="balanced", C=1.0),
        )
    if kind == "rf":
        return RandomForestClassifier(
            n_estimators=400, max_depth=8, min_samples_leaf=5,
            class_weight="balanced_subsample", random_state=seed, n_jobs=-1,
        )
    if kind == "gbt":
        return GradientBoostingClassifier(
            n_estimators=250, max_depth=3, learning_rate=0.05,
            subsample=0.9, random_state=seed,
        )
    raise ValueError(kind)


def loso_evaluate(df: pd.DataFrame, features: list[str], kind: str,
                  seed: int = 42) -> tuple[dict, dict, np.ndarray, np.ndarray, np.ndarray]:
    """
    Leave-one-subject-out evaluation.

    Returns (pooled metrics, per-subject metrics, out-of-fold labels/predictions/probabilities).
    """
    X, medians = design(df, features)
    y = df["target"].to_numpy(dtype=int)
    groups = df["subject"].to_numpy()

    logo = LeaveOneGroupOut()
    oof_pred = np.zeros(len(y), dtype=int)
    oof_prob = np.zeros(len(y), dtype=float)
    per_subject: dict[str, dict] = {}

    for train_idx, test_idx in logo.split(X, y, groups):
        held = groups[test_idx][0]
        model = make_model(kind, seed)
        model.fit(X[train_idx], y[train_idx])
        prob = model.predict_proba(X[test_idx])[:, 1]
        pred = (prob >= 0.5).astype(int)
        oof_pred[test_idx] = pred
        oof_prob[test_idx] = prob
        per_subject[held] = metrics(y[test_idx], pred, prob)
        per_subject[held]["n"] = int(len(test_idx))

    return metrics(y, oof_pred, oof_prob), per_subject, y, oof_pred, oof_prob


def export_json(path: Path, features: list[str], weights: list[float], bias: float,
                impute: list[float], cal_a: float, cal_b: float, report: dict) -> None:
    payload = {
        "model_version": "wesad-loso-logreg-v1",
        "type": "logistic_regression_with_missingness_indicators",
        "features": features,
        # Layout is [feature weights..., missing-flag weights...], matching designMatrix.
        "weights": weights,
        "bias": bias,
        "impute": impute,
        "calibration": {"a": cal_a, "b": cal_b},
        "feature_schema_version": 1,
        "preprocessing_version": 1,
        "baseline_version": 1,
        "calibration_version": 1,
        "validation": {
            "protocol": "leave-one-subject-out",
            "dataset": "WESAD",
            "subjects": report.get("n_subjects"),
            "windows": report.get("n_windows"),
            "loso_metrics": report.get("loso_metrics"),
        },
        "disclaimer": (
            "Research prototype. Not clinically validated. Trained on the WESAD laboratory "
            "protocol; deployment data (camera PPG, Health Connect) is not the same instrument. "
            "The StressSense score is an estimate, not a diagnosis."
        ),
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
def fit_platt(y: np.ndarray, logits: np.ndarray) -> tuple[float, float]:
    """
    Platt scaling: fit P(y=1) = sigmoid(a * f(x) + b).

    Fitted on **out-of-fold** logits, never on in-sample ones. Calibrating on the same predictions
    used to evaluate a model is the classic way to produce a probability that looks excellent in
    the report and is wrong in the field.
    """
    lr = LogisticRegression(max_iter=5000)
    lr.fit(logits.reshape(-1, 1), y)
    return float(lr.coef_[0][0]), float(lr.intercept_[0])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True)
    ap.add_argument("--out-model", default="../app/src/main/assets/stress_model_ml.json")
    ap.add_argument("--out-parity", default="../app/src/test/resources/parity_vectors.json")
    ap.add_argument("--out-report", default="REPORT.json")
    args = ap.parse_args()

    df, usable = load(Path(args.csv))
    subjects = sorted(df["subject"].unique())
    print(f"dataset: {len(df)} windows, {len(subjects)} subjects, "
          f"{int(df['target'].sum())} stress / {int((1 - df['target']).sum())} non-stress")

    # ---- Phase 9: feature-group ablation, all under the same LOSO split ------------------
    print("\n=== feature-group ablation (leave-one-subject-out, logistic regression) ===")
    ablation: dict[str, dict] = {}
    for name, feats in ABLATION_SETS.items():
        feats = [f for f in feats if f in usable]
        m, per, *_ = loso_evaluate(df, feats, "logreg")
        ablation[name] = {"features": feats, "metrics": m,
                          "per_subject": {k: v["balanced_accuracy"] for k, v in per.items()}}
        print(f"{name:22s} n_feat={len(feats):2d}  bal_acc={m['balanced_accuracy']:.4f}  "
              f"f1={m['f1']:.4f}  auc={m['roc_auc']:.4f}  brier={m['brier']:.4f}")

    # ---- Phase 11: model family comparison on the full feature set ----------------------
    print("\n=== model comparison (full feature set, leave-one-subject-out) ===")
    full_feats = [f for f in FEATURES if f in usable]
    comparison: dict[str, dict] = {}
    for kind in ("logreg", "rf", "gbt"):
        m, per, *_ = loso_evaluate(df, full_feats, kind)
        # Subject-to-subject spread matters: a model that works for some people and not others is
        # a worse field model than one with a slightly lower mean and a tighter spread.
        bal = [v["balanced_accuracy"] for v in per.values()]
        m["subject_bal_acc_std"] = float(np.std(bal))
        m["subject_bal_acc_min"] = float(np.min(bal))
        comparison[kind] = m
        print(f"{kind:8s} bal_acc={m['balanced_accuracy']:.4f}  f1={m['f1']:.4f}  "
              f"auc={m['roc_auc']:.4f}  brier={m['brier']:.4f}  "
              f"subject_std={m['subject_bal_acc_std']:.4f}")

    best_kind = max(
        comparison,
        key=lambda k: (comparison[k]["balanced_accuracy"], -comparison[k]["subject_bal_acc_std"]),
    )
    print(f"\nbest family by LOSO balanced accuracy: {best_kind}")

    best_m, best_per, y, oof_pred, oof_prob = loso_evaluate(df, full_feats, best_kind)
    if best_kind != "logreg":
        print("  NOTE: winner is not logistic regression. Android inference implements logistic "
              "regression only, so deployment still ships the logistic model below; the tree "
              "comparison is reported, not deployed.")

    # ---- Final deployable model: logistic regression over all usable features -------------
    X, medians = design(df, full_feats)
    final = make_model("logreg")
    final.fit(X, y)

    # Calibration from out-of-fold predictions (Phase 13) - never from in-sample ones.
    logits = np.log(np.clip(oof_prob, 1e-9, 1 - 1e-9) / np.clip(1 - oof_prob, 1e-9, 1 - 1e-9))
    cal_a, cal_b = fit_platt(y, logits)

    # Unwrap StandardScaler + LogisticRegression into raw design-matrix coefficients.
    scaler = final.named_steps["standardscaler"]
    clf = final.named_steps["logisticregression"]
    weights = (clf.coef_[0] / scaler.scale_).tolist()
    bias = float(clf.intercept_[0] - np.sum(clf.coef_[0] * scaler.mean_ / scaler.scale_))

    report = {
        "n_windows": int(len(df)),
        "n_subjects": int(len(subjects)),
        "subjects": subjects,
        "label_definition": (
            "WESAD protocol condition. target=1 for the stress condition (label 2); target=0 for "
            "baseline (1) and amusement (3). Meditation (4) and all transition/undefined segments "
            "are excluded, not folded into either class."
        ),
        "validation": "leave-one-subject-out",
        "ablation": ablation,
        "model_comparison": comparison,
        "selected_family_for_reporting": best_kind,
        "deployed_model": "logistic_regression",
        "features": full_feats,
        "calibration": {"a": cal_a, "b": cal_b},
        "loso_metrics": best_m,
        "loso_per_subject": best_per,
        "preprocessing": {
            "imputation": "training-set median per feature",
            "missingness_indicators": True,
            "note": "Missing is never encoded as zero.",
        },
    }

    out_model = Path(args.out_model)
    export_json(out_model, full_feats, weights, bias, medians.tolist(), cal_a, cal_b, report)
    print(f"\nwrote {out_model} ({len(weights)} weights over {len(full_feats)} features)")

    Path(args.out_report).write_text(
        json.dumps(report, indent=2, default=float), encoding="utf-8")
    print(f"wrote {args.out_report}")

    # ---- Phase 21: parity vectors proving Python and Android agree ---------------------
    rng = np.random.default_rng(7)
    vectors = []
    for _ in range(24):
        row = df.iloc[int(rng.integers(0, len(df)))]
        vec = {}
        for f in full_feats:
            v = row[f]
            # Inject realistic missingness so the parity test also covers NaN handling.
            if rng.random() < 0.15 or not np.isfinite(v):
                vec[f] = None
            else:
                vec[f] = round(float(v), 6)
        x = design(pd.DataFrame([vec]), full_feats, medians)[0][0]
        z = float(bias + np.dot(weights, x))
        p = 1.0 / (1.0 + np.exp(-(cal_a * z + cal_b)))
        vectors.append({
            "features": vec,
            "expected_raw_logit": round(z, 6),
            "expected_probability": round(p, 6),
            "expected_score": int(np.clip(p * 100, 0, 100)),
        })
    parity = Path(args.out_parity)
    parity.parent.mkdir(parents=True, exist_ok=True)
    parity.write_text(json.dumps({
        "model_version": "wesad-loso-logreg-v1",
        "feature_order": full_feats,
        "vectors": vectors,
    }, indent=2), encoding="utf-8")
    print(f"wrote {parity} ({len(vectors)} parity vectors)")


if __name__ == "__main__":
    main()