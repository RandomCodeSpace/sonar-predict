---
name: sonar-predictor
description: Use after writing or modifying source code, before committing or pushing — runs genuine SonarSource analyzers offline as a fast local quality gate to catch bugs, code smells, vulnerabilities and security hotspots. Also use when the user asks to check code quality, run sonar, or analyze a file or diff.
---

# sonar-predictor

An offline SonarSource pre-push quality gate — runs the genuine analyzers locally, no network, no server.

**Scan-only.** This skill only *scans and reports* — it reads source and emits findings, and never modifies any file. Applying fixes is the calling agent's job, not this tool's; running it is a safe, read-only operation.

Run `./bin/sonar` from this skill's base directory (the folder with this `SKILL.md`), or by its absolute path — it is not on `PATH`. The first invocation downloads the analyzer bundle (~150 MB) from Maven Central into a user cache; every subsequent call runs from that cache with no network. **Read the tool's own help before invoking it:** `./bin/sonar --help` lists the commands and the global options, and `./bin/sonar <command> --help` gives a command's own options and exact argument order. The skill scans a git changeset or explicit files and directories and reports in a chosen format — the help states the precise flags and where each one goes. That generated help is the single source of truth; this `SKILL.md` deliberately does not restate command syntax, which would drift. Do not guess flag names or their placement — read the help.

**Agent invocation pattern — `./bin/sonar agent-scan [scope]`.** A wrapper subcommand that bakes the out-of-context discipline into the tool: it runs the scan with `--format json` redirected to `.sonar-predictor/scan.json` at the project root, adds `.sonar-predictor/` to `.gitignore` on first use (when inside a git repo), and prints a compact summary to stdout — issue count, severity breakdown, file path. The calling agent reports that summary and points its caller at the file; deeper drill-down happens with `jq` on the file, on demand. With no scope argument, `agent-scan` defaults to `check --diff` (the git changeset); pass an explicit scope (`agent-scan analyze src/`, `agent-scan check src/Main.java`, etc.) to scan something else. All flags forward to the underlying CLI.

Exit codes: `0` clean, `1` issues found, `2` tool error.

Acting on findings: fix `BUG`/`VULNERABILITY`/`SECURITY_HOTSPOT` and `CRITICAL`/`MAJOR` first. This is a fast first-pass gate, not the release gate — fix the real issues and move on.

**Configuration / corporate environments.** The launcher reads `config.env` next to this `SKILL.md` — Maven proxy URL, JRE download URL, bundle and Java version pins. Edit it (or override with same-named env vars) to point at a corporate Nexus / Artifactory mirror and a private JRE source. The defaults use Maven Central and Adoptium Temurin's public API. `SONAR_PREDICTOR_HOME=/path/to/extracted/sonar-predictor` skips the bundle download entirely for fully air-gapped / pre-staged installs.

**Plugin-bundled agent variants.** Two named scanner subagents ship with this plugin: invoke `sonar-scanner-claude` on Claude Code (model: haiku) or `sonar-scanner-copilot` on GitHub Copilot CLI (model: gpt-5-mini). Selection is by agent name — pick the one matching your platform.
