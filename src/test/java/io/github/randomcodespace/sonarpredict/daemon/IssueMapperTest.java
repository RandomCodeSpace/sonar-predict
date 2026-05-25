package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonarsource.sonarlint.core.analysis.api.ClientInputFileEdit;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;
import org.sonarsource.sonarlint.core.commons.api.TextRange;

import io.github.randomcodespace.sonarpredict.protocol.dto.FileEdit;
import io.github.randomcodespace.sonarpredict.protocol.dto.QuickFix;
import io.github.randomcodespace.sonarpredict.protocol.dto.TextEdit;

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

    @Test
    @DisplayName("mapQuickFixes() preserves message, target path and text-edit positions")
    void mapQuickFixes_singleEditRoundtrip(@TempDir Path baseDir) throws Exception {
        Path pkgDir = Files.createDirectories(baseDir.resolve("com/example"));
        Path src = pkgDir.resolve("UtilityClass.java");
        Files.writeString(src, "package com.example;\npublic class UtilityClass {}\n");
        FileInputFile input = new FileInputFile(src, baseDir, SonarLanguage.JAVA, false);

        org.sonarsource.sonarlint.core.analysis.api.TextEdit engineEdit =
                new org.sonarsource.sonarlint.core.analysis.api.TextEdit(
                        new TextRange(3, 0, 3, 0),
                        "    private UtilityClass() {}\n");
        ClientInputFileEdit fileEdit = new ClientInputFileEdit(input, List.of(engineEdit));
        org.sonarsource.sonarlint.core.analysis.api.QuickFix engineQf =
                new org.sonarsource.sonarlint.core.analysis.api.QuickFix(
                        List.of(fileEdit), "Add a private constructor");

        List<QuickFix> mapped = IssueMapper.mapQuickFixes(List.of(engineQf), baseDir);

        assertEquals(1, mapped.size());
        QuickFix qf = mapped.get(0);
        assertEquals("Add a private constructor", qf.message());
        assertEquals(1, qf.fileEdits().size());
        FileEdit fe = qf.fileEdits().get(0);
        assertEquals("com/example/UtilityClass.java", fe.filePath());
        assertEquals(1, fe.edits().size());
        TextEdit te = fe.edits().get(0);
        assertEquals(3, te.startLine());
        assertEquals(0, te.startColumn());
        assertEquals(3, te.endLine());
        assertEquals(0, te.endColumn());
        assertEquals("    private UtilityClass() {}\n", te.replacement());
    }

    @Test
    @DisplayName("an empty engine quick-fix list maps to an empty DTO list")
    void mapQuickFixes_emptyList(@TempDir Path baseDir) {
        assertEquals(List.of(), IssueMapper.mapQuickFixes(List.of(), baseDir));
    }

    @Test
    @DisplayName("a multi-file quick-fix preserves both files in order")
    void mapQuickFixes_multiFile(@TempDir Path baseDir) throws Exception {
        Path a = baseDir.resolve("A.java");
        Path b = baseDir.resolve("B.java");
        Files.writeString(a, "class A {}\n");
        Files.writeString(b, "class B {}\n");
        FileInputFile inA = new FileInputFile(a, baseDir, SonarLanguage.JAVA, false);
        FileInputFile inB = new FileInputFile(b, baseDir, SonarLanguage.JAVA, false);

        org.sonarsource.sonarlint.core.analysis.api.QuickFix qf =
                new org.sonarsource.sonarlint.core.analysis.api.QuickFix(
                        List.of(
                                new ClientInputFileEdit(inA, List.of(
                                        new org.sonarsource.sonarlint.core.analysis.api.TextEdit(
                                                new TextRange(1, 6, 1, 7), "X"))),
                                new ClientInputFileEdit(inB, List.of(
                                        new org.sonarsource.sonarlint.core.analysis.api.TextEdit(
                                                new TextRange(1, 6, 1, 7), "Y")))),
                        "Rename across two files");

        List<QuickFix> mapped = IssueMapper.mapQuickFixes(List.of(qf), baseDir);

        assertEquals(1, mapped.size());
        assertEquals(2, mapped.get(0).fileEdits().size());
        assertEquals("A.java", mapped.get(0).fileEdits().get(0).filePath());
        assertEquals("B.java", mapped.get(0).fileEdits().get(1).filePath());
        assertEquals("X", mapped.get(0).fileEdits().get(0).edits().get(0).replacement());
        assertEquals("Y", mapped.get(0).fileEdits().get(1).edits().get(0).replacement());
    }
}
