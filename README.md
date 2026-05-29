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

### Enterprise / air-gapped install

For corporate or air-gapped machines that cannot reach GitHub or Maven Central,
the bootstrap wrappers `scripts/sonar-cli.sh` (Linux/macOS) and
`scripts/sonar-cli.ps1` (Windows) fetch the distribution zip — and, when no
suitable Java is present, an Eclipse Temurin JDK — from an internal Nexus raw
repository instead. Point them at it with `SONAR_NEXUS_BASE` (the raw-repo base
URL, no trailing slash):

```bash
export SONAR_NEXUS_BASE=https://nexus.example.com/repository/raw-hosted
./scripts/sonar-cli.sh check --diff
```

The wrappers verify artifact integrity before extracting anything:

- `SONAR_DIST_SHA256` — expected lowercase-hex SHA-256 of the distribution zip.
- `SONAR_JDK_SHA256` — expected SHA-256 of the JDK archive.
- If those are unset, the wrapper fetches a `SHA256SUMS` sibling published next to
  the artifact (one is published per release at
  `{base}/sonar-predictor/{version}/SHA256SUMS`) and checks against that.
- `SONAR_ALLOW_UNVERIFIED=1` — proceed when *no* expected hash can be found. This
  bypasses integrity checking and prints a loud warning; not recommended.

With none of the above resolvable the wrapper refuses to extract unverified bytes.
You can also verify by hand before running — e.g. `sha256sum -c SHA256SUMS` — since
the wrappers use the same `sha256sum` (Linux) / `shasum -a 256` (macOS) tooling.

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

A few more options on `check` / `analyze`:

- `--save <path>` — write the formatted report (per `--format`) to a file instead
  of stdout. Stdout then carries a compact summary (issue count plus severity/type
  rollup and the target file), so an agent or CI step gets a usable signal without
  parsing the report or needing `jq`.
- `--test-path <glob>` — treat files matching the glob as test code (repeatable,
  additive). Augments the built-in test-path detection (`src/test/**`, `*Test.java`,
  `*_test.go`, `*.spec.ts`, …) for non-standard layouts, e.g.
  `--test-path 'src/integration/**'`.

### Provisioning the analyzer runtime (`setup`)

The distribution zip already bundles the analyzers, so `setup` is only needed when
you install from a thinner package or want to refresh `~/.sonar`:

```bash
# Provision into ~/.sonar from Maven Central (default)
./bin/sonar setup

# Pull the analyzer/engine JARs from a private Maven mirror / Nexus
./bin/sonar setup --repo https://nexus.example.com/repository/maven-public

# Fully offline: provision from a local .tar.gz runtime bundle (no network)
./bin/sonar setup --offline /path/to/sonar-runtime.tar.gz
```

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

A Windows bootstrap wrapper (`scripts/sonar-cli.ps1`, alongside the
`scripts/sonar-cli.sh` POSIX one) ships for installing on Windows, but native
Windows analysis is not yet supported: the CLI ↔ daemon transport relies on a
Unix-domain (AF_UNIX) socket, so the daemon currently needs Linux, macOS, or WSL.

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
