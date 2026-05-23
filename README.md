<h1 align="center">sonar-predictor</h1>

<p align="center">
  <strong>Catch SonarQube-class issues on your machine — before you push.</strong><br>
  An offline static-analysis quality gate that runs the genuine SonarSource analyzers locally,
  built for AI agents and humans alike.
</p>

<p align="center">
  <a href="https://github.com/RandomCodeSpace/sonar-predict/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/RandomCodeSpace/sonar-predict/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=CI" alt="CI"></a>
  <a href="https://central.sonatype.com/namespace/io.github.randomcodespace.sonarpredict"><img src="https://img.shields.io/maven-central/v/io.github.randomcodespace.sonarpredict/sonar-predictor-cli?style=for-the-badge&logo=apachemaven&logoColor=white&label=maven%20central" alt="Maven Central"></a>
  <a href="https://github.com/RandomCodeSpace/sonar-predict/releases/latest"><img src="https://img.shields.io/github/v/release/RandomCodeSpace/sonar-predict?style=for-the-badge&logo=github&label=release" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=for-the-badge" alt="License"></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 17+">
  <img src="https://img.shields.io/badge/analyzes-12%20languages-4E9BCD?style=for-the-badge" alt="12 languages">
  <img src="https://img.shields.io/badge/100%25-offline-2EA44F?style=for-the-badge" alt="100% offline">
  <img src="https://img.shields.io/badge/agent-native%20skill-8A4FFF?style=for-the-badge" alt="Agent-native">
</p>

---

## What it is

`sonar-predictor` runs the **genuine SonarSource analyzers** — the same engines behind
SonarQube and SonarLint — entirely on your machine, with no server and no network. It's a
fast **pre-push quality gate**: an AI agent (or you) runs it right after writing code, to
catch bugs, code smells, vulnerabilities and security hotspots *before* they reach git and
the slow CI round-trip.

It is **scan-only** — it reads your source and reports findings, and never modifies a file.

## Features

- 🔍 **Genuine SonarSource rules** — embeds `sonarlint-analysis-engine`; the issues match what a SonarQube server would raise.
- 🌐 **100% offline** — no server, no account, no network calls at analysis time.
- ⚡ **Warm daemon** — a long-lived JVM keeps the engine hot; repeat scans are sub-second.
- 🧩 **12 languages** — Java · Kotlin · Python · JavaScript · TypeScript · CSS · PHP · Ruby · Scala · Go · HTML · XML.
- 🤖 **Agent-native** — ships as a self-contained Claude Code skill; machine-readable SARIF and token-lean JSON output.
- 📊 **Coverage import** — JaCoCo, Cobertura, LCOV, Go, Clover, SimpleCov — with a `--coverage-min` gate.
- 🎯 **Quality-profile import** — point `--config profile.xml` at a SonarQube quality profile to match your server's exact ruleset.
- 🚦 **Honest exit codes** — `0` clean · `1` issues found · `2` tool error — drop-in for a git `pre-push` hook.

## Quick start

`sonar-predictor` installs as a Claude Code / GitHub Copilot CLI plugin from this repo's built-in marketplace. The plugin is tiny (kilobytes); the analyzer bundle is fetched lazily from Maven Central on first scan and cached locally for every subsequent run.

```
/plugin marketplace add RandomCodeSpace/sonar-predict
/plugin install sonar-predictor@sonar-predict
```

The same two commands work on Claude Code and Copilot CLI — they share the `.claude-plugin` format. Two named scanner subagents come with the plugin: `sonar-scanner-claude` (model: haiku) and `sonar-scanner-copilot` (model: gpt-5-mini); selection is by agent name.

