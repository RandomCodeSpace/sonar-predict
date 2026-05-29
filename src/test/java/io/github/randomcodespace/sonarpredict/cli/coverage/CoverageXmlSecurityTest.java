package io.github.randomcodespace.sonarpredict.cli.coverage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

/**
 * Adversarial tests that lock in {@link CoverageXml}'s XXE hardening. Coverage
 * reports come from CI artifacts that are not necessarily trusted, so the parser
 * must never resolve an external entity into the document and must never detonate
 * an entity-expansion bomb. These tests guard the control so a later
 * "simplification" of {@code newSecureBuilder()} that reopens the hole fails the
 * build. (The pre-existing parser tests only prove a benign DOCTYPE is tolerated.)
 */
class CoverageXmlSecurityTest {

    private static final String SENTINEL = "TOP_SECRET_XXE_SENTINEL_4f3a";

    @Test
    @DisplayName("an external SYSTEM entity is never resolved into the parsed document")
    void externalEntityIsNotResolved(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("secret.txt"), SENTINEL);
        Path report = Files.writeString(dir.resolve("evil.xml"),
                "<?xml version=\"1.0\"?>\n"
                        + "<!DOCTYPE coverage [\n"
                        + "  <!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">\n"
                        + "]>\n"
                        + "<coverage>&xxe;</coverage>\n");

        // Blocked either way: the parser may reject the document outright, or
        // parse it with the entity left unresolved. The hole is open ONLY if the
        // external file's content is inlined into the DOM.
        try {
            Document doc = CoverageXml.parse(report);
            assertFalse(doc.getDocumentElement().getTextContent().contains(SENTINEL),
                    "external entity content must never be inlined into the parsed document");
        } catch (CoverageException blocked) {
            // Acceptable: the malicious document was rejected rather than parsed.
            assertFalse(blocked.getMessage().contains(SENTINEL),
                    "even the error message must not leak the external file content");
        }
    }

    @Test
    @DisplayName("an entity-expansion bomb is capped or rejected, never detonated")
    void entityExpansionBombIsNeutralized(@TempDir Path dir) throws Exception {
        StringBuilder dtd = new StringBuilder("  <!ENTITY a \"aaaaaaaaaa\">\n");
        String prev = "a";
        for (int level = 1; level <= 8; level++) {
            String name = "e" + level;
            dtd.append("  <!ENTITY ").append(name).append(" \"")
                    .append(("&" + prev + ";").repeat(10))
                    .append("\">\n");
            prev = name;
        }
        Path report = Files.writeString(dir.resolve("bomb.xml"),
                "<?xml version=\"1.0\"?>\n"
                        + "<!DOCTYPE coverage [\n" + dtd + "]>\n"
                        + "<coverage>&" + prev + ";</coverage>\n");

        // A detonated bomb would expand to ~10^9 characters. Secure processing
        // caps entity expansion, so parse must either throw or leave the
        // reference unexpanded — never materialize the blown-up text.
        boolean neutralized;
        try {
            Document doc = CoverageXml.parse(report);
            neutralized = doc.getDocumentElement().getTextContent().length() < 100_000;
        } catch (CoverageException rejected) {
            neutralized = true;
        }
        assertTrue(neutralized,
                "an entity-expansion bomb must be capped or rejected, not detonated");
    }
}
