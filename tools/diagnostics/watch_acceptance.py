"""Bounded read-only monitor for a single already-started acceptance run."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time

p=argparse.ArgumentParser()
p.add_argument('--serial',default='DKS9K23420020242')
p.add_argument('--label',required=True)
p.add_argument('--run-id')
p.add_argument('--display-id',type=int)
p.add_argument('--minutes',type=float,default=45)
a=p.parse_args()
root=Path(__file__).resolve().parents[2]
adb=Path.home()/'AppData/Local/Android/Sdk/platform-tools/adb.exe'

def adb_read(*args):
    r=subprocess.run([str(adb),'-s',a.serial,*args],capture_output=True,timeout=30)
    if r.returncode: raise RuntimeError(r.stderr.decode('utf-8','replace'))
    return r.stdout

listing=adb_read('exec-out','run-as','com.ynk.virtualdisplay','ls','-t','files/pet-events').decode().splitlines()
run=a.run_id or next(s[:-6] for s in listing if s.endswith('.jsonl') and not s.startswith('capture-'))
print('MONITOR_RUN_ID',run,flush=True)
deadline=time.monotonic()+a.minutes*60
previous=-1
last_heartbeat=0.0
while time.monotonic()<deadline:
    raw=adb_read('exec-out','run-as','com.ynk.virtualdisplay','cat','files/pet-events/'+run+'.jsonl')
    events=[]
    for line in raw.decode().splitlines():
        try: events.append(json.loads(line))
        except json.JSONDecodeError: pass  # The writer may be appending its last line.
    states=[e for e in events if e.get('kind')=='SESSION_STATE']
    last=states[-1] if states else {}
    new=[e for e in events if e.get('sequence',0)>previous and e.get('kind') in (
        'SESSION_STATE','ERROR','COURSE_STARTED','LEARNING_CONFIRMED','WORK_STARTED','WORK_COMPLETED')]
    for e in new:
        print(json.dumps({'sequence':e.get('sequence'),'wallTimeMs':e.get('wallTimeMs'),'kind':e.get('kind'),'payload':e.get('payload')},ensure_ascii=True),flush=True)
    invalid=[e for e in events if e.get('sequence',0)>previous and e.get('kind')=='FRAME_INVALID']
    if invalid:
        folder=root/'VirtualDisplay/.build-tmp/device-acceptance'/('monitor-'+run)
        folder.mkdir(parents=True,exist_ok=True)
        sequence=invalid[0]['sequence']
        for name,args in (('activities',('shell','dumpsys','activity','activities')),
                          ('power',('shell','dumpsys','power')),
                          ('processes',('shell','ps','-A'))):
            (folder/(str(sequence)+'-'+name+'.txt')).write_bytes(adb_read(*args))
        (folder/(str(sequence)+'-invalid.json')).write_text(json.dumps(invalid,ensure_ascii=False),encoding='utf-8')
        print('INVALID_FRAME_DIAGNOSTICS',str(folder),flush=True)
    if events: previous=max(e.get('sequence',0) for e in events)
    if new or time.monotonic()-last_heartbeat>=30:
        print(json.dumps({'heartbeat':True,'runId':run,'events':len(events),
            'inputs':sum(e.get('kind')=='INPUT_SENT' for e in events),
            'lastState':last.get('payload')},ensure_ascii=True),flush=True)
        last_heartbeat=time.monotonic()
    if last.get('payload',{}).get('state') in ('STOPPED','FAILED','COMPLETED'):
        # NOTE: a SESSION_STATE of STOPPED does NOT mean failure - the success path also ends with
        # SessionRecovery.finish(...) emitting state=STOPPED (see PetRecoveryBoundary). This block only
        # means "the run reached a terminal state, so stop polling and archive". Pass/fail must be read
        # from verdict.json (verdict / planRunState / counts) plus files/plan-runs/<runId>.json.
        r=subprocess.run([sys.executable,'-I',str(root/'tools/diagnostics/capture_acceptance_state.py'),
            '--serial',a.serial,'--run-id',run,'--label',a.label,
            *(['--display-id',str(a.display_id)] if a.display_id else [])],timeout=120,
            capture_output=True,text=True)
        sys.stdout.write(r.stdout or '')
        sys.stdout.flush()
        try:
            bundle=Path(json.loads(r.stdout or '{}').get('bundle',''))
            summary={}
            verdict_path=bundle/'verdict.json'
            if verdict_path.is_file():
                v=json.loads(verdict_path.read_text(encoding='utf-8'))
                summary['verdict']=v.get('verdict'); summary['mode']=v.get('mode')
                summary['completed']=v.get('completed'); summary['requested']=v.get('requested')
                summary['workCompleted']=v.get('workCompleted'); summary['careCompleted']=v.get('careCompleted')
                summary['stopReason']=v.get('stopReason')
            # The session verdict is the LEGACY one: for a finalized run it still reads
            # STOPPED/UNCONFIRMED. The authoritative post-finalization state is the persisted plan run.
            persisted_path=bundle/'persisted-plan-run.json'
            if persisted_path.is_file():
                p=json.loads(persisted_path.read_text(encoding='utf-8')).get('snapshot',{})
                summary['planRunState']=p.get('state'); summary['planRunStopReason']=p.get('stopReason')
                summary['counts']=p.get('counts')
            print('VERDICT_SUMMARY '+json.dumps(summary,ensure_ascii=True),flush=True)
        except (ValueError,OSError) as exc:
            print('VERDICT_SUMMARY_UNAVAILABLE '+str(exc),flush=True)
        raise SystemExit(r.returncode)
    time.sleep(5)
raise SystemExit('MONITOR_TIMEOUT: no terminal state observed; no input was sent by this monitor')
