package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.commons.api.TextRange;

class IssueMapperTest {

    private static final Path PLUGINS_DIR = Paths.get("plugins");

    private static RuleCatalog catalog;

    @BeforeAll
    static void buildCatalog() {
        catalog = RuleCatalog.fromPluginsDir(PLUGINS_DIR);
    }

    @Test
    @DisplayName("maps rule key, file path, range and message into a protocol Issue")
    void map_withRange_populatesAllFields() {
        TextRange range = new TextRange(3, 4, 5, 12);

        io.github.randomcodespace.sonarpredict.protocol.dto.Issue dto = IssueMapper.map(
                "java:S1118", "com/example/UtilityClass.java", range,
                "Add a private constructor.", catalog);

        assertEquals("java:S1118", dto.ruleKey());
        assertEquals("com/example/UtilityClass.java", dto.filePath());
        assertEquals(3, dto.startLine());
        assertEquals(4, dto.startColumn());
        assertEquals(5, dto.endLine());
        assertEquals(12, dto.endColumn());
        assertEquals("Add a private constructor.", dto.message());
    }

    @Test
    @DisplayName("severity and type come from the RuleCatalog, not a hardcode")
    void map_knownRule_resolvesSeverityAndTypeFromCatalog() {
        // java:S1118 is a Major CODE_SMELL per its bundled rule metadata.
        io.github.randomcodespace.sonarpredict.protocol.dto.Issue dto = IssueMapper.map(
                "java:S1118", "X.java", null, "msg", catalog);

        assertEquals(catalog.lookup("java:S1118").severity(), dto.severity());
        assertEquals(catalog.lookup("java:S1118").type(), dto.type());
        assertEquals("MAJOR", dto.severity());
        assertEquals("CODE_SMELL", dto.type());
    }

    @Test
    @DisplayName("an unknown rule key falls back to MAJOR / CODE_SMELL")
    void map_unknownRule_fallsBackToDefaults() {
        io.github.randomcodespace.sonarpredict.protocol.dto.Issue dto = IssueMapper.map(
                "java:DoesNotExist", "X.java", null, "msg", catalog);

        assertEquals("MAJOR", dto.severity());
        assertEquals("CODE_SMELL", dto.type());
    }

    @Test
    @DisplayName("a null text range (file-level issue) maps to zero positions")
    void map_withoutRange_usesZeroPositions() {
        io.github.randomcodespace.sonarpredict.protocol.dto.Issue dto = IssueMapper.map(
                "java:S1118", "com/example/UtilityClass.java", null,
                "File-level finding.", catalog);

        assertEquals(0, dto.startLine());
        assertEquals(0, dto.startColumn());
        assertEquals(0, dto.endLine());
        assertEquals(0, dto.endColumn());
        assertEquals("java:S1118", dto.ruleKey());
    }
}
