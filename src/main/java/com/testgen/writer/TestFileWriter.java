package com.testgen.writer;

import com.testgen.generator.GeneratedTest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;

@Slf4j
@Component
public class TestFileWriter {

    /**
     * Writes generated test content to the target directory, preserving package structure.
     *
     * @param test      the generated test
     * @param targetRoot  e.g. /project/src/test/java
     * @param overwrite   if false, skips files that already exist
     * @return true if written, false if skipped
     */
    public boolean write(GeneratedTest test, Path targetRoot, boolean overwrite) throws IOException {
        Path packageDir = targetRoot.resolve(test.packageName().replace(".", "/"));
        Files.createDirectories(packageDir);

        Path targetFile = packageDir.resolve(test.fileName());

        if (!overwrite && Files.exists(targetFile)) {
            log.info("Skipping (already exists): {}", targetFile);
            return false;
        }

        Files.writeString(targetFile, test.content(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Written: {}", targetFile);
        return true;
    }

    /**
     * Dry-run preview — returns the target path without writing anything.
     */
    public Path resolveTargetPath(GeneratedTest test, Path targetRoot) {
        return targetRoot
                .resolve(test.packageName().replace(".", "/"))
                .resolve(test.fileName());
    }
}
