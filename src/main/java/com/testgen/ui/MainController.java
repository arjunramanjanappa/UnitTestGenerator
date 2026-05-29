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
        Path sourceRoot = Path.of(req.sourcePath());
        if (!Files.isDirectory(sourceRoot)) {
            return ResponseEntity.badRequest()
                    .body(new ScanResponse(List.of(), 0, "Source path does not exist: " + req.sourcePath()));
        }

        List<String> includes = nullSafe(req.includePatterns());
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

                GenerationReport report = orchestrator.generate(
                        Path.of(req.sourcePath()),
                        Path.of(req.targetPath()),
                        req.overwrite(),
                        nullSafe(req.includePatterns()),
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
