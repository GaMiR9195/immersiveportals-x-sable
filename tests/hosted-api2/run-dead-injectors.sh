#!/usr/bin/env bash
# Applies the COMPLETE ipl_sable.mixins.json (defaultRequire 0) to every target class on the
# dependency classpath and lists handlers that were merged but are invoked nowhere, i.e.
# injectors that silently never applied. Sable's own mixins are only partially present
# (harness-sable*.json), so handlers aimed at methods Sable merges itself are reported too.
set -euo pipefail
cd "$(dirname "$0")/../.."
cpfile="${1:-build/hosted-api2-classpath.txt}"
[[ -f "$cpfile" ]] || { echo 'First: ./gradlew assemble jarJar writeHostedApi2Classpath -I tests/hosted-api2/write-classpath.gradle' >&2; exit 1; }
cp="$(cat "$cpfile")"
out="$(mktemp -d)"; xf="$(mktemp -d)"; trap 'rm -rf "$out" "$xf"' EXIT
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"; JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
cp -r tests/hosted-api2/harness-resources/* "$out/"
python3 - "$out" <<'PY'
import json,re,sys,pathlib
out=pathlib.Path(sys.argv[1]); j=json.load(open('src/main/resources/ipl_sable.mixins.json')); j.pop('refmap',None)
(out/'harness-ipl-full.mixins.json').write_text(json.dumps(j))
targets=set(); base=pathlib.Path('src/main/java/ipl/sable/mixin')
for m in j.get('mixins',[])+j.get('client',[]):
    s=(base/(m.replace('.','/')+'.java')).read_text()
    ann=re.search(r'@Mixin\s*\((.*?)\)\s*(?:public|abstract|class|interface|@)',s,re.S).group(1)
    imports={x.split('.')[-1]:x for x in re.findall(r'^import\s+([\w.]+);',s,re.M)}
    pkg=re.search(r'^package\s+([\w.]+);',s,re.M).group(1)
    targets.update(re.findall(r'"([\w.$]+)"',ann))
    for c in re.findall(r'([\w.]+)\.class',ann):
        targets.add(c if '.' in c else imports.get(c,pkg+'.'+c))
(out/'targets.txt').write_text('\n'.join(sorted(targets)))
PY
"$JAVAC" -proc:none -cp "build/classes/java/main:$cp" -d "$out" tests/hosted-api2/harness/*.java tests/hosted-api2/TargetAudit.java
"$JAVA" -Daudit.out="$xf" -Daudit.configs=harness-flywheel.mixins.json,harness-sable.mixins.json,harness-sable-common.mixins.json,harness-ipl-full.mixins.json \
 -cp "$out:build/classes/java/main:src/main/resources:$cp" harness.FullAudit "$out/targets.txt" | grep -E '^(FAIL|MISSING|SUMMARY)'
"$JAVA" -cp "$out:$cp" harness.DeadHandlers "$xf"
