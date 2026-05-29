package com.testgen.report;

import lombok.Builder;
import lombok.Data;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Data
@Builder
public class GenerationReport {

    private LocalDateTime generatedAt;
    private String sourcePath;
    private String targetPath;
    private int totalScanned;
    private int totalGenerated;
    private int totalSkipped;
    private int totalFailed;

    @Builder.Default
    private List<String> generatedFiles = new ArrayList<>();
    @Builder.Default
    private List<String> skippedFiles = new ArrayList<>();
    @Builder.Default
    private Map<String, String> failedFiles = new LinkedHashMap<>();

    public String toSummaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Unit Test Generator — Report ===\n");
        sb.append("Generated at : ").append(generatedAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("Source       : ").append(sourcePath).append("\n");
        sb.append("Target       : ").append(targetPath).append("\n\n");
        sb.append("Classes Scanned  : ").append(totalScanned).append("\n");
        sb.append("Tests Generated  : ").append(totalGenerated).append("\n");
        sb.append("Skipped          : ").append(totalSkipped).append("\n");
        sb.append("Failed           : ").append(totalFailed).append("\n");

        if (!failedFiles.isEmpty()) {
            sb.append("\n=== Failures ===\n");
            failedFiles.forEach((f, r) ->
                    sb.append("  ").append(f).append("\n    Reason: ").append(r).append("\n"));
        }
        return sb.toString();
    }

    public void exportToFile(Path outputPath) throws IOException {
        Files.writeString(outputPath, toSummaryText());
    }
}
