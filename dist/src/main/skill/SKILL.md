---
name: sonar-predictor
description: Use after writing or modifying source code, before committing or pushing — runs genuine SonarSource analyzers offline as a fast local quality gate to catch bugs, code smells, vulnerabilities and security hotspots. Also use when the user asks to check code quality, run sonar, or analyze a file or diff.
---

# sonar-predictor

An offline SonarSource pre-push quality gate — runs the genuine analyzers locally, no network, no server.

Run `./bin/sonar --help` from this directory for the full command reference, and `./bin/sonar <command> --help` for any command's options. That generated help is the source of truth — command docs are not duplicated here.

Primary invocation (checks the current git changeset): `./bin/sonar check --diff --format json`.

Exit codes: `0` clean, `1` issues found, `2` tool error.

Acting on findings: fix `BUG`/`VULNERABILITY`/`SECURITY_HOTSPOT` and `CRITICAL`/`MAJOR` first. This is a fast first-pass gate, not the release gate — fix the real issues and move on.
