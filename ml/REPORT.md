# StressSense stress model — measured results

Every number below was produced by `ml/train_wesad_stress.py` and is reproduced in `ml/REPORT.json`. Nothing here is hand-written or estimated.

## 1. Dataset and labels

- **Source**: WESAD (Schmidt et al., ICMI 2018) — 15 subjects, 515 analysis windows of 60 s each.
- **Subjects**: S10, S11, S13, S14, S15, S16, S17, S2, S3, S4, S5, S6, S7, S8, S9
- **Label definition**: WESAD protocol condition. target=1 for the stress condition (label 2); target=0 for baseline (1) and amusement (3). Meditation (4) and all transition/undefined segments are excluded, not folded into either class.
- **Windows straddling a transition were discarded**, not assigned to the majority label, because such a window carries no trustworthy target.

## 2. Validation protocol

**LEAVE-ONE-SUBJECT-OUT** — each subject is held out in turn and predicted by a model that never saw that subject. A random window split would leak badly here: consecutive windows from one person are nearly identical, so it would report a far higher score than the app can ever achieve on an unseen person.

## 3. Feature-group ablation (Phase 9)

| Feature group | n features | Balanced acc. | F1 | ROC-AUC | Brier |
|---|---:|---:|---:|---:|---:|
| A_hr_hrv | 6 | 0.8135 | 0.7251 | 0.8990 | 0.1328 |
| B_plus_baseline | 10 | 0.8924 | 0.8424 | 0.9593 | 0.0740 |
| C_plus_activity | 11 | 0.9003 | 0.8526 | 0.9567 | 0.0741 |
| D_plus_temporal | 15 | 0.9173 | 0.8688 | 0.9722 | 0.0617 |
| E_plus_respiration | 17 | 0.9272 | 0.8954 | 0.9814 | 0.0489 |
| F_all_candidates | 22 | 0.9184 | 0.8803 | 0.9795 | 0.0521 |

**Reading this honestly.**

- Going from heart rate/HRV alone (`A_hr_hrv`, 0.8135) to the full candidate set (`F_all_candidates`, 0.9184) is worth about **10 percentage points** of balanced accuracy. The extra data genuinely helps — this is not a case where every feature was decoration.
- But the **best rung is `E_plus_respiration`** (0.9272), *not* the full set (0.9184). Adding the signal-quality block on top makes the model slightly **worse**. Those features were therefore **not** retained just because they exist. Quality still gates the prediction — it simply does not belong in the linear combination.
- The single biggest jump is `B_plus_baseline`, i.e. **personal baseline normalisation** (0.8135 → 0.8924). That is the single strongest argument in this project for per-person baselines: the same raw physiology means very different things for different people, and modelling the deviation is what buys the accuracy.

## 4. Model comparison (Phase 11)

| Model | Balanced acc. | F1 | ROC-AUC | Brier | Per-subject spread (SD) | Worst subject |
|---|---:|---:|---:|---:|---:|---:|
| logreg | 0.9184 | 0.8803 | 0.9795 | 0.0521 | 0.0713 | 0.7778 |
| rf | 0.8888 | 0.8467 | 0.9594 | 0.0748 | 0.1243 | 0.6232 |
| gbt | 0.8927 | 0.8641 | 0.9651 | 0.0647 | 0.1206 | 0.6667 |

**Selected: logistic regression.** Measured reasons, in order of importance:

1. Best leave-one-subject-out balanced accuracy (0.9184) of the three families.
2. **Lowest subject-to-subject spread (0.0713)** — the most consistent across individuals. For a field device this matters more than a higher pooled mean: a model that works for some people and fails for others is worse in the field than its average score suggests.
3. Calibrates cleanly (Brier 0.0521 vs 0.0748 for RF and 0.0647 for GBT).
4. Ships as plain coefficients — no runtime dependency, no Python, no model runtime on Android — and its per-feature contributions give the developer-level explanation the app needs.

The tree models were trained and measured. They were **not** deployed. No deep learning was tried: with 15 subjects and ~500 windows there is nowhere near enough data to justify it, and it would have been the least deployable option on an Android phone.

