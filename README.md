# StressSense

StressSense is an Android-based physiological and contextual stress-monitoring platform designed to help
personnel understand physiological stress patterns and provide commanders with an appropriate high-level
overview.

The app is built with Kotlin and Jetpack Compose. It keeps **three deliberately separate views of
stress** — a model-derived *physiological* score, a monthly *perceived* score (PSS-10), and short
*contextual* check-ins — and never blends them into a single number.

> **Prototype status.** This is a research prototype for a Smart India Hackathon 2026 submission. It is
> **not** a medical device and must not be used for diagnosis, treatment, or clinical decision-making.
> See [Limitations](#limitations).

---

## Features

- **Two roles** — Personnel and Commander, authorised server-side-style at the repository layer
  (every read and write is checked against the authenticated account, not against UI state).
- **Health Connect integration** — heart rate, HRV/RMSSD, resting heart rate, respiratory rate, SpO2,
  steps, sleep, exercise and body measurements.
- **Camera-based PPG HRV measurement** — an on-device photoplethysmography pipeline (CameraX) that
  derives beat-to-beat intervals from the rear camera and torch, then computes RMSSD/SDNN locally.
- **Welltory camera measurement integration** — a second, independent route to a real camera HRV
  measurement, launched through Welltory's documented intent/callback integration.
- **Physiological stress estimation** — a 0–100 score produced entirely on-device.
- **WESAD-trained machine-learning model** — logistic regression with explicit missing-value handling.
- **Personal baseline** — the app requires several qualifying readings before it will issue a score at
  all, because a z-score against a single sample is meaningless.
- **Daily Check-in** — three short contextual questions.
- **Monthly PSS-10 perceived-stress questionnaire** — implemented as a prototype pending licensing.
- **Stress trends** — three separate charts, each on its own scale.
- **Commander dashboard** — a high-level grouped overview of personnel status.
- **Medical History with personnel privacy** — stored and visible only to the owning person.
- **Demo accounts** — eleven pre-seeded accounts with deterministic synthetic data for evaluation.

---

## Stress Architecture

### The physiological path (the only path that produces a stress score)

```
Physiological data (Health Connect / camera PPG / Welltory HRV)
    -> HR + HRV features
    -> personal baseline (personal deviation z-scores)
    -> temporal + context features
    -> ML feature vector (22 features)
    -> Logistic Regression model (wesad-loso-logreg-v1)
    -> physiological stress score 0-100
    -> Low / Moderate / High zone
```

Everything in this chain runs on the device. There is no server, no network call, and no remote
inference.

### The three systems are **not** combined

This is the most important thing to understand about StressSense:

| System | What it measures | Produces |
| --- | --- | --- |
| **Physiological stress** | Your autonomic nervous system via HRV | The 0–100 score and its zone |
| **Perceived stress (PSS-10)** | How stressed you say you feel | A separate 0–40 score, monthly |
| **Daily Context (Check-in)** | How stressed / rested / loaded you feel today | Context only — **no score** |

The Daily Check-in answers are **contextual inputs only**. They are never converted into, added to, or
blended with the physiological stress score. PSS-10 is a separate, independently-scored
self-report measure. **The three are never combined into one number**, and the Trends screen plots them
on three separate charts with separate scales so they are never visually compared as if they were the
same quantity.

---

## Machine Learning Model

| Property | Value |
| --- | --- |
| Dataset | **WESAD** (Wearable Stress and Affect Dataset) |
| Subjects | **15** |
| Validation protocol | **Leave-One-Subject-Out (LOSO)** |
| Final model | **Logistic Regression** (with missing-value indicators) |
| Model version | **`wesad-loso-logreg-v1`** |

### Validation metrics

Measured under LOSO cross-validation on WESAD:

| Metric | Value |
| --- | --- |
| Balanced Accuracy | **0.9184** |
| F1 | **0.8803** |
| ROC AUC | **0.9795** |
| Brier Score | **0.0521** |

The model reads 22 features: raw heart rate and HRV measures (RMSSD, SDNN, mean NN, SDSD, pNN50),
personal-baseline z-scores of those measures, respiratory rate, recent physical activity, short-term
temporal deltas and slopes, and signal-quality descriptors. When a feature was not measured it is
marked **missing** rather than invented — the model was trained with median imputation plus explicit
missingness indicators specifically so absent data degrades gracefully.

Adding the personal baseline and the temporal/context features measurably improved performance over raw
HRV alone.

### Important limitation — read this before quoting any accuracy number

WESAD derives its HRV from **chest ECG**. StressSense on a phone can obtain HRV from **camera PPG**
(or from Welltory's camera measurement). These are **different sensing modalities**: they measure
different signals, with different artefacts, motion sensitivity, contact pressure and environmental
confounders.

**The 91.84% balanced accuracy is a laboratory cross-validation figure on WESAD. It is not a guarantee
of real-world accuracy in this application, and it must not be presented as one.** Field performance
may differ substantially because of sensor and environmental differences.

---

## HRV

The app surfaces two time-domain HRV measures:

- **RMSSD** (root mean square of successive differences) — the standard short-window measure of
  beat-to-beat variability. It reflects parasympathetic (vagal) tone most strongly and responds quickly
  to change, including breathing rate.
- **SDNN** (standard deviation of normal-to-normal intervals) — the variability across the whole
  recording. It is the broader measure over the measurement window.

**HRV values depend on the measurement method, the person's physiology, posture, breathing pattern,
time of day, caffeine, hydration, temperature and their own baseline.** A single RMSSD number is
therefore *not* universally "normal" or "abnormal", and StressSense never presents one as such. The
model scores each reading primarily by **how far it sits relative to that individual's own recent
baseline**, not by comparing it to a population threshold.

---
## Data Sources

| Source | What it provides | Status |
| --- | --- | --- |
| **Health Connect** | Heart rate, HRV/RMSSD, resting HR, respiratory rate, SpO2, steps, sleep, exercise, height, weight | Real physiological data read from the Android health platform |
| **Camera PPG** | On-device heart rate and HRV derived from the rear camera | Real physiological data measured on-device |
| **Welltory** | A real camera HRV measurement performed by the Welltory app and returned to StressSense | Real physiological data measured on-device |
| **Demo data** | Deterministic **synthetic** physiological series for the demo accounts | **NOT real data** — see below |

### Real vs. demo data

Every persisted measurement records the `source` that produced it — `HEALTH_CONNECT`, `CAMERA_PPG` or
`WELLTORY_CAMERA` — so the two are never conflated in storage or in the UI.

The **demo accounts** carry **synthetic, deterministic** data. It is generated by a closed-form function
(there is no randomness), fed through the **same** deployed model as real measurements, and persisted
through the **same** repositories. Demo figures therefore illustrate the pipeline; they do not describe
any real person's physiology and must never be presented as if they did.

---

## Welltory Integration

Welltory is used as an **external camera measurement route**. StressSense launches the Welltory
measurement through Welltory's documented intent integration; the person completes a real camera
measurement inside the Welltory app; Welltory then returns the measurement result to StressSense
through its documented callback mechanism, and StressSense validates and persists the returned HRV
before feeding it into its own physiological pipeline.

**StressSense does NOT use Welltory's proprietary stress score as its own stress score.** Welltory
returns certain proprietary scalars alongside the HRV values; these are retained only as source metadata
for provenance. The physiological stress score shown in StressSense is produced **solely** by the
deployed `wesad-loso-logreg-v1` model. Welltory is used for the measurement, not for the score.

Welltory's integration requires partner approval from Welltory before a production app can receive
results. That approval is **not** bypassed here; the integration identifier is an isolated,
clearly-documented configuration point that is intentionally empty until approval is granted.

---

## Daily Check-in

The Daily Check-in asks three short contextual questions:

1. **How stressed do you feel today?**
2. **How rested do you feel?**
3. **How heavy was today's duty load?**

These are **contextual inputs and are NOT converted into a physiological stress score.** They are
stored as their own records, used to inform the questionnaire and coaching flow, and displayed as their
own card and their own chart. They never enter the ML feature vector and never alter the physiological
stress score.

---

## PSS-10

The monthly perceived-stress questionnaire implements the PSS-10 structure:

- **10 items**
- A **0–4 response scale** per item
- Items **4, 5, 7 and 8 are reverse-scored** before summing
- A **total score of 0–40**
- Intended as a **monthly** measurement

> **Prototype status.** The current implementation is a **prototype pending appropriate
> licensing/authorization of the official questionnaire wording.** The copyrighted PSS-10 item text is
> deliberately **not** included in this repository, and the app shows a pending/blocked state rather
> than reproducing protected wording. Before any real-world use, the official instrument must be
> obtained and used under its applicable licence or authorisation.

PSS-10 is a **separate perceived-stress measure**. Its total is never added to, blended with, or
compared against the physiological stress score.

---

## Commander Dashboard

The Commander dashboard provides a **high-level overview of personnel stress status**, grouping people
into Low / Moderate / High zones.

The grouping is derived dynamically from each person's published score using the same thresholds the
model's own zones use. There is no hard-coded mapping of a person to a group: if a person's next real
measurement produces a different score, they move group automatically.

---

## Privacy

**Medical history is personnel-only and is not shown to commanders.** It is stored against the owning
account and is reachable only through that account's own authorised access path. The Commander-facing
record carries operational monitoring fields only; no medical-history field exists on it at all.

Each person's measurements are stored under their own account id, and every read is authorised against
the authenticated session, so one person cannot read another's data.

Beyond that structural separation, this prototype makes **no legal or regulatory privacy
guarantees**. It is not a certified medical device and has not undergone any regulatory validation.
Deploying it with real personnel data would require a full privacy, security and data-protection review
appropriate to the setting.
---

## Demo Accounts

> ### DEMO CREDENTIALS — FOR TESTING ONLY
>
> The accounts below are **synthetic demonstration accounts** shipped with this prototype so the system
> can be evaluated without real data or hardware. They are **not production credentials**, they contain
> **no real personal data**, and they must be removed or replaced before any real deployment.

### Commander (DEMO)

| Username | Password |
| --- | --- |
| `commander@stresssense.demo` | `Demo@Commander2026` |

### Personnel (DEMO)

| Username | Password |
| --- | --- |
| `personnel01@stresssense.demo` | `Demo@Personnel2026` |
| `personnel02@stresssense.demo` | `Demo@Personnel2026` |
| `personnel03@stresssense.demo` | `Demo@Personnel2026` |
| `personnel04@stresssense.demo` | `Demo@Personnel2026` |
| `personnel05@stresssense.demo` | `Demo@Personnel2026` |
| `personnel06@stresssense.demo` | `Demo@Personnel2026` |
| `personnel07@stresssense.demo` | `Demo@Personnel2026` |
| `personnel08@stresssense.demo` | `Demo@Personnel2026` |
| `personnel09@stresssense.demo` | `Demo@Personnel2026` |
| `personnel10@stresssense.demo` | `Demo@Personnel2026` |

### Expected demo distribution

| Zone | Personnel |
| --- | --- |
| **LOW** | P01, P04, P07 |
| **MODERATE** | P02, P05, P08, P10 |
| **HIGH** | P03, P06, P09 |

### Expected latest scores

| Personnel | Score | Zone |
| --- | --- | --- |
| P01 | 17 | LOW |
| P02 | 53 | MODERATE |
| P03 | 78 | HIGH |
| P04 | 23 | LOW |
| P05 | 56 | MODERATE |
| P06 | 79 | HIGH |
| P07 | 17 | LOW |
| P08 | 46 | MODERATE |
| P09 | 79 | HIGH |
| P10 | 44 | MODERATE |

These figures are **outputs of the deployed model**, not values written by hand. The demo physiological
inputs are generated deterministically, run through the real `wesad-loso-logreg-v1` pipeline, and
whatever the model returns is what gets persisted and displayed. The expected values above are asserted
by automated tests, so if the model or the inputs ever change, the tests fail rather than the dashboard
quietly disagreeing with the documentation.

---

## Project Structure

```
StressWelfareBridge/
├── app/                              Android application module
│   ├── build.gradle.kts              App module build config (AGP + Compose plugins)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   Permissions, Health Connect rationale, Welltory callback
│       │   ├── assets/
│       │   │   ├── stress_model.json      Legacy on-device engine coefficients
│       │   │   └── stress_model_ml.json   Deployed model: features, weights, LOSO metrics
│       │   ├── java/com/bishnu/stresswelfarebridge/
│       │   │   ├── MainActivity.kt        Compose shell, navigation, Home/Stress/Trends screens
│       │   │   ├── StressEngine.kt        Health-Connect based inference + zone thresholds
│       │   │   ├── HealthRepository.kt    Health Connect reads + DailyHealth aggregation
│       │   │   ├── auth/                  Accounts, roles, sessions, password hashing
│       │   │   ├── ml/                    Deployed model, FeatureExtractor, estimator,
│       │   │   │                          PersonalBaseline, ObservationHistory, measurement store
│       │   │   ├── ppg/                   PPG signal types, HRV calculator (RMSSD/SDNN/SDSD/pNN50)
│       │   │   ├── camera/                CameraX capture, PPG controller, HRV screen and card
│       │   │   ├── welltory/              Welltory intent launch, callback activity, validation
│       │   │   ├── commander/             Personnel records, zone mapping, access control
│       │   │   ├── questionnaire/         Daily Check-in, PSS-10 scoring and storage
│       │   │   ├── trends/                Trend series construction
│       │   │   ├── medical/               Personnel-only medical history
│       │   │   ├── demo/                  Demo accounts, synthetic physiology, seeder
│       │   │   └── ui/                    Compose screens (auth, commander, theme, charts)
│       │   └── res/                      Icons and resources
│       └── test/                        681 JVM unit tests across 46 test classes
├── ml/                                Offline training / evaluation scripts (Python)
│   ├── wesad_features.py              Feature extraction + baseline logic (mirrors the app)
│   ├── train_wesad_stress.py           Trains and LOSO-validates the deployed model
│   ├── verify_export.py                Verifies the exported JSON matches the trained model
│   └── REPORT.md / REPORT.json         Recorded training and validation results
├── gradle/                             Gradle wrapper + version catalog (libs.versions.toml)
├── build.gradle.kts / settings.gradle.kts
├── gradle.properties
├── local.properties                    Machine-local SDK path (git-ignored)
└── README.md
```

---

## Testing

The project has a JVM unit-test suite covering the pipeline end to end.

**Current status: 681 tests across 46 test classes, all passing (`BUILD SUCCESSFUL`).**

```
./gradlew testDebugUnitTest
```

These are plain JVM tests (no device or emulator required). They load the **real deployed model asset**
and drive the **real** estimator, baseline, feature extractor and persistence layers — nothing is
stubbed — so they verify the actual pipeline rather than a mock of it.

Representative coverage includes:

- **Model parity** — the shipped `stress_model_ml.json` matches the expected schema and metrics.
- **Camera/Welltory stress pipeline** — a validated measurement is turned into a real score.
- **Baseline persistence** — the cold-start rule survives restarts, and no score is issued early.
- **Camera PPG signal processing** — frame reading, beat detection, HRV calculation, artefact rejection.
- **Trends**, **PSS-10 scoring**, **Daily Check-in**, and **medical-history privacy**.
- **Demo seeding** — the 3/4/3 distribution, per-person scores, determinism and idempotent re-seeding.
- **Account isolation** — one account can never read another's data.

---

## Limitations

- **WESAD is a research dataset.** The reported metrics are laboratory cross-validation results, not a
  field evaluation.
- **Field performance may differ.** Camera PPG and chest ECG are **different sensing modalities**;
  motion, contact pressure, skin tone, lighting and ambient noise all affect PPG in ways that do not
  affect ECG.
- **Sleep, SpO2 and BMI are not part of the deployed model.** The app can display them when present,
  but the `wesad-loso-logreg-v1` feature vector does not include them.
- **PSS-10 wording/licensing status.** The questionnaire structure is implemented, but the copyrighted
  item text is deliberately absent pending licensing/authorization.
- **Demo data is synthetic and deterministic.** It is not real personnel data and describes nobody's
  physiology.
- **Prototype, not a medical device.** No clinical validation, no regulatory clearance, and no
  diagnostic use.
- **Welltory partner approval is pending.** The integration is implemented against Welltory's
  documented mechanism, but result delivery requires Welltory to approve this app's package.

---

## Future Improvements

- Collect field data with **appropriate informed consent** and ethical approval.
- **Retrain and validate using device-specific PPG data** rather than ECG-derived HRV, so the model
  matches the sensor the app actually uses.
- Run an **independent external validation** on data not used for training.
- Improve **personalisation** — longer individual baselines, better handling of measurement conditions.
- Incorporate **additional physiological signals** (respiration, skin conductance, activity context).
- Extend the physiological validation to cover the camera PPG modality specifically.

---

## Building

```bash
./gradlew assembleDebug      # build the debug APK
./gradlew testDebugUnitTest  # run the JVM unit-test suite
```

Requires a JDK and the Android SDK. `local.properties` must point `sdk.dir` at your local SDK
installation; it is git-ignored because it is machine-specific.