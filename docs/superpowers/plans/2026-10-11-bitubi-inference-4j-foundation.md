# bitubi-inference-4j Foundation (sub-project 0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Jlama fork into `bitubi-inference-4j` (rename, attribution, build on JDK 25, CI on Linux, Windows and macOS, publishing), without changing behavior.

**Architecture:** Mechanical changes in small, reviewable commits. First clean up the line-ending noise, then make the build work on JDK 25 (baseline), and only then rename, so that a rename breakage can be told apart from a build breakage. A smoke test with a real model proves that the engine and the native code keep working.

**Tech Stack:** Java 25 (minimum), Maven (wrapper), JUnit 4, GitHub Actions, GitHub Packages.

**Spec:** `docs/superpowers/specs/2026-10-11-bitubi-inference-4j-foundation-design.md`

## Global Constraints

- Java package: `ao.bitubi.inference4j` (a hyphen is not valid in a package name).
- `groupId`: `ao.bitubi`; `artifactId`s: `bitubi-inference-4j-parent`, `bitubi-inference-4j-core`, `bitubi-inference-4j-native`, `bitubi-inference-4j-net`, `bitubi-inference-4j-cli`, `bitubi-inference-4j-tests`.
- CLI: command `bi4j`; main class `ao.bitubi.inference4j.cli.Bi4jCli` (currently `JlamaCli`).
- Version: `<revision>` = `0.1.0-SNAPSHOT`.
- Minimum and only target JDK: 25 (LTS). JDK 20, 21 and 22 are no longer supported. CI runs on Linux, Windows and macOS, always with JDK 25.
- Apache 2.0 attribution: keep `LICENSE`; create `NOTICE`; preserve the original copyright headers; mark modified files; do not use the original author's name/brand to suggest endorsement.
- GitHub organization: `bitubi-solutions`; `upstream` points to `tjake/jlama`.
- Hugging Face model ids (`tjake/...`) are external names and stay; likewise the `tjake/sdkman-for-toolchains` clone in the Dockerfile.
- Never add Claude trailers (`Co-Authored-By`, `Claude-Session`) to commits or PRs (author's rule).
- No change to engine logic. Every task ends with a green build.

## Review Focus

1. File paths with spaces, `$` or CRLF in the mechanical rename: files such as `constants$0.java` (names with `$`) must be moved and referenced correctly.
2. References to the old package inside strings (logger name in `logback*.xml`, the `.proto` `package`, `apiPackage` in `jlama-net/pom.xml`, `Main-Class` in the manifest): the rename must not leave them pointing to classes that do not exist (fails only at runtime).
3. Multi-release JAR of `jlama-native` without the `java25/` classes (`META-INF/versions/25`): the engine silently falls back to the path without native code (slower), with no build error.
4. Native binary and the `${os.detected.name}-${os.detected.arch}` classifier on macOS and Windows: the rename must not break `jlama-native` outside Linux.
5. Users on JDK 26 or later: the Vector API changes between incubation rounds and may throw `NoSuchMethodError`. Only JDK 25 is tested; record this in the README.

---

### Task 1: Normalize line endings

**Files:**
- Create: `.gitattributes`

**Interfaces:**
- Produces: working tree with no phantom differences; all following tasks assume a clean `git status`.

- [ ] **Step 1: Create the working branch**

```bash
git checkout docs/aby-foundation-spec
git checkout -b foundation/aby-rename
```

- [ ] **Step 2: Confirm the initial state (line endings only)**

Run: `git diff --ignore-cr-at-eol --stat | tail -1`
Expected: no output (no real content difference).

- [ ] **Step 3: Create `.gitattributes`**

```gitattributes
* text=auto eol=lf
*.sh text eol=lf
mvnw text eol=lf
*.bat text eol=crlf
*.cmd text eol=crlf
*.jpg binary
*.png binary
*.jar binary
```

- [ ] **Step 4: Renormalize and verify**

Run: `git add .gitattributes && git add --renormalize . && git status --short | grep -v '^??'`
Expected: only `A  .gitattributes` (no `M` files). If `M` entries appear, run `git diff --ignore-cr-at-eol --stat` again: it must still be empty.

- [ ] **Step 5: Commit**

```bash
git commit -m "chore: normaliza fim de linha com .gitattributes
```

---

### Task 2: Green build and tests on JDK 25 (baseline before the rename)

**Files:**
- Modify: `pom.xml` (compiler and surefire, lines ~118-205)
- Modify: `jlama-native/pom.xml` (`compile-jdk20/21/22` executions)
- Delete: `jlama-native/src/main/java20/`, `jlama-native/src/main/java21/`
- Rename: `jlama-native/src/main/java22/` to `jlama-native/src/main/java25/`
- Modify: `run-cli.sh`, `run-cli.bat` (the `.bat` has no version check; only the `.sh` is changed)
- Create (local, not versioned): `~/.m2/toolchains.xml`

**Interfaces:**
- Produces: green `./mvnw -B verify` with a single toolchain, JDK 25 (the minimum supported); tests executed on JDK 25 (`default-test` execution).

Context: today the build requires JDK 20, 21 and 22 toolchains (not installed on this machine: there are 21, 24 and 25 in `~/.sdkman/candidates/java`). `jlama-native` compiles per-version sources: `java20/` and `java21/` use FFM in preview, `java22/` uses final FFM. With JDK 25 as the minimum, only the last one stays; it is renamed to `java25/` (the multi-release JAR uses `META-INF/versions/25`).

- [ ] **Step 1: Create a local toolchain**

Create `~/.m2/toolchains.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides><version>25</version></provides>
    <configuration><jdkHome>/home/dumi/.sdkman/candidates/java/25.0.3-amzn</jdkHome></configuration>
  </toolchain>
</toolchains>
```

- [ ] **Step 2: Prove that the current build fails (baseline)**

Run: `./mvnw -B -DskipTests package 2>&1 | tail -15`
Expected: FAIL with a toolchain error (`Cannot find matching toolchain definitions ... jdk [ version='20' ]`, `'21'` or `'22'`).

- [ ] **Step 3: Remove JDK 20 and 21 and rename the 22 directory**

```bash
git rm -r jlama-native/src/main/java20 jlama-native/src/main/java21
git mv jlama-native/src/main/java22 jlama-native/src/main/java25
```

In `jlama-native/pom.xml`, delete the whole `<execution>` elements with `<id>compile-jdk20</id>` and `<id>compile-jdk21</id>`. In the `compile-jdk22` execution, change `<id>` to `compile-jdk25`, `<release>22</release>` to `<release>25</release>`, `<jdkToolchain><version>22</version></jdkToolchain>` to `<version>25</version>`, `<compileSourceRoot>` to `${project.basedir}/src/main/java25`, and remove `<arg>--enable-preview</arg>` (FFM is final since 22, and `--release 25` with `--enable-preview` is not needed here).

- [ ] **Step 4: Compile the base with JDK 25**

In `pom.xml`, in `maven-compiler-plugin`, replace:

```xml
<source>20</source>
<target>20</target>
...
<jdkToolchain>
  <version>22</version>
</jdkToolchain>
```

with:

```xml
<source>25</source>
<target>25</target>
...
<jdkToolchain>
  <version>25</version>
</jdkToolchain>
```

- [ ] **Step 4b: Keep the bytecode at 21 (recorded deviation)**

With `source`/`target` 25, `jlama-net` fails at runtime: Spring Boot 3.3.x (ASM) cannot read class version 69 (`Unsupported class file major version 69`, in `ChatApiTest`). Therefore, in the `maven-compiler-plugin` of `pom.xml`, use `<source>21</source>` and `<target>21</target>` (not 25), with an XML comment explaining why. JDK 25 remains the only JDK for build, test and runtime (toolchain, the native module's `java25/`, `run-cli`). Raise the target to 25 together with the Spring upgrade (sub-project 2).

- [ ] **Step 5: Surefire on JDK 25 only**

In `pom.xml`, keep a single `<execution>`, `default-test`, with `<jdkToolchain><version>25</version></jdkToolchain>`; delete the `jdk21-test` and `jdk22-test` executions. Keep the same `argLine` (includes `--enable-preview`).

- [ ] **Step 6: Adjust `run-cli.sh` and `run-cli.bat`**

In `run-cli.sh`, replace the version-check block with:

```bash
# Verify Java version is JDK 25 or newer
JAVA=$(get_java_exec)
JAVA_MAJOR_VERSION=$(get_java_major_version $JAVA)
if [[ "$JAVA_MAJOR_VERSION" -lt 25 ]]; then
  echo "Error: JDK 25 or newer is required to run this application."
  exit 1
fi
```

Do the equivalent in `run-cli.bat` (compare the major version with 25).

- [ ] **Step 7: Build with JDK 25 as `JAVA_HOME`**

Run: `JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B -DskipTests package 2>&1 | tail -20`
Expected: `BUILD SUCCESS`. If `jlama-native` fails to compile `java25/`, read the error: fix only the flag/`release` in question; do not change Java code. Confirm the multi-release JAR: `unzip -l jlama-native/target/jlama-native-*-linux-x86_64.jar | grep "META-INF/versions/25"` must list `cnative` classes.

- [ ] **Step 8: Run the tests**

Run: `JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B clean package 2>&1 | grep -E "Tests run:|BUILD" | tail -15`
Expected: `BUILD SUCCESS`. Use `package`, not `test`: `jlama-tests` needs the multi-release JAR of `jlama-native` (with `META-INF/versions/25`); with `test` it only sees `target/classes`, where Java does not apply multi-release, and the native tests fail with `UnsupportedOperationException: Not implemented for this JDK version`. Use `clean` when changing `source`/`target`, otherwise Maven does not recompile and the old classes remain. Tests that depend on models in `../models/` are skipped via `Assume` (they do not count as failures). Some `jlama-net` tests and `TestSample` download models from Hugging Face (tens of minutes the first time); a `Failed to download file` / `Connection reset` failure is a network problem: run again, since the model stays cached.

- [ ] **Step 9: Commit**

```bash
git add -A pom.xml jlama-native run-cli.sh run-cli.bat
git commit -m "build: exige JDK 25, remove suporte a JDK 20, 21 e 22
```

---

### Task 3: Smoke test with a real model

**Files:**
- Create: `jlama-tests/src/test/java/com/github/tjake/jlama/model/SmokeTest.java` (the path changes in the rename, Task 4)

**Interfaces:**
- Consumes: `Downloader(String workingDirectory, String model).huggingFaceModel()`, `ModelSupport.loadModel(File, DType, DType)`, `AbstractModel.generate(UUID, PromptContext, float, int, BiConsumer)` (as in `TestSample.java`).
- Produces: `SmokeTest.generatesSameTokensAsReference` and the reference `smoke-golden-25.txt` in `jlama-tests/src/test/resources/`; the test proves that the engine and the native code produce the same output after the rename.

- [ ] **Step 1: See how `TestSample.java` downloads and loads the model**

Run: `sed -n 30,75p jlama-tests/src/test/java/com/github/tjake/jlama/model/TestSample.java`
Expected: uses `Downloader` and `ModelSupport.loadModel` with `tjake/Qwen2.5-0.5B-Instruct-JQ4`. Copy the exact imports from there.

- [ ] **Step 2: Write the test (with comparison against a reference output)**

At temperature 0 generation is deterministic on the same machine. The test compares the generated text with a reference file (`smoke-golden-25.txt`, named after the JDK version). The reference is valid only for the machine where it was recorded: floating point may differ on another CPU or OS. With `SMOKE_RECORD=1` it records the reference instead of comparing.

```java
/*
 * Copyright 2024 T Jake Luciani
 * (Apache 2.0 header identical to conf/license-header)
 */
package com.github.tjake.jlama.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tjake.jlama.model.functions.Generator;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.prompt.PromptContext;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;

public class SmokeTest {

    private static final Path GOLDEN_DIR = Paths.get("src/test/resources");

    @Test
    public void generatesSameTokensAsReference() throws Exception {
        Assume.assumeTrue("SMOKE_MODEL=1 required", "1".equals(System.getenv("SMOKE_MODEL")));

        File dir = SafeTensorSupport.maybeDownloadModel("./models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");
        AbstractModel m = ModelSupport.loadModel(dir, DType.F32, DType.I8);

        PromptContext ctx = m.promptSupport().get().builder()
            .addUserMessage("What is the capital of France?")
            .build();

        Generator.Response r = m.generate(UUID.randomUUID(), ctx, 0.0f, 96, (s, f) -> {});
        assertFalse("no tokens generated", r.responseText.isBlank());

        if ("1".equals(System.getenv("SMOKE_RELAXED"))) {
            return; // CI: hardware differs from the reference one; only requires non-empty text
        }

        Path golden = GOLDEN_DIR.resolve("smoke-golden-" + Runtime.version().feature() + ".txt");
        if ("1".equals(System.getenv("SMOKE_RECORD"))) {
            Files.writeString(golden, r.responseText, StandardCharsets.UTF_8);
            return;
        }
        assertTrue("missing reference " + golden + " (run once with SMOKE_RECORD=1)", Files.exists(golden));
        assertEquals(Files.readString(golden, StandardCharsets.UTF_8), r.responseText);
    }
}
```

The fourth argument of `generate` is the **total** token limit (prompt included); 16 fails with `Prompt exceeds max tokens`. The download API is `SafeTensorSupport.maybeDownloadModel` (the README, which mentions `Downloader`, is outdated). If the Java download drops with `Connection reset`, download `model.safetensors` with `curl -L -C -` into `jlama-tests/models/tjake_Qwen2.5-0.5B-Instruct-JQ4/`: the downloader skips files that have the expected size.

- [ ] **Step 3: Record the references (before any rename)**

Run once, on your machine:

Run: `SMOKE_MODEL=1 SMOKE_RECORD=1 JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B -pl jlama-tests -am test -Dtest=SmokeTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD"; ls jlama-tests/src/test/resources/smoke-golden-*`
Expected: `BUILD SUCCESS` and a non-empty `smoke-golden-25.txt` file. (Downloads ~500 MB the first time.) If the download fails (model removed from Hugging Face), replace the id with another small model that exists and note it in the commit. Read the file: if the text is nonsensical (garbage), stop and investigate before continuing.

- [ ] **Step 4: Run in comparison mode**

Run: `SMOKE_MODEL=1 JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B -pl jlama-tests -am test -Dtest=SmokeTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD"`
Expected: `Tests run: 1, Failures: 0` and `BUILD SUCCESS`. Running twice in a row must pass both times (confirms determinism).

- [ ] **Step 5: Commit**

```bash
git add jlama-tests/src/test/java/com/github/tjake/jlama/model/SmokeTest.java jlama-tests/src/test/resources/smoke-golden-25.txt
git commit -m "test: teste de fumaça com saída de referência
```

---

### Task 3b: Performance baseline (before the rename)

**Files:**
- Create: `jlama-tests/src/test/java/com/github/tjake/jlama/model/PerfBaselineTest.java` (the path changes in the rename, Task 4)
- Create: `docs/benchmarks/baseline-<hardware>.md` (the name is chosen in Step 3)
- Create: `docs/benchmarks/run-perf.sh`

**Interfaces:**
- Consumes: `Generator.Response` with the public fields `promptTokens`, `generatedTokens`, `promptTimeMs`, `generateTimeMs`.
- Produces: reference numbers (tokens/s) per hardware, used in Task 4 and in sub-projects 1, 1b and 2.

Metrics for this baseline (quality and GPU metrics go into the specs of sub-projects 1 and 1b): prompt tokens/s (prefill), generation tokens/s (decode), model load time and peak memory. The numbers come from `Generator.Response` itself, without depending on JMH.

- [ ] **Step 1: Write the measurement test**

```java
/*
 * Copyright 2024 T Jake Luciani
 * (Apache 2.0 header identical to conf/license-header)
 */
package com.github.tjake.jlama.model;

import com.github.tjake.jlama.model.functions.Generator;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.prompt.PromptContext;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import java.io.File;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Test;

public class PerfBaselineTest {

    private static final int WARMUP_RUNS = 2;
    private static final int MEASURED_RUNS = 5;
    private static final int MAX_TOKENS = 256; // total token limit (prompt included), as in TestSample

    @Test
    public void measureTokensPerSecond() throws Exception {
        Assume.assumeTrue("PERF_BASELINE=1 required", "1".equals(System.getenv("PERF_BASELINE")));

        File dir = SafeTensorSupport.maybeDownloadModel("./models", "tjake/Qwen2.5-0.5B-Instruct-JQ4");

        long t0 = System.nanoTime();
        AbstractModel m = ModelSupport.loadModel(dir, DType.F32, DType.I8);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;

        PromptContext ctx = m.promptSupport().get().builder()
            .addUserMessage("Write a short paragraph about the history of the Java programming language.")
            .build();

        for (int i = 0; i < WARMUP_RUNS; i++) {
            m.generate(UUID.randomUUID(), ctx, 0.0f, MAX_TOKENS, (s, f) -> {});
        }

        double prefillSum = 0;
        double decodeSum = 0;
        for (int i = 0; i < MEASURED_RUNS; i++) {
            Generator.Response r = m.generate(UUID.randomUUID(), ctx, 0.0f, MAX_TOKENS, (s, f) -> {});
            prefillSum += r.promptTokens / (double) Math.max(1, r.promptTimeMs) * 1000.0;
            decodeSum += r.generatedTokens / (double) Math.max(1, r.generateTimeMs) * 1000.0;
        }

        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        System.out.printf(
            "PERF jdk=%d load_ms=%d prefill_tok_s=%.2f decode_tok_s=%.2f heap_used_mb=%d%n",
            Runtime.version().feature(), loadMs, prefillSum / MEASURED_RUNS, decodeSum / MEASURED_RUNS, usedMb);
    }
}
```

- [ ] **Step 2: Run script**

Create `docs/benchmarks/run-perf.sh`:

```bash
#!/bin/bash
# Uso: docs/benchmarks/run-perf.sh <jdk-home>
set -e
JDK_HOME="${1:?uso: run-perf.sh <jdk-home>}"
echo "== hardware =="
lscpu | grep -E "Model name|^CPU\(s\)|Flags" | sed -E 's/(Flags:).*/\1 (ver avx abaixo)/'
grep -o -w -E "avx512[a-z_]*|avx2|avx|fma|neon" /proc/cpuinfo | sort -u | tr '\n' ' '; echo
echo "== medição =="
PERF_BASELINE=1 JAVA_HOME="$JDK_HOME" ./mvnw -B -pl jlama-tests -am test \
  -Dtest=PerfBaselineTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "^PERF |BUILD"
```

Run: `chmod +x docs/benchmarks/run-perf.sh`

- [ ] **Step 3: Measure and record**

Run: `docs/benchmarks/run-perf.sh /home/dumi/.sdkman/candidates/java/25.0.3-amzn | tee /tmp/perf-before.txt`
Expected: one `PERF jdk=25 ...` line and `BUILD SUCCESS`. Run it three times and note the variation. Create `docs/benchmarks/baseline-<hardware>.md` (choose `<hardware>` from the CPU model, lowercase and hyphenated) with: the hardware block printed by the script, the commit (`git rev-parse --short HEAD`), the JDK, the three runs and the observed variation range (min-max). This variation defines the tolerance used in Task 4.

- [ ] **Step 4: Commit**

```bash
git add jlama-tests/src/test/java/com/github/tjake/jlama/model/PerfBaselineTest.java docs/benchmarks
git commit -m "test: baseline de desempenho (tokens/s) antes do rename
```

---

### Task 4: Mechanical rename (package, groupId, artifacts, CLI)

**Files:**
- Move: all `.../com/github/tjake/jlama/` directories to `.../ao/bitubi/inference4j/` (in `jlama-core`, `jlama-native` (`java/`, `java25/`), `jlama-net`, `jlama-cli`, `jlama-tests`)
- Modify: all files containing `com.github.tjake` (list: `grep -rl`)
- Modify: `pom.xml`, `jlama-*/pom.xml`, `jlama-net/src/main/proto/JlamaService.proto`, `jlama-tests/src/test/resources/logback-test.xml`, `conf/logback.xml`, `.run/*.xml`
- Rename: class `JlamaCli` to `Bi4jCli`

**Interfaces:**
- Produces: package `ao.bitubi.inference4j.*`; main class `ao.bitubi.inference4j.cli.Bi4jCli`; artifacts `bitubi-inference-4j-*`.

- [ ] **Step 1: List what will be touched (to compare later)**

Run: `grep -rl "com.github.tjake" . | grep -v '^./.git/' | wc -l`
Expected: `154` (the 154 from the survey; if it differs, note the new number).

- [ ] **Step 2: Move the directories**

```bash
for d in $(find . -type d -path '*/com/github/tjake/jlama' -not -path './.git/*'); do
  base="${d%/com/github/tjake/jlama}"
  mkdir -p "$base/ao/bitubi"
  git mv "$d" "$base/ao/bitubi/inference4j"
done
find . -type d -empty -path '*/com/*' -not -path './.git/*' -delete
```

Run: `find . -path '*/com/github/tjake*' -not -path './.git/*' | head`
Expected: no output.

- [ ] **Step 3: Replace package references in text**

```bash
grep -rl "com\.github\.tjake\.jlama" . --exclude-dir=.git --exclude-dir=target \
  | xargs sed -i 's/com\.github\.tjake\.jlama/ao.bitubi.inference4j/g'
grep -rl "com/github/tjake/jlama" . --exclude-dir=.git --exclude-dir=target \
  | xargs sed -i 's#com/github/tjake/jlama#ao/bitubi/inference4j#g'
```

This covers Java `package`/`import`, `Main-Class`, `apiPackage`/`modelPackage` in `jlama-net/pom.xml`, the `.proto` `package`, `logback*.xml` and the `MemorySegment.class` exclusions in `jlama-native/pom.xml`.

- [ ] **Step 4: groupId and artifactIds**

```bash
grep -rl "com\.github\.tjake" . --exclude-dir=.git --exclude-dir=target \
  | xargs sed -i 's#<groupId>com\.github\.tjake</groupId>#<groupId>ao.bitubi</groupId>#g'
for m in parent core native net cli tests; do
  grep -rl "jlama-$m" . --include=pom.xml --exclude-dir=.git --exclude-dir=target \
    | xargs sed -i "s#jlama-$m#bitubi-inference-4j-$m#g"
done
sed -i 's#<revision>0.8.4</revision>#<revision>0.1.0-SNAPSHOT</revision>#' pom.xml
```

Then rename the module directories to match the `artifactId` and update the `<module>` entries in `pom.xml`:

```bash
for m in core native net cli tests; do git mv jlama-$m bitubi-inference-4j-$m; done
sed -i 's#<module>jlama-\(core\|native\|net\|cli\|tests\)</module>#<module>bitubi-inference-4j-\1</module>#' pom.xml
```

Fix the remaining `jlama-` occurrences in paths: `grep -rn "jlama-" --include=pom.xml --include=*.sh --include=*.bat --include=*.yaml --include=Dockerfile . | grep -v '^./.git/'` and change each one to `bitubi-inference-4j-` (e.g. `run-cli.sh` uses `./jlama-cli/target/jlama-cli.jar`; `<finalName>` in the cli pom).

- [ ] **Step 5: Rename the CLI**

```bash
git mv bitubi-inference-4j-cli/src/main/java/ao/bitubi/inference4j/cli/JlamaCli.java \
       bitubi-inference-4j-cli/src/main/java/ao/bitubi/inference4j/cli/Bi4jCli.java
grep -rl "JlamaCli" . --exclude-dir=.git --exclude-dir=target | xargs sed -i 's/JlamaCli/Bi4jCli/g'
```

In `Bi4jCli.java`, change the picocli command name to `bi4j` (look for `name = "jlama"` and replace it with `name = "aby"`).

- [ ] **Step 6: No reference to the old package is left**

Run: `grep -rn "com\.github\.tjake\|com/github/tjake" . --exclude-dir=.git --exclude-dir=target | grep -v "docs/superpowers"`
Expected: no output.

- [ ] **Step 7: Build and tests**

Run: `JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B clean verify 2>&1 | grep -E "Tests run:|BUILD|ERROR" | tail -15`
Expected: `BUILD SUCCESS`. If `jlama-core` fails because of a `module-info`/resource with the old name, run `grep -rn "jlama" --include=*.json --include=*.properties --include=*.xml --exclude-dir=target .` and fix the package name.

- [ ] **Step 8: Output comparison (proves the native code and the engine survived)**

Do not record new references: the test compares against the ones recorded before the rename (Task 3).

Run: `SMOKE_MODEL=1 JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B -pl bitubi-inference-4j-tests -am test -Dtest=SmokeTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD"`
Expected: `Tests run: 1, Failures: 0` and `BUILD SUCCESS`, i.e. the generated text is identical to the one before the rename. If it fails with a text difference, the rename broke something (probably the native path); do not re-record the reference.

Then confirm that the native path loads: `unzip -l bitubi-inference-4j-native/target/bitubi-inference-4j-native-*-linux-x86_64.jar | grep -E "META-INF/versions/25/ao/bitubi"` must list `cnative` classes (Review Focus 3).

- [ ] **Step 9: Performance comparison**

Run: `docs/benchmarks/run-perf.sh /home/dumi/.sdkman/candidates/java/25.0.3-amzn | tee /tmp/perf-after.txt`
Expected: `prefill_tok_s` and `decode_tok_s` within the min-max range recorded in `docs/benchmarks/baseline-<hardware>.md` (Task 3b). If it falls below the range, run it twice more to rule out noise; if it persists, the rename changed the execution path (for example, the native code is no longer used) and must be investigated before the commit. Append the "after" numbers to the same baseline file.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor: renomeia para bitubi-inference-4j (pacote, groupId, artefatos, CLI)
```

---

### Task 5: Attribution and identity (NOTICE, README, metadata)

**Files:**
- Create: `NOTICE`
- Modify: `README.md`, `pom.xml` (url, scm, developers, description), `conf/license-header`, `Dockerfile` (`image.source` label), `docker-compose.yaml`, `kubernetes/helm/jlama/values.yaml`, `kubernetes/gke_values.yaml`

**Interfaces:**
- Consumes: package and artifacts from Task 4.
- Produces: attribution compliant with Apache 2.0.

- [ ] **Step 1: Create `NOTICE`**

```text
bitubi-inference-4j
Copyright 2026 Bitubi Solutions

Este produto inclui software derivado do Jlama
(https://github.com/tjake/jlama), Copyright 2024 T Jake Luciani e
colaboradores, licenciado sob a Apache License, Version 2.0.

Os arquivos originais mantêm seus cabeçalhos de copyright. Arquivos
modificados por Bitubi Solutions são marcados com um aviso de alteração.
```

- [ ] **Step 2: Update the new license header**

In `conf/license-header`, keep the existing header (original author) and append at the end of the block, before ` */`:

```text
 *
 * Modifications Copyright 2026 Bitubi Solutions
```

Warning: Spotless applies this header to every `.java` file. Therefore, do not run `spotless:apply` at this point (it would rewrite 150+ files). Note the decision in the commit.

- [ ] **Step 3: `pom.xml` metadata**

In the root `pom.xml`, replace: `<name>Jlama Parent</name>` with `<name>bitubi-inference-4j Parent</name>`, `<url>` with `https://github.com/bitubi-solutions/bitubi-inference-4j`, `scm` (`connection`, `developerConnection`, `url`) and `issueManagement` with `bitubi-solutions/bitubi-inference-4j`, `<description>` with `bitubi-inference-4j: motor de inferência de LLMs em Java (derivado do Jlama)`. In `<developers>`, keep Jake Luciani and add a `<developer>` with `<organization>Bitubi Solutions</organization>`.

- [ ] **Step 4: README**

Rewrite the title and introduction for `bitubi-inference-4j` and add, right after the badges, a section:

```markdown
## Origem

bitubi-inference-4j é um fork mantido do [Jlama](https://github.com/tjake/jlama)
(© T Jake Luciani e colaboradores, Apache 2.0). Veja o arquivo `NOTICE`.
```

Replace the `jlama ...` commands with `bi4j ...`, the `groupId` in the Maven examples with `ao.bitubi` and the `artifactId`s with `bitubi-inference-4j-*`. Keep the `tjake/...` model ids (they are Hugging Face names) and the link to `hf.co/tjake`. Remove the `jbang app install jlama@tjake` installation and leave `jbang` as "coming soon".

- [ ] **Step 5: Docker and Kubernetes**

In `Dockerfile`, change `LABEL org.opencontainers.image.source` to `https://github.com/bitubi-solutions/bitubi-inference-4j`. In `kubernetes/helm/jlama/values.yaml` and `kubernetes/gke_values.yaml`, replace `repository: docker.io/tjake/jlama` with `ghcr.io/bitubi-solutions/bitubi-inference-4j`. Do not touch the model ids.

- [ ] **Step 6: Check the spec criterion**

Run: `grep -rniI "tjake" . --exclude-dir=.git --exclude-dir=target --exclude-dir=superpowers | grep -v "hf.co/tjake\|tjake/[A-Za-z0-9._-]*\(JQ4\|Jlama\|jlama\|Instruct\|gemma\|Q4\)\|sdkman-for-toolchains\|tjake/jlama"`
Expected: only legitimate lines (`NOTICE`, the README "Origem" section, copyright headers with "T Jake Luciani", BibTeX citation). Any other occurrence must be fixed.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: adiciona NOTICE e atribuição ao Jlama original
```

---

### Task 6: CI on GitHub Actions (JDK 25 on Linux, Windows and macOS)

**Files:**
- Modify: `.github/workflows/unit-tests.yaml`
- Modify: `docs/benchmarks/run-perf.sh` (hardware per OS)

**Interfaces:**
- Consumes: green build (Task 2), `SmokeTest` (Task 3) and `PerfBaselineTest` (Task 3b).
- Produces: `Unit Test CI` workflow running on `ubuntu-latest`, `windows-latest`, `macos-latest` (ARM) and a macOS Intel runner, all with JDK 25; performance summary per OS.

- [ ] **Step 1: Make `run-perf.sh` portable (Linux, macOS and Windows/Git Bash)**

Replace the script's `== hardware ==` block with:

```bash
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
```

And change the first argument line to accept `JAVA_HOME` from the environment: `JDK_HOME="${1:-$JAVA_HOME}"`.

- [ ] **Step 2: Replace the workflow**

```yaml
name: Unit Test CI

on:
  pull_request:
    types: [ opened, synchronize, reopened ]
  workflow_dispatch:
  push:
    branches:
      - main
    paths:
      - .github/workflows/unit-tests.yaml
      - '**.java'
      - '**/pom.xml'

jobs:
  build:
    strategy:
      fail-fast: false
      matrix:
        os: [ ubuntu-latest, windows-latest, macos-latest, macos-15-intel ]
    runs-on: ${{ matrix.os }}
    defaults:
      run:
        shell: bash
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: 25
          distribution: temurin
          mvn-toolchain-vendor: Eclipse Adoptium
          cache: maven
      - name: Compile, run tests, and package (JDK 25)
        run: ./mvnw -B package
      - name: Cache small model
        uses: actions/cache@v4
        with:
          path: bitubi-inference-4j-tests/models
          key: small-model-qwen2.5-0.5b-jq4
      - name: Smoke test with a small model
        env:
          SMOKE_MODEL: "1"
          SMOKE_RELAXED: "1"
        run: ./mvnw -B -pl bitubi-inference-4j-tests -am test -Dtest=SmokeTest -Dsurefire.failIfNoSpecifiedTests=false
      - name: Performance snapshot (informational)
        run: |
          docs/benchmarks/run-perf.sh "$JAVA_HOME" | tee perf.txt
          { echo '### Desempenho em ${{ matrix.os }}'; echo '```'; cat perf.txt; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
```

Notes:
- `SMOKE_RELAXED=1` in CI: the text references were recorded on your machine and floating point may differ on another CPU or OS, so CI only requires non-empty text. The strict comparison runs locally (Tasks 3 and 4).
- The performance step is informational (it does not fail the job): it serves to collect the Windows and macOS baseline. After the first green run, copy each OS's `PERF` lines from the job summary to `docs/benchmarks/baseline-ci-<os>.md`.
- `macos-latest` is ARM (arm64); `macos-15-intel` is x86_64. **Confirm at https://docs.github.com/actions/using-github-hosted-runners that the `macos-15-intel` label exists**; if it does not, remove it and record that macOS Intel is not tested.
- If `bitubi-inference-4j-native` does not compile on Windows or on macOS ARM (the `Makefile` uses `gcc`/`make`/`ld`), the failure must show up here. Do not hide it with `continue-on-error`: open a dedicated task for native support on that OS.

- [ ] **Step 3: Validate the syntax**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/unit-tests.yaml')); print('ok')"`
Expected: `ok`.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/unit-tests.yaml docs/benchmarks/run-perf.sh
git commit -m "ci: JDK 25 em Linux, Windows e macOS, com teste de fumaça e desempenho
```

---

### Task 7: Publishing to GitHub Packages

**Files:**
- Modify: `pom.xml` (`release` profile: `distributionManagement`, publishing plugins)
- Modify: `.github/workflows/release.yaml`

**Interfaces:**
- Consumes: groupId `ao.bitubi` and the `scm` URLs from Task 5.
- Produces: `release` profile that publishes to `https://maven.pkg.github.com/bitubi-solutions/bitubi-inference-4j`; manual `Release` workflow.

Author's decision: the Maven Central release is postponed (it requires verifying the `bitubi.ao` domain, a GPG key and the Central Portal). For now we publish to GitHub Packages, which requires neither domain verification nor GPG. Limitation to record in the README: GitHub Packages requires authentication (a token with `read:packages`) even to read public packages, so it does not work as open public distribution.

- [ ] **Step 1: Change the publishing target**

In the `release` profile of `pom.xml`: remove the OSSRH `<distributionManagement>`, the `nexus-staging-maven-plugin` and the `maven-gpg-plugin` (keep `maven-source-plugin` and `maven-javadoc-plugin`). Add:

```xml
<distributionManagement>
  <repository>
    <id>github</id>
    <name>GitHub Packages</name>
    <url>https://maven.pkg.github.com/bitubi-solutions/bitubi-inference-4j</url>
  </repository>
</distributionManagement>
```

- [ ] **Step 2: Rewrite `release.yaml`**

```yaml
name: Release
on: workflow_dispatch

jobs:
  release:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: 25
          distribution: temurin
          mvn-toolchain-vendor: Eclipse Adoptium
          cache: maven
          server-id: github
          server-username: GITHUB_ACTOR
          server-password: GITHUB_TOKEN
      - name: Publish
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: ./mvnw -B -DskipTests -Prelease deploy
```

Note on the native libraries: the old job published each OS's classifier (macOS, Windows) to the same staging repository. Here there is no staging: the job above publishes only the Linux classifier (`linux-x86_64`). For the other OSes, later add per-OS jobs that run `./mvnw -B -Prelease -am -pl bitubi-inference-4j-native deploy`; before doing so, check whether GitHub Packages accepts republishing the same POM and main JAR from another job (it may respond 409). Record in the README that, in `0.1.0`, only Linux x86_64 is published.

- [ ] **Step 3: Local dry run (sending nothing)**

Run: `JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B -DskipTests -Prelease package 2>&1 | grep -E "BUILD|ERROR" | tail -5`
Expected: `BUILD SUCCESS` (generates sources and javadoc). Do not run `deploy` locally.

- [ ] **Step 4: Commit**

```bash
git add pom.xml .github/workflows/release.yaml
git commit -m "build: publica no GitHub Packages
```

---

### Task 8: Minimal governance and migration to `bitubi-solutions`

**Files:**
- Create: `CONTRIBUTING.md`, `.github/ISSUE_TEMPLATE/bug_report.md`, `.github/ISSUE_TEMPLATE/feature_request.md`, `.github/pull_request_template.md`

**Interfaces:**
- Consumes: all previous tasks.

- [ ] **Step 1: `CONTRIBUTING.md` with DCO**

```markdown
# Contribuindo

1. Faça um fork e crie uma branch.
2. Rode `./mvnw verify` com JDK 25.
3. Assine seus commits com `git commit -s` (Developer Certificate of Origin,
   https://developercertificate.org/). PRs sem `Signed-off-by` não serão aceitos.
4. Abra o PR descrevendo o que mudou e como testou.

Ao contribuir você declara ter o direito de licenciar sua contribuição sob a
Apache License 2.0.
```

- [ ] **Step 2: Templates**

`.github/ISSUE_TEMPLATE/bug_report.md` with fields: `bi4j` version, JDK (`java -version`), OS and CPU, model, command executed, output. `feature_request.md` with: problem, proposal, alternatives. `.github/pull_request_template.md` with: summary, how to test, checklist (green `verify`, signed commits).

- [ ] **Step 3: Verify everything before the migration**

Run: `JAVA_HOME=/home/dumi/.sdkman/candidates/java/25.0.3-amzn ./mvnw -B clean verify 2>&1 | grep -E "BUILD"`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -s -m "docs: adiciona CONTRIBUTING com DCO e templates
```

- [ ] **Step 5: Migrate the repository (owner action; confirm first)**

This step is externally visible; only run it with the author's explicit authorization. Two options:
(a) Transfer `DumiJDev/Jlama` to the organization in the GitHub UI (Settings, Transfer) and rename it to `bitubi-inference-4j`;
(b) create an empty `bitubi-solutions/bitubi-inference-4j` and push the history.

For (b):

```bash
git remote rename origin old-origin
git remote add origin git@github.com:bitubi-solutions/bitubi-inference-4j.git
git remote add upstream https://github.com/tjake/jlama.git
git push -u origin foundation/aby-rename
```

Then open the PR to `main` in the organization and check that CI goes green on all three OSes.

- [ ] **Step 6: First publication to GitHub Packages (after Step 5; confirm with the author)**

Change `<revision>` to `0.1.0`, commit, create the `v0.1.0` tag and run the `Release` workflow. Check at `https://github.com/orgs/bitubi-solutions/packages` that the `bitubi-inference-4j-*` artifacts appear. Set `<revision>` back to `0.1.1-SNAPSHOT`. The Maven Central release is out of scope for this plan.

---

## Self-Review

- **Spec coverage:** spec step 1 (line endings) = Task 1; step 2 (migrate) = Task 8 Step 5; step 3 (rename) = Task 4; step 4 (attribution) = Task 5; step 5 (build and CI) = Tasks 2 and 6; step 6 (publishing) = Tasks 7 and 8 Step 6; step 7 (governance) = Task 8. Definition of done: green `verify` on JDK 25 (Tasks 2, 4, 8), `grep tjake` (Task 5 Step 6), artifact on GitHub Packages (Task 8 Step 6), output comparison (Tasks 3 and 4), performance comparison against the baseline (Tasks 3b and 4), green CI on Linux, Windows and macOS (Task 6).
- **Divergence from the spec:** the criterion "`grep -ri tjake` returns nothing" was relaxed to allow Hugging Face model ids and the `sdkman-for-toolchains` clone (external names). The spec should be updated with this caveat.
- **Points I could not verify without running:** whether `jlama-native` compiles on Windows and on macOS (ARM and Intel; the `Makefile` uses `gcc`, `make` and `ld`); whether the `macos-15-intel` runner label exists; whether the smoke-test model exists on Hugging Face. Each has a verification step and a fallback in the tasks above.
