"""Train the StressSense prototype model from a CSV with these columns:
hr_z,hrv_z,rhr_z,sleep_z,activity_z,label

Usage: python train_stress_model.py labelled_data.csv app/src/main/assets/stress_model.json
"""
import json, sys
import pandas as pd
from sklearn.linear_model import LogisticRegression

FEATURES=["hr_z","hrv_z","rhr_z","sleep_z","activity_z"]
if len(sys.argv)!=3: raise SystemExit("usage: python train_stress_model.py input.csv output.json")
df=pd.read_csv(sys.argv[1]).dropna(subset=FEATURES+["label"])
X=df[FEATURES].astype(float); y=df["label"].astype(int)
model=LogisticRegression(max_iter=2000).fit(X,y)
out={"version":1,"type":"logistic_regression","features":FEATURES,"weights":model.coef_[0].tolist(),"bias":float(model.intercept_[0]),"classes":model.classes_.tolist()}
with open(sys.argv[2],"w") as f: json.dump(out,f,indent=2)
print("saved",sys.argv[2])
