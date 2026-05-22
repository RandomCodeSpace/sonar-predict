---
name: sonar-predictor
description: Use after writing or modifying source code, before committing or pushing — runs genuine SonarSource analyzers offline as a fast local quality gate to catch bugs, code smells, vulnerabilities and security hotspots. Also use when the user asks to check code quality, run sonar, or analyze a file or diff.
---

# sonar-predictor

An offline SonarSource pre-push quality gate — runs the genuine analyzers locally, no network, no server.

**Scan-only.** This skill only *scans and reports* — it reads source and emits findings, and never modifies any file. Applying fixes is the calling agent's job, not this tool's; running it is a safe, read-only operation.

Run `./bin/sonar` from this skill's base directory (the folder containing this `SKILL.md`), or call it by its absolute path — it is not on `PATH`. `./bin/sonar --help` gives the full command reference, and `./bin/sonar <command> --help` any command's options. That generated help is the source of truth — command docs are not duplicated here.

Primary invocation for a git repo (checks the current changeset): `./bin/sonar check --diff --format json`. For loose files or a non-git directory, pass paths explicitly: `./bin/sonar check <file>... --format json`, or `./bin/sonar analyze <dir> --format json`.

Exit codes: `0` clean, `1` issues found, `2` tool error.

Acting on findings: fix `BUG`/`VULNERABILITY`/`SECURITY_HOTSPOT` and `CRITICAL`/`MAJOR` first. This is a fast first-pass gate, not the release gate — fix the real issues and move on.
