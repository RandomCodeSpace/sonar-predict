# sonar-predictor

Offline, no-server, pre-push code-quality gate. Runs the genuine SonarSource analyzers
(the same engines behind SonarQube and SonarLint) entirely on your machine — no account,
no network at analysis time — to catch bugs, code smells, vulnerabilities and security
hotspots before they reach git and the CI round-trip. Scan-only: reads source, never
modifies a file.

## Languages

Java, Kotlin, Python, JavaScript, TypeScript, PHP, Ruby, Scala, Go, HTML, XML.

JavaScript / TypeScript analysis additionally requires Node.js 18.17+ on `PATH`; without
it those languages are skipped.

## Install

A single zip — `sonar-predictor-dist-0.2.0.zip` — contains everything: launchers
(`bin/sonar`, `bin/sonar.bat`), shaded CLI + daemon jars (`lib/`), and the 10 SonarSource
analyzer plugins (`plugins/`). Pick one route.

### Option A: GitHub Release

```bash
curl -LO https://github.com/RandomCodeSpace/sonar-predict/releases/download/v0.2.0/sonar-predictor-dist-0.2.0.zip
unzip sonar-predictor-dist-0.2.0.zip
./sonar-predictor/bin/sonar --help
```

Or grab the asset by hand from
<https://github.com/RandomCodeSpace/sonar-predict/releases>.

### Option B: Maven Central

```bash
mvn dependency:get \
  -Dartifact=io.github.randomcodespace.sonarpredict:sonar-predictor:0.2.0:zip:dist \
  -Dtransitive=false
unzip ~/.m2/repository/io/github/randomcodespace/sonarpredict/sonar-predictor/0.2.0/sonar-predictor-0.2.0-dist.zip
./sonar-predictor/bin/sonar --help
```

### Option C: Build from source

```bash
git clone https://github.com/RandomCodeSpace/sonar-predict.git
cd sonar-predict
mvn -B clean package
# output: target/sonar-predictor-dist-0.2.0-SNAPSHOT.zip
```

## Usage

```bash
# Analyze the current git changeset (the agent / pre-push workflow)
./bin/sonar check --diff --format json

# Analyze explicit files
./bin/sonar check path/to/file.java path/to/other.py --format json

# Analyze a whole directory
./bin/sonar analyze /path/to/project --format json
```

Output formats: `--format sarif|json|text` (SARIF is the default). Add `--config
<profile.xml>` to use an imported SonarQube quality profile, or `--coverage <report>`
(plus `--coverage-min N`) to fold in a JaCoCo / Cobertura / LCOV / Go / Clover /
SimpleCov coverage report.

## Exit codes

| Code | Meaning |
|------|---------|
| `0` | clean — no issues |
| `1` | issues found |
| `2` | tool error |

Drop-in for a git `pre-push` hook; `sonar install-hook` wires one up for you.

## How it works

A short-lived `sonar` CLI talks to a long-lived analysis daemon over a local Unix
domain socket. The daemon keeps the SonarLint analysis engine and analyzer plugins
warm, so the first call costs a few seconds and every call after is sub-second. The
daemon exits on its own after 50 minutes idle.

## Triage guidance

When `check` reports findings, fix in this order:

1. **Type** — `BUG` and `VULNERABILITY` first, then `SECURITY_HOTSPOT`, then
   `CODE_SMELL`.
2. **Severity** — within a type, fix `CRITICAL` and `MAJOR` before `MINOR` / `INFO`.

The JSON output carries both fields on every issue.

## Prerequisites

| Requirement | For |
|---|---|
| **Java 21+** (JDK or JRE) | running the CLI and daemon — auto-discovered (`JAVA_HOME` → `PATH` → common install locations) |
| **Linux or macOS** | the daemon uses Unix domain sockets (Windows support is on the roadmap) |
| **`git`** | the `check --diff` workflow |
| **Node.js 18.17+** | JavaScript / TypeScript analysis |
| **~1 GB RAM, ~165 MB disk** | the daemon embeds the analysis engine |

Nothing is downloaded at analysis time — the tool is fully offline once the zip is
unpacked.

## Scope

`sonar-predictor` predicts the **rule-based** findings of a SonarQube server — bugs,
code smells, vulnerabilities and security hotspots — and imports coverage. It is a fast
local first-pass, **not** a replacement for release-time gates: the server's deep
cross-file taint analysis, Fortify, and dependency / CVE scanning remain their own
tools.

## License & third-party components

This project's source (CLI, daemon, build) is licensed under the
[Apache License 2.0](LICENSE).

The SonarSource analyzers and engine that `sonar-predictor` invokes at runtime are
third-party components with their own licenses, listed in [NOTICE](NOTICE). Headline
points:

- **`sonarlint-analysis-engine`** (the embedded analysis runtime, by SonarSource) —
  **LGPL v3**.
- **SonarSource language analyzers** (`sonar-java`, `sonar-python`,
  `sonar-javascript`, `sonar-php`, `sonar-kotlin`, `sonar-go`, `sonar-ruby`,
  `sonar-scala`, `sonar-html`, `sonar-xml`) — **SONAR Source-Available License v1.0
  (SSALv1)** since SonarSource's 2024 license change. Source-available, free for
  internal use, with restrictions on operating a competing "Service Offering".

These JARs are redistributed unchanged inside the `plugins/` directory of the
distribution zip, fetched from Maven Central at build time. None of this project's
source code is derived from SonarSource code. For the legal text of each license, see
[NOTICE](NOTICE).
