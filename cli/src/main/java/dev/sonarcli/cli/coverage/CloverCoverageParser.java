package dev.sonarcli.cli.coverage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

/**
 * Parses a Clover XML coverage report — the format PHPUnit emits for PHP.
 *
 * <p>Clover nests {@code <file>} elements under {@code <project>}; each
 * {@code <line>} carries a {@code type} ({@code stmt}, {@code cond},
 * {@code method}) and a {@code count}. Line coverage counts only
 * {@code type="stmt"} lines: each is a coverable line, covered when
 * {@code count > 0}. The file path is the {@code path} attribute, falling back
 * to {@code name} when {@code path} is absent.
 */
public final class CloverCoverageParser implements CoverageParser {

    @Override
    public CoverageReport parse(Path path) {
        Element root = CoverageXml.parse(path).getDocumentElement();
        if (root == null || !"coverage".equals(root.getNodeName())) {
            throw new CoverageException(
                    "not a Clover report: " + path + " (expected a <coverage> root)");
        }

        // Collect statement line numbers per file path.
        var coverableByFile = new java.util.LinkedHashMap<String,
                java.util.NavigableSet<Integer>>();
        var coveredByFile = new java.util.LinkedHashMap<String,
                java.util.NavigableSet<Integer>>();
        for (Element file : CoverageXml.elementsByTag(root, "file")) {
            collectFile(file, coverableByFile, coveredByFile);
        }

        List<FileCoverage> files = new ArrayList<>();
        coverableByFile.forEach((file, coverable) ->
                files.add(new FileCoverage(file, coveredByFile.get(file), coverable)));
        return new CoverageReport(files);
    }

    /**
     * Records the statement-coverage line numbers for one {@code <file>}
     * element into the accumulating maps, keyed by the file's path attribute.
     */
    private static void collectFile(
            Element file,
            java.util.Map<String, java.util.NavigableSet<Integer>> coverableByFile,
            java.util.Map<String, java.util.NavigableSet<Integer>> coveredByFile) {
        String filePath = filePathOf(file);
        if (filePath == null) {
            return;
        }
        var coverable = coverableByFile.computeIfAbsent(
                filePath, k -> new java.util.TreeSet<>());
        var covered = coveredByFile.computeIfAbsent(
                filePath, k -> new java.util.TreeSet<>());
        for (Element line : CoverageXml.children(file, "line")) {
            recordStatementLine(line, coverable, covered);
        }
    }

    /** Returns the file's path attribute, or {@code name} as fallback, or {@code null}. */
    private static String filePathOf(Element file) {
        String filePath = file.getAttribute("path");
        if (filePath == null || filePath.isBlank()) {
            filePath = file.getAttribute("name");
        }
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        return filePath;
    }

    /** Adds a {@code <line type="stmt">} entry to coverable/covered as appropriate. */
    private static void recordStatementLine(Element line,
                                            java.util.NavigableSet<Integer> coverable,
                                            java.util.NavigableSet<Integer> covered) {
        if (!"stmt".equals(line.getAttribute("type"))) {
            return;
        }
        int number = CoverageXml.intAttr(line, "num");
        coverable.add(number);
        if (CoverageXml.intAttr(line, "count") > 0) {
            covered.add(number);
        }
    }
}
