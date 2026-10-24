"""Restore the authorized Shizuku + calibrated pet display. Never starts work/care/study."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('--serial',default='DKS9K23420020242')
a=p.parse_args()
b=[r'C:\Users\lu158\AppData\Local\Android\Sdk\platform-tools\adb.exe','-s',a.serial]
APP='com.ynk.virtualdisplay'
QQ='com.tencent.mobileqq'

def command(*args,check=True):
    r=subprocess.run(b+list(args),capture_output=True,timeout=20)
    if check and r.returncode: raise RuntimeError(r.stderr.decode('utf-8','replace').strip())
    return r.stdout.decode('utf-8','replace')

def tree():
    command('shell','uiautomator','dump','/data/local/tmp/pet-environment.xml')
    raw=command('exec-out','cat','/data/local/tmp/pet-environment.xml')
    return ET.fromstring(raw)

def texts(root): return [n.get('text','') for n in root.iter('node')]

def tap(label,package=APP,display=0,band=None):
    root=tree(); parents={c:p for p in root.iter() for c in p}; candidates={}
    for node in root.iter('node'):
        if node.get('package')!=package or node.get('text')!=label: continue
        if node.get('enabled')=='false': continue
        target=node
        bounds=tuple(map(int,re.findall(r'\d+',target.get('bounds',''))))
        if len(bounds)!=4 or bounds[0]>=bounds[2] or bounds[1]>=bounds[3]:
            while target.get('clickable')!='true' and target in parents: target=parents[target]
            if target.get('clickable')!='true': continue
            bounds=tuple(map(int,re.findall(r'\d+',target.get('bounds',''))))
        if len(bounds)!=4: continue
        x1,y1,x2,y2=bounds
        w,h=(1084,2412) if display==0 else (720,1600)
        if not(0<=x1<x2<=w and 0<=y1<y2<=h): continue
        if band and not(band[0]<=x1<=band[2] and band[1]<=y1<=band[3]): continue
        candidates[bounds]=target
    if len(candidates)!=1: raise RuntimeError('TARGET_NOT_UNIQUE_OR_VISIBLE:'+label+':'+str(len(candidates)))
    x1,y1,x2,y2=next(iter(candidates))
    command('shell','input','-d',str(display),'tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(0.3)
    print('identified target:',label,'display',display,flush=True)

service=command('shell','dumpsys','activity','services',APP+'.automation.PetAutomationService')
if re.search(r'ServiceRecord\{[^\n]*PetAutomationService',service):
    raise RuntimeError('AUTOMATION_SERVICE_ACTIVE: refusing environment navigation')
pid=command('shell','pidof','shizuku_server',check=False).strip()
if not pid:
    package=command('shell','dumpsys','package','moe.shizuku.privileged.api')
    native=re.search(r'legacyNativeLibraryDir=(\S+)',package)
    if not native: raise RuntimeError('SHIZUKU_NATIVE_DIRECTORY_UNREAD')
    starter=native.group(1)+'/arm64/libshizuku.so'
    command('shell','test','-x',starter)
    print(command('shell',starter),flush=True)
    for _ in range(24):
        pid=command('shell','pidof','shizuku_server',check=False).strip()
        if pid: break
        time.sleep(0.25)
    if not pid: raise RuntimeError('SHIZUKU_START_UNCONFIRMED')
print('Shizuku server:',pid,flush=True)
command('shell','am','start','--display','0','-n',APP+'/.ui.main.MainActivity')
time.sleep(0.5)
root=tree()
if 'Shizuku 未运行' in texts(root): tap('重试检测')
root=tree()
if '虚拟屏控制台' not in texts(root):
    tap('设置'); tap('虚拟屏')
root=tree()
if not any('特权服务' in t and '已连接' in t for t in texts(root)):
    raise RuntimeError('PRIVILEGED_SERVICE_NOT_CONNECTED')
ids=[int(m.group(1)) for t in texts(root) if (m:=re.fullmatch(r'显示器 #(\d+)',t))]
if not ids:
    tap('新建显示器')
    for label,new in [('宽度','720'),('高度','1600'),('DPI','320')]:
        root=tree()
        nodes=[n for n in root.iter('node') if n.get('class')=='android.widget.EditText' and any(c.get('text')==label for c in n.iter('node'))]
        if len(nodes)!=1: raise RuntimeError('DISPLAY_FIELD_AMBIGUOUS:'+label)
        node=nodes[0]; x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
        command('shell','input','-d','0','tap',str((x1+x2)//2),str((y1+y2)//2))
        command('shell','input','-d','0','keyevent','123',*(['67']*len(node.get('text',''))))
        command('shell','input','-d','0','text',new)
        time.sleep(0.15); command('shell','input','-d','0','keyevent','4')
    root=tree()
    values=[n.get('text') for n in root.iter('node') if n.get('class')=='android.widget.EditText']
    if values!=['720','1600','320']: raise RuntimeError('DISPLAY_GEOMETRY_NOT_CONFIRMED')
    tap('创建并启动'); root=tree()
    ids=[int(m.group(1)) for t in texts(root) if (m:=re.fullmatch(r'显示器 #(\d+)',t))]
if len(ids)!=1: raise RuntimeError('OWNED_DISPLAY_AMBIGUOUS:'+str(ids))
if not any('720' in t and '1600' in t for t in texts(root)):
    raise RuntimeError('OWNED_DISPLAY_GEOMETRY_UNCONFIRMED')
display=ids[0]
print('Calibrated display:',display,flush=True)
activities=command('shell','dumpsys','activity','activities')
section=re.search(r'Display: mDisplayId='+str(display)+r'\b(.*?)(?=\n  Display:|\Z)',activities,re.S)
if section and 'AdelieFragmentActivity' in section.group(1):
    print('Pet activity already present; no game navigation performed',flush=True)
else:
    command('shell','am','start','--display',str(display),'-f','0x18000000','-n',QQ+'/.activity.SplashActivity')
    time.sleep(0.5)
    root=tree()
    if not any(n.get('package')==QQ for n in root.iter('node')): raise RuntimeError('QQ_FOREGROUND_NOT_CONFIRMED')
    tap('搜索',QQ,display)
    tap('宠物',QQ,display)
    # Current 720p QQ function result: exact pet label in the measured FUNCTION tile band.
    # Category labels and group names elsewhere cannot supply this target.
    tap('宠物',QQ,display,band=(150,430,300,650))
    time.sleep(0.5)
    activities=command('shell','dumpsys','activity','activities')
    section=re.search(r'Display: mDisplayId='+str(display)+r'\b(.*?)(?=\n  Display:|\Z)',activities,re.S)
    if not section or 'AdelieFragmentActivity' not in section.group(1): raise RuntimeError('PET_ACTIVITY_NOT_CONFIRMED')
    print('Pet function opened; automation will verify the scene before any consuming action',flush=True)
command('shell','am','start','--display','0','-n',APP+'/.ui.main.MainActivity')
print('ENVIRONMENT_RESTORED displayId='+str(display)+'; no work/care/study requested',flush=True)
