#!/bin/bash

# Function to get Java executable path
get_java_exec() {
  if [[ -n "$JAVA_HOME" ]]; then
    echo "$JAVA_HOME/bin/java"
  else
    echo "java"
  fi
}

# Function to extract the major version of Java
get_java_major_version() {
  local version=$($1 -version 2>&1 | awk -F '"' '/version/ {print $2}')
  echo ${version%%.*}
}

# Verify Java version is JDK 25 or newer
JAVA=$(get_java_exec)
JAVA_MAJOR_VERSION=$(get_java_major_version $JAVA)
if [[ "$JAVA_MAJOR_VERSION" -lt 25 ]]; then
  echo "Error: JDK 25 or newer is required to run this application."
  exit 1
fi

# Define the path of the relative JAR
BI4J_RELATIVE_JAR="./bitubi-inference-4j-cli/target/bitubi-inference-4j-cli.jar"
# Path to the logback.xml
LOGBACK_CONFIG="./conf/logback.xml"

BI4J_JVM_ARGS="$BI4J_JVM_ARGS -ea -server -Dstdout.encoding=UTF-8 -Djdk.incubator.vector.VECTOR_ACCESS_OOB_CHECK=0 --add-opens=jdk.incubator.vector/jdk.incubator.vector=ALL-UNNAMED --add-modules=jdk.incubator.vector --add-exports java.base/sun.nio.ch=ALL-UNNAMED --enable-preview --enable-native-access=ALL-UNNAMED \
 -XX:+UnlockDiagnosticVMOptions -XX:CompilerDirectivesFile=./inlinerules.json -XX:+AlignVector -XX:+UseStringDeduplication \
 -XX:+UseCompressedOops -XX:+UseCompressedClassPointers"
        
# Which jar runs, and where its AOT cache lives (next to it)
if [[ -z "$BI4J_PREINSTALLED_JAR" ]]; then
  BI4J_JAR=$BI4J_RELATIVE_JAR
  # If the relative JAR doesn't exist, build it
  if [[ ! -f $BI4J_JAR ]]; then
    echo "The JAR $BI4J_JAR is missing. Attempting to build..."
    ./mvnw clean package -DskipTests
    if [[ $? -ne 0 ]]; then
      echo "Error building the JAR. Please check your build setup."
      exit 1
    fi
  fi
else
  BI4J_JAR=$BI4J_PREINSTALLED_JAR
fi
BI4J_AOT="$(dirname "$BI4J_JAR")/bi4j.aot"

# ./run-cli.sh --create-aot <model> [serve options]: starts the server once, exercises it and stops it, so the JVM
# writes the class/AOT cache (JDK 25). Later launches pick it up by themselves and start about twice as fast.
if [[ "$1" == "--create-aot" ]]; then
  shift
  if [[ $# -eq 0 ]]; then
    echo "Usage: $0 --create-aot <model> [serve options]"
    exit 1
  fi
  PORT=${BI4J_AOT_PORT:-18999}
  rm -f "$BI4J_AOT"
  $JAVA $BI4J_JVM_ARGS $BI4J_JVM_ARGS_EXTRA -XX:AOTCacheOutput="$BI4J_AOT" -jar "$BI4J_JAR" serve "$@" --port "$PORT" --no-history &
  PID=$!
  for _ in $(seq 1 600); do
    kill -0 $PID 2>/dev/null || { echo "The training run stopped early; no cache was written."; exit 1; }
    curl -s -m 1 "http://127.0.0.1:$PORT/healthz" | grep -qi '"ready"' && break
    sleep 0.5
  done
  curl -s -m 5 "http://127.0.0.1:$PORT/" -o /dev/null
  curl -s -m 5 "http://127.0.0.1:$PORT/v1/models" -o /dev/null
  curl -s -m 120 "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' -d '{"messages":[{"role":"user","content":"hi"}],"max_tokens":8}' -o /dev/null
  curl -s -m 120 -N "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' -d '{"messages":[{"role":"user","content":"hi"}],"max_tokens":8,"stream":true}' -o /dev/null
  kill -TERM $PID
  wait $PID 2>/dev/null
  if [[ -f "$BI4J_AOT" ]]; then echo "AOT cache written: $BI4J_AOT"; exit 0; fi
  echo "The JVM did not write a cache."
  exit 1
fi

# Use the cache when it is newer than the jar. A stale or incompatible cache never stops the launch: the JVM
# prints a warning and runs without it.
if [[ -f "$BI4J_AOT" && "$BI4J_AOT" -nt "$BI4J_JAR" ]]; then
  BI4J_JVM_ARGS="$BI4J_JVM_ARGS -XX:AOTCache=$BI4J_AOT"
fi

$JAVA $BI4J_JVM_ARGS $BI4J_JVM_ARGS_EXTRA -jar "$BI4J_JAR" "$@"
