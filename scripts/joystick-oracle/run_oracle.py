#!/usr/bin/env python3
"""Build test-only adapters and execute original recovered DEX calculations on an explicit lab emulator."""
import argparse
import collections
import datetime
import hashlib
import json
import math
import os
import pathlib
import struct
import subprocess

BASE=pathlib.Path(__file__).resolve().parent
PROJECT=BASE.parents[1]
SDK=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT','/opt/homebrew/share/android-commandlinetools'))
JAVA=pathlib.Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@17'))
REFERENCE_SHA256='457095c52c1055aa7df539284a34975718a667ad7c812b3cc1e4938c60435fcf'
CANDIDATE=PROJECT/'app/src/main/java/cn/offline/mapper/engine/JoystickTrajectory.java'
CANDIDATES=[CANDIDATE,CANDIDATE.with_name('JoystickController.java'),CANDIDATE.with_name('TouchSink.java')]

def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(args,**kw):return subprocess.run([str(x) for x in args],check=True,**kw)
def f32(hex_value):return struct.unpack('!f',bytes.fromhex(hex_value.zfill(8)))[0]

def branch_coverage(report):
    counts=collections.Counter();by_strength=collections.defaultdict(set)
    for row in report['cases']:
        ref=row['reference'];strong=ref['strong']
        floats=[x for x in ref['pathCalls'] if x.startswith('f:')]
        q=f32(floats[1].split(':')[1]);spread=f32(ref['spreadBits'])
        length=math.hypot(ref['target'][0]-row['start'][0],ref['target'][1]-row['start'][1])
        if q < (.3 if strong else .15) and length>2*spread:branch='arc'
        elif q < (.5 if strong else .25) and length>spread:branch='spiral'
        elif q < (.75 if strong else .45):branch='quarticS'
        elif q < (.9 if strong else .65):branch='cubicOpposite'
        else:branch='cubicMix'
        row['branchEvidence']={'branch':branch,'q':q,'length':length,'spread':spread,
            'kind':'branch classified from original recorded random return and geometry using recovered branch conditions'}
        counts[branch]+=1;by_strength[strong].add(branch)
    return {'counts':dict(counts),'byStrong':{str(k).lower():sorted(v) for k,v in by_strength.items()},
        'allFiveByBothStrengths':all(len(by_strength[v])==5 for v in (True,False))}

