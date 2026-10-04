"""Train a small logistic-regression research model from a labelled WESAD feature CSV.

Expected columns (case-insensitive aliases are accepted):
HR/mean_hr, RMSSD/rmssd, resting_hr/rhr, sleep_minutes, steps, label/condition.
For WESAD, use baseline as 0 and stress as 1. Subject-wise validation should be used
for any reported research metric. This script writes Android-compatible stress_model.json.
"""
import argparse, json
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ALIASES = {
    'hr':['hr','mean_hr','hr_mean'], 'rmssd':['rmssd','hrv_rmssd'],
    'rhr':['rhr','resting_hr'], 'sleep':['sleep_minutes','sleep'],
    'steps':['steps','step_count'], 'label':['label','condition','stress_label']
}

def pick(df, names):
    cols={c.lower():c for c in df.columns}
    for n in names:
        if n in cols: return cols[n]
    raise KeyError(f'Missing one of {names}')

ap=argparse.ArgumentParser(); ap.add_argument('--csv', required=True); ap.add_argument('--out', default='../app/src/main/assets/stress_model.json'); args=ap.parse_args()
df=pd.read_csv(args.csv)
cols={k:pick(df,v) for k,v in ALIASES.items()}
X=df[[cols['hr'],cols['rmssd'],cols['rhr'],cols['sleep'],cols['steps']]].apply(pd.to_numeric,errors='coerce').dropna()
y=df.loc[X.index,cols['label']]
if y.dtype == object:
    y=y.astype(str).str.lower().map(lambda x: 1 if x in {'stress','2','1','high'} else 0)
y=pd.to_numeric(y,errors='coerce')
mask=y.notna(); X=X.loc[mask]; y=y.loc[mask].astype(int)
model=make_pipeline(StandardScaler(), LogisticRegression(max_iter=2000,class_weight='balanced'))
model.fit(X,y)
scaler=model.named_steps['standardscaler']; clf=model.named_steps['logisticregression']
# z-score model: Android uses personal z-scores, so export only the classifier coefficients.
obj={'version':3,'type':'logistic_regression_wesad_feature_model','features':['hr_z','hrv_z','resting_hr_z','sleep_z','steps_z'], 'weights':clf.coef_[0].tolist(), 'bias':float(clf.intercept_[0]), 'training':'WESAD-labelled feature CSV', 'note':'Validate subject-independently and with in-the-wild data before operational use.'}
with open(args.out,'w') as f: json.dump(obj,f,indent=2)
print('Wrote',args.out)
