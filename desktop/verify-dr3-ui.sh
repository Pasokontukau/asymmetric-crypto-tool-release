#!/usr/bin/env bash
# DR-3 UI smoke: real MainFrame shell plus batch-decrypt routes.
# Requires a desktop session (opens windows), JDK 17 and Maven.
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${AEGIX_JAVA_HOME:-$HOME/.jdks/corretto-17}"
export JAVA_HOME
MVN="${MVN:-$HOME/.trae-cn/tools/maven/latest/bin/mvn}"
if [ ! -x "$JAVA_HOME/bin/javac" ] || [ ! -x "$MVN" ]; then
  echo "JDK 17 or Maven not found; set AEGIX_JAVA_HOME and MVN." >&2
  exit 1
fi

JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8"

"$MVN" -B -ntp -q package -DskipTests
jars=(target/*-jar-with-dependencies.jar)
JAR="${jars[0]}"
if [ ! -f "$JAR" ]; then
  echo "fat jar not found" >&2
  exit 1
fi

CLASSES=target/e2e-dr3/classes
mkdir -p "$CLASSES"
"$JAVAC" -encoding UTF-8 -cp "$JAR" -d "$CLASSES" \
  tools/e2e/UiDriverSupport.java tools/e2e/ShellSmokeDriver.java \
  tools/e2e/BatchMergeDriver.java tools/e2e/OrdinaryDecryptDriver.java

run() {
  local name="$1"
  shift
  local home="target/e2e-dr3/home-$name"
  rm -rf "$home"
  mkdir -p "$home"
  JAVA_HOME="$JAVA_HOME" "$JAVA" -Dfile.encoding=UTF-8 \
    -cp "$JAR;$CLASSES" "$@" "$home"
}

run shell ShellSmokeDriver
for mode in merge incomplete mixed single duplicate mixedSets cancel; do
  run "$mode" BatchMergeDriver "$mode"
done
for mode in singlePart ordinary ordinaryCancel readonly; do
  run "$mode" OrdinaryDecryptDriver "$mode"
done

echo "DR3_UI_ALL_PASS"
