#!/usr/bin/env python3
"""Verify the actual delivered APK; never infer success from build settings."""
import hashlib,json,os,pathlib,re,subprocess,sys,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
apk=root/'outputs/离线键鼠映射-v1.0.0-root.apk'
sdk=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT','/opt/homebrew/share/android-commandlinetools'))
bt=sdk/'build-tools/35.0.0'
env=os.environ.copy();env.setdefault('JAVA_HOME','/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')
def run(*args):return subprocess.check_output(list(map(str,args)),text=True,env=env)
badging=run(bt/'aapt','dump','badging',apk)
assert "name='cn.offline.mapper'" in badging and "sdkVersion:'28'" in badging
assert 'android.permission.INTERNET' not in badging
assert 'application-debuggable' not in badging
signing=run(bt/'apksigner','verify','--min-sdk-version','24','--verbose','--print-certs',apk)
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signing
assert 'Verified using v3 scheme (APK Signature Scheme v3): true' in signing
run(bt/'zipalign','-c','-P','16','4',apk)
libs=[]
ndk=sdk/'ndk/27.2.12479018/toolchains/llvm/prebuilt/darwin-x86_64/bin'
with zipfile.ZipFile(apk) as z:
    for abi,machine in [('arm64-v8a',183),('armeabi-v7a',40),('x86_64',62)]:
        name=f'lib/{abi}/libmapper_input.so';data=z.read(name)
        assert data[:4]==b'\x7fELF' and int.from_bytes(data[18:20],'little')==machine
        current=root/f'app/src/main/jniLibs/{abi}/libmapper_input.so'
        assert data==current.read_bytes(),f'APK contains stale native engine: {abi}'
        layout=run(ndk/'llvm-readelf','-W','-l',current)
        loads=[l for l in layout.splitlines() if l.strip().startswith('LOAD')]
        assert loads and all(int(l.split()[-1],16)>=16384 for l in loads), f'ELF is not aligned for 16 KB: {abi}'
        relro=[l.split() for l in layout.splitlines() if l.strip().startswith('GNU_RELRO')]
        assert all((int(r[2],16)+int(r[5],16))%16384==0 for r in relro),f'RELRO is not 16 KB aligned: {abi}'
        libs.append({'abi':abi,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'loadAlignment':16384})
    dex=b''.join(z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n))
    assert b'EngineSelfTest' not in dex,'development tests should not be shipped'
report={'artifact':apk.name,'package':'cn.offline.mapper','version':'1.0.0','minSdk':28,'targetSdk':35,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'debuggable':False,'internetPermission':False,'signatureV2':True,'signatureV3':True,'signatureVerificationMinSdk':24,'zipalign16KB':True,'certificateSha256':re.search(r'certificate SHA-256 digest: (\w+)',signing).group(1),'nativeLibraries':libs,'physicalPhoneVerified':False,'completeOriginalReplica':False}
(root/'outputs/交付校验.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
(root/'outputs/签名校验.txt').write_text(signing)
print(json.dumps(report,ensure_ascii=False,indent=2))
