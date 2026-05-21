package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link QualityProfile}: parses a SonarQube quality-profile XML
 * export into rule keys ({@code repositoryKey:key}) and per-rule parameters.
 */
class QualityProfileTest {

    private static final String PROFILE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <profile>
              <name>My Way</name>
              <language>java</language>
              <rules>
                <rule>
                  <repositoryKey>java</repositoryKey>
                  <key>S1118</key>
                  <priority>MAJOR</priority>
                  <parameters></parameters>
                </rule>
                <rule>
                  <repositoryKey>java</repositoryKey>
                  <key>S1192</key>
                  <priority>CRITICAL</priority>
                  <parameters>
                    <parameter>
                      <key>threshold</key>
                      <value>5</value>
                    </parameter>
                  </parameters>
                </rule>
                <rule>
                  <repositoryKey>python</repositoryKey>
                  <key>S100</key>
                </rule>
              </rules>
            </profile>
            """;

    private Path writeProfile(Path dir, String xml) throws Exception {
        Path file = dir.resolve("profile.xml");
        Files.writeString(file, xml);
        return file;
    }

    @Test
    @DisplayName("parse reads every rule, keyed as repositoryKey:key")
    void parseReadsAllRuleKeys(@TempDir Path dir) throws Exception {
        QualityProfile profile = QualityProfile.parse(writeProfile(dir, PROFILE_XML));

        assertEquals(3, profile.rules().size(), "all three rules must be parsed");
        assertTrue(profile.ruleKeys().contains("java:S1118"));
        assertTrue(profile.ruleKeys().contains("java:S1192"));
        assertTrue(profile.ruleKeys().contains("python:S100"));
    }

    @Test
    @DisplayName("parse reads per-rule parameters")
    void parseReadsParameters(@TempDir Path dir) throws Exception {
        QualityProfile profile = QualityProfile.parse(writeProfile(dir, PROFILE_XML));

        QualityProfile.ProfileRule s1192 = profile.rules().stream()
                .filter(r -> "java:S1192".equals(r.ruleKey()))
                .findFirst()
                .orElseThrow();
        assertEquals("5", s1192.parameters().get("threshold"),
                "the threshold parameter must be parsed");
    }

    @Test
    @DisplayName("a rule with no parameters parses to an empty parameter map")
    void parseRuleWithoutParameters(@TempDir Path dir) throws Exception {
        QualityProfile profile = QualityProfile.parse(writeProfile(dir, PROFILE_XML));

        QualityProfile.ProfileRule s1118 = profile.rules().stream()
                .filter(r -> "java:S1118".equals(r.ruleKey()))
                .findFirst()
                .orElseThrow();
        assertTrue(s1118.parameters().isEmpty(),
                "a rule with an empty <parameters> must carry no params");

        QualityProfile.ProfileRule s100 = profile.rules().stream()
                .filter(r -> "python:S100".equals(r.ruleKey()))
                .findFirst()
                .orElseThrow();
        assertTrue(s100.parameters().isEmpty(),
                "a rule with no <parameters> element at all must carry no params");
    }

    @Test
    @DisplayName("the profile exposes its declared name and language")
    void parseExposesNameAndLanguage(@TempDir Path dir) throws Exception {
        QualityProfile profile = QualityProfile.parse(writeProfile(dir, PROFILE_XML));

        assertEquals("My Way", profile.name());
        assertEquals("java", profile.language());
    }

    @Test
    @DisplayName("a missing profile file fails with a clear exception")
    void parseMissingFileThrows(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.xml");

        QualityProfileException ex = assertThrows(QualityProfileException.class,
                () -> QualityProfile.parse(missing));
        assertTrue(ex.getMessage().contains("does-not-exist.xml"),
                "the error must name the missing file, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("malformed XML fails with a clear exception")
    void parseMalformedXmlThrows(@TempDir Path dir) throws Exception {
        Path bad = writeProfile(dir, "<profile><rules><rule><key>S1");

        QualityProfileException ex = assertThrows(QualityProfileException.class,
                () -> QualityProfile.parse(bad));
        assertFalse(ex.getMessage().isBlank(), "the error must explain the parse failure");
    }
}
