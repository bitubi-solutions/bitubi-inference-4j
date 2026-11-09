#!/bin/bash
# The chat UI on a scripted fake model, no download and no model: edit a template or a static file under
# bitubi-inference-4j-server/src/main, save, refresh the browser. Options: --port N, --delay-ms N, --fail-at N.
# The first run builds what it needs (a minute); later runs start in about 9 seconds.
set -e
cd "$(dirname "$0")"
MODULE=bitubi-inference-4j-server
CP_FILE=$MODULE/target/dev-classpath.txt

if [[ ! -d $MODULE/target/test-classes || ! -f $CP_FILE ]]; then
  echo "First run: building the server module and its classpath..."
  ./mvnw -B -q -DskipTests -pl $MODULE -am install
  ./mvnw -B -q -pl $MODULE test-compile dependency:build-classpath -Dmdep.outputFile="$PWD/$CP_FILE" -Dmdep.includeScope=test
fi

JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
exec $JAVA --add-modules jdk.incubator.vector -XX:TieredStopAtLevel=1 \
  -cp "$MODULE/target/test-classes:$MODULE/target/classes:$(cat $CP_FILE)" \
  ao.bitubi.inference4j.server.DevServer "$@"
