package io.github.randomcodespace.sonarpredict.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies the CLI version at runtime for picocli's {@code --version} option and
 * for every other surface that reports the tool version (the {@code version}
 * subcommand and the SARIF {@code tool.driver.version}).
 *
 * <p>Resolved from the build, mirroring the daemon's own version resolution: the
 * {@code Implementation-Version} of this class's package, populated from the
 * Maven artifact version when the CLI runs from its packaged (shaded) JAR — the
 * shade manifest sets {@code Implementation-Version} to {@code ${project.version}},
 * so the CLI and daemon report the same number. In a test/exploded-classes run no
 * manifest is present, so a {@code dev} fallback is used and the value is never
 * {@code null}.
 *
 * <p>This lives in the {@code cli} package — not borrowed from the daemon — to
 * keep the cli↛daemon boundary intact; it merely replicates the same minimal
 * manifest-read mechanism.
 */
public final class SonarVersionProvider implements IVersionProvider {

    private static final String FALLBACK = "0.3.0-dev";

    /** The current CLI version, never {@code null}. */
    public static String version() {
        Package pkg = SonarVersionProvider.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null && !version.isBlank() ? version : FALLBACK;
    }

    /** picocli's {@code --version} contract: the lines to print. */
    @Override
    public String[] getVersion() {
        return new String[] {"sonar " + version()};
    }
}
