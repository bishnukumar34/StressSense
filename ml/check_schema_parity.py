"""Confirm the Kotlin FeatureSchema order is byte-identical to the exported model's feature order."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
kotlin = (root / "app/src/main/java/com/bishnu/stresswelfarebridge/ml/FeatureSchema.kt").read_text(
    encoding="utf-8")
model = json.loads((root / "app/src/main/assets/stress_model_ml.json").read_text(encoding="utf-8"))

# Only the entries inside the `val ALL` list use these private helper constructors.
block = kotlin.split("val ALL: List<Feature> = listOf(")[1].split("\n    )")[0]
names = re.findall(r'\b(?:acute|resp|dev|act|tmp|qual)\("([a-z0-9_]+)"', block)

print("kotlin schema features :", len(names))
print("model artifact features:", len(model["features"]))
print("weights               :", len(model["weights"]))
print("EXACT ORDER MATCH     :", names == model["features"])
print("weight count == 2x n  :", len(model["weights"]) == 2 * len(names))
if names != model["features"]:
    print("kotlin:", names)
    print("model :", model["features"])
    raise SystemExit(1)
print("\nOK: Android FeatureSchema and the Python-exported model agree on feature order.")