def display_path(path):
    try:return str(path.relative_to(PROJECT))
    except ValueError:return path.name

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--reference-dex',type=pathlib.Path,required=True,help='Locally supplied original runtime DEX; never uploaded by this script')
    parser.add_argument('--avd-name',default='MapperResearch20261014',help='Exact name of your own disposable test AVD')
    parser.add_argument('--out',type=pathlib.Path,default=PROJECT/'research/joystick-oracle')
    args=parser.parse_args();DEX=args.reference_dex.resolve();args.out=args.out.resolve();args.out.mkdir(parents=True,exist_ok=True)
    adb=[SDK/'platform-tools/adb','-s',args.serial]
    name=run([*adb,'emu','avd','name'],capture_output=True,text=True).stdout
    if args.avd_name not in name.splitlines():raise RuntimeError('Device does not match the explicitly selected disposable AVD')
    if digest(DEX)!=REFERENCE_SHA256:raise RuntimeError('Original recovered DEX digest differs')
    classes=args.out/'build/classes';classes.mkdir(parents=True,exist_ok=True)
    for p in classes.rglob('*.class'):p.unlink()
    snapshot=args.out/'source-snapshot';snapshot.mkdir(exist_ok=True)
    source_versions=[]
    for source in CANDIDATES:
        dest=snapshot/source.name;dest.write_bytes(source.read_bytes())
        source_versions.append({'path':str(source.relative_to(PROJECT)),'sha256':digest(dest),'snapshot':display_path(dest)})
    sources=sorted((BASE/'src').rglob('*.java'))+sorted(snapshot.glob('*.java'))
    env=os.environ.copy();env['JAVA_HOME']=str(JAVA)
    run([JAVA/'bin/javac','--release','11','-cp',SDK/'platforms/android-35/android.jar','-d',classes,*sources])
    jar=args.out/'oracle.jar'
    run([SDK/'build-tools/35.0.0/d8','--lib',SDK/'platforms/android-35/android.jar','--output',jar,*sorted(classes.rglob('*.class'))],env=env)
    run([*adb,'shell','mkdir -p /data/local/tmp/mapper-trajectory-oracle'])
    run([*adb,'push',jar,'/data/local/tmp/mapper-trajectory-oracle.jar'])
    run([*adb,'push',DEX,'/data/local/tmp/mapper-trajectory-original.dex'])
    actual=run([*adb,'shell','sha256sum /data/local/tmp/mapper-trajectory-original.dex'],capture_output=True,text=True).stdout.split()[0]
    if actual!=digest(DEX):raise RuntimeError('Device original DEX digest differs')
    with (args.out/'stdout.txt').open('w') as stdout,(args.out/'stderr.txt').open('w') as stderr:
        proc=subprocess.run([str(x) for x in [*adb,'shell','CLASSPATH=/data/local/tmp/mapper-trajectory-oracle.jar app_process / oracle.TrajectoryOracle /data/local/tmp/mapper-trajectory-original.dex compare']],stdout=stdout,stderr=stderr)
    reports=[json.loads(line) for line in (args.out/'stdout.txt').read_text().splitlines() if line.startswith('{')]
    if len(reports)!=1:raise RuntimeError('Missing complete runtime report; inspect stdout/stderr and logcat')
    report=reports[0];report['branchCoverage']=branch_coverage(report)
    report['coverageComplete']=report['branchCoverage']['allFiveByBothStrengths']
    with (args.out/'state-stdout.txt').open('w') as stdout,(args.out/'state-stderr.txt').open('w') as stderr:
        state_proc=subprocess.run([str(x) for x in [*adb,'shell','CLASSPATH=/data/local/tmp/mapper-trajectory-oracle.jar app_process / oracle.StateOracle /data/local/tmp/mapper-trajectory-original.dex']],stdout=stdout,stderr=stderr)
    state_reports=[json.loads(line) for line in (args.out/'state-stdout.txt').read_text().splitlines() if line.startswith('{')]
    if len(state_reports)!=1:raise RuntimeError('Missing complete state runtime report')
    report['controllerState']=state_reports[0]
    report['passed']=report['passed'] and state_reports[0]['passed']
    (args.out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    meta={'completedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'deviceSerial':args.serial,
        'ownedAvd':args.avd_name,'deviceDexSha256':actual,'originalDex':display_path(DEX),
        'originalDexSha256':digest(DEX),'originalDexChanged':False,'candidateSources':source_versions,
        'testJarSha256':digest(jar),'exitCode':proc.returncode,'stateExitCode':state_proc.returncode,'sources':[
            {'path':display_path(p),'sha256':digest(p)} for p in sources],
        'resultSha256':digest(args.out/'result.json'),'scope':'Unmodified original DEX calculation methods with test-only output/display adapters; no original app launch or native authentication'}
    (args.out/'provenance.json').write_text(json.dumps(meta,indent=2))
    print(json.dumps({'passed':report['passed'],'randomCases':len(report['cases']),'directCases':len(report['directCases']),
        'timingCases':len(report['timingAllocator']),'mismatches':report['mismatches'],'coverage':report['branchCoverage'],
        'controllerMasks':len(state_reports[0]['maskCases']),'controllerSequences':len(state_reports[0]['sequenceCases'])},ensure_ascii=False))
    if proc.returncode!=0 or state_proc.returncode!=0 or not report['passed'] or not report['coverageComplete']:raise SystemExit(1)

if __name__=='__main__':main()
