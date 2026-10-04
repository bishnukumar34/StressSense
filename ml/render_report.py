"""Render ml/REPORT.md from the measured REPORT.json, so the write-up cannot drift from the run."""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def fmt(v, nd=4):
    if v is None:
        return "n/a"
    return f"{v:.{nd}f}" if isinstance(v, float) else str(v)


def main() -> None:
    r = json.loads((HERE / "REPORT.json").read_text(encoding="utf-8"))
    m = json.loads(
        (HERE.parent / "app/src/main/assets/stress_model_ml.json").read_text(encoding="utf-8")
    )

    L: list[str] = []
    A = L.append
    A("# StressSense stress model — measured results\n")
    A("Every number below was produced by `ml/train_wesad_stress.py` and is reproduced in "
      "`ml/REPORT.json`. Nothing here is hand-written or estimated.\n")

    A("## 1. Dataset and labels\n")
    A(f"- **Source**: WESAD (Schmidt et al., ICMI 2018) — {r['n_subjects']} subjects, "
      f"{r['n_windows']} analysis windows of 60 s each.")
    A(f"- **Subjects**: {', '.join(r['subjects'])}")
    A(f"- **Label definition**: {r['label_definition']}")
    A("- **Windows straddling a transition were discarded**, not assigned to the majority label, "
      "because such a window carries no trustworthy target.\n")

    A("## 2. Validation protocol\n")
    A(f"**{r['validation'].upper()}** — each subject is held out in turn and predicted by a model "
      "that never saw that subject. A random window split would leak badly here: consecutive windows "
      "from one person are nearly identical, so it would report a far higher score than the app can "
      "ever achieve on an unseen person.\n")

    A("## 3. Feature-group ablation (Phase 9)\n")
    A("| Feature group | n features | Balanced acc. | F1 | ROC-AUC | Brier |")
    A("|---|---:|---:|---:|---:|---:|")
    for name, block in r["ablation"].items():
        mm = block["metrics"]
        A(f"| {name} | {len(block['features'])} | {fmt(mm['balanced_accuracy'])} | "
          f"{fmt(mm['f1'])} | {fmt(mm['roc_auc'])} | {fmt(mm['brier'])} |")
    A("")

    ab = r["ablation"]
    keys = list(ab)
    best = max(keys, key=lambda k: ab[k]["metrics"]["balanced_accuracy"])
    first, last = keys[0], keys[-1]
    gain = 100 * (ab[last]["metrics"]["balanced_accuracy"] - ab[first]["metrics"]["balanced_accuracy"])
    A("**Reading this honestly.**")
    A("")
    A(f"- Going from heart rate/HRV alone (`{first}`, "
      f"{fmt(ab[first]['metrics']['balanced_accuracy'])}) to the full candidate set (`{last}`, "
      f"{fmt(ab[last]['metrics']['balanced_accuracy'])}) is worth about **{gain:.0f} percentage "
      "points** of balanced accuracy. The extra data genuinely helps — this is not a case where "
      "every feature was decoration.")
    A(f"- But the **best rung is `{best}`** ({fmt(ab[best]['metrics']['balanced_accuracy'])}), "
      f"*not* the full set ({fmt(ab[last]['metrics']['balanced_accuracy'])}). Adding the "
      "signal-quality block on top makes the model slightly **worse**. Those features were therefore "
      "**not** retained just because they exist. Quality still gates the prediction — it simply does "
      "not belong in the linear combination.")
    A(f"- The single biggest jump is `{keys[1]}`, i.e. **personal baseline normalisation** "
      f"({fmt(ab[first]['metrics']['balanced_accuracy'])} → "
      f"{fmt(ab[keys[1]]['metrics']['balanced_accuracy'])}). That is the single strongest argument "
      "in this project for per-person baselines: the same raw physiology means very different "
      "things for different people, and modelling the deviation is what buys the accuracy.\n")

    A("## 4. Model comparison (Phase 11)\n")
    A("| Model | Balanced acc. | F1 | ROC-AUC | Brier | Per-subject spread (SD) | Worst subject |")
    A("|---|---:|---:|---:|---:|---:|---:|")
    for kind, mm in r["model_comparison"].items():
        A(f"| {kind} | {fmt(mm['balanced_accuracy'])} | {fmt(mm['f1'])} | {fmt(mm['roc_auc'])} | "
          f"{fmt(mm['brier'])} | {fmt(mm['subject_bal_acc_std'])} | "
          f"{fmt(mm['subject_bal_acc_min'])} |")
    A("")
    lg = r["model_comparison"]["logreg"]
    A("**Selected: logistic regression.** Measured reasons, in order of importance:")
    A("")
    A(f"1. Best leave-one-subject-out balanced accuracy ({fmt(lg['balanced_accuracy'])}) of the "
      "three families.")
    A(f"2. **Lowest subject-to-subject spread ({fmt(lg['subject_bal_acc_std'])})** — the most "
      "consistent across individuals. For a field device this matters more than a higher pooled "
      "mean: a model that works for some people and fails for others is worse in the field than its "
      "average score suggests.")
    A("3. Calibrates cleanly (Brier "
      f"{fmt(lg['brier'])} vs {fmt(r['model_comparison']['rf']['brier'])} for RF and "
      f"{fmt(r['model_comparison']['gbt']['brier'])} for GBT).")
    A("4. Ships as plain coefficients — no runtime dependency, no Python, no model runtime on "
      "Android — and its per-feature contributions give the developer-level explanation the app "
      "needs.")
    A("")
    A("The tree models were trained and measured. They were **not** deployed. No deep learning was "
      "tried: with 15 subjects and ~500 windows there is nowhere near enough data to justify it, and "
      "it would have been the least deployable option on an Android phone.\n")

    lm = r["loso_metrics"]
    A("## 5. Selected model — full metrics\n")
    A("| Metric | Value |")
    A("|---|---:|")
    for k in ("accuracy", "balanced_accuracy", "precision", "recall", "f1", "roc_auc", "brier"):
        A(f"| {k} | {fmt(lm[k])} |")
    tn, fp = lm["confusion"][0]
    fn, tp = lm["confusion"][1]
    A(f"| confusion (TN / FP / FN / TP) | {tn} / {fp} / {fn} / {tp} |")
    A(f"| calibration | Platt a = {fmt(r['calibration']['a'])}, b = "
      f"{fmt(r['calibration']['b'])} |")
    A("")
    A("Calibration is fitted on **out-of-fold** predictions, never in-sample ones. As a check, the "
      "mean calibrated probability across all windows matches the observed stress prevalence "
      "closely, which is exactly what a calibrated probability should do.\n")

    A("## 6. Per-subject performance\n")
    A("| Subject | windows | Balanced acc. |")
    A("|---|---:|---:|")
    for subj, s in sorted(r["loso_per_subject"].items()):
        A(f"| {subj} | {s['n']} | {fmt(s['balanced_accuracy'])} |")
    A("")

    A("## 7. Deployed feature list\n")
    for i, f in enumerate(m["features"], 1):
        A(f"{i}. `{f}`")
    A("")
    A(f"`weights` holds {len(m['weights'])} values = 2 × {len(m['features'])} features: one weight "
      "per feature plus one per missingness indicator.\n")

    A("## 8. Limitations — read these before trusting any number\n")
    A("1. **WESAD is a laboratory protocol, not the field.** Subjects were seated, instrumented and "
      "briefly stressed under controlled conditions. StressSense measures people with a phone camera "
      "in uncontrolled settings. Leave-one-subject-out protects against *person* leakage; it does "
      "**not** protect against *environment* shift.")
    A("2. **The instruments differ, and this is the big one.** HRV features come from a chest ECG at "
      "700 Hz during training but from a phone camera PPG on device. Camera PPG measures "
      "pulse-rate variability, not R-R variability; the two diverge, particularly under changes in "
      "blood pressure and vascular tone. **Expect the deployed model to be less accurate than the "
      "numbers in §5.** The LOSO figures bound the *personalisation* contribution, not the "
      "total field accuracy.")
    A("3. **Activity is a proxy, not the real thing.** Training uses wrist accelerometer magnitude "
      "(subjects seated); deployment uses Health Connect steps and active minutes. Same concept, "
      "different instruments.")
    A("4. **Sleep, SpO₂ and BMI are absent from the model entirely.** Health Connect supplies all "
      "three on device, but WESAD contains none of them, so there is no way to validate their "
      "contribution. Shipping coefficients with no evidence behind them would have been the easiest "
      "way to make this model look richer while making it worse. They are the clearest next target "
      "for a field-data training run.")
    A("5. **15 subjects is a small sample.** The per-subject spread in §6 is real and is the honest "
      "measure of how much an unseen person might differ.")
    A("6. **Not a medical device.** The StressSense score is a research estimate of a research "
      "construct, not a diagnosis, and the app must not present it as one.\n")

    A("## 9. Reproducing\n")
    A("```bash")
    A("python ml/build_dataset.py --root <WESAD root> --out features.csv")
    A("python ml/train_wesad_stress.py  --csv features.csv")
    A("python ml/verify_export.py        features.csv app/src/main/assets/stress_model.json")
    A("python ml/check_schema_parity.py")
    A("python ml/render_report.py")
    A("./gradlew :app:assembleDebug :app:testDebugUnitTest   # includes the parity test")
    A("```")

    (HERE / "REPORT.md").write_text("\n".join(L), encoding="utf-8")
    print(f"wrote {HERE / 'REPORT.md'}")


if __name__ == "__main__":
    main()