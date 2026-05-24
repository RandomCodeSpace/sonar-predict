package io.github.randomcodespace.sonarpredict.cli.coverage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The six coverage-report formats sonar imports in v1, plus content-sniffing
 * {@linkplain #detect(Path) detection}.
 *
 * <p>Detection reads only the head of the file and matches on the root XML
 * element, the first text line, or — for SimpleCov — the {@code .resultset.json}
 * filename together with its JSON shape. The formats are deliberately
 * distinguishable: JaCoCo and Cobertura both have XML roots but JaCoCo's is
 * {@code <report>} while Cobertura's is {@code <coverage>} carrying a
 * {@code <packages>} child; Clover's root is also {@code <coverage>} but it
 * carries a {@code clover=} attribute and a {@code <project>} child.
 */
public enum CoverageFormat {

    /** JaCoCo XML — Java/Kotlin/Scala. */
    JACOCO,
    /** Cobertura / {@code coverage.py} XML — Python. */
    COBERTURA,
    /** LCOV tracefile — JS/TS. */
    LCOV,
    /** Go {@code go test -coverprofile} profile — Go. */
    GO,
    /** Clover XML — PHP. */
    CLOVER,
    /** SimpleCov {@code .resultset.json} — Ruby. */
    SIMPLECOV;

    /** Bytes of the file head read for sniffing — enough for an XML prolog. */
    private static final int SNIFF_BYTES = 8192;

    /**
     * Identifies the coverage format of a file by content sniffing.
     *
     * @param path the report file
     * @return the detected format
     * @throws CoverageException if the file is missing, unreadable, or matches
     *                           no known format
     */
    public static CoverageFormat detect(Path path) {
        if (path == null) {
            throw new CoverageException("coverage report path is null");
        }
        if (!Files.isRegularFile(path)) {
            throw new CoverageException(
                    "coverage report file not found: " + path.toAbsolutePath());
        }

        String head = readHead(path);
        String name = path.getFileName().toString();

        CoverageFormat detected = detectXml(head);
        if (detected == null) {
            detected = detectTextual(head, name);
        }
        if (detected != null) {
            return detected;
        }

        throw new CoverageException(
                "unrecognized coverage report format: " + path
                + " (expected JaCoCo, Cobertura, LCOV, Go profile, Clover, or SimpleCov)");
    }

    /** Identifies the XML-based formats (JaCoCo, Clover, Cobertura) from a head snippet. */
    private static CoverageFormat detectXml(String head) {
        if (head.contains("<report") && head.contains("JACOCO")) {
            return JACOCO;
        }
        // JaCoCo without the DTD: <report> root with <sourcefile> children.
        if (rootElementIs(head, "report")) {
            return JACOCO;
        }
        if (rootElementIs(head, "coverage")) {
            if (head.contains("clover=") || head.contains("<project")) {
                return CLOVER;
            }
            return COBERTURA;
        }
        return null;
    }

    /** Identifies the textual formats (Go, LCOV, SimpleCov) from a head snippet and filename. */
    private static CoverageFormat detectTextual(String head, String name) {
        if (head.startsWith("mode:")) {
            return GO;
        }
        if (head.contains("SF:") || head.startsWith("TN:")) {
            return LCOV;
        }
        if (name.endsWith(".resultset.json") && head.contains("\"coverage\"")) {
            return SIMPLECOV;
        }
        return null;
    }

    /**
     * Whether the first XML element in {@code head} has the given tag name —
     * skipping any prolog ({@code <?xml ?>}), DOCTYPE, or comments.
     */
    private static boolean rootElementIs(String head, String tag) {
        int i = 0;
        int n = head.length();
        while (i < n) {
            int lt = head.indexOf('<', i);
            if (lt < 0) {
                return false;
            }
            int skipped = skipNonElement(head, lt);
            if (skipped == lt) {
                // Not a prolog/comment/doctype — this is the first real element.
                return matchesTag(head, lt, tag);
            }
            if (skipped < 0) {
                return false;
            }
            i = skipped;
        }
        return false;
    }

    /**
     * If {@code head} at {@code lt} starts a prolog ({@code <?...?>}), comment
     * ({@code <!--...-->}), or DOCTYPE ({@code <!...>}), returns the index just
     * past the construct; returns {@code -1} when the construct is unterminated;
     * returns {@code lt} unchanged when the position is a real element tag.
     */
    private static int skipNonElement(String head, int lt) {
        if (head.startsWith("<?", lt)) {
            int end = head.indexOf("?>", lt);
            return end < 0 ? -1 : end + 2;
        }
        if (head.startsWith("<!--", lt)) {
            int end = head.indexOf("-->", lt);
            return end < 0 ? -1 : end + 3;
        }
        if (head.startsWith("<!", lt)) {
            int end = head.indexOf('>', lt);
            return end < 0 ? -1 : end + 1;
        }
        return lt;
    }

    /** Whether the element at {@code lt} is {@code <tag} followed by whitespace or {@code >}. */
    private static boolean matchesTag(String head, int lt, String tag) {
        int n = head.length();
        int after = lt + 1 + tag.length();
        return head.regionMatches(lt + 1, tag, 0, tag.length())
                && after <= n
                && (after == n
                        || Character.isWhitespace(head.charAt(after))
                        || head.charAt(after) == '>');
    }

    private static String readHead(Path path) {
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = in.readNBytes(SNIFF_BYTES);
            return new String(buffer, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new CoverageException(
                    "could not read coverage report " + path + ": " + e.getMessage(), e);
        }
    }
}
