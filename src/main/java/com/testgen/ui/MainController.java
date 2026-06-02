package com.testgen.ui;

import com.testgen.generator.NamingConvention;
import com.testgen.generator.TestOrchestrator;
import com.testgen.report.GenerationReport;
import com.testgen.scanner.FileScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MainController {

    private final TestOrchestrator orchestrator;
    private final FileScanner fileScanner;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // Holds the last report per session (single-user tool)
    private volatile GenerationReport lastReport;

    // ── Scan ─────────────────────────────────────────────────────────────────

    @PostMapping("/scan")
    public ResponseEntity<ScanResponse> scan(@RequestBody ScanRequest req) {
        // Normalise: accept either a folder path OR a single .java file path
        NormalizedSource src = normalizeSourcePath(req.sourcePath());
        if (src == null) {
            return ResponseEntity.badRequest()
                    .body(new ScanResponse(List.of(), 0,
                            "Path does not exist or is not a .java file / directory: " + req.sourcePath()));
        }
        Path sourceRoot = src.root();

        // If user gave a single file, auto-add the class name as include filter
        List<String> includes = src.singleClassName() != null
                ? List.of(src.singleClassName())
                : nullSafe(req.includePatterns());
        List<String> excludes = nullSafe(req.excludePatterns());

        List<Path> files = fileScanner.scanJavaFiles(sourceRoot, includes, excludes);

        List<ClassEntry> entries = files.stream().map(f -> {
            String relative = sourceRoot.relativize(f).toString()
                    .replace("\\", "/")
                    .replace(".java", "");
            String pkg     = relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')).replace("/", ".") : "";
            String name    = f.getFileName().toString().replace(".java", "");
            return new ClassEntry(name, pkg, f.toString());
        }).toList();

        return ResponseEntity.ok(new ScanResponse(entries, entries.size(), null));
    }

    // ── Generate (SSE stream) ─────────────────────────────────────────────────

    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generate(@RequestBody GenerateRequest req) {
        SseEmitter emitter = new SseEmitter(600_000L);

        executor.submit(() -> {
            try {
                NamingConvention convention = parseConvention(req.namingConvention());

                // Normalise source — accept folder OR single .java file
                NormalizedSource normSrc = normalizeSourcePath(req.sourcePath());
                Path resolvedSourceRoot = normSrc != null ? normSrc.root() : Path.of(req.sourcePath());

                List<String> includes = normSrc != null && normSrc.singleClassName() != null
                        ? List.of(normSrc.singleClassName())
                        : nullSafe(req.includePatterns());

                GenerationReport report = orchestrator.generate(
                        resolvedSourceRoot,
                        Path.of(req.targetPath()),
                        req.overwrite(),
                        includes,
                        nullSafe(req.excludePatterns()),
                        convention,
                        req.dryRun(),
                        req.inheritanceDepth(),
                        (current, total) -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("progress")
                                        .data(Map.of("current", current, "total", total)));
                            } catch (IOException e) {
                                log.warn("SSE progress send failed: {}", e.getMessage());
                            }
                        }
                );

                lastReport = report;

                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(Map.of(
                                "generated", report.getTotalGenerated(),
                                "skipped",   report.getTotalSkipped(),
                                "failed",    report.getTotalFailed(),
                                "scanned",   report.getTotalScanned(),
                                "summary",   report.toSummaryText()
                        )));
                emitter.complete();

            } catch (Exception e) {
                log.error("Generation error: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage())));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody PreviewRequest req) {
        try {
            Path sourceRoot = Path.of(req.sourcePath());
            NamingConvention convention = parseConvention(req.namingConvention());

            // Dry-run a single class: include only the requested class name
            GenerationReport report = orchestrator.generate(
                    sourceRoot,
                    Path.of(System.getProperty("java.io.tmpdir"), "testgen-preview"),
                    true,
                    List.of(req.className()),
                    List.of(),
                    convention,
                    true,
                    1,
                    null
            );

            String previewText = report.getGeneratedFiles().isEmpty()
                    ? "No preview available for: " + req.className()
                    : "Preview — " + report.getTotalGenerated() + " file(s) would be generated:\n\n"
                      + String.join("\n", report.getGeneratedFiles());

            return ResponseEntity.ok(Map.of("content", previewText, "files", report.getGeneratedFiles()));

        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("content", "Preview error: " + e.getMessage(), "files", List.of()));
        }
    }

    // ── Report download ───────────────────────────────────────────────────────

    @GetMapping("/report/download")
    public ResponseEntity<String> downloadReport() {
        if (lastReport == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"test-gen-report.txt\"")
                .contentType(MediaType.TEXT_PLAIN)
                .body(lastReport.toSummaryText());
    }

    @GetMapping("/report")
    public ResponseEntity<Map<String, Object>> getReport() {
        if (lastReport == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(Map.of(
                "summary",   lastReport.toSummaryText(),
                "generated", lastReport.getTotalGenerated(),
                "skipped",   lastReport.getTotalSkipped(),
                "failed",    lastReport.getTotalFailed(),
                "scanned",   lastReport.getTotalScanned()
        ));
    }

    // ── Source path normalisation ─────────────────────────────────────────────

    /**
     * Accepts either a folder path (existing directory) or a single .java file path.
     *
     * Folder: root = the folder as-is, singleClassName = null
     * File  : root = derived java source root (walks up by package depth),
     *         singleClassName = the class simple name (used as include filter)
     *
     * Returns null if the path does not exist or is invalid.
     */
    private NormalizedSource normalizeSourcePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return null;

        Path p = Path.of(rawPath.trim());
        if (!Files.exists(p)) return null;

        if (Files.isDirectory(p)) {
            return new NormalizedSource(p, null);
        }

        // Single .java file
        if (!rawPath.trim().endsWith(".java")) return null;

        String className = p.getFileName().toString().replace(".java", "");
        Path sourceRoot  = deriveSourceRoot(p);
        return new NormalizedSource(sourceRoot, className);
    }

    /**
     * Derives the java source root from a .java file by reading its package declaration
     * and walking up the directory tree by the number of package segments.
     *
     * Example:
     *   file    = /project/src/main/java/com/example/ClassA.java
     *   package = com.example  (2 segments)
     *   root    = /project/src/main/java   (walk up 2 dirs from file's parent)
     */
    private Path deriveSourceRoot(Path javaFile) {
        try {
            // Read just the first few lines to find the package declaration
            String packageName = Files.lines(javaFile)
                    .map(String::trim)
                    .filter(line -> line.startsWith("package "))
                    .findFirst()
                    .map(line -> line.replace("package ", "").replace(";", "").trim())
                    .orElse("");

            if (packageName.isEmpty()) {
                // No package — file is at the root of the source tree
                return javaFile.getParent();
            }

            int depth = packageName.split("\\.").length;
            Path dir = javaFile.getParent();
            for (int i = 0; i < depth; i++) {
                if (dir == null) break;
                dir = dir.getParent();
            }
            return dir != null ? dir : javaFile.getParent();

        } catch (Exception e) {
            log.warn("Could not derive source root from {}: {}", javaFile, e.getMessage());
            return javaFile.getParent();
        }
    }

    private record NormalizedSource(Path root, String singleClassName) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> nullSafe(List<String> list) {
        return list == null ? List.of() : list;
    }

    private NamingConvention parseConvention(String value) {
        if (value == null || value.isBlank()) return NamingConvention.TEST_METHOD_SCENARIO;
        try { return NamingConvention.valueOf(value); }
        catch (IllegalArgumentException e) { return NamingConvention.TEST_METHOD_SCENARIO; }
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ScanRequest(
            String sourcePath,
            List<String> includePatterns,
            List<String> excludePatterns) {}

    public record ScanResponse(
            List<ClassEntry> files,
            int total,
            String error) {}

    public record ClassEntry(
            String name,
            String packageName,
            String fullPath) {}

    public record GenerateRequest(
            String sourcePath,
            String targetPath,
            List<String> includePatterns,
            List<String> excludePatterns,
            String namingConvention,
            int inheritanceDepth,
            boolean overwrite,
            boolean dryRun) {}

    public record PreviewRequest(
            String sourcePath,
            String className,
            String namingConvention) {}
}
