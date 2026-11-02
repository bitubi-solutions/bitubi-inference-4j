#!/bin/bash
# Usage: docs/benchmarks/run-perf.sh [jdk-home]   (default: $JAVA_HOME)
set -e
JDK_HOME="${1:-$JAVA_HOME}"
echo "== hardware =="
case "$(uname -s)" in
  Linux)
    lscpu | grep -E "Model name|^CPU\(s\)"
    grep -o -w -E "avx512[a-z_]*|avx2|avx|fma" /proc/cpuinfo | sort -u | tr '\n' ' '; echo ;;
  Darwin)
    sysctl -n machdep.cpu.brand_string
    echo "CPUs: $(sysctl -n hw.ncpu) arch: $(uname -m)" ;;
  MINGW*|MSYS*|CYGWIN*)
    echo "${PROCESSOR_IDENTIFIER:-unknown}"
    echo "CPUs: ${NUMBER_OF_PROCESSORS:-?}" ;;
esac
echo "== measurement =="
PERF_BASELINE=1 JAVA_HOME="$JDK_HOME" ./mvnw -B -pl bitubi-inference-4j-tests -am package \
  -Dtest=PerfBaselineTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "^PERF |BUILD"