Prefer the raw CLI? Grab the skill bundle directly from the [latest release](https://github.com/RandomCodeSpace/sonar-predict/releases/latest) and run `./bin/sonar` from the unpacked directory. The first invocation auto-discovers Java 17+ (or downloads one if none is found); every subsequent call is offline.

## Usage

```sh
sonar check <file>...            # check specific files
sonar check --diff [ref]         # check only the current git changeset (the agent path)
sonar analyze <dir>              # scan a whole project
sonar rules show java:S1118      # full metadata for a rule
sonar install-hook               # wire it as a git pre-push hook
sonar --help                     # the complete command reference
```

Pick the output with `--format sarif|json|text` (SARIF is the default). Add
`--config <profile.xml>` to analyze with an imported SonarQube quality profile, or
`--coverage <report>` to fold in a coverage report.

```sh
sonar --format json check --diff
sonar --coverage target/site/jacoco/jacoco.xml --coverage-min 80 analyze .
```

## Corporate / air-gapped setup

The plugin's launcher reads a single configuration file at `plugin/skills/sonar-predictor/config.env`. Edit it (or set the same-named environment variable, which takes precedence) to point at a corporate Maven proxy, a private JRE mirror, or a vendored bundle version — no code changes.

| Key | What it controls |
|-----|------------------|
| `SONAR_MAVEN_REPO_URL` | Where the analyzer bundle is downloaded from. Default: Maven Central. |
| `SONAR_BUNDLE_VERSION` | Bundle version pin. Bump in lockstep with each plugin release. |
| `SONAR_MIN_JAVA_VERSION` | Minimum Java major version required. Default: `17`. |
| `SONAR_JRE_URL_TEMPLATE` | JRE source for auto-download (when none is on the system). Tokens `{os}` `{arch}` `{version}` are substituted at runtime. Default: Adoptium Temurin API. |
| `SONAR_JRE_VERSION` | JRE version to fetch. Default: `17`. |
| `SONAR_DISABLE_JRE_AUTODOWNLOAD` | Set to `1` to refuse the JRE auto-download (corp-policy escape hatch). |

For a fully pre-staged install — no network at all on the developer machine — extract a skill bundle by hand into a known location and point the launcher at it:

```sh
export SONAR_PREDICTOR_HOME=/opt/sonar-predictor
```

The forking workflow: clone this repo, edit `plugin/skills/sonar-predictor/config.env`, push to your fork, `/plugin marketplace add <your-org>/sonar-predict`. One file.

## How it works

```
  sonar  (thin CLI)  ──local Unix socket──▶  sonar-daemon  (long-lived JVM)
   args · diff detection                      embeds sonarlint-analysis-engine
   output formatting                          loads the 12 analyzer plugins
```

A short-lived `sonar` CLI talks to a long-lived analysis **daemon** over a local Unix domain
socket. The daemon keeps the SonarSource engine and analyzer plugins **warm**, so the first
call costs a few seconds and every call after is sub-second. The daemon exits on its own
after 50 minutes idle.

## Prerequisites

| Requirement | For |
|---|---|
| **Java 17+** (JDK or JRE) | running the CLI and daemon — auto-discovered |
| **Linux or macOS** | the daemon uses Unix domain sockets (Windows support is in progress) |
| **`git`** | the `check --diff` workflow |
| **Node.js 18.17+** | JavaScript / TypeScript / CSS analysis |

Nothing is downloaded at analysis time — the tool is fully offline once the bundle is unzipped.

## Build from source

```sh
git clone https://github.com/RandomCodeSpace/sonar-predict.git
cd sonar-predict
mvn package
# self-contained skill bundle → dist/target/skill/sonar-predictor/
```

A Maven multi-module build (Java 17): `protocol` (wire DTOs), `daemon` (analysis engine host),
`cli` (the `sonar` command), `dist` (the skill bundle). The library modules are published to
Maven Central under `io.github.randomcodespace.sonarpredict`.

## Scope

`sonar-predictor` predicts the **rule-based** findings of a SonarQube server — bugs, code
smells, vulnerabilities and security hotspots — and imports coverage. It is a fast local
first-pass, **not** a replacement for release-time gates: the SonarQube server's deep
cross-file taint analysis, Fortify, and dependency/CVE scanning remain their own tools.

## License & third-party components

This project (the CLI, daemon, protocol, dist module, plugin scaffolding) is licensed under the [Apache License 2.0](LICENSE).

The SonarSource analyzers and engine that `sonar-predictor` invokes at runtime are **third-party components with their own licenses**, listed in [NOTICE](NOTICE). Headline points:

- **`sonarlint-analysis-engine`** (the embedded analysis runtime, by SonarSource) — **LGPL v3**.
- **SonarSource language analyzers** (`sonar-java`, `sonar-python`, `sonar-javascript`, `sonar-php`, `sonar-kotlin`, `sonar-go`, `sonar-ruby`, `sonar-scala`, `sonar-html`, `sonar-xml`) — **SONAR Source-Available License v1.0 (SSALv1)** since SonarSource's 2024 license change. Source-available, free for internal use, with restrictions on operating a competing "Service Offering".

The plugin **does not redistribute** these JARs — its launcher fetches them from Maven Central on first invocation (the same channel SonarSource themselves publish to). Editing `SONAR_MAVEN_REPO_URL` to a corporate mirror that proxies Maven Central is equivalent. None of this project's source code is derived from SonarSource code.

For a deeper inventory and the legal text of each license, see [NOTICE](NOTICE). If your environment's policy needs the analyzer JARs to come from somewhere other than Maven Central, the corporate / air-gapped setup above is your knob.
