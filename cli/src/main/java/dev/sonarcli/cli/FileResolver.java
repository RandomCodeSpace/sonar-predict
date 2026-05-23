package dev.sonarcli.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a CLI file-selection request into a base directory plus base-relative
 * file paths — the shape an {@code AnalyzeRequest} needs.
 *
 * <p>Three selection modes:
 * <ul>
 *   <li>{@link #resolveExplicit(List)} — an explicit list of files; each is
 *       validated to exist and rebased onto their common parent.</li>
 *   <li>{@link #resolveProject(Path)} — a directory walk collecting analyzable
 *       source files, skipping build output ({@code target/}), the VCS
 *       directory ({@code .git/}), and any hidden directory.</li>
 *   <li>{@link #resolveDiff(Path, String)} — files changed against a git ref
 *       (default {@code HEAD}) that still exist on disk.</li>
 * </ul>
 *
 * <p>"Analyzable" is decided by a fixed source-file extension allowlist
 * covering the daemon's vendored analyzers — the CLI cannot link the daemon's
 * language detector, so the contract is duplicated here intentionally.
 */
public final class FileResolver {

    /** Directory names never descended into during a project walk. */
    private static final Set<String> SKIP_DIRS = Set.of("target", "build", "node_modules");

    /** Source-file extensions the vendored analyzers can process. */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            "java", "js", "jsx", "ts", "tsx", "py", "go", "rb",
            "php", "kt", "kts", "scala", "html", "xml", "css", "vue");

    /** A base directory and the analyzable file paths relative to it. */
    public record ResolvedFiles(Path baseDir, List<String> relativePaths) {
        public ResolvedFiles {
            Objects.requireNonNull(baseDir, "baseDir");
            relativePaths = List.copyOf(relativePaths);
        }

        /** Whether no files were selected. */
        public boolean isEmpty() {
            return relativePaths.isEmpty();
        }
    }

    /**
     * Resolves an explicit list of files. Each must exist; the base directory
     * is their deepest common ancestor.
     *
     * @param files file paths (absolute or relative to the working directory)
     * @return the resolved base directory and relative paths
     * @throws IllegalArgumentException if the list is empty or a file is missing
     */
    public ResolvedFiles resolveExplicit(List<String> files) {
        Objects.requireNonNull(files, "files");
        if (files.isEmpty()) {
            throw new IllegalArgumentException("no files given to analyze");
        }
        List<Path> absolute = new ArrayList<>();
        for (String file : files) {
            Path path = Path.of(file).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("file does not exist: " + file);
            }
            absolute.add(path);
        }
        Path base = commonAncestor(absolute);
        return rebased(base, absolute);
    }

    /**
     * Walks {@code projectDir}, collecting analyzable source files.
     *
     * @param projectDir the directory to scan
     * @return the resolved base directory ({@code projectDir}) and relative paths
     * @throws IllegalArgumentException if {@code projectDir} is not a directory
     */
    public ResolvedFiles resolveProject(Path projectDir) {
        Objects.requireNonNull(projectDir, "projectDir");
        Path base = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("not a directory: " + projectDir);
        }
        List<Path> sources = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(base)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = dir.getFileName().toString();
                    if (name.startsWith(".") || SKIP_DIRS.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && isAnalyzable(file)) {
                        sources.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk project directory: " + base, e);
        }
        return rebased(base, sources);
    }

    /**
     * Resolves files changed against a git ref.
     *
     * @param projectDir the git working tree to inspect
     * @param ref        the ref to diff against, or {@code null} for {@code HEAD}
     * @return the resolved base directory and the changed files that still exist
     * @throws IllegalArgumentException if {@code projectDir} is not a directory
     * @throws DaemonException          if the {@code git} invocation fails
     */
    public ResolvedFiles resolveDiff(Path projectDir, String ref) {
        Objects.requireNonNull(projectDir, "projectDir");
        Path base = projectDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("not a directory: " + projectDir);
        }
        String against = (ref == null || ref.isBlank()) ? "HEAD" : ref;
        List<String> changed = gitDiffNames(base, against);
        List<Path> existing = new ArrayList<>();
        for (String name : changed) {
            Path path = base.resolve(name);
            // git reports renamed/deleted files too; keep only ones still present.
            if (Files.isRegularFile(path)) {
                existing.add(path.normalize());
            }
        }
        return rebased(base, existing);
    }

    /**
     * Suppresses {@code java:S4036}: {@code sonar} is a developer tool invoked
     * from a developer shell; PATH-resolving {@code git} is the intended
     * behaviour, not a tampering vector. Hard-coding {@code /usr/bin/git}
     * would break Homebrew, asdf, mise, nix, and Windows installs.
     */
    @SuppressWarnings("java:S4036")
    private static List<String> gitDiffNames(Path workingTree, String ref) {
        ProcessBuilder builder = new ProcessBuilder(
                "git", "diff", "--name-only", ref)
                .directory(workingTree.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        List<String> names = new ArrayList<>();
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        names.add(line.strip());
                    }
                }
            }
            int code = process.waitFor();
            if (code != 0) {
                throw new DaemonException(
                        "git diff against '" + ref + "' failed with exit code " + code);
            }
        } catch (IOException e) {
            throw new DaemonException("could not run git diff: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DaemonException("interrupted while running git diff", e);
        }
        return names;
    }

    private static boolean isAnalyzable(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return SOURCE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    private static ResolvedFiles rebased(Path base, List<Path> files) {
        Set<String> relative = new TreeSet<>();
        for (Path file : files) {
            relative.add(base.relativize(file).toString().replace('\\', '/'));
        }
        return new ResolvedFiles(base, new ArrayList<>(relative));
    }

    private static Path commonAncestor(List<Path> paths) {
        Path ancestor = paths.get(0).getParent();
        for (Path path : paths) {
            Path parent = path.getParent();
            while (ancestor != null && !parent.startsWith(ancestor)) {
                ancestor = ancestor.getParent();
            }
        }
        return ancestor != null ? ancestor : paths.get(0).getRoot();
    }
}
