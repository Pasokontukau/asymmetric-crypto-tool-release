#!/usr/bin/env bash
# 官网截图：用真实 MainFrame 渲染 1200×840 布局，按 2x 输出 2400×1680 PNG，深/浅共 8 张。
# 2x 输出供高分屏使用（官网按 1180 CSS px 显示），需要 1x 时设 SHOT_SCALE=1。
# 需要桌面会话（会短暂开窗口）、JDK 17 与 Maven。
#
# 用法：
#   bash desktop/shoot-site-shots.sh              # 输出到 desktop/target/site-shots/
#   bash desktop/shoot-site-shots.sh /tmp/shots   # 输出到指定目录
#
# 产物命名已对齐发布仓 docs/screenshots/：
#   desktop-keys / desktop-contacts / desktop-secure-text / desktop-stego
#   浅色版本加 -light 后缀。
# 仅使用临时 home 里的演示数据，不读真实用户目录。
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

CLASSES=target/site-shots-classes
rm -rf "$CLASSES"
mkdir -p "$CLASSES"
"$JAVAC" -encoding UTF-8 -cp "$JAR" -d "$CLASSES" tools/e2e/UiShotDriver.java

OUT_DIR="${1:-target/site-shots}"
mkdir -p "$OUT_DIR"
OUT_ABS="$(cd "$OUT_DIR" && pwd)"

# 从 home 目录里跑，classpath 用相对路径：含非 ASCII 的绝对路径会被 Git Bash 的路径转换破坏
JAR_REL="../../../$JAR"
CLASSES_REL="../../../$CLASSES"

for theme in dark light; do
  home="target/site-shots/home-$theme"
  rm -rf "$home"
  mkdir -p "$home"
  target="$OUT_ABS/$theme"
  rm -rf "$target"
  mkdir -p "$target"
  (
    cd "$home"
    "$JAVA" -Dfile.encoding=UTF-8 -cp "$JAR_REL;$CLASSES_REL" \
      UiShotDriver "$(pwd)" "$target" "$theme" "0,1,2,6" "${SHOT_SCALE:-2}"
  )
  suffix=""
  if [ "$theme" = "light" ]; then
    suffix="-light"
  fi
  mv "$target/tab0.png" "$target/desktop-keys$suffix.png"
  mv "$target/tab1.png" "$target/desktop-contacts$suffix.png"
  mv "$target/tab2.png" "$target/desktop-secure-text$suffix.png"
  mv "$target/tab6.png" "$target/desktop-stego$suffix.png"
  rm -rf "$home"
done

echo "UI_SHOTS_ALL_DONE"
echo "深色：$OUT_ABS/dark/*.png"
echo "浅色：$OUT_ABS/light/*.png"
echo "发布仓更新：cp \"$OUT_ABS/dark\"/*.png \"$OUT_ABS/light\"/*.png ../asymmetric-crypto-tool-release/docs/screenshots/"