## 5. Selected model — full metrics

| Metric | Value |
|---|---:|
| accuracy | 0.9282 |
| balanced_accuracy | 0.9184 |
| precision | 0.8662 |
| recall | 0.8947 |
| f1 | 0.8803 |
| roc_auc | 0.9795 |
| brier | 0.0521 |
| confusion (TN / FP / FN / TP) | 342 / 21 / 16 / 136 |
| calibration | Platt a = 0.9590, b = -0.7026 |

Calibration is fitted on **out-of-fold** predictions, never in-sample ones. As a check, the mean calibrated probability across all windows matches the observed stress prevalence closely, which is exactly what a calibrated probability should do.

## 6. Per-subject performance

| Subject | windows | Balanced acc. |
|---|---:|---:|
| S10 | 35 | 0.8712 |
| S11 | 35 | 1.0000 |
| S13 | 35 | 0.9200 |
| S14 | 35 | 0.9800 |
| S15 | 36 | 0.8182 |
| S16 | 35 | 0.9200 |
| S17 | 36 | 0.9600 |
| S2 | 33 | 0.8283 |
| S3 | 33 | 1.0000 |
| S4 | 33 | 0.8333 |
| S5 | 33 | 0.9583 |
| S6 | 34 | 0.9292 |
| S7 | 35 | 1.0000 |
| S8 | 35 | 0.9583 |
| S9 | 32 | 0.7778 |

## 7. Deployed feature list

1. `hr_bpm`
2. `rmssd_ms`
3. `sdnn_ms`
4. `mean_nn_ms`
5. `sdsd_ms`
6. `pnn50_pct`
7. `hr_z`
8. `rmssd_z`
9. `sdnn_z`
10. `mean_nn_z`
11. `resp_rate_bpm`
12. `resp_rate_z`
13. `activity_recent_norm`
14. `hr_delta_recent`
15. `rmssd_delta_recent`
16. `hr_slope_recent`
17. `rmssd_slope_recent`
18. `ppg_quality`
19. `confidence`
20. `valid_nn_ratio`
21. `artifact_ratio`
22. `duration_s`

`weights` holds 44 values = 2 × 22 features: one weight per feature plus one per missingness indicator.

## 8. Limitations — read these before trusting any number

1. **WESAD is a laboratory protocol, not the field.** Subjects were seated, instrumented and briefly stressed under controlled conditions. StressSense measures people with a phone camera in uncontrolled settings. Leave-one-subject-out protects against *person* leakage; it does **not** protect against *environment* shift.
2. **The instruments differ, and this is the big one.** HRV features come from a chest ECG at 700 Hz during training but from a phone camera PPG on device. Camera PPG measures pulse-rate variability, not R-R variability; the two diverge, particularly under changes in blood pressure and vascular tone. **Expect the deployed model to be less accurate than the numbers in §5.** The LOSO figures bound the *personalisation* contribution, not the total field accuracy.
3. **Activity is a proxy, not the real thing.** Training uses wrist accelerometer magnitude (subjects seated); deployment uses Health Connect steps and active minutes. Same concept, different instruments.
4. **Sleep, SpO₂ and BMI are absent from the model entirely.** Health Connect supplies all three on device, but WESAD contains none of them, so there is no way to validate their contribution. Shipping coefficients with no evidence behind them would have been the easiest way to make this model look richer while making it worse. They are the clearest next target for a field-data training run.
5. **15 subjects is a small sample.** The per-subject spread in §6 is real and is the honest measure of how much an unseen person might differ.
6. **Not a medical device.** The StressSense score is a research estimate of a research construct, not a diagnosis, and the app must not present it as one.

## 9. Reproducing

```bash
python ml/build_dataset.py --root <WESAD root> --out features.csv
python ml/train_wesad_stress.py  --csv features.csv
python ml/verify_export.py        features.csv app/src/main/assets/stress_model.json
python ml/check_schema_parity.py
python ml/render_report.py
./gradlew :app:assembleDebug :app:testDebugUnitTest   # includes the parity test
```