---
name: sonar-predictor
description: Use after writing or modifying source code, before committing or pushing — runs genuine SonarSource analyzers offline as a fast local quality gate to catch bugs, code smells, vulnerabilities and security hotspots. Also use when the user asks to check code quality, run sonar, or analyze a file or diff.
---

# sonar-predictor

An offline SonarSource pre-push quality gate — runs the genuine analyzers locally, no network, no server.

**Scan-only.** This skill only *scans and reports* — it reads source and emits findings, and never modifies any file. Applying fixes is the calling agent's job, not this tool's; running it is a safe, read-only operation.

Run `./bin/sonar` from this skill's base directory (the folder with this `SKILL.md`), or by its absolute path — it is not on `PATH`. **Read the tool's own help before invoking it:** `./bin/sonar --help` lists the commands and the global options, and `./bin/sonar <command> --help` gives a command's own options and exact argument order. The skill scans a git changeset or explicit files and directories and reports in a chosen format — the help states the precise flags and where each one goes. That generated help is the single source of truth; this `SKILL.md` deliberately does not restate command syntax, which would drift. Do not guess flag names or their placement — read the help.

Exit codes: `0` clean, `1` issues found, `2` tool error.

Acting on findings: fix `BUG`/`VULNERABILITY`/`SECURITY_HOTSPOT` and `CRITICAL`/`MAJOR` first. This is a fast first-pass gate, not the release gate — fix the real issues and move on.
