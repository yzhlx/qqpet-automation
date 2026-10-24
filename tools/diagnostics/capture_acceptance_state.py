"""Read-only local acceptance bundle. Never launches, taps, stops or changes device settings.

The optional file list also pulls the PLAN path's per-round completion records
(`plan-round-<i>-completion.json`, discovered by listing the evidence directory) and the
saved plan document (`files/plan-v2.json`), because a STUDY-bearing combo and the SAVE-7
read-back both need them as authority. Missing optional files are recorded in
`missingOptionalFiles`; a remote `cat` error is never written as a JSON artifact.
"""
import argparse
import datetime as dt
import json
import re
from pathlib import Path
import subprocess

p=argparse.ArgumentParser()
p.add_argument('--serial',default='DKS9K23420020242')
p.add_argument('--run-id')
p.add_argument('--label',required=True)
p.add_argument('--display-id',type=int)
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
adb=Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'
out=root/'VirtualDisplay/.build-tmp/device-acceptance'/('continuation-'+dt.datetime.now().strftime('%Y%m%d-%H%M%S-%f')+'-'+a.label)
out.mkdir(parents=True,exist_ok=False)

def command(*args):
    r=subprocess.run([str(adb),'-s',a.serial,*args],capture_output=True,timeout=30)
    if r.returncode: raise RuntimeError(r.stderr.decode('utf-8','replace').strip())
    return r.stdout

def app_file(path):
    return command('exec-out','run-as','com.ynk.virtualdisplay','cat',path)

def save_json(name,data):
    # adb exec-out may put a remote cat error in stdout with exit 0. That is NOT a JSON artifact.
    parsed=json.loads(data.decode('utf-8'))
    if not isinstance(parsed,(dict,list)): raise ValueError('NOT_A_JSON_DOCUMENT:'+name)
    (out/name).write_bytes(data)
    return parsed

listing=command('exec-out','run-as','com.ynk.virtualdisplay','ls','-t','files/pet-events').decode('utf-8').splitlines()
run=a.run_id or next(name[:-6] for name in listing if name.endswith('.jsonl') and not name.startswith('capture-'))
raw=app_file('files/pet-events/'+run+'.jsonl')
events=[json.loads(line) for line in raw.decode('utf-8').splitlines() if line.strip()]
(out/'events.jsonl').write_bytes(raw)
(out/'pet_mvp.log').write_bytes(app_file('files/pet_mvp.log'))
(out/'budget.xml').write_bytes(app_file('shared_prefs/pet_mvp_budget.xml'))
save_json('checkpoint.json',app_file('files/pet-checkpoints/'+run+'.json'))
errors=[]
try:
    budget=app_file('files/pet-minute-budget-v2.bin')
    if not budget.startswith(b'\x00\x0cQQPET_BUDGET'): raise ValueError('INVALID_BUDGET_DOCUMENT')
    (out/'pet-minute-budget-v2.bin').write_bytes(budget)
except (RuntimeError,ValueError) as exc: errors.append('authoritative budget: '+str(exc))
observed=[e.get('payload',{}).get('displayId') for e in events]
observed=[d for d in observed if isinstance(d,int) and d>0]
display=a.display_id or (observed[-1] if observed else None)
if not display or display<=0: raise ValueError('DISPLAY_ID_REQUIRED: pass actual --display-id')
for identifier in (0,display):
    image=command('exec-out','screencap','-d',str(identifier),'-p')
    if not image.startswith(b'\x89PNG\r\n\x1a\n'): raise ValueError('INVALID_SCREENSHOT:'+str(identifier))
    (out/('display-%d.png'%identifier)).write_bytes(image)
(out/'activities.txt').write_bytes(command('shell','dumpsys','activity','activities'))
(out/'power.txt').write_bytes(command('shell','dumpsys','power'))
paths=[]
for e in events:
    for path in e.get('evidence',[]):
        if path.startswith('/storage/emulated/0/Android/data/com.ynk.virtualdisplay/files/pet_mvp_session/'):
            directory=path.split('/round-')[0]
            if directory not in paths: paths.append(directory)
round_files_by_directory={}
for directory in paths[-1:]:
    saved_round_files=[]
    for name in ('verdict.json','run-record.json','round-1.json','round-1-completion.json',
                 'plan-steps.json','plan-care.json','plan-run.json','work-only.json','care-only.json'):
        try: save_json(name,command('exec-out','cat',directory+'/'+name))
        except (RuntimeError,ValueError) as exc: errors.append(name+': '+str(exc))
    # The PLAN path writes plan-round-<i>-completion.json (one per lesson), not round-1-completion.json.
    # Discover them by listing the evidence directory instead of guessing the round count.
    listing=command('shell','ls',directory).decode('utf-8','replace')
    for name in sorted(set(re.findall(r'(plan-round-\d+-completion\.json)',listing))):
        try:
            save_json(name,command('exec-out','cat',directory+'/'+name))
            saved_round_files.append(name)
        except (RuntimeError,ValueError) as exc: errors.append(name+': '+str(exc))
    round_files_by_directory[directory]=saved_round_files
try: save_json('plan-v2.json',app_file('files/plan-v2.json'))
except (RuntimeError,ValueError) as exc: errors.append('saved plan: '+str(exc))
try:
    persisted=save_json('persisted-plan-run.json',app_file('files/plan-runs/'+run+'.json'))
except (RuntimeError,ValueError) as exc:
    persisted=None
    errors.append('persisted plan: '+str(exc))
# A STUDY-bearing run must carry one plan-round-<i>-completion.json per lesson; a run without a
# STUDY step legitimately has none and must not gain a fake entry. Absence is reported, never filled.
expected_rounds=0
if persisted:
    steps=((persisted.get('snapshot') or {}).get('request') or {}).get('planSnapshot',{}).get('steps',[])
    expected_rounds=sum(s.get('rounds',0) for s in steps if s.get('type')=='STUDY')
saved_round_names=[n for names in round_files_by_directory.values() for n in names]
for i in range(1,expected_rounds+1):
    if not any(n=='plan-round-%d-completion.json'%i for n in saved_round_names):
        errors.append('plan-round-%d-completion.json: NOT_FOUND (run has a STUDY step with %d rounds)'%(i,expected_rounds))
summary={'runId':run,'displayId':display,'deviceTime':command('shell','date','+%Y-%m-%dT%H:%M:%S%z').decode().strip(),
    'budgetAuthority':'pet-minute-budget-v2.bin' if (out/'pet-minute-budget-v2.bin').exists() else 'legacy-or-unavailable',
    'eventCount':len(events),'inputCount':sum(e.get('kind')=='INPUT_SENT' for e in events),
    'expectedStudyRounds':expected_rounds,'savedPlanRoundFiles':sorted(saved_round_names),
    'lastStates':[{'sequence':e.get('sequence'),**e.get('payload',{})} for e in events if e.get('kind')=='SESSION_STATE'][-8:],
    'missingOptionalFiles':errors}
(out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'bundle':str(out),**summary},ensure_ascii=True,indent=2))
