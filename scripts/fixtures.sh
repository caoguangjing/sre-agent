#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=var/java-classpath.txt
java -cp "target/classes:$(cat var/java-classpath.txt)" io.agentjava.fixtures.FixtureCli "$@"
