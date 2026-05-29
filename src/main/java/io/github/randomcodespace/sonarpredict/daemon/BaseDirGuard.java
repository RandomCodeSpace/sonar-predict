package io.github.randomcodespace.sonarpredict.daemon;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Path-traversal defense for the analysis core: decides whether a request file
 * resolves to a real path safely contained within the analysis base directory.
 *
 * <p>A direct socket client could send an absolute path, a {@code ..} escape,
 * or a symlinked name resolving outside {@code baseDir} to read files the
 * daemon was never asked to analyze. This guard is the single place that
 * decision is made, so the accept/reject rule is testable in isolation.
 */
final class BaseDirGuard {

    private BaseDirGuard() {
        // utility class
    }

    /**
     * Whether a request file resolves to a real path inside {@code baseDir}.
     *
     * <p>Three guards, all required: the supplied {@code relative} name must
     * not be an absolute path, must contain no {@code ..} segment, and the
     * file's <em>real</em> path — symlinks resolved — must lie under the real
     * {@code baseDir}. The first two reject the obvious traversal payloads
     * before any filesystem touch; the third is the backstop.
     *
     * <p>The containment check uses {@link Path#toRealPath()}, not a lexical
     * {@link Path#startsWith(Path)} on {@code normalize()}-d paths.
     * {@code normalize()} only collapses {@code .} and {@code ..} textually —
     * it does not follow symbolic links. A symlink inside the project tree
     * pointing <em>outside</em> {@code baseDir} therefore passes a lexical
     * check yet reads an arbitrary target file; resolving real paths closes
     * that escape. {@code toRealPath()} throwing — a non-existent file, a
     * broken symlink, or a missing {@code baseDir} — is treated as "not
     * contained": the file is rejected and the caller warns and skips it,
     * rather than the request crashing.
     *
     * @param relative the raw file name from the request
     * @param resolved the absolute, normalized path it resolved to
     * @param baseDir  the absolute, normalized analysis base directory
     * @return {@code true} only if the file is safely contained in {@code baseDir}
     */
    static boolean isWithinBaseDir(String relative, Path resolved, Path baseDir) {
        Path rawRelative = Path.of(relative);
        if (rawRelative.isAbsolute()) {
            return false;
        }
        for (Path segment : rawRelative) {
            if ("..".equals(segment.toString())) {
                return false;
            }
        }
        try {
            // Resolve symlinks on both sides: a symlinked component could
            // otherwise point the file outside the tree while still passing
            // a purely lexical startsWith() check.
            Path realBase = baseDir.toRealPath();
            Path realResolved = resolved.toRealPath();
            return realResolved.startsWith(realBase);
        } catch (IOException e) {
            // A non-existent file, a broken symlink, or a missing baseDir:
            // cannot prove containment, so reject — the caller skips and warns.
            return false;
        }
    }
}
