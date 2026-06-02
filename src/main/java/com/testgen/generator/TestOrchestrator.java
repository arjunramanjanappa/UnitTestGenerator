package com.testgen.generator;

import com.testgen.camel.CamelRouteMetadata;
import com.testgen.camel.CamelXmlRouteParser;
import com.testgen.classifier.ClassClassifier;
import com.testgen.classifier.ClassType;
import com.testgen.generator.builder.DataBuilderGenerator;
import com.testgen.generator.strategy.*;
import com.testgen.parser.ClassMetadata;
import com.testgen.parser.JavaClassParser;
import com.testgen.parser.MethodMetadata;
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
import java.util.stream.Collectors;

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
     * @param inheritanceDepth max levels of parent chain to stub (0 = none, 1 = direct parent only)
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

        String springBootVersion = detectSpringBootVersion(sourceRoot);

        Path resourcesRoot = sourceRoot.getParent().resolve("resources");
        List<CamelRouteMetadata> xmlRoutes = xmlRouteParser.parseXmlRoutes(resourcesRoot);

        List<Path> javaFiles = fileScanner.scanJavaFiles(sourceRoot, includePatterns, excludePatterns);
        int total = javaFiles.size();
        report.totalScanned(total);

        // ── Pre-pass: build className → Path index for parent + interface resolution ──
        Map<String, Path> fileIndex = buildFileIndex(sourceRoot);

        // ── Pre-pass: determine concrete (non-interface, non-abstract) class names ──
        Set<String> concreteClassNames = resolveConcreteClassNames(fileIndex);

        // ── Pre-pass: build interface simple-name → Path index ──
        Map<String, Path> interfaceIndex = buildInterfaceIndex(sourceRoot);

        int generated = 0, skipped = 0, failed = 0;
        List<String> generatedFiles  = new ArrayList<>();
        List<String> skippedFiles    = new ArrayList<>();
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

                // Feature 1: resolve parent chain up to inheritanceDepth levels
                List<ClassMetadata> parentChain =
                        resolveParentChain(meta, fileIndex, inheritanceDepth);
                meta = meta.withParentChain(parentChain);

                // Feature 2: resolve interface default methods
                List<MethodMetadata> ifaceDefaults =
                        resolveInterfaceDefaultMethods(meta, interfaceIndex);
                meta = meta.withInterfaceDefaultMethods(ifaceDefaults);

                // Feature 4: attach concrete class name set for @Spy vs @Mock decisions
                meta = meta.withConcreteClassNames(concreteClassNames);

                // Resolve parsed metadata for types used in method params (for typed inline init)
                meta = meta.withParamTypeRegistry(resolveParamTypeRegistry(meta, fileIndex));

                TestStrategy strategy = pickStrategy(meta, xmlRoutes);
                List<GeneratedTest> tests = new ArrayList<>(strategy.generate(meta, convention));
                tests.add(dataBuilderGenerator.generate(meta));

                for (GeneratedTest test : tests) {
                    if (dryRun) {
                        generatedFiles.add("[DRY-RUN] " + fileWriter.resolveTargetPath(test, targetRoot));
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

    // ── File index helpers ──────────────────────────────────────────────────

    /**
     * Walks sourceRoot and builds simpleName → Path for all .java files.
     * Includes both classes and interfaces.
     */
    private Map<String, Path> buildFileIndex(Path sourceRoot) {
        Map<String, Path> index = new HashMap<>();
        try {
            Files.walk(sourceRoot)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString().replace(".java", "");
                        index.put(fileName, p);
                    });
        } catch (Exception e) {
            log.warn("Could not build file index from {}: {}", sourceRoot, e.getMessage());
        }
        return index;
    }

    /**
     * Returns the subset of fileIndex entries that are interfaces.
     */
    private Map<String, Path> buildInterfaceIndex(Path sourceRoot) {
        Map<String, Path> index = new HashMap<>();
        try {
            Files.walk(sourceRoot)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            com.github.javaparser.ast.CompilationUnit cu =
                                    com.github.javaparser.StaticJavaParser.parse(p);
                            cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                    .filter(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration::isInterface)
                                    .ifPresent(iface -> index.put(iface.getNameAsString(), p));
                        } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            log.warn("Could not build interface index from {}: {}", sourceRoot, e.getMessage());
        }
        return index;
    }

    /**
     * Returns the set of simple class names that are concrete (not interface, not abstract).
     * Used to decide @Mock vs @Spy for injected fields.
     */
    private Set<String> resolveConcreteClassNames(Map<String, Path> fileIndex) {
        Set<String> concrete = new HashSet<>();
        for (Map.Entry<String, Path> entry : fileIndex.entrySet()) {
            try {
                com.github.javaparser.ast.CompilationUnit cu =
                        com.github.javaparser.StaticJavaParser.parse(entry.getValue());
                cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                        .filter(c -> !c.isInterface() && !c.isAbstract())
                        .ifPresent(c -> concrete.add(c.getNameAsString()));
            } catch (Exception ignored) {}
        }
        return Collections.unmodifiableSet(concrete);
    }

    // ── Parent chain resolution (Feature 1) ────────────────────────────────

    private List<ClassMetadata> resolveParentChain(ClassMetadata m,
                                                    Map<String, Path> fileIndex,
                                                    int depth) {
        List<ClassMetadata> chain = new ArrayList<>();
        ClassMetadata current = m;
        for (int level = 0; level < depth; level++) {
            if (!current.hasSuperClass()) break;
            Path parentFile = fileIndex.get(current.superClassName());
            if (parentFile == null) break;

            Optional<ClassMetadata> parsed = classParser.parse(parentFile);
            if (parsed.isEmpty()) break;

            ClassMetadata parent = classifier.classify(parsed.get());
            chain.add(parent);
            current = parent;
        }
        return chain;
    }

    // ── Interface default method resolution (Feature 2) ────────────────────

    private List<MethodMetadata> resolveInterfaceDefaultMethods(ClassMetadata m,
                                                                  Map<String, Path> ifaceIndex) {
        if (m.interfaces().isEmpty()) return List.of();
        return m.interfaces().stream()
                .filter(ifaceIndex::containsKey)
                .flatMap(iface -> classParser.parseInterfaceDefaultMethods(ifaceIndex.get(iface)).stream())
                .collect(Collectors.toList());
    }

    // ── Param type registry (typed inline init for non-TestData types) ──────

    /**
     * For every domain-object type used as a method parameter in this class,
     * tries to find its source file in fileIndex and parse its ClassMetadata.
     * Strategies use this to generate typed field-setter calls instead of null.
     */
    private Map<String, ClassMetadata> resolveParamTypeRegistry(ClassMetadata m,
                                                                  Map<String, Path> fileIndex) {
        Map<String, ClassMetadata> registry = new HashMap<>();
        m.methods().forEach(mm -> mm.parameters().forEach(p -> {
            String rawType = p.type().replaceAll("<.*>", "").trim();
            if (registry.containsKey(rawType)) return;
            Path srcFile = fileIndex.get(rawType);
            if (srcFile == null) return;
            classParser.parse(srcFile).ifPresent(parsed ->
                    registry.put(rawType, classifier.classify(parsed)));
        }));
        return registry.isEmpty() ? Map.of() : registry;
    }

    // ── Strategy selection ──────────────────────────────────────────────────

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

    // ── Spring Boot version detection ───────────────────────────────────────

    private String detectSpringBootVersion(Path sourceRoot) {
        Path current = sourceRoot;
        for (int i = 0; i < 5; i++) {
            current = current.getParent();
            if (current == null) break;
            Path pom = current.resolve("pom.xml");
            if (Files.exists(pom)) {
                try (InputStream is = Files.newInputStream(pom)) {
                    Document doc = DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(is);
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
