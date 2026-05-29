package com.testgen.generator;

import com.testgen.camel.CamelRouteMetadata;
import com.testgen.camel.CamelXmlRouteParser;
import com.testgen.classifier.ClassClassifier;
import com.testgen.classifier.ClassType;
import com.testgen.generator.builder.DataBuilderGenerator;
import com.testgen.generator.strategy.*;
import com.testgen.parser.ClassMetadata;
import com.testgen.parser.JavaClassParser;
import com.testgen.report.GenerationReport;
import com.testgen.scanner.FileScanner;
import com.testgen.writer.TestFileWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * Orchestrates the full scan → parse → classify → generate → write pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestOrchestrator {

    private final FileScanner fileScanner;
    private final JavaClassParser classParser;
    private final ClassClassifier classifier;
    private final CamelXmlRouteParser xmlRouteParser;
    private final DataBuilderGenerator dataBuilderGenerator;
    private final TestFileWriter fileWriter;

    /**
     * Full generation run.
     *
     * @param sourceRoot       path to src/main/java of the target project
     * @param targetRoot       path to src/test/java of the target project
     * @param overwrite        whether to overwrite existing test files
     * @param includePatterns  package / class name substrings to include (empty = all)
     * @param excludePatterns  package / class name substrings to exclude
     * @param convention       test method naming convention
     * @param dryRun           if true, compute everything but do not write files
     * @param inheritanceDepth max levels of parent chain to stub (0 = direct parent only)
     * @param progressCallback called with (currentFile, totalFiles) as each file is processed
     */
    public GenerationReport generate(
            Path sourceRoot,
            Path targetRoot,
            boolean overwrite,
            List<String> includePatterns,
            List<String> excludePatterns,
            NamingConvention convention,
            boolean dryRun,
            int inheritanceDepth,
            BiConsumer<Integer, Integer> progressCallback) {

        GenerationReport.GenerationReportBuilder report = GenerationReport.builder()
                .generatedAt(LocalDateTime.now())
                .sourcePath(sourceRoot.toString())
                .targetPath(targetRoot.toString());

        // Detect Spring Boot version from target project pom.xml
        String springBootVersion = detectSpringBootVersion(sourceRoot);

        // Parse all XML Camel routes from src/main/resources
        Path resourcesRoot = sourceRoot.getParent().resolve("resources");
        List<CamelRouteMetadata> xmlRoutes = xmlRouteParser.parseXmlRoutes(resourcesRoot);

        // Scan Java files
        List<Path> javaFiles = fileScanner.scanJavaFiles(sourceRoot, includePatterns, excludePatterns);
        int total = javaFiles.size();
        report.totalScanned(total);

        int generated = 0, skipped = 0, failed = 0;
        List<String> generatedFiles = new ArrayList<>();
        List<String> skippedFiles   = new ArrayList<>();
        Map<String, String> failedFiles = new LinkedHashMap<>();

        for (int idx = 0; idx < total; idx++) {
            Path javaFile = javaFiles.get(idx);
            if (progressCallback != null) progressCallback.accept(idx + 1, total);

            try {
                Optional<ClassMetadata> parsed = classParser.parse(javaFile);
                if (parsed.isEmpty()) {
                    skipped++;
                    skippedFiles.add(javaFile.toString());
                    continue;
                }

                ClassMetadata meta = classifier.classify(parsed.get())
                        .withSpringBootVersion(springBootVersion);

                TestStrategy strategy = pickStrategy(meta, xmlRoutes);
                List<GeneratedTest> tests = strategy.generate(meta, convention);

                // Also generate TestData
                tests = new ArrayList<>(tests);
                tests.add(dataBuilderGenerator.generate(meta));

                for (GeneratedTest test : tests) {
                    if (dryRun) {
                        Path preview = fileWriter.resolveTargetPath(test, targetRoot);
                        generatedFiles.add("[DRY-RUN] " + preview);
                        generated++;
                    } else {
                        boolean written = fileWriter.write(test, targetRoot, overwrite);
                        if (written) {
                            generatedFiles.add(fileWriter.resolveTargetPath(test, targetRoot).toString());
                            generated++;
                        } else {
                            skipped++;
                            skippedFiles.add(fileWriter.resolveTargetPath(test, targetRoot).toString());
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Failed to process {}: {}", javaFile, e.getMessage(), e);
                failed++;
                failedFiles.put(javaFile.toString(), e.getMessage());
            }
        }

        return report
                .totalGenerated(generated)
                .totalSkipped(skipped)
                .totalFailed(failed)
                .generatedFiles(generatedFiles)
                .skippedFiles(skippedFiles)
                .failedFiles(failedFiles)
                .build();
    }

    /** Convenience overload with defaults. */
    public GenerationReport generate(Path sourceRoot, Path targetRoot, boolean overwrite) {
        return generate(sourceRoot, targetRoot, overwrite,
                List.of(), List.of(), NamingConvention.TEST_METHOD_SCENARIO,
                false, 1, null);
    }

    private TestStrategy pickStrategy(ClassMetadata m, List<CamelRouteMetadata> xmlRoutes) {
        return switch (m.classType()) {
            case SERVICE                          -> new ServiceTestStrategy();
            case CONTROLLER, REST_CONTROLLER      -> new ControllerTestStrategy();
            case REPOSITORY                       -> new RepositoryTestStrategy();
            case CAMEL_ROUTE                      -> new CamelRouteTestStrategy(xmlRoutes);
            case COMPONENT                        -> new ComponentTestStrategy();
            default                               -> new DefaultTestStrategy();
        };
    }

    private String detectSpringBootVersion(Path sourceRoot) {
        // Walk up from src/main/java to find pom.xml
        Path current = sourceRoot;
        for (int i = 0; i < 5; i++) {
            current = current.getParent();
            if (current == null) break;
            Path pom = current.resolve("pom.xml");
            if (Files.exists(pom)) {
                try (InputStream is = Files.newInputStream(pom)) {
                    Document doc = DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(is);
                    NodeList versions = doc.getElementsByTagName("version");
                    // Look for spring-boot-starter-parent version
                    NodeList parents = doc.getElementsByTagName("parent");
                    if (parents.getLength() > 0) {
                        Element parent = (Element) parents.item(0);
                        NodeList artifactIds = parent.getElementsByTagName("artifactId");
                        if (artifactIds.getLength() > 0
                                && artifactIds.item(0).getTextContent().contains("spring-boot")) {
                            NodeList v = parent.getElementsByTagName("version");
                            if (v.getLength() > 0) return v.item(0).getTextContent();
                        }
                    }
                } catch (Exception e) {
                    log.warn("Could not read pom.xml at {}: {}", pom, e.getMessage());
                }
            }
        }
        return "3.x";
    }
}
