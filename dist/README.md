# sonar-predictor :: dist

The distribution module. It assembles the **skill bundle** that `sonar-predictor`'s plugin launcher downloads at first run.

## What this module builds

```sh
mvn package
```

produces, under `dist/target/`:

- `skill/sonar-predictor/` — the exploded skill bundle:
  - `SKILL.md` — the agent-facing skill definition
  - `bin/sonar`, `bin/sonar.bat` — launchers that auto-discover a Java 17+ runtime
  - `lib/` — the CLI and daemon shaded fat jars
  - `plugins/` — the 10 SonarSource analyzer plugins
- `sonar-predict-skill-<version>.zip` — the same tree zipped, attached to the GitHub Release and **published to Maven Central as `io.github.randomcodespace.sonarpredict:sonar-predictor-dist:<version>:zip`**. The plugin's bootstrap launcher pulls this artifact (from Maven Central, or a corporate proxy of it) on first invocation.

The analyzer plugins are resolved through Maven, so any mirror, Nexus, or corporate proxy configured in your `settings.xml` is honored — there are no hardcoded download URLs in the build.

## How users install `sonar-predictor`

End users do not interact with this module directly. They install the plugin from the in-repo `/plugin/` directory through their AI tool's marketplace:

```
/plugin marketplace add RandomCodeSpace/sonar-predict
/plugin install sonar-predictor@sonar-predict
```

The plugin's launcher (`/plugin/skills/sonar-predictor/bin/sonar`) downloads the bundle this module builds from Maven Central, verifies its SHA-1, caches it under `~/.cache/sonar-predictor/<version>/`, and execs the cached `bin/sonar` on every subsequent call.

For raw / non-plugin use (manual or air-gapped install), the same skill bundle zip is attached to each GitHub Release.

## Prerequisites at runtime

**Required:**
- **Java 17+** (JDK or JRE) — the CLI and daemon are JVM processes. `bin/sonar` auto-discovers it (`JAVA_HOME` → `PATH` → common install locations); the plugin's bootstrap also auto-downloads one from `SONAR_JRE_URL_TEMPLATE` (defaulting to Adoptium Temurin) if none is found.
- **Linux or macOS** — the daemon uses Unix domain sockets. Windows is not yet supported (the TCP-socket fallback is a TODO).
- A writable temp directory and ~1 GB free RAM (the daemon embeds the analysis engine; idle ~150–350 MB, up to ~1 GB during JS/TS analysis).
- ~165 MB disk for the bundle.

**For specific features:**
- **`git`** on `PATH` — required for `sonar check --diff` (the primary agent workflow).
- **Node.js 18.17+** on `PATH` — required for JavaScript/TypeScript/CSS analysis. Without it those 3 languages are skipped.

**Not required after first invocation:** no network, no per-language SDK or compiler, no Maven.

## Releasing

A release is cut by pushing a version tag. The `.github/workflows/publish.yml` GitHub Actions pipeline then:

1. derives the release version from the tag (`v0.1.0` → `0.1.0`) and strips `-SNAPSHOT`,
2. builds, tests, GPG-signs and deploys the library modules `protocol`, `daemon` and `cli` to Maven Central via the Sonatype Central Portal,
3. publishes this `dist` module's skill bundle zip to Maven Central as `sonar-predictor-dist:<version>:zip` (this is the artifact the plugin's bootstrap downloads),
4. creates a GitHub Release carrying two bundles:
   - `sonar-predict-<version>-src.zip` — the whole repository as a `git archive` of `HEAD`,
   - `sonar-predict-skill-<version>.zip` — the assembled skill bundle.

To cut release `X.Y.Z`:

```sh
git tag vX.Y.Z
git push origin vX.Y.Z
```

When the release is cut, bump the plugin launcher's pinned bundle version in `plugin/skills/sonar-predictor/config.env` (`SONAR_BUNDLE_VERSION`) in the same commit, so a freshly installed plugin downloads the bundle that matches it.

The workflow can also be run manually from the **Actions** tab (`workflow_dispatch`), supplying the version explicitly.

### Required repository secrets

| Secret | Purpose |
|--------|---------|
| `OSS_NEXUS_USER` | Sonatype Central Portal token username |
| `OSS_NEXUS_PASS` | Sonatype Central Portal token password |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored GPG private key used to sign artifacts |
| `MAVEN_GPG_PASSPHRASE` | Passphrase for that GPG key (omit if the key has none) |

`GITHUB_TOKEN` is provided automatically. The `io.github.randomcodespace` namespace must be registered and verified on the Sonatype Central Portal.
