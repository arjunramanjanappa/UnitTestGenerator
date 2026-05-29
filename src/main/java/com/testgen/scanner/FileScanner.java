package com.testgen.scanner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
public class FileScanner {

    public List<Path> scanJavaFiles(Path sourceRoot) {
        return scanJavaFiles(sourceRoot, List.of(), List.of());
    }

    public List<Path> scanJavaFiles(Path sourceRoot,
                                    List<String> includePatterns,
                                    List<String> excludePatterns) {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                    .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                    .filter(p -> includePatterns.isEmpty() || matchesAny(p, includePatterns))
                    .filter(p -> !matchesAny(p, excludePatterns))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Failed to scan {}: {}", sourceRoot, e.getMessage());
            return List.of();
        }
    }

    private boolean matchesAny(Path path, List<String> patterns) {
        String normalized = path.toString().replace("\\", "/");
        return patterns.stream()
                .filter(p -> !p.isBlank())
                .anyMatch(pattern -> {
                    String regex = pattern.replace(".", "\\.").replace("*", ".*");
                    return normalized.contains(pattern) || normalized.matches(".*" + regex + ".*");
                });
    }
}
