from pathlib import Path
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import uuid
import zipfile

parser=argparse.ArgumentParser(description="Build a synthetic real-device Binder verification APK; no account or Nyanime data is used.")
parser.add_argument('--sdk', type=Path)
parser.add_argument('--java-home', type=Path)
parser.add_argument('--adb', type=Path)
parser.add_argument('--serial', help="Run on this explicit ADB device, then remove the synthetic package")
args=parser.parse_args()
PROBE=Path(__file__).resolve().parent
ROOT=PROBE.parents[2]
BUILD=ROOT/'.local/telegram-ipc-probe'/uuid.uuid4().hex
BUILD.mkdir(parents=True,exist_ok=True)
sdk=args.sdk or os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
if not sdk:
    props=ROOT/'local.properties'
    if props.exists():
        sdk=next((line.split('=',1)[1].replace('\\:',':').replace('\\\\','/') for line in props.read_text().splitlines() if line.startswith('sdk.dir=')),None)
if not sdk: raise SystemExit('Pass --sdk or set ANDROID_SDK_ROOT')
SDK=Path(sdk)
java_home=args.java_home or os.environ.get('JAVA_HOME')
if not java_home: raise SystemExit('Pass --java-home or set JAVA_HOME to a JDK17+ directory')
JAVA=Path(java_home)/'bin'
CACHE=Path(os.environ.get('GRADLE_USER_HOME',Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
TOOLS=sorted((SDK/'build-tools').iterdir())[-1]
ANDROID=SDK/'platforms/android-36/android.jar'
KOTLIN=re.search(r'kotlin_version\s*=\s*"([^"]+)"',(ROOT/'gradle/kotlinx.versions.toml').read_text()).group(1)
SOURCE=ROOT/'app/src/main/java/eu/kanade/tachiyomi/data/telegram/TelegramIpcPayload.kt'
EXE='.exe' if os.name=='nt' else ''
def jar(group,name,version=None):
    candidates=list((CACHE/group/name).glob((version or '*')+'/*/*.jar'))
    assert candidates,(group,name,version)
    return candidates[-1]
def run(args):
    r=subprocess.run([str(x) for x in args],capture_output=True,text=True)
    if r.returncode:
        print(r.stdout[-5000:]); print(r.stderr[-5000:]); raise SystemExit(r.returncode)
    if r.stderr.strip(): print(r.stderr[-1000:])
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib',KOTLIN)
annotations=jar('org.jetbrains','annotations')
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable',KOTLIN),stdlib,
          jar('org.jetbrains.kotlin','kotlin-script-runtime',KOTLIN),jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
          jar('org.jetbrains.kotlin','kotlin-daemon-embeddable',KOTLIN),
          jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm'),annotations]
classes=BUILD/'classes'; classes.mkdir(exist_ok=True)
run([JAVA/('java'+EXE),'-Xmx512m','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
     '-no-stdlib','-no-reflect','-jvm-target','1.8','-classpath',os.pathsep.join(map(str,[ANDROID,stdlib,annotations])),
     '-d',classes,SOURCE])
run([JAVA/('javac'+EXE),'-source','8','-target','8','-cp',os.pathsep.join(map(str,[ANDROID,stdlib,classes])),
     '-d',classes,PROBE/'EchoService.java',PROBE/'PayloadProbe.java'])
code=BUILD/'probe.jar'
with zipfile.ZipFile(code,'w') as archive:
    for file in classes.rglob('*.class'): archive.write(file,file.relative_to(classes).as_posix())
run([JAVA/('java'+EXE),'-Xmx512m','-cp',jar('com.android.tools','r8'),'com.android.tools.r8.D8',
     '--min-api','26','--lib',ANDROID,'--output',BUILD,code,stdlib,annotations])
apk=BUILD/'probe-unsigned.apk'
run([TOOLS/('aapt2'+EXE),'link','-I',ANDROID,'--manifest',PROBE/'AndroidManifest.xml','-o',apk])
with zipfile.ZipFile(apk,'a') as archive:
    for file in BUILD.glob('classes*.dex'): archive.write(file,file.name)
key=BUILD/'fixture.jks'
if not key.exists():
    run([JAVA/('keytool'+EXE),'-genkeypair','-keystore',key,'-storepass','fixture-password','-keypass','fixture-password',
         '-alias','fixture','-dname','CN=Synthetic IPC Fixture','-keyalg','RSA','-validity','30'])
signed=BUILD/'probe.apk'
run([JAVA/('java'+EXE),'-jar',TOOLS/'lib/apksigner.jar','sign','--ks',key,'--ks-pass','pass:fixture-password',
     '--out',signed,apk])
print('Synthetic test APK:',signed)
print('Helper SHA256:',hashlib.sha256((SOURCE).read_bytes()).hexdigest())

if args.serial:
    adb=args.adb or Path(shutil.which('adb') or SDK/'platform-tools'/('adb'+EXE))
    if not adb.exists(): raise SystemExit('ADB missing from selected SDK')
    package='it.owouwuiwi.nyanime.ipcprobe'
    installed=False
    try:
        result=subprocess.run([str(adb),'-s',args.serial,'install','-r',str(signed)],capture_output=True,text=True,check=True)
        print(result.stdout)
        installed=True
        result=subprocess.run([str(adb),'-s',args.serial,'shell','am','instrument','-w',package+'/probe.PayloadProbe'],capture_output=True,text=True,timeout=60,check=True)
        print(result.stdout)
        if 'ipc_payload=passed' not in result.stdout: raise SystemExit('Real-device Binder verification failed')
    finally:
        if installed:
            result=subprocess.run([str(adb),'-s',args.serial,'uninstall',package],capture_output=True,text=True)
            print('Synthetic package removed:',result.returncode==0 and 'Success' in result.stdout)
