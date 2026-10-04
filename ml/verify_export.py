"""Verify the exported JSON reproduces the sklearn pipeline's predictions exactly."""
import json
import sys
import numpy as np
import pandas as pd

sys.path.insert(0, ".")
from train_wesad_stress import design, make_model

csv_path, model_path = sys.argv[1], sys.argv[2]
df = pd.read_csv(csv_path).dropna(subset=["target"])
m = json.load(open(model_path))

features = m["features"]
weights = np.array(m["weights"], dtype=float)
bias = float(m["bias"])
impute = np.array(m["impute"], dtype=float)
ca, cb = m["calibration"]["a"], m["calibration"]["b"]

# Reference: refit the same pipeline the trainer used.
X, med = design(df, features)
assert np.allclose(med, impute), "impute vector differs from the exported one"
pipe = make_model("logreg")
pipe.fit(X, df["target"].to_numpy(dtype=int))
p_ref = pipe.predict_proba(X)[:, 1]

# Exported model applied to the same rows.
X_exp = design(df, features, impute)[0]
z = bias + X_exp @ weights
p_exp = 1.0 / (1.0 + np.exp(-z))
p_exp_cal = 1.0 / (1.0 + np.exp(-(ca * z + cb)))

# Uncalibrated probability from the pipeline, for a like-for-like comparison.
scaler = pipe.named_steps["standardscaler"]
clf = pipe.named_steps["logisticregression"]
w = clf.coef_[0] / scaler.scale_
b = float(clf.intercept_[0] - np.sum(clf.coef_[0] * scaler.mean_ / scaler.scale_))
z2 = b + X_exp @ w
p_uncal = 1.0 / (1.0 + np.exp(-z2))

print(f"max |p_pipeline - p_exported_uncalibrated| = {np.abs(p_ref - p_uncal).max():.3e}")
print(f"max |z_export - z_unwrapped|                 = {np.abs(z - z2).max():.3e}")
print(f"mean p (pipeline) = {p_ref.mean():.4f}, mean p (exported) = {p_uncal.mean():.4f}")
print(f"calibration a={ca:.4f} b={cb:.4f}; "
      f"mean calibrated p = {p_exp_cal.mean():.4f}, "
      f"observed stress rate = {df['target'].mean():.4f}")