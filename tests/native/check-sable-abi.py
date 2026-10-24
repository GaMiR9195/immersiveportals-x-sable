#!/usr/bin/env python3
"""Compare every Sable 2.0.6 native declaration with Rust's JNI signature.
Uses the actual supplied dependency JAR, not a handwritten expected method list.
Usage: JAVA_HOME=... python3 tests/native/check-sable-abi.py /path/to/sable-sable_rapier-1.21.1-2.0.6.jar
"""
import os,re,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
if len(sys.argv)!=2: raise SystemExit(__doc__)
java=Path(os.environ['JAVA_HOME'])/'bin/javap' if 'JAVA_HOME' in os.environ else 'javap'
api=subprocess.check_output([str(java),'-p','-s','-classpath',sys.argv[1],
 'dev.ryanhcode.sable.physics.impl.rapier.Rapier3D'],text=True)
rust='\n'.join(p.read_text() for p in (root/'natives/rapier/src').glob('*.rs'))
entries={}
for m in re.finditer(r'fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_(\w+)(?:\s*<[^>]*>)?\s*\((.*?)\)\s*(?:->\s*([^\s{]+))?\s*\{',rust,re.S):
 name,args,ret=m.groups()
 entries[name]=(re.findall(r'\w+\s*:\s*(\w+)(?:<[^>]*>)?',args)[2:],ret or 'void')
types={'double':'jdouble','int':'jint','long':'jlong','boolean':'jboolean','byte':'jbyte',
 'double[]':'JDoubleArray','int[]':'JIntArray','void':'void'}
def jni(t): return types.get(t,'JObject')
def normalize(t): return {'SableJointHandle':'jlong'}.get(t.split('<')[0],t.split('<')[0])
errors=[];count=0
for ret,name,args in re.findall(r'\bnative\s+(\S+)\s+(\w+)\(([^)]*)\);',api):
 count+=1;expected=([jni(t.strip()) for t in args.split(',') if t.strip()],jni(ret))
 found=entries.get(name)
 actual=None if found is None else ([normalize(t) for t in found[0]],normalize(found[1]))
 if expected!=actual: errors.append(f'{name}: Java {expected} != Rust {actual}')
if count<50: errors.append(f'Unexpectedly small API: only {count} methods')
if errors: raise SystemExit('\n'.join(errors))
print(f'PASS: all {count} JNI method signatures match the actual Sable JAR (arguments AND returns)')
