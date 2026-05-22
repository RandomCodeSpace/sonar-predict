# sonar-predictor :: dist

The distribution module. It builds **sonar-predictor** as a self-contained
Claude Code agent skill — a directory an AI agent can use with zero setup.

## Build

From the repository root:

```sh
mvn package
```

This produces, under `dist/target/`:

- `skill/sonar-predictor/` — the exploded skill bundle:
  - `SKILL.md` — the agent-facing skill definition (the only file loaded into context)
  - `bin/sonar`, `bin/sonar.bat` — launchers that auto-discover a Java 17+ runtime
  - `lib/` — the CLI and daemon fat jars
  - `plugins/` — the 10 SonarSource analyzer plugins
- `sonar-predict-skill-<version>.zip` — the same tree zipped, for non-skill use
  (manual install, or transfer to an air-gapped host)

The analyzer plugins are fetched through Maven, so any mirror, Nexus, or
corporate proxy configured in your `settings.xml` is honored — there are no
hardcoded download URLs.

## Install as a Claude Code skill

```sh
cp -r dist/target/skill/sonar-predictor ~/.claude/skills/
```

The skill is then available to agents in any project.

## Prerequisites

The skill runs on a system Java; nothing is downloaded at runtime.

**Required:**
- **Java 17+** (JDK or JRE) — the CLI and daemon are JVM processes. `bin/sonar` auto-discovers it (`JAVA_HOME` → `PATH` → common install locations); one must exist somewhere on the machine.
- **Linux or macOS** — the daemon uses Unix domain sockets. Windows is not yet supported (the TCP-socket fallback is a TODO).
- A writable temp directory and ~1 GB free RAM (the daemon embeds the analysis engine; idle ~150–350 MB, up to ~1 GB during JS/TS analysis).
- ~165 MB disk for the bundle.

**For specific features:**
- **`git`** on `PATH` — required for `sonar check --diff` (the primary agent workflow). `sonar check <files>` and `sonar analyze <dir>` work without it.
- **Node.js 18.17+** on `PATH` — required for JavaScript/TypeScript/CSS analysis. Without it those 3 languages are skipped (the other 9 still analyze).

**Not required:** no network at runtime (fully offline after install), no per-language SDK or compiler, no Maven (build-time only).

If no Java 17+ is found, the `bin/sonar` launcher exits with a clear message.

## Releasing

A release is cut by pushing a version tag. The
`.github/workflows/publish.yml` GitHub Actions pipeline then:

1. derives the release version from the tag (`v0.1.0` → `0.1.0`) and strips
   `-SNAPSHOT` — Maven Central rejects snapshot versions,
2. builds, tests, GPG-signs and deploys the library modules
   `protocol`, `daemon` and `cli` to Maven Central via the Sonatype
   Central Portal (this `dist` module sets `maven.deploy.skip=true`, so it
   is built but never staged to Central),
3. creates a GitHub Release carrying two bundles:
   - **`sonar-predict-<version>-src.zip`** — the whole repository as a
     `git archive` of `HEAD`,
   - **`sonar-predict-skill-<version>.zip`** — the assembled skill bundle.

To cut release `X.Y.Z`:

```sh
git tag vX.Y.Z
git push origin vX.Y.Z
```

The workflow can also be run manually from the **Actions** tab
(`workflow_dispatch`), supplying the version explicitly.

### Required repository secrets

The pipeline needs these secrets on the `RandomCodeSpace/sonar-predict`
repository (Settings → Secrets and variables → Actions):

| Secret | Purpose |
|--------|---------|
| `OSS_NEXUS_USER` | Sonatype Central Portal token username |
| `OSS_NEXUS_PASS` | Sonatype Central Portal token password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored GPG private key used to sign artifacts |
| `MAVEN_GPG_PASSPHRASE` | Passphrase for that GPG key (omit if the key has none) |

`GITHUB_TOKEN` is provided automatically and needs no setup. The
`io.github.randomcodespace` namespace must also be registered and verified
on the Sonatype Central Portal.
