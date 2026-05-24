package dev.sonarcli.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.sonarcli.protocol.dto.RuleMetadata;

class RuleCatalogTest {

    /** The vendored plugin directory, relative to the daemon module root. */
    private static final Path PLUGINS_DIR = Paths.get("plugins");

    private static final Set<String> VALID_SEVERITIES =
            Set.of("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");
    private static final Set<String> VALID_TYPES =
            Set.of("BUG", "CODE_SMELL", "VULNERABILITY", "SECURITY_HOTSPOT");

    private static RuleCatalog catalog;

    @BeforeAll
    static void buildCatalog() {
        catalog = RuleCatalog.fromPluginsDir(PLUGINS_DIR);
    }

    @Test
    @DisplayName("catalog built from the vendored plugins is non-empty")
    void catalog_isNonEmpty() {
        assertFalse(catalog.isEmpty(), "expected the rule catalog to contain rules");
        assertTrue(catalog.size() > 1000,
                "expected thousands of rules across all analyzers, got: " + catalog.size());
    }

    @Test
    @DisplayName("lookup(java:S1118) yields real, well-formed rule metadata")
    void lookup_knownJavaRule_yieldsMetadata() {
        RuleMetadata md = catalog.lookup("java:S1118");

        assertNotNull(md, "java:S1118 must be in the catalog");
        assertEquals("java:S1118", md.ruleKey());
        assertFalse(md.name() == null || md.name().isBlank(), "name must be non-blank");
        assertEquals("java", md.language());
        assertTrue(VALID_SEVERITIES.contains(md.severity()),
                "severity must be a valid enum value, got: " + md.severity());
        assertTrue(VALID_TYPES.contains(md.type()),
                "type must be a valid enum value, got: " + md.type());
        assertFalse(md.descriptionHtml() == null || md.descriptionHtml().isBlank(),
                "descriptionHtml must be non-blank");
    }

    @Test
    @DisplayName("lookup resolves Python and JavaScript analyzer rules too")
    void lookup_pythonAndJsRules() {
        RuleMetadata py = catalog.lookup("python:S1481");
        assertNotNull(py, "python:S1481 must be in the catalog");
        assertEquals("py", py.language());
        assertTrue(VALID_TYPES.contains(py.type()));

        RuleMetadata js = catalog.lookup("javascript:S3923");
        assertNotNull(js, "javascript:S3923 must be in the catalog");
        assertEquals("js", js.language());
        assertTrue(VALID_SEVERITIES.contains(js.severity()));
    }

    @Test
    @DisplayName("typescript: rules mirror their javascript: siblings")
    void lookup_typescriptRule_mirrorsJavascript() {
        RuleMetadata ts = catalog.lookup("typescript:S1186");
        assertNotNull(ts, "typescript:S1186 must be mirrored from javascript:S1186");
        assertEquals("typescript:S1186", ts.ruleKey());
        assertEquals("ts", ts.language());
        assertTrue(VALID_SEVERITIES.contains(ts.severity()),
                "severity must be a valid enum value, got: " + ts.severity());
        assertTrue(VALID_TYPES.contains(ts.type()));
        assertFalse(ts.descriptionHtml() == null || ts.descriptionHtml().isBlank(),
                "descriptionHtml must be non-blank");

        RuleMetadata js = catalog.lookup("javascript:S1186");
        assertNotNull(js);
        assertEquals(js.name(), ts.name(), "TS rule reuses the JS rule's name");
        assertEquals(js.severity(), ts.severity());
        assertEquals(js.type(), ts.type());
        assertEquals(js.descriptionHtml(), ts.descriptionHtml());
    }

    @Test
    @DisplayName("lookup of an unknown rule key returns null")
    void lookup_unknownKey_returnsNull() {
        assertNull(catalog.lookup("java:DoesNotExist"));
        assertNull(catalog.lookup("not-a-key"));
        assertNull(catalog.lookup(null));
    }
}
