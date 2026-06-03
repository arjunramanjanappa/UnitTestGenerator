package com.testgen.generator.strategy;

import com.testgen.generator.NamingConvention;
import com.testgen.parser.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * Shared code-generation utilities for all strategies.
 * Each concrete strategy calls these helpers to build test file content.
 */
public abstract class AbstractTestStrategy implements TestStrategy {

    protected NamingConvention convention = NamingConvention.TEST_METHOD_SCENARIO;

    // ── Known AOP / proxy-based annotations ────────────────────────────────
    // These annotations are silently inactive in pure Mockito (Unit) tests.
    // The generator emits warnings in Unit and dedicated stubs in Functional.

    private static final Set<String> SPRING_AOP_ANNOTATIONS = Set.of(
            "Transactional", "Async", "Cacheable", "CacheEvict", "CachePut",
            "CacheConfig", "Scheduled", "EventListener", "Retryable", "Recover"
    );

    private static final Set<String> SECURITY_ANNOTATIONS = Set.of(
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed",
            "PermitAll", "DenyAll"
    );

    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "Validated", "Valid"
    );

    // All well-known annotations — anything else on a method is treated as custom AOP
    private static final Set<String> ALL_KNOWN_ANNOTATIONS;
    static {
        Set<String> known = new HashSet<>();
        known.addAll(SPRING_AOP_ANNOTATIONS);
        known.addAll(SECURITY_ANNOTATIONS);
        known.addAll(VALIDATION_ANNOTATIONS);
        // non-AOP framework annotations that don't need stubs
        known.addAll(Set.of("Override", "Deprecated", "SuppressWarnings",
                "PostConstruct", "PreDestroy", "Bean", "RequestMapping",
                "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
                "PathVariable", "RequestBody", "RequestParam", "ResponseBody",
                "ResponseStatus", "ExceptionHandler", "RestController", "Controller",
                "Service", "Repository", "Component", "Autowired", "Value",
                "Qualifier", "Primary", "Lazy", "Scope", "Profile",
                "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max",
                "Pattern", "Email", "Positive", "Negative", "AssertTrue", "AssertFalse"));
        ALL_KNOWN_ANNOTATIONS = Collections.unmodifiableSet(known);
    }

    /** Returns AOP-relevant annotations on a method (Spring AOP + security + custom). */
    private List<String> aopAnnotations(MethodMetadata mm) {
        return mm.annotations().stream()
                .filter(a -> SPRING_AOP_ANNOTATIONS.contains(a)
                        || SECURITY_ANNOTATIONS.contains(a)
                        || !ALL_KNOWN_ANNOTATIONS.contains(a))
                .toList();
    }

    /** Category label for a single annotation name. */
    private String annotationCategory(String annotation) {
        if (SPRING_AOP_ANNOTATIONS.contains(annotation)) return "Spring AOP proxy";
        if (SECURITY_ANNOTATIONS.contains(annotation))   return "Security interceptor";
        return "Custom AOP/aspect";
    }

    // ── Indentation helper ──────────────────────────────────────────────────

    protected String i(int n) {
        return "    ".repeat(n);
    }

    // ── Default value literals ──────────────────────────────────────────────

    protected String defaultValue(String rawType) {
        String type = rawType.replaceAll("<.*>", "").trim();
        return switch (type) {
            case "String"        -> "\"testValue\"";
            case "int",
                 "Integer"       -> "1";
            case "long",
                 "Long"          -> "1L";
            case "double",
                 "Double"        -> "1.0";
            case "float",
                 "Float"         -> "1.0f";
            case "boolean",
                 "Boolean"       -> "true";
            case "byte",
                 "Byte"          -> "(byte) 1";
            case "short",
                 "Short"         -> "(short) 1";
            case "char",
                 "Character"     -> "'a'";
            case "BigDecimal"    -> "BigDecimal.ONE";
            case "BigInteger"    -> "BigInteger.ONE";
            case "LocalDate"     -> "LocalDate.now()";
            case "LocalDateTime" -> "LocalDateTime.now()";
            case "LocalTime"     -> "java.time.LocalTime.now()";
            case "ZonedDateTime" -> "java.time.ZonedDateTime.now()";
            case "OffsetDateTime"-> "java.time.OffsetDateTime.now()";
            case "Instant"       -> "java.time.Instant.now()";
            case "UUID"          -> "UUID.randomUUID()";
            // java.sql types — no-arg constructor does NOT exist
            case "Timestamp",
                 "java.sql.Timestamp"  -> "new java.sql.Timestamp(System.currentTimeMillis())";
            case "Date",
                 "java.sql.Date"       -> "new java.sql.Date(System.currentTimeMillis())";
            case "Time",
                 "java.sql.Time"       -> "new java.sql.Time(System.currentTimeMillis())";
            case "java.util.Date"      -> "new java.util.Date()";
            case "void"          -> "";
            case "List"          -> "List.of()";
            case "Map"           -> "Map.of()";
            case "Set"           -> "Set.of()";
            case "Optional"      -> "Optional.empty()";
            default              -> "null /* TODO: provide " + rawType + " */";
        };
    }

    // ── Dependency import resolution ────────────────────────────────────────

    /**
     * Collects all simple type names referenced in the generated test
     * (mocked fields, parent chain, method params, return types, thrown exceptions,
     * interface default method params/returns) and matches them against the
     * source class's own import list to emit fully-qualified import statements.
     *
     * This ensures generated tests compile without manual import editing.
     */
    protected String buildDependencyImports(ClassMetadata m) {
        // Collect every simple type name the test file will reference
        Set<String> usedSimpleNames = new LinkedHashSet<>();

        // Injected / mock fields
        for (FieldMetadata f : m.mockCandidates()) {
            usedSimpleNames.add(f.simpleType());
        }

        // Parent chain classes (for @Spy declarations)
        if (m.hasParentChain()) {
            m.parentChain().forEach(p -> usedSimpleNames.add(p.className()));
        } else if (m.hasSuperClass()) {
            usedSimpleNames.add(m.superClassName());
        }

        // Own methods: params, return types, thrown exceptions
        collectMethodTypes(m.methods(), usedSimpleNames);

        // Interface default methods: params, return types
        if (m.hasInterfaceDefaultMethods()) {
            collectMethodTypes(m.interfaceDefaultMethods(), usedSimpleNames);
        }

        // Build FQN → simple-name map from the source file's imports
        Map<String, String> simpleToFqn = new LinkedHashMap<>();
        for (String fqn : m.imports()) {
            String simpleName = fqn.contains(".")
                    ? fqn.substring(fqn.lastIndexOf('.') + 1)
                    : fqn;
            simpleToFqn.put(simpleName, fqn);
        }

        // Emit an import for each used type that appears in the source imports
        StringBuilder sb = new StringBuilder();
        for (String simple : usedSimpleNames) {
            // Strip generic part if present (e.g. "List<Order>" → "Order")
            String[] parts = simple.replaceAll(".*<|>.*", "").split("[,\\s]+");
            for (String part : parts) {
                String stripped = part.trim().replaceAll("[\\[\\]]", "");
                if (stripped.isEmpty()) continue;
                String fqn = simpleToFqn.get(stripped);
                if (fqn != null && !fqn.startsWith("java.lang")) {
                    sb.append("import ").append(fqn).append(";\n");
                }
            }
        }
        return sb.toString();
    }

    private void collectMethodTypes(List<MethodMetadata> methods, Set<String> target) {
        for (MethodMetadata mm : methods) {
            if (!"void".equals(mm.returnType())) target.add(mm.returnType());
            mm.parameters().forEach(p -> target.add(p.type()));
            target.addAll(mm.thrownExceptions());
        }
    }

    // ── Common import blocks ────────────────────────────────────────────────

    /**
     * Spring Boot 3.4 moved @MockBean → @MockitoBean (new package).
     * We pick the right import based on the detected target project version.
     */
    protected String commonImports(String springBootVersion) {
        return commonImports();
    }

    protected String commonImports() {
        return "import org.junit.jupiter.api.*;\n"
             + "import org.junit.jupiter.api.extension.ExtendWith;\n"
             + "import org.junit.jupiter.params.ParameterizedTest;\n"
             + "import org.junit.jupiter.params.provider.CsvSource;\n"
             + "import org.junit.jupiter.params.provider.EnumSource;\n"
             + "import org.mockito.*;\n"
             + "import org.mockito.junit.jupiter.MockitoExtension;\n"
             + "import static org.mockito.Mockito.mockStatic;\n"
             + "import org.springframework.beans.factory.annotation.Autowired;\n"
             + "import org.springframework.boot.test.context.SpringBootTest;\n"
             + "import org.springframework.context.ApplicationContext;\n"
             + "import org.springframework.test.context.ActiveProfiles;\n"
             + "import org.springframework.test.util.ReflectionTestUtils;\n"
             + "import java.math.BigDecimal;\n"
             + "import java.math.BigInteger;\n"
             + "import java.time.LocalDate;\n"
             + "import java.time.LocalDateTime;\n"
             + "import java.util.*;\n"
             + "import static org.junit.jupiter.api.Assertions.*;\n"
             + "import static org.mockito.Mockito.*;\n"
             + "import static org.mockito.ArgumentMatchers.*;\n";
    }

    /** @MockBean removed — use @Mock everywhere to avoid spring-boot-test dependency issues. */
    protected String mockBeanAnnotation(String springBootVersion) {
        return "@Mock";
    }

    // ── Mock / MockBean declarations (Feature 4: @Spy for concrete types) ────

    /**
     * Emits @Mock or @Spy for each injected field based on whether the field type
     * is a concrete class found in the scanned source root.
     * – Interface / unknown type → @Mock  (safe, works everywhere)
     * – Concrete class found in source → @Spy  (calls real methods unless stubbed)
     */
    protected String buildMockDeclarations(ClassMetadata m, int indent) {
        return buildMockDeclarations(m.mockCandidates(), indent, m.concreteClassNames());
    }

    protected String buildMockDeclarations(List<FieldMetadata> fields, int indent) {
        return buildMockDeclarations(fields, indent, Set.of());
    }

    private String buildMockDeclarations(List<FieldMetadata> fields, int indent,
                                          Set<String> concreteTypes) {
        StringBuilder sb = new StringBuilder();
        for (FieldMetadata f : fields) {
            if (f.isApplicationContext()) {
                sb.append(i(indent)).append("@Mock\n");
                sb.append(i(indent)).append("ApplicationContext ").append(f.name()).append(";\n\n");
            } else if (f.isMockCandidate()) {
                boolean isConcrete = concreteTypes.contains(f.simpleType());
                String annotation  = isConcrete ? "@Spy" : "@Mock";
                if (f.isConstructorInjected()) {
                    sb.append(i(indent)).append("// Constructor-injected — Mockito @InjectMocks wires via constructor\n");
                }
                sb.append(i(indent)).append(annotation).append("\n");
                sb.append(i(indent)).append("private ").append(f.type()).append(" ").append(f.name()).append(";\n\n");
            }
        }
        return sb.toString();
    }

    protected String buildMockBeanDeclarations(List<FieldMetadata> fields, int indent) {
        return buildMockBeanDeclarations(fields, indent, null);
    }

    protected String buildMockBeanDeclarations(List<FieldMetadata> fields, int indent,
                                                String springBootVersion) {
        // @MockBean removed — @Mock works everywhere without spring-boot-test dependency
        String annotation = "@Mock";
        StringBuilder sb = new StringBuilder();
        for (FieldMetadata f : fields) {
            if (f.isApplicationContext()) {
                sb.append(i(indent)).append(annotation).append("\n");
                sb.append(i(indent)).append("ApplicationContext ").append(f.name()).append(";\n\n");
            } else if (f.isMockCandidate()) {
                if (f.isConstructorInjected()) {
                    sb.append(i(indent)).append("// Constructor-injected — wired via Spring context\n");
                }
                sb.append(i(indent)).append(annotation).append("\n");
                sb.append(i(indent)).append("private ").append(f.type()).append(" ").append(f.name()).append(";\n\n");
            }
        }
        return sb.toString();
    }

    // ── Multi-level parent spy declarations (Feature 1) ────────────────────

    /**
     * Emits @Spy declarations for every level of the parent chain.
     * Deepest ancestor is declared first so Mockito can resolve injection order.
     * Example for ClassA → ClassB → ClassC:
     *   @Spy ClassC grandParent;   // level 2
     *   @Spy ClassB parent;        // level 1
     */
    protected String buildParentSpyDeclarations(ClassMetadata m, int indent) {
        if (!m.hasParentChain()) {
            // Fall back to single-level if parentChain not resolved but superClassName known
            if (!m.hasSuperClass()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append(i(indent)).append("@Spy\n");
            sb.append(i(indent)).append("private ").append(m.superClassName()).append(" parent;\n\n");
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        List<ClassMetadata> chain = m.parentChain();
        // Emit deepest first
        for (int level = chain.size() - 1; level >= 0; level--) {
            ClassMetadata parent = chain.get(level);
            String varName = level == 0 ? "parent" : "ancestor" + level;
            sb.append(i(indent)).append("@Spy\n");
            sb.append(i(indent)).append("private ").append(parent.className()).append(" ").append(varName).append(";\n\n");
        }
        return sb.toString();
    }

    // ── ApplicationContext stubs ────────────────────────────────────────────

    protected String buildAppCtxStubs(ClassMetadata m, int indent) {
        if (!m.hasApplicationContext()) return "";
        String field = m.fields().stream()
                .filter(FieldMetadata::isApplicationContext)
                .map(FieldMetadata::name)
                .findFirst().orElse("applicationContext");
        // lenient() — Mockito 5 strict stubbing: these may not be called by every test method
        return i(indent) + "lenient().when(" + field + ".getBean(any(Class.class))).thenReturn(mock(Object.class));\n"
             + i(indent) + "lenient().when(" + field + ".getBean(anyString(), any(Class.class))).thenReturn(mock(Object.class));\n"
             + i(indent) + "lenient().when(" + field + ".containsBean(anyString())).thenReturn(true);\n";
    }

    // ── Parent-class (BAU inheritance) stubs ────────────────────────────────

    /**
     * Stubs overridden parent methods using Mockito spy + doReturn for the direct parent.
     * Also handles super.xxx() calls within the method body.
     * For multi-level chains, ancestor stubs are appended after the direct parent stubs.
     */
    /**
     * Stubs ALL public/protected methods from the parent chain on the spy subject.
     *
     * With spy(new ClassA()), any inherited method that is NOT stubbed will execute
     * the real ClassB implementation — which may call external dependencies, throw
     * exceptions, or produce unexpected side effects.
     *
     * Two categories:
     *  A) Overridden methods — ClassA overrides ClassB; stub on spy to isolate ClassA logic
     *  B) Inherited non-overridden methods — ClassA inherits directly; stub on spy
     *     to prevent real ClassB code from running during ClassA's unit tests
     */
    protected String buildSuperClassStubs(ClassMetadata m, int indent) {
        if (!m.hasSuperClass()) return "";
        StringBuilder sb = new StringBuilder();

        // Collect all overridden method names for dedup
        Set<String> overriddenNames = m.overriddenMethods().stream()
                .map(MethodMetadata::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // ── A) Overridden methods — active stubs on spy ──────────────────────
        if (!m.overriddenMethods().isEmpty()) {
            sb.append(i(indent)).append("// A) Overridden methods from ").append(m.superClassName())
              .append(" — stub on spy to isolate ClassA logic\n");
        }
        for (MethodMetadata mm : m.overriddenMethods()) {
            sb.append(emitSpyStub(mm, "subject", indent, "", m));

            // super.xxx() calls within overridden method — also stub on spy
            if (mm.hasSuperCalls()) {
                for (String superCall : mm.superMethodCalls()) {
                    if (mm.name().equals(superCall)) {
                        sb.append(emitSpyStub(mm, "subject", indent,
                                " // super." + superCall + "() — prevent real " + m.superClassName() + " execution", m));
                    } else {
                        sb.append(i(indent))
                          .append("// TODO: stub super.").append(superCall)
                          .append("() — doReturn(...).when(subject).").append(superCall).append("(...);\n");
                    }
                }
            }
        }

        // ── B) Inherited non-overridden methods — stub to prevent real ClassB execution ──
        if (m.hasParentChain()) {
            for (int level = 0; level < m.parentChain().size(); level++) {
                ClassMetadata parent = m.parentChain().get(level);
                List<MethodMetadata> inheritedMethods = parent.methods().stream()
                        .filter(MethodMetadata::isTestable)
                        .filter(mm -> !mm.isFinal()) // final methods cannot be stubbed
                        .filter(mm -> !overriddenNames.contains(mm.name())) // already stubbed above
                        .toList();

                if (!inheritedMethods.isEmpty()) {
                    sb.append(i(indent))
                      .append("// B) Inherited non-overridden methods from ").append(parent.className())
                      .append(" — stub ALL on spy to prevent real ").append(parent.className()).append(" execution\n");
                    for (MethodMetadata mm : inheritedMethods) {
                        sb.append(emitSpyStub(mm, "subject", indent,
                                " // inherited from " + parent.className(), m));
                        overriddenNames.add(mm.name()); // dedup across ancestor levels
                    }
                }
            }
        } else if (m.hasSuperClass()) {
            // parentChain empty — parent source is outside the scanned source root
            // (e.g. framework class like RouteBuilder, or external library)
            sb.append(i(indent))
              .append("// Parent ").append(m.superClassName())
              .append(" is outside the source root (framework/external class).\n");
            sb.append(i(indent))
              .append("// Stub inherited methods manually if they execute during tests:\n");
            sb.append(i(indent))
              .append("// lenient().doReturn(...).when(subject).inheritedMethodName(any(...));\n");
        }

        return sb.toString();
    }

    /** Emits a lenient doReturn/doNothing stub on the given target (spy or parent var). */
    private String emitSpyStub(MethodMetadata mm, String target, int indent) {
        return emitSpyStub(mm, target, indent, "", null);
    }

    private String emitSpyStub(MethodMetadata mm, String target, int indent, String comment) {
        return emitSpyStub(mm, target, indent, comment, null);
    }

    private String emitSpyStub(MethodMetadata mm, String target, int indent,
                                String comment, ClassMetadata m) {
        String matchers = mm.parameters().stream()
                .map(p -> "any(" + p.type().replaceAll("<.*>", "").trim() + ".class)")
                .collect(Collectors.joining(", "));
        if (mm.hasReturnValue()) {
            // Use typed return value when class metadata available (concreteClassNames / paramTypeRegistry)
            String returnVal = m != null
                    ? typedReturnValue(mm.returnType(), m)
                    : defaultValue(mm.returnType());
            return i(indent) + "lenient().doReturn(" + returnVal + ").when("
                    + target + ")." + mm.name() + "(" + matchers + ");" + comment + "\n";
        } else {
            return i(indent) + "lenient().doNothing().when(" + target + ")." + mm.name()
                    + "(" + matchers + ");" + comment + "\n";
        }
    }

    /**
     * Returns a typed return value for spy stubs based on what we know about the type:
     *  - In concreteClassNames (source root)  → TypeTestData.buildValidType()
     *  - In paramTypeRegistry (parsed)        → new Type()
     *  - Primitive / standard type            → defaultValue()
     *  - Unknown external type                → null
     */
    private String typedReturnValue(String rawReturnType, ClassMetadata m) {
        String rawType = rawReturnType.replaceAll("<.*>", "").trim();
        String base    = defaultValue(rawReturnType);
        if (!base.startsWith("null")) return base; // primitive / standard type

        if (m.concreteClassNames() != null && m.concreteClassNames().contains(rawType)) {
            return rawType + "TestData.buildValid" + rawType + "()";
        }
        if (m.paramTypeRegistry() != null && m.paramTypeRegistry().containsKey(rawType)) {
            return "new " + rawType + "()";
        }
        return "null"; // external type — no typed value available
    }

    // ── @BeforeEach ─────────────────────────────────────────────────────────

    /**
     * Determines whether to use spy() or @InjectMocks for the Unit nested class.
     *
     * spy() is needed when there are actual method calls in the class body that
     * must be intercepted to isolate the logic under test:
     *
     *   a) super.xxx() calls — delegate to parent; must be stubbed on spy to prevent
     *      real parent execution (regardless of whether a superclass exists)
     *
     *   b) internal method calls — calls to other methods declared in THIS class;
     *      must be stubbed on spy so only the entry-point logic is exercised
     *      (structural detection — no name-prefix restriction)
     *
     * @InjectMocks is sufficient when NEITHER applies:
     *   - No super calls, no internal helper calls
     *   - Works even without a no-arg constructor (Mockito handles injection)
     *   - Having a superclass alone is NOT enough — only spy if there are actual calls
     */
    protected boolean requiresSpyPattern(ClassMetadata m) {
        // a) any method body contains super.xxx() calls
        boolean hasSuperCalls = m.methods().stream().anyMatch(MethodMetadata::hasSuperCalls);
        if (hasSuperCalls) return true;

        // b) any testable method calls other public/protected methods in THIS class
        // (private methods can't be stubbed by Mockito — spy only helps for public/protected helpers)
        Set<String> stubbableOwnMethods = m.methods().stream()
                .filter(mm -> mm.isPublic() || mm.isProtected())
                .map(MethodMetadata::name)
                .collect(Collectors.toSet());
        return m.methods().stream()
                .filter(MethodMetadata::isTestable)
                .flatMap(mm -> mm.helperMethodCalls().stream())
                .anyMatch(stubbableOwnMethods::contains);
    }

    /**
     * Emits the subject field declaration for the Unit class.
     * When @InjectMocks is used, also emits the @InjectMocks annotation.
     */
    protected String buildSubjectDeclaration(ClassMetadata m, int indent) {
        if (requiresSpyPattern(m)) {
            // Spy: declared as plain field, initialised in @BeforeEach
            return i(indent) + "private " + m.className() + " subject;\n\n";
        } else {
            // @InjectMocks: Mockito handles injection (constructor / field / setter)
            return i(indent) + "@InjectMocks\n"
                 + i(indent) + "private " + m.className() + " subject;\n\n";
        }
    }

    /**
     * Smart @BeforeEach:
     *  - spy(new Class()) + ReflectionTestUtils injection  when spy pattern required
     *  - bare setUp() with stubs only                      when @InjectMocks is used
     */
    protected String buildBeforeEach(ClassMetadata m, String subject, boolean usesMockBeans, int indent) {
        StringBuilder sb = new StringBuilder();
        boolean useSpy = !usesMockBeans && requiresSpyPattern(m);

        sb.append(i(indent)).append("@BeforeEach\n");
        sb.append(i(indent)).append("void setUp() {\n");

        if (useSpy) {
            // Spy pattern: instantiate and wrap
            sb.append(i(indent + 1)).append("// spy() required: class has superclass or internal helper methods\n");

            // Constructor injection: spy(new Class(dep1, dep2))
            List<FieldMetadata> ctorFields = m.mockCandidates().stream()
                    .filter(FieldMetadata::isConstructorInjected).toList();
            if (!ctorFields.isEmpty()) {
                String ctorArgs = ctorFields.stream()
                        .map(FieldMetadata::name).collect(Collectors.joining(", "));
                sb.append(i(indent + 1)).append(m.className()).append(" rawInstance = new ")
                  .append(m.className()).append("(").append(ctorArgs).append(");\n");
            } else {
                sb.append(i(indent + 1)).append(m.className()).append(" rawInstance = new ")
                  .append(m.className()).append("();\n");
            }
            sb.append(i(indent + 1)).append(subject).append(" = spy(rawInstance);\n\n");

            // Field-injected mocks via ReflectionTestUtils (BAU field injection)
            for (FieldMetadata f : m.mockCandidates()) {
                if (!f.isApplicationContext() && !f.isConstructorInjected()) {
                    sb.append(i(indent + 1)).append("ReflectionTestUtils.setField(").append(subject)
                      .append(", \"").append(f.name()).append("\", ").append(f.name()).append(");\n");
                }
            }
        }
        // For @InjectMocks: Mockito extension handles injection — nothing to do here

        // @Value fields
        for (FieldMetadata f : m.valueFields()) {
            sb.append(i(indent + 1)).append("ReflectionTestUtils.setField(").append(subject)
              .append(", \"").append(f.name()).append("\", \"testValue\");\n");
        }

        if (m.hasApplicationContext()) {
            sb.append(buildAppCtxStubs(m, indent + 1));
        }

        // Repository stubs
        sb.append(buildRepositoryStubs(m, indent + 1));

        if (useSpy) {
            // Stub internal helpers on spy (Pattern D)
            sb.append(buildHelperMethodStubs(m, subject, indent + 1));
            // Stub all parent methods on spy (Patterns A/B)
            if (m.hasSuperClass()) {
                sb.append(buildSuperClassStubs(m, indent + 1));
            }
        }

        boolean hasPostConstruct = m.methods().stream()
                .anyMatch(mm -> mm.annotations().contains("PostConstruct"));
        if (hasPostConstruct) {
            sb.append(i(indent + 1))
              .append("// @PostConstruct runs on Spring init — verify any side effects below\n");
        }

        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // ── Pattern D: internal helper method stubs ──────────────────────────────

    /**
     * Stubs internal helper methods (populate/build/create/map/assemble prefix) on the spy
     * to prevent complex internal logic from executing during unit tests.
     */
    protected String buildHelperMethodStubs(ClassMetadata m, String subject, int indent) {
        // Collect method names that are: (a) called from a testable method body, AND
        // (b) actually declared in this class (not false-positives from no-scope calls to external methods)
        Set<String> ownMethodNames = m.methods().stream()
                .map(MethodMetadata::name)
                .collect(Collectors.toSet());

        Set<String> helperNames = m.methods().stream()
                .filter(MethodMetadata::isTestable)
                .flatMap(mm -> mm.helperMethodCalls().stream())
                .filter(ownMethodNames::contains)   // only stub methods declared in THIS class
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (helperNames.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("// Pattern D: stub internal helpers on spy (prevents complex logic execution)\n");
        for (String helperName : helperNames) {
            m.methods().stream()
              .filter(mm -> mm.name().equals(helperName))
              .filter(mm -> mm.isPublic() || mm.isProtected()) // private methods cannot be stubbed by Mockito
              .findFirst()
              .ifPresent(mm -> {
                  String matchers = mm.parameters().stream()
                          .map(p -> "any(" + p.type().replaceAll("<.*>", "").trim() + ".class)")
                          .collect(Collectors.joining(", "));
                  if (mm.hasReturnValue()) {
                      sb.append(i(indent))
                        .append("lenient().doReturn(").append(typedReturnValue(mm.returnType(), m))
                        .append(").when(").append(subject).append(").").append(helperName)
                        .append("(").append(matchers).append(");\n");
                  } else {
                      sb.append(i(indent))
                        .append("lenient().doNothing().when(").append(subject).append(").").append(helperName)
                        .append("(").append(matchers).append(");\n");
                  }
              });
        }
        return sb.toString();
    }

    // ── Repository field stubs ──────────────────────────────────────────────

    /**
     * For every injected field whose simple type ends with "Repository",
     * emits commented-out stubs for the common JPA operations so developers
     * know exactly what to configure rather than getting silent null returns.
     */
    protected String buildRepositoryStubs(ClassMetadata m, int indent) {
        List<FieldMetadata> repos = m.mockCandidates().stream()
                .filter(f -> f.simpleType().endsWith("Repository"))
                .toList();
        if (repos.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("// Repository stubs — JPA mocks return null by default; configure as needed\n");
        for (FieldMetadata f : repos) {
            String mock = f.name();
            String entity = f.simpleType().replace("Repository", "");
            sb.append(i(indent))
              .append("// when(").append(mock).append(".findById(any())).thenReturn(Optional.of(")
              .append(entity).append("TestData.buildValid").append(entity).append("()));\n");
            sb.append(i(indent))
              .append("// when(").append(mock).append(".findAll()).thenReturn(")
              .append(entity).append("TestData.build").append(entity).append("List());\n");
            sb.append(i(indent))
              .append("// when(").append(mock).append(".save(any())).thenAnswer(inv -> inv.getArgument(0));\n");
            sb.append(i(indent))
              .append("// doNothing().when(").append(mock).append(").deleteById(any());\n");
        }
        return sb.toString();
    }

    // ── Wire @Nested (shared across all strategies) ─────────────────────────

    protected String buildWireNested(ClassMetadata m, String subjectDecl, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("@Nested\n");
        sb.append(i(indent)).append("@SpringBootTest\n");
        sb.append(i(indent)).append("@ActiveProfiles(\"test\")\n");
        sb.append(i(indent)).append("class Wire {\n\n");
        sb.append(i(indent + 1)).append(subjectDecl).append("\n\n");
        sb.append(i(indent + 1)).append("@Test\n");
        sb.append(i(indent + 1)).append("void contextLoads() {\n");
        sb.append(i(indent + 2)).append("assertNotNull(subject);\n");
        sb.append(i(indent + 1)).append("}\n\n");
        sb.append(i(indent + 1)).append("@Test\n");
        sb.append(i(indent + 1)).append("void ").append(convention.unitTestMethod("beanWiring", "wire")).append("() {\n");
        sb.append(i(indent + 2)).append("assertNotNull(subject);\n");
        sb.append(i(indent + 2)).append("// TODO: verify full Spring context integration\n");
        sb.append(i(indent + 1)).append("}\n");
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // ── Pattern C: branch tests (conditional logic) ─────────────────────────

    protected String buildBranchTests(MethodMetadata mm, String subject,
                                       ClassMetadata m, int indent) {
        if (!mm.hasConditionals()) return "";
        StringBuilder sb = new StringBuilder();
        String throwsDecl = checkedThrowsClause(mm);

        // If condition scenarios were detected, generate targeted tests using named TestData
        if (mm.hasConditionScenarios()) {
            for (com.testgen.parser.ConditionScenario sc : mm.conditionScenarios()) {
                String ownerTestData = m.className() + "TestData";

                // TRUE branch — uses named scenario method from ClassATestData
                String trueName = convention.unitTestMethod(mm.name(),
                        "when_" + sc.trueLabel(), buildParamSuffix(mm));
                sb.append(i(indent)).append("@Test\n");
                sb.append(i(indent)).append("void ").append(trueName).append("()").append(throwsDecl).append(" {\n");
                sb.append(i(indent + 1)).append("// Scenario: ").append(sc.paramName()).append(".")
                  .append(sc.fieldName()).append(" = ").append(sc.trueSetExpr())
                  .append(" → condition TRUE → ").append(sc.trueLabel()).append("\n");
                // Use named scenario from ClassATestData
                sb.append(i(indent + 1)).append(sc.paramType()).append(" ").append(sc.paramName())
                  .append(" = ").append(ownerTestData).append(".").append(sc.trueMethodName()).append("();\n");
                if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
                sb.append(i(indent + 1)).append("// TODO: assert TRUE-branch outcome\n");
                sb.append(i(indent)).append("}\n\n");

                // FALSE branch
                String falseName = convention.unitTestMethod(mm.name(),
                        "when_" + sc.falseLabel(), buildParamSuffix(mm));
                sb.append(i(indent)).append("@Test\n");
                sb.append(i(indent)).append("void ").append(falseName).append("()").append(throwsDecl).append(" {\n");
                sb.append(i(indent + 1)).append("// Scenario: ").append(sc.paramName()).append(".")
                  .append(sc.fieldName()).append(" = ").append(sc.falseSetExpr())
                  .append(" → condition FALSE → ").append(sc.falseLabel()).append("\n");
                sb.append(i(indent + 1)).append(sc.paramType()).append(" ").append(sc.paramName())
                  .append(" = ").append(ownerTestData).append(".").append(sc.falseMethodName()).append("();\n");
                if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
                sb.append(i(indent + 1)).append("// TODO: assert FALSE-branch outcome\n");
                sb.append(i(indent)).append("}\n\n");
            }
            return sb.toString();
        }

        // Generic fallback when no specific conditions were detected
        String trueName = convention.unitTestMethod(mm.name(), "when_condition_isTrue",
                buildParamSuffix(mm));
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(trueName).append("()").append(throwsDecl).append(" {\n");
        sb.append(i(indent + 1)).append("// Pattern C — TRUE branch: configure subject/mocks for the positive condition\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());
        sb.append(i(indent + 1)).append("// TODO: set field / doReturn to trigger TRUE branch\n");
        if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
        sb.append(i(indent + 1)).append("// TODO: assert true-branch outcome\n");
        sb.append(i(indent)).append("}\n\n");

        String falseName = convention.unitTestMethod(mm.name(), "when_condition_isFalse",
                buildParamSuffix(mm));
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(falseName).append("()").append(throwsDecl).append(" {\n");
        sb.append(i(indent + 1)).append("// Pattern C — FALSE branch\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());
        sb.append(i(indent + 1)).append("// TODO: set field / doReturn to trigger FALSE branch\n");
        if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
        sb.append(i(indent + 1)).append("// TODO: assert false-branch outcome\n");
        sb.append(i(indent)).append("}\n\n");

        return sb.toString();
    }

    // ── Pattern G: boundary tests (numeric/comparison) ──────────────────────

    protected String buildBoundaryTests(MethodMetadata mm, String subject,
                                         ClassMetadata m, int indent) {
        if (!mm.hasNumericComparisons()) return "";
        StringBuilder sb = new StringBuilder();
        String throwsDecl = checkedThrowsClause(mm);

        for (String label : List.of("belowThreshold", "atThreshold", "aboveThreshold")) {
            String testName = convention.unitTestMethod(mm.name(), label, buildParamSuffix(mm));
            sb.append(i(indent)).append("@Test\n");
            sb.append(i(indent)).append("void ").append(testName).append("()").append(throwsDecl).append(" {\n");
            sb.append(i(indent + 1)).append("// Pattern G — boundary: '").append(label).append("'\n");
            buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());
            sb.append(i(indent + 1)).append("// TODO: set the numeric field to the boundary value (e.g. threshold-1 / threshold / threshold+1)\n");
            if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
            sb.append(i(indent + 1)).append("// TODO: assert outcome for ").append(label).append("\n");
            sb.append(i(indent)).append("}\n\n");
        }
        return sb.toString();
    }

    // ── Pattern A: static dependency mock tests ─────────────────────────────

    protected String buildStaticMockTests(MethodMetadata mm, String subject,
                                           ClassMetadata m, int indent) {
        if (!mm.hasStaticDependencies()) return "";
        StringBuilder sb = new StringBuilder();
        String throwsDecl = checkedThrowsClause(mm);

        for (String staticClass : mm.staticCallClasses()) {
            String testName = convention.unitTestMethod(mm.name(),
                    "with_" + staticClass + "_mocked", buildParamSuffix(mm));
            sb.append(i(indent)).append("@Test\n");
            sb.append(i(indent)).append("void ").append(testName).append("()").append(throwsDecl).append(" {\n");
            sb.append(i(indent + 1)).append("// Pattern A — static dependency: mock ").append(staticClass).append("\n");
            sb.append(i(indent + 1)).append("try (MockedStatic<").append(staticClass).append("> mockedStatic = mockStatic(")
              .append(staticClass).append(".class)) {\n");
            sb.append(i(indent + 2)).append("// TODO: mockedStatic.when(() -> ").append(staticClass)
              .append(".someMethod(any())).thenReturn(expectedValue);\n");
            buildParamSetup(mm, sb, indent + 2, m.concreteClassNames(), m.paramTypeRegistry());
            if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 2);
            sb.append(i(indent + 2)).append("// TODO: assert result\n");
            sb.append(i(indent + 1)).append("}\n");
            sb.append(i(indent)).append("}\n\n");
        }
        return sb.toString();
    }

    // ── Pattern H: exception flow test ──────────────────────────────────────

    protected String buildExceptionFlowTest(MethodMetadata mm, String subject,
                                             ClassMetadata m, int indent) {
        if (!mm.hasTryCatch() && !mm.throwsExceptions()) return "";
        String exType  = primaryException(mm) != null ? primaryException(mm) : "Exception";
        String testName = convention.unitTestMethod(mm.name(), "whenExceptionOccurs", buildParamSuffix(mm));
        StringBuilder sb = new StringBuilder();

        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(testName).append("() {\n");
        sb.append(i(indent + 1)).append("// Pattern H — exception flow: assert the FINAL thrown exception\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());

        // Identify the first non-repository mock to throw from
        String triggerMock = m.mockCandidates().stream()
                .filter(f -> !f.simpleType().endsWith("Repository") && !f.isApplicationContext())
                .map(FieldMetadata::name)
                .findFirst()
                .orElse("<mockDep>");
        sb.append(i(indent + 1))
          .append("doThrow(new ").append(exType).append("(\"test\"))")
          .append(".when(").append(triggerMock).append(").").append(mm.name()).append("(")
          .append(mm.parameters().stream().map(p -> "any(" + p.type().replaceAll("<.*>", "").trim() + ".class)")
                  .collect(Collectors.joining(", ")))
          .append("); // TODO: identify correct trigger\n");

        sb.append(i(indent + 1)).append("// Assert the final exception (may be wrapped by try/catch re-throw)\n");
        if (mm.isProtected()) {
            sb.append(i(indent + 1)).append("assertThrows(").append(exType).append(".class, () ->\n");
            sb.append(i(indent + 2)).append("ReflectionTestUtils.invokeMethod(subject, \"")
              .append(mm.name()).append("\"")
              .append(mm.parameters().isEmpty() ? "" : ", " + paramNames(mm)).append("));\n");
        } else {
            sb.append(i(indent + 1)).append("assertThrows(").append(exType).append(".class, () ->\n");
            sb.append(i(indent + 2)).append("subject.").append(mm.name()).append("(").append(paramNames(mm)).append("));\n");
        }
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // ── AOP annotation awareness ────────────────────────────────────────────

    /**
     * Emits a single-line comment before a Unit test method when the source method
     * carries AOP/proxy annotations that are inactive in pure Mockito tests.
     */
    protected String buildAopWarningComment(MethodMetadata mm, int indent) {
        List<String> aop = aopAnnotations(mm);
        if (aop.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String ann : aop) {
            sb.append(i(indent))
              .append("// NOTE: @").append(ann).append(" (").append(annotationCategory(ann))
              .append(") is NOT active in Unit layer — verify its behaviour in Functional/Wire\n");
        }
        return sb.toString();
    }

    /**
     * Generates dedicated Functional-layer test stubs for every method that carries
     * AOP/proxy annotations. These stubs run inside a Spring context where the
     * proxy IS active, so the behaviour can actually be exercised.
     */
    protected String buildFunctionalAopTestMethods(ClassMetadata m, int indent) {
        StringBuilder sb = new StringBuilder();
        for (MethodMetadata mm : m.ownPublicMethods()) {
            List<String> aop = aopAnnotations(mm);
            if (aop.isEmpty() || mm.isProtected()) continue;

            for (String ann : aop) {
                String testName    = convention.unitTestMethod(mm.name(), ann.toLowerCase() + "_behaviour");
                String throwsDecl  = checkedThrowsClause(mm);
                sb.append(i(indent)).append("@Test\n");
                sb.append(i(indent)).append("void ").append(testName).append("()").append(throwsDecl).append(" {\n");
                sb.append(i(indent + 1)).append("// @").append(ann)
                  .append(" — ").append(annotationCategory(ann))
                  .append(" is active here (Spring proxy wraps subject)\n");

                if (SPRING_AOP_ANNOTATIONS.contains(ann)) {
                    appendSpringAopStub(ann, mm, sb, indent + 1, m);
                } else if (SECURITY_ANNOTATIONS.contains(ann)) {
                    sb.append(i(indent + 1))
                      .append("// TODO: call subject.").append(mm.name())
                      .append("(...) with insufficient role → expect AccessDeniedException\n");
                    sb.append(i(indent + 1))
                      .append("// TODO: call subject.").append(mm.name())
                      .append("(...) with correct role → expect success\n");
                } else {
                    // custom annotation
                    sb.append(i(indent + 1))
                      .append("// TODO: verify @").append(ann)
                      .append(" aspect behaviour — e.g. audit log written, metric recorded\n");
                    sb.append(i(indent + 1)).append("// subject.").append(mm.name())
                      .append("(").append(buildDefaultParamArgs(mm, m.concreteClassNames())).append(");\n");
                    sb.append(i(indent + 1))
                      .append("// TODO: assert aspect side-effect\n");
                }
                sb.append(i(indent)).append("}\n\n");
            }
        }
        return sb.toString();
    }

    private void appendSpringAopStub(String ann, MethodMetadata mm, StringBuilder sb, int indent,
                                      ClassMetadata m) {
        String call = "subject." + mm.name() + "(" + buildDefaultParamArgs(mm, m.concreteClassNames()) + ")";
        switch (ann) {
            case "Transactional" -> {
                // Surface @Transactional attribute values from the annotation if available
                String txAttrs = mm.annotations().stream()
                        .filter(a -> a.startsWith("Transactional"))
                        .findFirst()
                        .filter(a -> a.contains("("))
                        .map(a -> " " + a.substring(a.indexOf('(')))
                        .orElse("");
                sb.append(i(indent)).append("// @Transactional").append(txAttrs).append("\n");
                sb.append(i(indent)).append("// Verify transaction commits on success\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// TODO: assert expected DB state after commit\n\n");
                sb.append(i(indent)).append("// Verify transaction rolls back on exception\n");
                sb.append(i(indent))
                  .append("// TODO: configure mock to throw RuntimeException → assertThrows, then verify rollback\n");
            }
            case "Async" -> {
                sb.append(i(indent)).append("// Async method — returns immediately; use CompletableFuture or CountDownLatch\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// TODO: await async completion and assert side-effects\n");
            }
            case "Cacheable", "CachePut" -> {
                sb.append(i(indent)).append("// First call — cache miss, real method executes\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// Second call — cache hit, real method NOT invoked again\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// TODO: verify(mockDep, times(1)).someMethod(any()); // called once despite two invocations\n");
            }
            case "CacheEvict" -> {
                sb.append(i(indent)).append("// Populate cache, then call evict method\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// TODO: assert subsequent cacheable call hits real method again\n");
            }
            case "Retryable" -> {
                sb.append(i(indent)).append("// Configure mock to fail N-1 times then succeed\n");
                sb.append(i(indent)).append("// TODO: doThrow(...).doReturn(...).when(mockDep).someMethod(any());\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// TODO: verify(mockDep, times(/* retryCount */)).someMethod(any());\n");
            }
            default -> {
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// TODO: assert @").append(ann).append(" behaviour\n");
            }
        }
    }

    private String buildDefaultParamArgs(MethodMetadata mm) {
        return buildDefaultParamArgs(mm, null);
    }

    private String buildDefaultParamArgs(MethodMetadata mm, Set<String> concreteClassNames) {
        return mm.parameters().stream()
                .map(p -> {
                    String raw   = p.type().replaceAll("<.*>", "").trim();
                    String value = defaultValue(p.type());
                    if (value.startsWith("null") && concreteClassNames != null
                            && concreteClassNames.contains(raw)) {
                        return raw + "TestData.buildValid" + raw + "()";
                    }
                    return value;
                })
                .collect(Collectors.joining(", "));
    }

    // ── Test method generation ──────────────────────────────────────────────

    protected String buildTestMethods(ClassMetadata m, String subject, int indent) {
        StringBuilder sb = new StringBuilder();

        List<MethodMetadata> overridden = m.overriddenMethods();
        if (!overridden.isEmpty()) {
            sb.append(i(indent))
              .append("// --- Overridden methods (parent ").append(m.superClassName())
              .append(" stubbed, child behavior tested) ---\n\n");
            for (MethodMetadata mm : overridden) {
                sb.append(buildSingleTestMethod(mm, subject, m, indent));
            }
        }

        List<MethodMetadata> own = m.ownNonOverriddenMethods();
        if (!own.isEmpty()) {
            if (m.hasSuperClass()) {
                sb.append(i(indent)).append("// --- Own methods ---\n\n");
            }
            for (MethodMetadata mm : own) {
                sb.append(buildSingleTestMethod(mm, subject, m, indent));
            }
        }

        if (m.hasSuperClass()) {
            sb.append(i(indent)).append("// Inherited non-overridden methods → covered by ")
              .append(m.superClassName()).append("Test\n\n");
        }

        // Feature 2: interface default method tests
        if (m.hasInterfaceDefaultMethods()) {
            sb.append(i(indent)).append("// --- Interface default methods ---\n\n");
            for (MethodMetadata mm : m.interfaceDefaultMethods()) {
                sb.append(i(indent))
                  .append("// Default method from interface — exercised via subject (no override needed)\n");
                sb.append(buildSingleTestMethod(mm, subject, m, indent));
            }
        }

        return sb.toString();
    }

    protected String buildSingleTestMethod(MethodMetadata mm, String subject,
                                           ClassMetadata m, int indent) {
        StringBuilder sb = new StringBuilder();

        // AOP warning — emitted before the @Test so the reader sees it immediately
        sb.append(buildAopWarningComment(mm, indent));

        // Success test — name includes param-type suffix to disambiguate overloaded methods
        String paramSuffix  = buildParamSuffix(mm);
        String throwsClause = checkedThrowsClause(mm);
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ")
          .append(convention.unitTestMethod(mm.name(), "success", paramSuffix))
          .append("()").append(throwsClause).append(" {\n");
        sb.append(i(indent + 1)).append("// given — params auto-initialized with typed defaults\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());

        // Mock stub hints using typed matchers (any(TypeName.class)) for each domain param
        buildMockStubHints(mm, m, sb, indent + 1);

        if (mm.isProtected()) {
            sb.append(i(indent + 1)).append("// when — protected access via ReflectionTestUtils (BAU class not modified)\n");
            buildReflectionCall(mm, subject, sb, indent + 1);
        } else {
            sb.append(i(indent + 1)).append("// when\n");
            buildDirectCall(mm, subject, sb, indent + 1);
        }

        sb.append(i(indent + 1)).append("// then\n");
        if (mm.hasReturnValue()) {
            sb.append(i(indent + 1)).append("assertNotNull(result);\n");
            buildResultAssertHints(mm, m, sb, indent + 1);
        } else {
            buildVerifyHints(mm, m, sb, indent + 1);
        }
        sb.append(i(indent)).append("}\n\n");

        // One exception test per method (most specific declared exception)
        String primaryException = primaryException(mm);
        if (primaryException != null) {
            sb.append(buildExceptionTestMethod(mm, subject, primaryException, indent, m));
        }

        // Pattern C: branch tests for conditional logic
        sb.append(buildBranchTests(mm, subject, m, indent));

        // Pattern G: numeric boundary tests
        sb.append(buildBoundaryTests(mm, subject, m, indent));

        // Pattern A: static dependency mock tests
        sb.append(buildStaticMockTests(mm, subject, m, indent));

        // Pattern H: exception flow test (try/catch)
        if (mm.hasTryCatch()) {
            sb.append(buildExceptionFlowTest(mm, subject, m, indent));
        }

        // @ParameterizedTest for primitive / String params
        boolean hasSimpleParam = mm.parameters().stream()
                .anyMatch(p -> isPrimitive(p.type()) || "String".equals(p.type()));
        if (hasSimpleParam && mm.isPublic() && !mm.isProtected()) {
            sb.append(buildParameterizedTestMethod(mm, subject, indent));
        }

        return sb.toString();
    }

    protected String buildExceptionTestMethod(MethodMetadata mm, String subject,
                                              String exType, int indent, ClassMetadata m) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("@Test\n");
        // Exception test: assertThrows wraps the call in a lambda — no throws clause needed
        sb.append(i(indent)).append("void ")
          .append(convention.exceptionTestMethod(mm.name(), exType)).append("() {\n");
        sb.append(i(indent + 1)).append("// given\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());

        // Specific doThrow stub wired to the exact exception type
        sb.append(i(indent + 1))
          .append("// Arrange: configure a mock dependency to throw ").append(exType).append("\n");
        sb.append(i(indent + 1))
          .append("// doThrow(new ").append(exType).append("(\"test\"))")
          .append(".when(<mockDep>).<methodThatTriggers>(any()); // TODO: identify the triggering mock call\n");

        sb.append(i(indent + 1)).append("// when / then\n");
        String params = paramNames(mm);
        if (mm.isProtected()) {
            String sep = params.isEmpty() ? "" : ", ";
            sb.append(i(indent + 1))
              .append(exType).append(" thrown = assertThrows(").append(exType).append(".class, () ->\n");
            sb.append(i(indent + 2)).append("ReflectionTestUtils.invokeMethod(")
              .append(subject).append(", \"").append(mm.name()).append("\"")
              .append(sep).append(params).append("));\n");
        } else {
            sb.append(i(indent + 1))
              .append(exType).append(" thrown = assertThrows(").append(exType).append(".class, () ->\n");
            sb.append(i(indent + 2)).append(subject).append(".")
              .append(mm.name()).append("(").append(params).append("));\n");
        }
        sb.append(i(indent + 1)).append("assertNotNull(thrown);\n");
        sb.append(i(indent + 1))
          .append("// TODO: assertEquals(\"expected message\", thrown.getMessage());\n");
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    protected String buildParameterizedTestMethod(MethodMetadata mm, String subject, int indent) {
        String testName   = convention.unitTestMethod(mm.name(), "parameterized");
        String throwsDecl = checkedThrowsClause(mm);
        return i(indent) + "@ParameterizedTest\n"
             + i(indent) + "@CsvSource({\"value1\", \"value2\"}) // TODO: provide representative values\n"
             + i(indent) + "void " + testName + "(String param)" + throwsDecl + " {\n"
             + i(indent + 1) + "// TODO: cast param to required type, invoke " + subject + "." + mm.name() + "(...)\n"
             + i(indent) + "}\n\n";
    }

    // ── Auto-init aware assertion/stub hints ────────────────────────────────

    /**
     * Emits commented-out mock stub hints using typed matchers — any(TypeName.class) —
     * so developers know exactly which type to match.
     * Only emits hints for domain-object params (non-primitive / non-standard types).
     */
    private void buildMockStubHints(MethodMetadata mm, ClassMetadata m,
                                     StringBuilder sb, int indent) {
        List<MethodMetadata.ParameterMetadata> domainParams = mm.parameters().stream()
                .filter(p -> defaultValue(p.type()).startsWith("null"))
                .toList();
        if (domainParams.isEmpty()) return;

        sb.append(i(indent)).append("// Stub mock dependencies — use typed matcher for initialized params:\n");
        for (MethodMetadata.ParameterMetadata p : domainParams) {
            String rawType = p.type().replaceAll("<.*>", "").trim();
            sb.append(i(indent))
              .append("// when(<mockDep>.<method>(any(").append(rawType).append(".class)))")
              .append(".thenReturn(").append(typedReturnHint(rawType, m)).append(");\n");
        }
    }

    /**
     * Emits field-level assertion hints when the return type is a known domain object.
     */
    private void buildResultAssertHints(MethodMetadata mm, ClassMetadata m,
                                         StringBuilder sb, int indent) {
        String rawReturn = mm.returnType().replaceAll("<.*>", "").trim();
        if (defaultValue(mm.returnType()).startsWith("null")) {
            // Return type is a domain object — suggest field assertions
            com.testgen.parser.ClassMetadata retMeta =
                    m.paramTypeRegistry() != null ? m.paramTypeRegistry().get(rawReturn) : null;
            if (retMeta != null && !retMeta.fields().isEmpty()) {
                sb.append(i(indent)).append("// Assert result fields (auto-initialized type — update as needed):\n");
                retMeta.fields().stream()
                        .filter(f -> !f.isInjected() && !f.isApplicationContext() && !f.isValue())
                        .limit(3) // top 3 fields to keep it concise
                        .forEach(f -> sb.append(i(indent))
                                .append("// assertNotNull(result.get").append(toSetterSuffix(f.name())).append("());\n"));
            } else if (m.concreteClassNames() != null && m.concreteClassNames().contains(rawReturn)) {
                sb.append(i(indent)).append("// assertNotNull(result); // ").append(rawReturn)
                  .append("TestData.buildValid").append(rawReturn).append("() shows available fields\n");
            } else {
                sb.append(i(indent)).append("// TODO: assert specific fields on result\n");
            }
        } else {
            sb.append(i(indent)).append("// TODO: assertEquals(expectedValue, result);\n");
        }
    }

    /**
     * Emits typed verify hints for void methods using initialized params.
     */
    private void buildVerifyHints(MethodMetadata mm, ClassMetadata m,
                                   StringBuilder sb, int indent) {
        if (mm.parameters().isEmpty()) {
            sb.append(i(indent)).append("// TODO: verify(mockDep).someMethod();\n");
            return;
        }
        sb.append(i(indent)).append("// Verify interactions using initialized params:\n");
        for (MethodMetadata.ParameterMetadata p : mm.parameters()) {
            String rawType = p.type().replaceAll("<.*>", "").trim();
            boolean isDomain = defaultValue(p.type()).startsWith("null");
            String matcher = isDomain ? "any(" + rawType + ".class)" : p.name();
            sb.append(i(indent))
              .append("// verify(<mockDep>).<method>(").append(matcher).append(");\n");
        }
    }

    /** Returns an appropriate return-value hint for mock stub setup. */
    private String typedReturnHint(String rawType, ClassMetadata m) {
        if (m.concreteClassNames() != null && m.concreteClassNames().contains(rawType)) {
            return rawType + "TestData.buildValid" + rawType + "()";
        }
        com.testgen.parser.ClassMetadata meta =
                m.paramTypeRegistry() != null ? m.paramTypeRegistry().get(rawType) : null;
        if (meta != null) return "new " + rawType + "()";
        return "mock(" + rawType + ".class)";
    }

    // ── Method name disambiguation helpers ─────────────────────────────────

    /**
     * Returns a short param-type suffix for overloaded method disambiguation.
     * Empty string when the method has no params or only one param (no collision risk).
     * e.g. process(MSBaseVO, String) → "MSBaseVO_String"
     */
    private String buildParamSuffix(MethodMetadata mm) {
        if (mm.parameters().size() <= 1) return "";
        return mm.parameters().stream()
                .map(p -> p.type().replaceAll("<.*>", "").trim())
                .collect(Collectors.joining("_"));
    }

    /**
     * Returns the single exception type to test for a given method.
     * Preference order:
     *  1. Most specific declared exception (not Exception / Throwable)
     *  2. If only broad exceptions declared, use the first one
     *  3. null if no exceptions declared
     */
    private String primaryException(MethodMetadata mm) {
        if (!mm.throwsExceptions()) return null;
        List<String> exceptions = mm.thrownExceptions();
        // Prefer custom/specific exceptions over base Exception/Throwable/RuntimeException
        return exceptions.stream()
                .filter(e -> !e.equals("Exception") && !e.equals("Throwable") && !e.equals("RuntimeException"))
                .findFirst()
                .orElse(exceptions.get(0));
    }

    // ── Exception / throws helpers ──────────────────────────────────────────

    /**
     * Returns " throws ExType1, ExType2" if the method declares any thrown exceptions.
     * If the root Exception (or Throwable) is already in the list it covers everything —
     * simplify to just " throws Exception" to avoid redundant declarations.
     */
    protected String checkedThrowsClause(MethodMetadata mm) {
        if (!mm.throwsExceptions()) return "";
        List<String> exceptions = mm.thrownExceptions();
        // If broad Exception / Throwable is declared, no need to list narrower types
        if (exceptions.contains("Exception") || exceptions.contains("Throwable")) {
            return " throws Exception";
        }
        return " throws " + String.join(", ", exceptions);
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Generates local variable declarations for each method parameter.
     *
     * Resolution order for domain-object types (non-primitive, non-standard):
     *  1. Type is in concreteClassNames (scanned source root, TestData will be generated)
     *       → TypeTestData.buildValidType()
     *  2. Type is in paramTypeRegistry (source found, field metadata available)
     *       → new TypeName() + typed field setters (one line per field)
     *  3. External / unknown type (not in source root at all)
     *       → new TypeName() — no-arg constructor; at least avoids NPE
     */
    private void buildParamSetup(MethodMetadata mm, StringBuilder sb, int indent,
                                  Set<String> concreteClassNames) {
        buildParamSetup(mm, sb, indent, concreteClassNames, Map.of());
    }

    private void buildParamSetup(MethodMetadata mm, StringBuilder sb, int indent,
                                  Set<String> concreteClassNames,
                                  Map<String, com.testgen.parser.ClassMetadata> paramTypeRegistry) {
        for (MethodMetadata.ParameterMetadata p : mm.parameters()) {
            String rawType   = p.type().replaceAll("<.*>", "").trim();
            String value     = defaultValue(p.type());
            boolean isDomain = value.startsWith("null"); // non-primitive, non-standard type

            if (!isDomain) {
                sb.append(i(indent)).append(p.type()).append(" ").append(p.name())
                  .append(" = ").append(value).append(";\n");
                continue;
            }

            // Check concreteClassNames first — TestData file will be generated for it
            if (concreteClassNames != null && concreteClassNames.contains(rawType)) {
                sb.append(i(indent)).append(p.type()).append(" ").append(p.name()).append(" = ")
                  .append(rawType).append("TestData.buildValid").append(rawType).append("();\n");
                continue;
            }

            // Check paramTypeRegistry — source found, generate typed inline init
            com.testgen.parser.ClassMetadata typeMeta =
                    paramTypeRegistry != null ? paramTypeRegistry.get(rawType) : null;
            if (typeMeta != null) {
                sb.append(i(indent)).append(p.type()).append(" ").append(p.name())
                  .append(" = new ").append(rawType).append("();\n");
                for (com.testgen.parser.FieldMetadata f : typeMeta.fields()) {
                    if (f.isInjected() || f.isApplicationContext() || f.isValue()) continue;
                    String fv = defaultValue(f.type());
                    if (!fv.startsWith("null")) {
                        // Only set fields where we can generate a meaningful typed value
                        sb.append(i(indent)).append(p.name()).append(".set")
                          .append(toSetterSuffix(f.name())).append("(").append(fv).append(");\n");
                    }
                }
                continue;
            }

            // External / unknown type — check defaultValue first (handles java.sql.Timestamp etc.)
            // before falling back to new Type() which may not have a no-arg constructor
            String extVal = defaultValue(rawType);
            if (!extVal.startsWith("null")) {
                sb.append(i(indent)).append(p.type()).append(" ").append(p.name())
                  .append(" = ").append(extVal).append(";\n");
            } else {
                sb.append(i(indent)).append(p.type()).append(" ").append(p.name())
                  .append(" = new ").append(rawType).append("(); // external type — set required fields manually\n");
            }
        }
    }

    /**
     * Converts a field name to a JavaBeans setter suffix, handling underscores.
     * e.g. "myField" → "MyField", "my_field" → "MyField"
     */
    private String toSetterSuffix(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return fieldName;
        if (!fieldName.contains("_")) {
            return Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        for (String part : fieldName.split("_")) {
            if (part.isEmpty()) continue;
            String lower = part.toLowerCase();
            sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return sb.toString();
    }

    private void buildDirectCall(MethodMetadata mm, String subject, StringBuilder sb, int indent) {
        String params = paramNames(mm);
        if (mm.hasReturnValue()) {
            sb.append(i(indent)).append(mm.returnType()).append(" result = ")
              .append(subject).append(".").append(mm.name()).append("(").append(params).append(");\n");
        } else {
            sb.append(i(indent)).append(subject).append(".").append(mm.name())
              .append("(").append(params).append(");\n");
        }
    }

    private void buildReflectionCall(MethodMetadata mm, String subject, StringBuilder sb, int indent) {
        String params = paramNames(mm);
        String sep = params.isEmpty() ? "" : ", ";
        if (mm.hasReturnValue()) {
            sb.append(i(indent)).append(mm.returnType()).append(" result = ")
              .append("ReflectionTestUtils.invokeMethod(")
              .append(subject).append(", \"").append(mm.name()).append("\"")
              .append(sep).append(params).append(");\n");
        } else {
            sb.append(i(indent)).append("ReflectionTestUtils.invokeMethod(")
              .append(subject).append(", \"").append(mm.name()).append("\"")
              .append(sep).append(params).append(");\n");
        }
    }

    protected String paramNames(MethodMetadata mm) {
        return mm.parameters().stream()
                .map(MethodMetadata.ParameterMetadata::name)
                .collect(Collectors.joining(", "));
    }

    protected boolean isPrimitive(String type) {
        return Set.of("int", "Integer", "long", "Long", "double", "Double",
                "float", "Float", "boolean", "Boolean", "byte", "Byte",
                "short", "Short", "char", "Character").contains(type);
    }
}
