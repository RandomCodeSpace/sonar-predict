#!/usr/bin/env bash
#
# Fetches the vendored SonarSource analyzer plugin JARs from Maven Central
# into this directory, then verifies them against CHECKSUMS.txt.
#
# The JARs themselves are NOT committed to the repository (~160 MB total) —
# only this script and CHECKSUMS.txt are. Run this once after cloning so the
# daemon module's tests and analysis can load the analyzers offline thereafter.
#
# Usage:  ./fetch.sh
set -euo pipefail

cd "$(dirname "$0")"
BASE="https://repo1.maven.org/maven2"

# group-path  artifact  version
PLUGINS=(
  "org/sonarsource/java sonar-java-plugin 8.15.0.39343"
  "org/sonarsource/python sonar-python-plugin 5.5.0.23291"
  "org/sonarsource/javascript sonar-javascript-plugin 10.24.0.33043"
  "org/sonarsource/php sonar-php-plugin 3.46.0.13151"
  "org/sonarsource/kotlin sonar-kotlin-plugin 3.2.0.7239"
  "org/sonarsource/slang sonar-go-plugin 1.18.1.827"
  "org/sonarsource/slang sonar-ruby-plugin 1.19.0.471"
  "org/sonarsource/slang sonar-scala-plugin 1.19.0.484"
  "org/sonarsource/html sonar-html-plugin 3.19.0.5695"
  "org/sonarsource/xml sonar-xml-plugin 2.13.0.5938"
)

for entry in "${PLUGINS[@]}"; do
  read -r gp art ver <<<"$entry"
  jar="${art}-${ver}.jar"
  if [ -f "$jar" ]; then
    echo "skip   $jar (already present)"
    continue
  fi
  url="${BASE}/${gp}/${art}/${ver}/${jar}"
  echo "fetch  $url"
  curl -fsSL -o "$jar" "$url"
done

echo "verify CHECKSUMS.txt"
sha256sum -c CHECKSUMS.txt
echo "OK — all analyzer plugin JARs present and verified."
