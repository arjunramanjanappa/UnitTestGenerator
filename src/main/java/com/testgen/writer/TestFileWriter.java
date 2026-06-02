package com.testgen.writer;

import com.testgen.generator.GeneratedTest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

@Slf4j
@Component
public class TestFileWriter {

    /**
     * Writes generated test content to the target directory, preserving package structure.
     *
     * @param test       the generated test
     * @param targetRoot e.g. /project/src/test/java  (or deeper — overlap is handled)
     * @param overwrite  if false, skips files that already exist
     * @return true if written, false if skipped
     */
    public boolean write(GeneratedTest test, Path targetRoot, boolean overwrite) throws IOException {
        Path packageDir = resolvePackageDir(targetRoot, test.packageName());
        Files.createDirectories(packageDir);

        Path targetFile = packageDir.resolve(test.fileName());

        if (!overwrite && Files.exists(targetFile)) {
            log.info("Skipping (already exists): {}", targetFile);
            return false;
        }

        Files.writeString(targetFile, test.content(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Written: {}", targetFile);
        return true;
    }

    /**
     * Dry-run preview — returns the target path without writing anything.
     */
    public Path resolveTargetPath(GeneratedTest test, Path targetRoot) {
        return resolvePackageDir(targetRoot, test.packageName()).resolve(test.fileName());
    }

    // ── Package directory resolution ────────────────────────────────────────

    /**
     * Resolves the package directory under targetRoot, handling the case where
     * targetRoot already contains a prefix of the package path.
     *
     * Example — user enters targetRoot = src/test/java/com/uob/dge
     *   packageName = com.uob.dge.ft.service.impl.cn
     *   Without fix → src/test/java/com/uob/dge/com/uob/dge/ft/service/impl/cn  (double-nested)
     *   With fix    → src/test/java/com/uob/dge/ft/service/impl/cn              (correct)
     */
    private Path resolvePackageDir(Path targetRoot, String packageName) {
        String[] pkgParts  = packageName.split("\\.");
        String   targetStr = normalizeSlashes(targetRoot.toString());

        // Walk from the longest possible overlap down to 1 segment.
        // Stop at the first suffix of targetRoot that matches a prefix of the package path.
        for (int overlap = pkgParts.length; overlap > 0; overlap--) {
            String candidate = String.join("/", Arrays.copyOfRange(pkgParts, 0, overlap));
            if (targetStr.endsWith("/" + candidate) || targetStr.equals(candidate)) {
                // targetRoot already includes the first `overlap` package segments.
                // Append only the remaining segments.
                String[] remaining = Arrays.copyOfRange(pkgParts, overlap, pkgParts.length);
                if (remaining.length == 0) return targetRoot;
                return targetRoot.resolve(String.join("/", remaining));
            }
        }

        // No overlap — targetRoot is the java root; append the full package path.
        return targetRoot.resolve(packageName.replace(".", "/"));
    }

    private String normalizeSlashes(String path) {
        return path.replace("\\", "/");
    }
}
