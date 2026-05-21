# engine-spike

Throwaway SPIKE proving the SonarSource standalone analysis engine can be
embedded in plain Java 17, load `sonar-java-plugin` from a file path, analyze
a Java source file, and emit a real issue **fully offline**.

This is a STANDALONE Maven project. It is deliberately NOT a module of the
parent `sonar-predictor` reactor.

## Run

```sh
mvn clean package
java -jar target/engine-spike.jar \
    plugins/sonar-java-plugin-8.15.0.39343.jar \
    fixture \
    fixture/UtilityClass.java
```

Expected tail of output:

```
[spike] issues raised     : 1
  - ruleKey=java:S1118 message="Add a private constructor to hide the implicit public one." line=11
[spike] SUCCESS: engine embedded offline, 1 issue(s) raised, including java:S1118
```

## Layout

- `pom.xml`            — standalone build; shade plugin builds a runnable fat jar.
- `src/main/java`      — `EngineSpike` (driver) + `FileClientInputFile`.
- `plugins/`           — vendored `sonar-java-plugin` jar, loaded at runtime by path.
- `fixture/`           — `UtilityClass.java`, violates rule `java:S1118`.

## Key facts

- Engine: `org.sonarsource.sonarlint.core:sonarlint-analysis-engine:10.24.0.81415`.
- The plugin jar is loaded from a FILE PATH, not as a Maven dependency.
- Analysis makes ZERO outbound network calls (verified with `strace`).
