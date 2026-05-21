package dev.sonarcli.cli.coverage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.w3c.dom.Element;

/**
 * Parses a JaCoCo XML coverage report.
 *
 * <p>JaCoCo nests {@code <sourcefile name="...">} elements under
 * {@code <package name="...">}; each source file carries several
 * {@code <counter type="...">} elements and a sequence of {@code <line nr="..."
 * mi="..." ci="..."/>} elements. Per-line data is read from the {@code <line>}
 * elements when present (a line is covered when its covered-instruction count
 * {@code ci > 0}), so reports can be merged line by line; the {@code LINE}
 * {@code <counter>} is used as a fallback when no {@code <line>} elements exist.
 *
 * <p>The reported file path is the package name joined to the source-file name
 * (e.g. {@code com/example/Calculator.java}) when a package name is present, so
 * files of the same name in different packages stay distinct.
 */
public final class JacocoCoverageParser implements CoverageParser {

    @Override
    public CoverageReport parse(Path path) {
        Element root = CoverageXml.parse(path).getDocumentElement();
        if (root == null || !"report".equals(root.getNodeName())) {
            throw new CoverageException(
                    "not a JaCoCo report: " + path + " (expected a <report> root)");
        }

        List<FileCoverage> files = new ArrayList<>();
        for (Element pkg : CoverageXml.elementsByTag(root, "package")) {
            String pkgName = pkg.getAttribute("name");
            for (Element sourcefile : CoverageXml.children(pkg, "sourcefile")) {
                String name = sourcefile.getAttribute("name");
                String filePath = pkgName == null || pkgName.isBlank()
                        ? name
                        : pkgName + "/" + name;
                files.add(coverage(filePath, sourcefile));
            }
        }
        return new CoverageReport(files);
    }

    /**
     * Reads per-line coverage from {@code <line>} elements; when a source file
     * has none, falls back to its {@code type="LINE"} {@code <counter>}.
     */
    private static FileCoverage coverage(String filePath, Element sourcefile) {
        List<Element> lineElements = CoverageXml.children(sourcefile, "line");
        if (!lineElements.isEmpty()) {
            NavigableSet<Integer> coverable = new TreeSet<>();
            NavigableSet<Integer> covered = new TreeSet<>();
            for (Element line : lineElements) {
                int nr = CoverageXml.intAttr(line, "nr");
                coverable.add(nr);
                // A line is covered when at least one of its instructions ran.
                if (CoverageXml.intAttr(line, "ci") > 0) {
                    covered.add(nr);
                }
            }
            return new FileCoverage(filePath, covered, coverable);
        }
        for (Element counter : CoverageXml.children(sourcefile, "counter")) {
            if ("LINE".equals(counter.getAttribute("type"))) {
                int c = CoverageXml.intAttr(counter, "covered");
                int missed = CoverageXml.intAttr(counter, "missed");
                return FileCoverage.ofCounts(filePath, c, c + missed);
            }
        }
        return FileCoverage.ofCounts(filePath, 0, 0);
    }
}
