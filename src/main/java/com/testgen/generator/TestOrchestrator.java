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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
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

                // Detect @Entity types instantiated inline via new X() — use MockedConstruction in tests
                meta = meta.withEntityConstructions(resolveEntityConstructions(meta, fileIndex));

                // Detect ApplicationContext.getBean(X.class) repo/DAO lookups (separate from makeDAO)
                meta = meta.withAppContextRepos(resolveAppContextRepos(meta, fileIndex));

                // Resolve static method return types for type-aware mock stubs
                meta = meta.withResolvedStaticTypes(resolveStaticReturnTypes(meta, fileIndex));

                // Detect @Repository types obtained via service-locator cast — add mocks + verify stubs
                meta = meta.withServiceLocatorRepos(resolveServiceLocatorRepos(meta, fileIndex));

                TestStrategy strategy = pickStrategy(meta, xmlRoutes);
                List<GeneratedTest> tests = new ArrayList<>(strategy.generate(meta, convention));

                // Generate TestData for the class under test
                tests.add(dataBuilderGenerator.generate(meta));

                // Also generate TestData for every domain type referenced in method params / return types
                // so the generated tests compile without missing XxxTestData references
                generateDependentTestData(meta, tests, javaFiles, fileIndex);

                // Generate TestData for @Repository method return entity types
                // (TPIBCasCounter returned by findByInternalId etc.) to cover all code paths
                generateRepoReturnTestData(meta, tests, javaFiles, fileIndex);

                // Cascade: generate TestData for domain object FIELD types within already-generated
                // TestData classes (e.g. FTBaseVO has CoolingPeriodVO field →
                // CoolingPeriodVOTestData must be generated so FTBaseVOTestData compiles)
                Set<String> cascadeGenerated = new HashSet<>();
                cascadeGenerated.add(meta.className()); // don't re-generate the main class
                generateFieldTypeTestData(meta, tests, fileIndex, cascadeGenerated);

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

    // ── Parent chain resolution ─────────────────────────────────────────────

    /**
     * Resolves the parent chain.
     *
     * Priority per level:
     *  1. Source file in fileIndex  → JavaParser (full fidelity)
     *  2. Source not found          → reflection via Class.forName (framework/library classes)
     *  3. Neither resolvable        → stop, warn
     *
     * depth=0 → 1 level (direct parent); depth=N → N+1 levels
     */
    private List<ClassMetadata> resolveParentChain(ClassMetadata m,
                                                    Map<String, Path> fileIndex,
                                                    int depth) {
        List<ClassMetadata> chain = new ArrayList<>();
        ClassMetadata current = m;
        for (int level = 0; level <= depth; level++) {
            if (!current.hasSuperClass()) break;
            String parentName = current.superClassName();

            // 1. Source file in project
            Path parentFile = fileIndex.get(parentName);
            if (parentFile != null) {
                Optional<ClassMetadata> parsed = classParser.parse(parentFile);
                if (parsed.isPresent()) {
                    ClassMetadata parent = classifier.classify(parsed.get());
                    chain.add(parent);
                    current = parent;
                    continue;
                }
            }

            // 2. Framework/library class — resolve via reflection
            ClassMetadata reflected = resolveViaReflection(parentName, current.imports());
            if (reflected != null) {
                chain.add(reflected);
                current = reflected;
                continue;
            }

            // 3. Unresolvable
            log.warn("Parent '{}' of '{}' not found in source root or classpath",
                    parentName, m.className());
            break;
        }
        return chain;
    }

    /**
     * Resolves a class from the classpath via reflection when its source is not
     * in the project source root (e.g. Spring's RouteBuilder, HttpServlet, etc.).
     *
     * FQN lookup order:
     *  a) Scan the child class's imports for an entry ending with ".SimpleName"
     *  b) Try java.lang.SimpleName as fallback
     */
    private ClassMetadata resolveViaReflection(String simpleName, List<String> imports) {
        String fqn = imports.stream()
                .filter(imp -> imp.endsWith("." + simpleName))
                .findFirst()
                .orElseGet(() -> "java.lang." + simpleName);
        try {
            Class<?> clazz = Class.forName(fqn, false,
                    Thread.currentThread().getContextClassLoader());
            log.info("Resolved parent '{}' via reflection ({})", simpleName, fqn);
            return buildMetadataFromReflection(clazz);
        } catch (ClassNotFoundException e) {
            log.debug("Parent '{}' ({}) not on classpath — skipping reflection resolution", simpleName, fqn);
            return null;
        }
    }

    /**
     * Converts a reflected Class into a minimal ClassMetadata with method signatures.
     * Fields are not populated (not needed for stub generation).
     */
    private ClassMetadata buildMetadataFromReflection(Class<?> clazz) {
        List<MethodMetadata> methods = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            int mod = method.getModifiers();
            if (Modifier.isStatic(mod) || method.isSynthetic() || method.isBridge()) continue;
            if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) continue;

            // Use generic type info where available to preserve type parameters
            List<MethodMetadata.ParameterMetadata> params = new ArrayList<>();
            java.lang.reflect.Type[] genericParamTypes = method.getGenericParameterTypes();
            java.lang.reflect.Parameter[] rawParams = method.getParameters();
            for (int pi = 0; pi < rawParams.length; pi++) {
                String typeName = pi < genericParamTypes.length
                        ? simplifyReflectedType(genericParamTypes[pi])
                        : rawParams[pi].getType().getSimpleName();
                params.add(new MethodMetadata.ParameterMetadata(typeName, rawParams[pi].getName()));
            }
            // Exact throws clause from reflection
            List<String> thrown = Arrays.stream(method.getGenericExceptionTypes())
                    .map(this::simplifyReflectedType)
                    .toList();

            // Use generic return type for exact signature (List<X>, Optional<X>, etc.)
            String returnTypeName = simplifyReflectedType(method.getGenericReturnType());
            methods.add(new MethodMetadata(
                    method.getName(),
                    returnTypeName,
                    params, thrown, List.of(),
                    Modifier.isPublic(mod), Modifier.isProtected(mod),
                    false, Modifier.isAbstract(mod), Modifier.isFinal(mod),
                    false, false,
                    List.of(), List.of(), List.of(), List.of(), false, false, false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
            ));
        }

        String superName = clazz.getSuperclass() != null
                && !clazz.getSuperclass().equals(Object.class)
                ? clazz.getSuperclass().getSimpleName() : null;

        return new ClassMetadata(
                clazz.getSimpleName(), clazz.getPackageName(),
                "[classpath:" + clazz.getName() + "]",
                ClassType.POJO, List.of(), List.of(), methods, List.of(),
                superName, List.of(),
                Modifier.isAbstract(clazz.getModifiers()), clazz.isInterface(),
                false, false, List.of(), null,
                List.of(), List.of(), Set.of(), Map.of(), Set.of(), List.of(), Map.of(), List.of()
        );
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

    // ── Dependent TestData generation ──────────────────────────────────────

    /**
     * For every domain type in the class's paramTypeRegistry that originated from
     * a project source file (not a reflection-resolved framework class), generates
     * a companion TestData file so the generated test compiles without missing
     * XxxTestData references.
     *
     * Skips:
     *  - Types from the classpath/reflection (sourceFilePath starts with "[classpath:")
     *  - Types already being processed in the main scan loop (avoids duplicates)
     *  - Types whose TestData file was already added in a previous dep expansion
     */
    private void generateDependentTestData(ClassMetadata meta,
                                            List<GeneratedTest> tests,
                                            List<Path> alreadyScanned) {
        generateDependentTestData(meta, tests, alreadyScanned, null);
    }

    private void generateDependentTestData(ClassMetadata meta,
                                            List<GeneratedTest> tests,
                                            List<Path> alreadyScanned,
                                            Map<String, Path> fileIndexForCascade) {
        if (meta.paramTypeRegistry() == null || meta.paramTypeRegistry().isEmpty()) return;

        // Build a set of class names already covered by the main scan
        Set<String> alreadyScannedNames = alreadyScanned.stream()
                .map(p -> p.getFileName().toString().replace(".java", ""))
                .collect(Collectors.toSet());

        // Track TestData file names already added to this batch (within same class's deps)
        Set<String> addedTestDataFiles = tests.stream()
                .map(GeneratedTest::fileName)
                .collect(Collectors.toSet());

        for (Map.Entry<String, com.testgen.parser.ClassMetadata> entry
                : meta.paramTypeRegistry().entrySet()) {

            com.testgen.parser.ClassMetadata depMeta = entry.getValue();

            // Skip reflection-resolved framework/library types
            if (depMeta.sourceFilePath().startsWith("[classpath:")) continue;

            // Skip if this type will be (or was) processed in the main scan loop
            if (alreadyScannedNames.contains(depMeta.className())) continue;

            // Skip interfaces, abstract classes and @Repository/@Service types —
            // these cannot be instantiated; they need mock(), not TestData
            if (depMeta.isInterface() || depMeta.isAbstract()) continue;
            boolean isSpringComponent = depMeta.annotations().stream()
                    .anyMatch(a -> a.equals("Repository") || a.equals("Service") || a.equals("Component"));
            if (isSpringComponent) continue;

            // Skip if TestData was already added (e.g. from a previous dep in same class)
            String testDataFileName = depMeta.className() + "TestData.java";
            if (addedTestDataFiles.contains(testDataFileName)) continue;

            // Place companion TestData in the SAME package as the class under test
            // so it is generated alongside ClassATest.java, not in the dependency's package.
            // Also inherit the owning class's import list so FQN resolution works correctly
            // (avoids same-simple-name ambiguity across packages).
            com.testgen.parser.ClassMetadata enriched = depMeta
                    .withConcreteClassNames(meta.concreteClassNames())
                    .withPackageName(meta.packageName())   // ← same package as ClassA
                    .withImports(meta.imports());           // ← ClassA's imports as FQN authority

            tests.add(dataBuilderGenerator.generate(enriched));
            addedTestDataFiles.add(testDataFileName);
            log.info("Generated companion TestData for {} in package {}",
                    depMeta.className(), meta.packageName());

            // Cascade: generate TestData for domain object fields within this dep type
            if (fileIndexForCascade != null) {
                Set<String> depVisited = new HashSet<>();
                depVisited.add(meta.className());
                depVisited.add(depMeta.className());
                generateFieldTypeTestData(enriched, tests, fileIndexForCascade, depVisited);
            }
        }
    }

    // ── Static method return type resolution ───────────────────────────────

    /**
     * For each static call token "ClassName.methodName:argCount" detected in the class,
     * looks up the static class source and resolves the method's return type.
     * Result: "ClassName.methodName" → "ReturnType" for type-aware mock stub generation.
     */
    private Map<String, String> resolveStaticReturnTypes(ClassMetadata m, Map<String, Path> fileIndex) {
        Map<String, String> result = new HashMap<>();
        m.methods().stream()
                .filter(mm -> mm.staticCallTokens() != null)
                .flatMap(mm -> mm.staticCallTokens().stream())
                .distinct()
                .forEach(token -> {
                    // token format: "ClassName.methodName:argCount"
                    int dotIdx   = token.indexOf('.');
                    int colonIdx = token.lastIndexOf(':');
                    if (dotIdx < 0 || colonIdx < 0) return;

                    String className  = token.substring(0, dotIdx);
                    String methodName = token.substring(dotIdx + 1, colonIdx);
                    int argCount      = Integer.parseInt(token.substring(colonIdx + 1));
                    String key        = className + "." + methodName;
                    if (result.containsKey(key)) return;

                    Path srcFile = fileIndex.get(className);
                    if (srcFile == null) return;
                    try {
                        com.github.javaparser.ast.CompilationUnit cu =
                                com.github.javaparser.StaticJavaParser.parse(srcFile);
                        cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                .ifPresent(cls -> cls.getMethods().stream()
                                        .filter(md -> md.getNameAsString().equals(methodName))
                                        .filter(md -> md.getParameters().size() == argCount
                                                || md.isVariableArityMethod())
                                        .findFirst()
                                        .ifPresent(md -> result.put(key, md.getTypeAsString())));
                    } catch (Exception ignored) {}
                });
        return result.isEmpty() ? Map.of() : result;
    }

    // ── Entity construction detection ──────────────────────────────────────

    /**
     * Resolves which types instantiated via new X() inside the class's methods
     * are JPA @Entity / @Table classes. These need MockedConstruction in tests.
     */
    private Set<String> resolveEntityConstructions(ClassMetadata m, Map<String, Path> fileIndex) {
        Set<String> entities = new HashSet<>();
        m.methods().stream()
                .filter(mm -> mm.constructedTypes() != null)
                .flatMap(mm -> mm.constructedTypes().stream())
                .distinct()
                .forEach(typeName -> {
                    Path srcFile = fileIndex.get(typeName);
                    if (srcFile == null) return;
                    try {
                        com.github.javaparser.ast.CompilationUnit cu =
                                com.github.javaparser.StaticJavaParser.parse(srcFile);
                        cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                .ifPresent(cls -> {
                                    boolean isEntity = cls.getAnnotations().stream()
                                            .map(a -> a.getNameAsString())
                                            .anyMatch(a -> a.equals("Entity") || a.equals("Table"));
                                    if (isEntity) entities.add(typeName);
                                });
                    } catch (Exception ignored) {}
                });
        return entities.isEmpty() ? Set.of() : Collections.unmodifiableSet(entities);
    }

    // ── Reflection type name helpers ───────────────────────────────────────

    /**
     * Converts a reflected generic type to a simple readable name.
     * e.g. java.util.List<java.lang.String> → List<String>
     *      java.util.Optional<com.example.FTBaseVO> → Optional<FTBaseVO>
     */
    private String simplifyReflectedType(java.lang.reflect.Type type) {
        if (type instanceof Class<?> cls) {
            return cls.getSimpleName();
        }
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            String raw = ((Class<?>) pt.getRawType()).getSimpleName();
            String args = Arrays.stream(pt.getActualTypeArguments())
                    .map(this::simplifyReflectedType)
                    .collect(Collectors.joining(", "));
            return raw + "<" + args + ">";
        }
        if (type instanceof java.lang.reflect.WildcardType) return "?";
        if (type instanceof java.lang.reflect.TypeVariable) return "Object";
        return type.getTypeName().replaceAll("[a-z]+\\.", ""); // strip package prefixes
    }

    // ── Field-type cascade TestData generation ─────────────────────────────

    /**
     * Cascades TestData generation to domain object types used as FIELDS in the given class.
     *
     * Problem: FTBaseVO has 'private CoolingPeriodVO ftcoolingperiod'.
     * FTBaseVOTestData references CoolingPeriodVOTestData.buildValidCoolingPeriodVO()
     * but CoolingPeriodVOTestData is never generated → compile error.
     *
     * Solution: walk each non-static, non-injected field type; if the type exists in
     * the source root and is a concrete class, generate its TestData and recurse.
     * A visited set prevents infinite loops from circular VO references.
     */
    private void generateFieldTypeTestData(ClassMetadata meta,
                                            List<GeneratedTest> tests,
                                            Map<String, Path> fileIndex,
                                            Set<String> visited) {
        for (com.testgen.parser.FieldMetadata f : meta.fields()) {
            if (f.isStatic() || f.isApplicationContext() || f.isValue()) continue;

            String rawType = f.simpleType();
            if (rawType.isEmpty() || !Character.isUpperCase(rawType.charAt(0))) continue;
            if (!visited.add(rawType)) continue; // already processed — prevents circular loops

            // Skip if TestData already queued
            String testDataFile = rawType + "TestData.java";
            if (tests.stream().anyMatch(t -> t.fileName().equals(testDataFile))) continue;

            Path srcFile = fileIndex.get(rawType);
            if (srcFile == null) continue; // external type — can't generate

            classParser.parse(srcFile).ifPresent(parsed -> {
                // Skip interfaces, abstract classes, Spring components
                if (parsed.isInterface() || parsed.isAbstract()) return;
                if (parsed.annotations().stream().anyMatch(a ->
                        a.equals("Repository") || a.equals("Service") || a.equals("Component"))) return;

                ClassMetadata enriched = classifier.classify(parsed)
                        .withConcreteClassNames(meta.concreteClassNames())
                        .withParamTypeRegistry(meta.paramTypeRegistry())
                        .withPackageName(meta.packageName())   // co-locate with test
                        .withImports(meta.imports());           // ClassA's FQN authority

                tests.add(dataBuilderGenerator.generate(enriched));
                log.info("Generated cascade TestData for field type: {} (from {})",
                        rawType, meta.className());

                // Recurse for this type's own domain object fields
                generateFieldTypeTestData(enriched, tests, fileIndex, visited);
            });
        }
    }

    // ── Repo return type TestData generation ───────────────────────────────

    /**
     * For every method return type of a service-locator @Repository that exists in
     * the project source root, generates a companion TestData file.
     *
     * Example: TPIBCasCounterRepo.findByInternalId() returns TPIBCasCounter (@Entity).
     * → Generates TPIBCasCounterTestData.java alongside the test, with:
     *     buildValidTPIBCasCounter()   — all fields set with typed defaults
     *     buildInvalidTPIBCasCounter() — constraint violations
     *   so when(repo.find()).thenReturn(TPIBCasCounterTestData.buildValidTPIBCasCounter())
     *   gives a realistic object whose fields can be asserted on.
     */
    private void generateRepoReturnTestData(ClassMetadata meta,
                                             List<GeneratedTest> tests,
                                             List<Path> alreadyScanned,
                                             Map<String, Path> fileIndex) {
        if (!meta.hasServiceLocatorRepos()) return;

        Set<String> alreadyScannedNames = alreadyScanned.stream()
                .map(p -> p.getFileName().toString().replace(".java", ""))
                .collect(Collectors.toSet());

        Set<String> addedFiles = tests.stream()
                .map(GeneratedTest::fileName)
                .collect(Collectors.toSet());

        meta.serviceLocatorRepos().forEach(sla ->
            sla.repoCalls().forEach(call -> {
                if (call.returnType() == null) return;

                // Extract entity class name from return type (strips List<>, Optional<>, etc.)
                String entityType;
                String rt = call.returnType();
                if (rt.contains("<")) {
                    entityType = rt.substring(rt.indexOf('<') + 1, rt.lastIndexOf('>'))
                                   .replaceAll("<.*>", "").trim();
                } else {
                    entityType = rt.replaceAll("<.*>", "").trim();
                }

                if (entityType.isEmpty() || !Character.isUpperCase(entityType.charAt(0))) return;
                if (alreadyScannedNames.contains(entityType)) return;

                String testDataFile = entityType + "TestData.java";
                if (!addedFiles.add(testDataFile)) return; // already added

                Path srcFile = fileIndex.get(entityType);
                if (srcFile == null) return; // external type — can't generate

                classParser.parse(srcFile).ifPresent(parsed -> {
                    // Skip interfaces, abstract classes, and @Repository types —
                    // these cannot be instantiated with new() or have setters called.
                    // repoReturnValue() already handles them with mock(Type.class).
                    if (parsed.isInterface() || parsed.isAbstract()) {
                        log.debug("Skipping TestData for {}: is interface or abstract", entityType);
                        return;
                    }
                    boolean isRepo = parsed.annotations().stream()
                            .anyMatch(a -> a.equals("Repository") || a.equals("Component")
                                       || a.equals("Service"));
                    if (isRepo) {
                        log.debug("Skipping TestData for {}: is @Repository/@Component/@Service", entityType);
                        return;
                    }
                    ClassMetadata enriched = classifier.classify(parsed)
                            .withConcreteClassNames(meta.concreteClassNames())
                            .withPackageName(meta.packageName())
                            .withImports(meta.imports());
                    tests.add(dataBuilderGenerator.generate(enriched));
                    log.info("Generated TestData for repo return type: {} (from {})",
                            entityType, sla.repoType());
                });
            })
        );
    }

    // ── ApplicationContext.getBean repo resolution ──────────────────────────

    /**
     * Detects repos/DAOs obtained via ApplicationContext.getBean(X.class) or
     * getBean("name", X.class). These are separate from makeDAO-based service locators
     * and need their own @Mock fields injected differently.
     *
     * Pattern detected: context.getBean(TPIBFTPayeeRepo.class) → repo type = TPIBFTPayeeRepo
     */
    private List<com.testgen.parser.ServiceLocatorAccess> resolveAppContextRepos(
            ClassMetadata m, Map<String, Path> fileIndex) {
        List<com.testgen.parser.ServiceLocatorAccess> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        m.methods().stream()
                .filter(mm -> mm.getBeanCallTypes() != null)
                .forEach(mm -> mm.getBeanCallTypes().forEach(typeName -> {
                    if (!seen.add(typeName)) return;
                    Path srcFile = fileIndex.get(typeName);
                    if (srcFile == null) return;
                    try {
                        com.github.javaparser.ast.CompilationUnit cu =
                                com.github.javaparser.StaticJavaParser.parse(srcFile);
                        cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                .ifPresent(cls -> {
                                    boolean isRepo = cls.getAnnotations().stream()
                                            .map(a -> a.getNameAsString())
                                            .anyMatch(a -> a.equals("Repository") || a.equals("Component"));
                                    if (!isRepo) return;

                                    // Detect which method calls getBean for this type
                                    String locator = mm.helperMethodCalls().stream()
                                            .findFirst().orElse("applicationContext");

                                    List<com.testgen.parser.ServiceLocatorAccess.RepoCall> calls =
                                            resolveRepoCalls(typeName, mm, cls);
                                    result.add(new com.testgen.parser.ServiceLocatorAccess(
                                            typeName, locator,
                                            com.testgen.parser.ServiceLocatorAccess.toFieldName(typeName),
                                            calls));
                                });
                    } catch (Exception ignored) {}
                }));
        return result;
    }

    // ── Service locator repo resolution ────────────────────────────────────

    /**
     * Finds @Repository types obtained via service-locator casts in method bodies:
     *   TPIBFTPayeeRepo repo = (TPIBFTPayeeRepo) makeDAO(BEAN_ID)
     *
     * For each cast-to type that is @Repository-annotated, records:
     *  - the repo simple class name
     *  - the service-locator method name (from the CastExpr's sub-expression)
     */
    private List<com.testgen.parser.ServiceLocatorAccess> resolveServiceLocatorRepos(
            ClassMetadata m, Map<String, Path> fileIndex) {

        List<com.testgen.parser.ServiceLocatorAccess> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Also detect wrapper methods: getPayeeRepo() { return (TPIBFTPayeeRepo) makeDAO(...) }
        // If a helper method in the parent chain returns a @Repository type, use that specific type
        Set<String> calledViaHelper = m.methods().stream()
                .filter(MethodMetadata::isTestable)
                .flatMap(mm -> mm.helperMethodCalls().stream())
                .collect(Collectors.toSet());
        if (m.hasParentChain()) {
            for (ClassMetadata parent : m.parentChain()) {
                for (MethodMetadata pm : parent.methods()) {
                    if (!calledViaHelper.contains(pm.name())) continue;
                    if (!pm.hasReturnValue()) continue;
                    String retType = pm.returnType().replaceAll("<.*>", "").trim();
                    if (retType.isEmpty() || !Character.isUpperCase(retType.charAt(0))) continue;
                    // If this wrapper returns a specific type (not Object/void), add to castToTypes pool
                    Path srcFile = fileIndex.get(retType);
                    if (srcFile != null) {
                        try {
                            com.github.javaparser.ast.CompilationUnit cu =
                                    com.github.javaparser.StaticJavaParser.parse(srcFile);
                            boolean isRepo = cu.findFirst(
                                    com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                    .map(cls -> cls.getAnnotations().stream()
                                            .anyMatch(a -> a.getNameAsString().equals("Repository")))
                                    .orElse(false);
                            if (isRepo && seen.add(retType)) {
                                result.add(new com.testgen.parser.ServiceLocatorAccess(
                                        retType, pm.name(),
                                        com.testgen.parser.ServiceLocatorAccess.toFieldName(retType),
                                        List.of()));
                                log.info("Detected specific repo type {} from wrapper method {}()",
                                        retType, pm.name());
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        m.methods().stream()
                .filter(mm -> mm.castToTypes() != null)
                .forEach(mm -> mm.castToTypes().forEach(typeName -> {
                    if (!seen.add(typeName)) return;
                    Path srcFile = fileIndex.get(typeName);
                    if (srcFile == null) return;
                    try {
                        com.github.javaparser.ast.CompilationUnit cu =
                                com.github.javaparser.StaticJavaParser.parse(srcFile);
                        cu.findFirst(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                .ifPresent(cls -> {
                                    boolean isRepo = cls.getAnnotations().stream()
                                            .map(a -> a.getNameAsString())
                                            .anyMatch(a -> a.equals("Repository"));
                                    if (!isRepo) return;

                                    // Service-locator method name
                                    String locator = mm.helperMethodCalls().stream()
                                            .filter(call -> !call.equals(mm.name()))
                                            .findFirst()
                                            .orElse("makeDAO");

                                    // Resolve method calls on this repo from the tokens
                                    // Token format: "RepoType|methodName|argCount"
                                    List<com.testgen.parser.ServiceLocatorAccess.RepoCall> repoCalls =
                                            resolveRepoCalls(typeName, mm, cls);

                                    result.add(new com.testgen.parser.ServiceLocatorAccess(
                                            typeName, locator,
                                            com.testgen.parser.ServiceLocatorAccess.toFieldName(typeName),
                                            repoCalls));
                                });
                    } catch (Exception ignored) {}
                }));
        return result;
    }

    /**
     * For each repoMethodCallToken of format "RepoType|methodName|argCount",
     * looks up the actual method signature from the interface's source to get
     * parameter types and return type for accurate when(...).thenReturn(...) generation.
     */
    private List<com.testgen.parser.ServiceLocatorAccess.RepoCall> resolveRepoCalls(
            String repoType,
            MethodMetadata mm,
            com.github.javaparser.ast.body.ClassOrInterfaceDeclaration ifaceCls) {

        if (mm.repoMethodCallTokens() == null) return List.of();

        List<com.testgen.parser.ServiceLocatorAccess.RepoCall> calls = new ArrayList<>();

        for (String token : mm.repoMethodCallTokens()) {
            String[] parts = token.split("\\|");
            if (parts.length < 3 || !parts[0].equals(repoType)) continue;
            String methodName = parts[1];
            int argCount      = Integer.parseInt(parts[2]);

            // Look up actual method in the interface to get param + return types
            ifaceCls.getMethods().stream()
                    .filter(md -> md.getNameAsString().equals(methodName))
                    .filter(md -> md.getParameters().size() == argCount)
                    .findFirst()
                    .ifPresentOrElse(
                        md -> {
                            List<MethodMetadata.ParameterMetadata> params =
                                    md.getParameters().stream()
                                      .map(p -> new MethodMetadata.ParameterMetadata(
                                              p.getTypeAsString(), p.getNameAsString()))
                                      .toList();
                            calls.add(new com.testgen.parser.ServiceLocatorAccess.RepoCall(
                                    methodName, params, md.getTypeAsString()));
                        },
                        () -> {
                            // Method not found in interface — use any() placeholders
                            List<MethodMetadata.ParameterMetadata> params =
                                    java.util.Collections.nCopies(argCount,
                                            new MethodMetadata.ParameterMetadata("Object", "arg"));
                            calls.add(new com.testgen.parser.ServiceLocatorAccess.RepoCall(
                                    methodName, params, "Object"));
                        }
                    );
        }
        return calls;
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
