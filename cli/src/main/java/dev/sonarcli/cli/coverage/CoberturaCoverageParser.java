package dev.sonarcli.cli.coverage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

/**
 * Parses a Cobertura XML coverage report — the shape {@code coverage.py}'s
 * {@code xml} formatter emits for Python projects.
 *
 * <p>Cobertura nests {@code <class filename="...">} under
 * {@code <packages><package><classes>}; each class carries a {@code <lines>}
 * block of {@code <line number="N" hits="H"/>} elements. Every {@code <line>}
 * is a coverable line; a line is covered when {@code hits > 0}. Multiple
 * {@code <class>} entries can share a {@code filename} (e.g. several classes in
 * one source file), so their line counts are summed per file.
 */
public final class CoberturaCoverageParser implements CoverageParser {

    @Override
    public CoverageReport parse(Path path) {
        Element root = CoverageXml.parse(path).getDocumentElement();
        if (root == null || !"coverage".equals(root.getNodeName())) {
            throw new CoverageException(
                    "not a Cobertura report: " + path + " (expected a <coverage> root)");
        }

        // Collect line numbers per filename: a source file may host several
        // <class> entries (e.g. several classes in one Python module).
        var coverableByFile = new java.util.LinkedHashMap<String,
                java.util.NavigableSet<Integer>>();
        var coveredByFile = new java.util.LinkedHashMap<String,
                java.util.NavigableSet<Integer>>();
        for (Element clazz : CoverageXml.elementsByTag(root, "class")) {
            String filename = clazz.getAttribute("filename");
            if (filename == null || filename.isBlank()) {
                continue;
            }
            var coverable = coverableByFile.computeIfAbsent(
                    filename, k -> new java.util.TreeSet<>());
            var covered = coveredByFile.computeIfAbsent(
                    filename, k -> new java.util.TreeSet<>());
            for (Element lines : CoverageXml.children(clazz, "lines")) {
                for (Element line : CoverageXml.children(lines, "line")) {
                    int number = CoverageXml.intAttr(line, "number");
                    coverable.add(number);
                    if (CoverageXml.intAttr(line, "hits") > 0) {
                        covered.add(number);
                    }
                }
            }
        }

        List<FileCoverage> files = new ArrayList<>();
        coverableByFile.forEach((filename, coverable) ->
                files.add(new FileCoverage(filename,
                        coveredByFile.get(filename), coverable)));
        return new CoverageReport(files);
    }
}
