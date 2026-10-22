#!/usr/bin/env bash
# Сборка, тесты и HTML-отчёт о покрытии — одной командой: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

JACOCO_VERSION=0.8.12
JACOCO_DIR=lib/jacoco
BUILD=build
MIN_COVERAGE=80

# Единственная зависимость (только для тестов) — JaCoCo, скачивается один раз.
if [ ! -f "$JACOCO_DIR/lib/jacococli.jar" ]; then
    echo "Скачиваю JaCoCo $JACOCO_VERSION..."
    mkdir -p "$JACOCO_DIR"
    curl -fsSL -o jacoco.zip \
        "https://repo1.maven.org/maven2/org/jacoco/jacoco/${JACOCO_VERSION}/jacoco-${JACOCO_VERSION}.zip"
    unzip -q -o jacoco.zip -d "$JACOCO_DIR"
    rm -f jacoco.zip
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/test-classes" "$BUILD/report"

echo "Компиляция..."
find src/main/java -name '*.java' > "$BUILD/main-sources.txt"
javac --release 21 -encoding UTF-8 -d "$BUILD/classes" @"$BUILD/main-sources.txt"
find src/test/java -name '*.java' > "$BUILD/test-sources.txt"
javac --release 21 -encoding UTF-8 -cp "$BUILD/classes" -d "$BUILD/test-classes" @"$BUILD/test-sources.txt"

echo "Тесты и замер покрытия..."
java -javaagent:"$JACOCO_DIR/lib/jacocoagent.jar=destfile=$BUILD/jacoco.exec" \
    -cp "$BUILD/classes:$BUILD/test-classes" loganalyzer.Tests

java -jar "$JACOCO_DIR/lib/jacococli.jar" report "$BUILD/jacoco.exec" \
    --classfiles "$BUILD/classes" \
    --sourcefiles src/main/java \
    --html "$BUILD/report" \
    --csv "$BUILD/coverage.csv" \
    --name "Log Analyzer"

echo
echo "HTML-отчёт: $BUILD/report/index.html"
awk -F, -v min="$MIN_COVERAGE" 'NR > 1 { missed += $8; covered += $9 }
    END {
        total = missed + covered
        share = (total > 0) ? 100 * covered / total : 0
        printf "Покрытие строк: %d из %d (%.1f%%), порог %.1f%%\n", covered, total, share, min
        exit (share >= min) ? 0 : 1
    }' "$BUILD/coverage.csv"
