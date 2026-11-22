#!/usr/bin/env bash
# P-38 密码缓存三模式的真实界面自测：真 MainFrame + 真 JOptionPane 密码框。
# 需要桌面会话（会短暂打开窗口）、JDK 17 与 Maven；会写 Windows 密钥链里的 e2e-cache-* 条目。
# 用法：bash verify-password-cache-ui.sh [--skip-ttl]
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

CLASSES=target/e2e-pwd-cache/classes
mkdir -p "$CLASSES"
"$JAVAC" -encoding UTF-8 -cp "$JAR" -d "$CLASSES" \
  tools/e2e/UiDriverSupport.java tools/e2e/PasswordCacheDriver.java

BASE=target/e2e-pwd-cache
rm -rf "$BASE/home-a" "$BASE/home-b" "$BASE/home-c" "$BASE/home-d" "$BASE/home-e" "$BASE/home-f"
for name in a b c d e f; do
  mkdir -p "$BASE/home-$name"
done

run() {
  local mode="$1"
  local home="$2"
  echo "=== $mode ($home) ==="
  JAVA_HOME="$JAVA_HOME" "$JAVA" -Dfile.encoding=UTF-8 \
    -cp "$JAR;$CLASSES" PasswordCacheDriver "$mode" "$home"
}

run convenient "$BASE/home-a"
run convenient-restart "$BASE/home-a"
run balanced-after-convenient "$BASE/home-a"
run switch-key "$BASE/home-e"
run convenient-delete "$BASE/home-f"
run strict "$BASE/home-c"
run wrong-password "$BASE/home-d"
if [ "${1:-}" != "--skip-ttl" ]; then
  run balanced-ttl "$BASE/home-b"
fi

echo "PASSWORD_CACHE_UI_ALL_PASS"
