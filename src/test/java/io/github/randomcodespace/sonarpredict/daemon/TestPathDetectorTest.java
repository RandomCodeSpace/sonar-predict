package io.github.randomcodespace.sonarpredict.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.sonarsource.sonarlint.core.commons.api.SonarLanguage;

class TestPathDetectorTest {

    // --- Path-segment based detection (cross-language) ----------------------

    @Test
    void srcTestUnderMavenLayoutIsTest() {
        assertTrue(TestPathDetector.isTest(
                "src/test/java/io/github/randomcodespace/sonarpredict/daemon/AnalysisServiceTest.java",
                SonarLanguage.JAVA));
    }

    @Test
    void topLevelTestsDirIsTest() {
        assertTrue(TestPathDetector.isTest("tests/test_thing.py", SonarLanguage.PYTHON));
        assertTrue(TestPathDetector.isTest("tests/scanner.spec.ts", SonarLanguage.TS));
    }

    @Test
    void jestUnderscoresDirIsTest() {
        assertTrue(TestPathDetector.isTest("src/__tests__/scanner.js", SonarLanguage.JS));
    }

    @Test
    void rspecSpecDirIsTest() {
        assertTrue(TestPathDetector.isTest("spec/models/order_spec.rb", SonarLanguage.RUBY));
    }

    // --- Per-language filename-based detection ------------------------------

    @Test
    void javaTestSuffixesAreTest() {
        assertTrue(TestPathDetector.isTest("foo/bar/AnalysisServiceTest.java", SonarLanguage.JAVA));
        assertTrue(TestPathDetector.isTest("foo/bar/AnalysisServiceTests.java", SonarLanguage.JAVA));
        assertTrue(TestPathDetector.isTest("foo/bar/AnalysisServiceIT.java", SonarLanguage.JAVA));
    }

    @Test
    void kotlinTestSuffixesAreTest() {
        assertTrue(TestPathDetector.isTest("foo/AnalyzerTest.kt", SonarLanguage.KOTLIN));
        assertTrue(TestPathDetector.isTest("foo/AnalyzerTests.kt", SonarLanguage.KOTLIN));
    }

    @Test
    void goUnderscoreTestSuffixIsTest() {
        assertTrue(TestPathDetector.isTest("cmd/main_test.go", SonarLanguage.GO));
    }

    @Test
    void pythonTestPrefixAndSuffixAreTest() {
        assertTrue(TestPathDetector.isTest("a/test_thing.py", SonarLanguage.PYTHON));
        assertTrue(TestPathDetector.isTest("a/thing_test.py", SonarLanguage.PYTHON));
    }

    @Test
    void jestAndJasmineSpecsAreTest() {
        assertTrue(TestPathDetector.isTest("src/foo.test.js", SonarLanguage.JS));
        assertTrue(TestPathDetector.isTest("src/foo.spec.js", SonarLanguage.JS));
        assertTrue(TestPathDetector.isTest("src/foo.test.jsx", SonarLanguage.JS));
        assertTrue(TestPathDetector.isTest("src/foo.spec.jsx", SonarLanguage.JS));
        assertTrue(TestPathDetector.isTest("src/foo.test.ts", SonarLanguage.TS));
        assertTrue(TestPathDetector.isTest("src/foo.spec.ts", SonarLanguage.TS));
        assertTrue(TestPathDetector.isTest("src/foo.test.tsx", SonarLanguage.TS));
        assertTrue(TestPathDetector.isTest("src/foo.spec.tsx", SonarLanguage.TS));
    }

    @Test
    void phpTestSuffixIsTest() {
        assertTrue(TestPathDetector.isTest("app/OrderTest.php", SonarLanguage.PHP));
    }

    @Test
    void scalaTestSuffixIsTest() {
        assertTrue(TestPathDetector.isTest("foo/AnalyzerTest.scala", SonarLanguage.SCALA));
    }

    @Test
    void rubySpecAndTestSuffixesAreTest() {
        assertTrue(TestPathDetector.isTest("models/order_spec.rb", SonarLanguage.RUBY));
        assertTrue(TestPathDetector.isTest("models/order_test.rb", SonarLanguage.RUBY));
    }

    // --- Negatives: production code stays production ------------------------

    @Test
    void productionJavaPathIsNotTest() {
        assertFalse(TestPathDetector.isTest(
                "src/main/java/io/github/randomcodespace/sonarpredict/daemon/AnalysisService.java",
                SonarLanguage.JAVA));
    }

    @Test
    void productionGoPathIsNotTest() {
        assertFalse(TestPathDetector.isTest("cmd/main.go", SonarLanguage.GO));
    }

    @Test
    void productionPythonPathIsNotTest() {
        assertFalse(TestPathDetector.isTest("app/service.py", SonarLanguage.PYTHON));
    }

    @Test
    void javaFileNamedSimilarlyButNotASuffixIsNotTest() {
        // 'TestHelper' contains 'Test' but the *suffix* is 'Helper'
        assertFalse(TestPathDetector.isTest(
                "src/main/java/foo/TestHelper.java", SonarLanguage.JAVA));
    }

    @Test
    void htmlXmlCssHaveNoFilenameConventionAndPathSegmentStillWorks() {
        // No filename pattern → falls through to path segments
        assertFalse(TestPathDetector.isTest("src/main/resources/index.html", SonarLanguage.HTML));
        assertFalse(TestPathDetector.isTest("src/main/resources/config.xml", SonarLanguage.XML));
        assertFalse(TestPathDetector.isTest("src/main/resources/style.css", SonarLanguage.CSS));
        // …but if they ARE under tests/, the path segment marks them as test
        assertTrue(TestPathDetector.isTest("tests/fixtures/sample.html", SonarLanguage.HTML));
    }

    // --- Edge cases ---------------------------------------------------------

    @Test
    void nullPathIsNotTest() {
        assertFalse(TestPathDetector.isTest(null, SonarLanguage.JAVA));
    }

    @Test
    void emptyPathIsNotTest() {
        assertFalse(TestPathDetector.isTest("", SonarLanguage.JAVA));
    }

    @Test
    void nullLanguageStillRespectsCommonPathSegments() {
        assertTrue(TestPathDetector.isTest("src/test/java/foo/Bar.java", null));
        assertFalse(TestPathDetector.isTest("src/main/java/foo/Bar.java", null));
    }

    @Test
    void windowsBackslashPathsAreNormalised() {
        assertTrue(TestPathDetector.isTest(
                "daemon\\src\\test\\java\\dev\\sonarcli\\daemon\\AnalysisServiceTest.java",
                SonarLanguage.JAVA));
    }
}
