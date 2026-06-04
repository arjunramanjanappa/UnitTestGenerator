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

        // Service-locator @Repository mock fields + method param/return types
        if (m.hasServiceLocatorRepos()) {
            m.serviceLocatorRepos().forEach(sla -> {
                usedSimpleNames.add(sla.repoType());
                sla.repoCalls().forEach(call -> {
                    // Return type raw (strip generics)
                    String retRaw = call.returnType() != null
                            ? call.returnType().replaceAll("<.*>", "").trim() : null;
                    if (retRaw != null) usedSimpleNames.add(retRaw);
                    // Inner type of Optional<X>, List<X> etc.
                    if (call.returnType() != null && call.returnType().contains("<")) {
                        String inner = call.returnType()
                                .substring(call.returnType().indexOf('<') + 1,
                                           call.returnType().lastIndexOf('>'))
                                .replaceAll("<.*>", "").trim();
                        if (!inner.isEmpty()) usedSimpleNames.add(inner);
                    }
                    call.params().forEach(p -> usedSimpleNames.add(p.type()));
                });
            });
        }

        // Static dependency classes (MockedStatic<MasterUtil> — MasterUtil needs import)
        m.methods().stream()
                .filter(mm -> mm.staticCallClasses() != null)
                .flatMap(mm -> mm.staticCallClasses().stream())
                .forEach(usedSimpleNames::add);

        // @Entity types constructed inline (MockedConstruction<TpibFtPayee>)
        if (m.hasEntityConstructions()) {
            usedSimpleNames.addAll(m.entityConstructions());
        }

        // Build FQN → simple-name map.
        // RULE: always infer from source class — never guess packages.
        // Priority: explicit imports > static member imports > same-package types
        Map<String, String> simpleToFqn = new LinkedHashMap<>();
        List<String> wildcardPackages = new ArrayList<>(); // "import com.example.*" packages

        for (String fqn : m.imports()) {
            if (fqn.endsWith(".*")) {
                // Wildcard import — package will be searched in fileIndex
                wildcardPackages.add(fqn.substring(0, fqn.length() - 2)); // strip ".*"
                continue;
            }
            String simpleName = fqn.contains(".")
                    ? fqn.substring(fqn.lastIndexOf('.') + 1)
                    : fqn;
            simpleToFqn.put(simpleName, fqn);

            // Handle static member imports: "import static com.uob.MasterUtil.METHOD_NAME"
            if (fqn.contains(".")) {
                int lastDot = fqn.lastIndexOf('.');
                int prevDot  = fqn.lastIndexOf('.', lastDot - 1);
                if (prevDot >= 0) {
                    String parentSeg = fqn.substring(prevDot + 1, lastDot);
                    if (!parentSeg.isEmpty() && Character.isUpperCase(parentSeg.charAt(0))) {
                        simpleToFqn.putIfAbsent(parentSeg, fqn.substring(0, lastDot));
                    }
                }
            }
        }

        // Resolve wildcard packages: look up class names in paramTypeRegistry and concreteClassNames
        // to find which FQN corresponds to a used simple name from a wildcard-imported package
        if (!wildcardPackages.isEmpty() && m.paramTypeRegistry() != null) {
            m.paramTypeRegistry().values().forEach(dep -> {
                if (dep.packageName() != null && wildcardPackages.contains(dep.packageName())) {
                    simpleToFqn.putIfAbsent(dep.className(), dep.fullClassName());
                }
            });
        }

        // Also add imports from paramTypeRegistry entries (repo interfaces, dep classes)
        // so types like TPIBCasCounter returned by repo methods can be resolved
        if (m.paramTypeRegistry() != null) {
            m.paramTypeRegistry().values().forEach(dep ->
                dep.imports().forEach(fqn -> {
                    String simple = fqn.contains(".")
                            ? fqn.substring(fqn.lastIndexOf('.') + 1)
                            : fqn;
                    simpleToFqn.putIfAbsent(simple, fqn); // don't override source class's imports
                }));
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
             + "import static org.mockito.Mockito.mockConstruction;\n"
             + "import org.mockito.MockedConstruction;\n"
             + "import org.springframework.context.ApplicationContext;\n"
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
        StringBuilder sb = new StringBuilder();
        sb.append(buildMockDeclarations(m.mockCandidates(), indent, m.concreteClassNames()));
        // Service-locator repos are not @Autowired fields — add @Mock for them explicitly
        if (m.hasServiceLocatorRepos()) {
            sb.append(i(indent)).append("// Service-locator @Repository mocks (obtained via makeDAO/factory)\n");
            for (com.testgen.parser.ServiceLocatorAccess sla : m.serviceLocatorRepos()) {
                sb.append(i(indent)).append("@Mock\n");
                sb.append(i(indent)).append("private ").append(sla.repoType())
                  .append(" ").append(sla.fieldName()).append(";\n\n");
            }
        }
        return sb.toString();
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
        // Also skip parent methods where ClassA has its own method with the same name
        // (overloaded — different params, no @Override). ClassA's dispatch routes to its
        // own version; the parent's version with a different signature won't be invoked.
        Set<String> ownMethodNames = m.methods().stream()
                .map(MethodMetadata::name)
                .collect(Collectors.toSet());

        // Collect all method names actually CALLED from ClassA's own testable methods.
        // Only inherited methods that ClassA actively invokes need to be stubbed —
        // if saveRecTxn is never called in ClassA, there is no execution path reaching it
        // and no stub is needed (and it may not even be accessible from the test package).
        Set<String> calledInClassA = m.methods().stream()
                .filter(MethodMetadata::isTestable)
                .flatMap(mm -> mm.helperMethodCalls().stream())
                .collect(Collectors.toSet());

        // ── B) Inherited non-overridden methods — emit as COMMENTS only ────────
        // Generic rule: inherited parent methods are NEVER emitted as active stubs.
        //
        // Reason: access modifier (protected) and package differences cannot be
        // reliably determined at generation time for all project structures.
        // Calling subject.protectedMethod() from the test class compiles only when
        // the test is in the SAME package as the declaring class — which is not
        // guaranteed when the parent is in a framework or base package.
        //
        // Category A (@Override methods) remain active — those are ClassA's own
        // methods, always accessible from ClassA's test package.
        //
        // Uncomment any stub below if the real method causes issues during tests.
        if (m.hasParentChain()) {
            for (int level = 0; level < m.parentChain().size(); level++) {
                ClassMetadata parent = m.parentChain().get(level);

                List<MethodMetadata> inheritedMethods = parent.methods().stream()
                        .filter(MethodMetadata::isTestable)
                        .filter(mm -> !mm.isFinal())
                        .filter(mm -> !overriddenNames.contains(mm.name()))
                        .filter(mm -> !ownMethodNames.contains(mm.name()))
                        .filter(mm -> calledInClassA.contains(mm.name()))
                        .toList();

                if (!inheritedMethods.isEmpty()) {
                    sb.append(i(indent))
                      .append("// B) Inherited non-overridden methods from ").append(parent.className())
                      .append(" — uncomment if real parent execution causes issues:\n");
                    for (MethodMetadata mm : inheritedMethods) {
                        String matchers = mm.parameters().stream()
                                .map(p -> mockitoMatcher(p.type()))
                                .collect(Collectors.joining(", "));
                        // Always a comment — never active — avoids protected-access compile errors
                        sb.append(i(indent)).append("// lenient().")
                          .append(mm.hasReturnValue()
                                ? "doReturn(" + typedReturnValue(mm.returnType(), m) + ")"
                                : "doNothing()")
                          .append(".when(subject).").append(mm.name()).append("(").append(matchers)
                          .append("); // inherited from ").append(parent.className()).append("\n");
                        overriddenNames.add(mm.name());
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
                .map(p -> mockitoMatcher(p.type()))
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
        // Use TestableClassName when the subclass is generated (widens protected methods)
        String typeName = needsTestablSubclass(m) ? testableName(m) : m.className();
        if (requiresSpyPattern(m)) {
            return i(indent) + "private " + typeName + " subject;\n\n";
        } else {
            return i(indent) + "@InjectMocks\n"
                 + i(indent) + "private " + typeName + " subject;\n\n";
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
        // throws Exception: required when parent/helper stubs call methods that declare checked exceptions
        sb.append(i(indent)).append("void setUp() throws Exception {\n");

        if (useSpy) {
            // Use TestableClassName when it was generated (widens protected parent methods)
            String spyType = needsTestablSubclass(m) ? testableName(m) : m.className();
            sb.append(i(indent + 1)).append(spyType).append(" rawSpy = new ").append(spyType).append("();\n");
            sb.append(i(indent + 1)).append(subject).append(" = spy(rawSpy);\n\n");

            // Inject all mocks via ReflectionTestUtils (BAU field injection — source not modified)
            for (FieldMetadata f : m.mockCandidates()) {
                if (!f.isApplicationContext()) {
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
            // If using testable subclass, inject service-locator mocks via _mockDaos map
            if (needsTestablSubclass(m) && m.hasServiceLocatorRepos()) {
                sb.append(i(indent + 1)).append("// Inject service-locator mocks into testable subclass\n");
                for (com.testgen.parser.ServiceLocatorAccess sla : m.serviceLocatorRepos()) {
                    sb.append(i(indent + 1)).append("((").append(testableName(m)).append(") rawSpy)._mockDaos.put(")
                      .append(sla.repoType()).append(".BEAN_ID, ").append(sla.fieldName()).append(");\n");
                }
            } else if (m.hasServiceLocatorRepos()) {
                sb.append(buildServiceLocatorStubs(m, subject, indent + 1));
            }
            // Stub internal helpers on spy (Pattern D)
            sb.append(buildHelperMethodStubs(m, subject, indent + 1));
            // Stub parent methods on spy (Patterns A/B)
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

    // ── Generic accessibility helper ────────────────────────────────────────

    /**
     * Returns true only if methodName is declared in ClassA's OWN public/protected methods.
     *
     * Generic rule applied to ALL active lenient() stubs:
     *   lenient().when(subject).method() compiles ONLY when 'method' belongs to
     *   ClassA itself — because the test class is in ClassA's package.
     *
     *   Inherited methods (from parent ClassB in a different package) with protected
     *   access are NOT callable from the test class, causing compile errors.
     *   → emit as a comment instead of active code.
     */
    protected boolean isOwnAccessibleMethod(String methodName, ClassMetadata m) {
        return m.methods().stream()
                .filter(mm -> mm.isPublic() || mm.isProtected())
                .anyMatch(mm -> mm.name().equals(methodName));
    }

    // ── Service-locator repo stubs ──────────────────────────────────────────

    /**
     * Stubs service-locator calls (makeDAO / factory methods) on the spy to return
     * the mocked @Repository instead of hitting the real service locator at runtime.
     *
     *   TPIBFTPayeeRepo repo = (TPIBFTPayeeRepo) makeDAO(BEAN_ID)
     *   →  lenient().doReturn(tpibFTPayeeRepo).when(subject).makeDAO(any());
     */
    protected String buildServiceLocatorStubs(ClassMetadata m, String subject, int indent) {
        if (!m.hasServiceLocatorRepos()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("// Service-locator stubs — return mocked @Repository instead of real DAO\n");
        for (com.testgen.parser.ServiceLocatorAccess sla : m.serviceLocatorRepos()) {
            // Stub the service-locator method (e.g. makeDAO) on the spy.
            // If makeDAO is from a parent class (protected, different package), calling
            // subject.makeDAO() from the test won't compile — emit as a comment.
            String locatorStub = "lenient().doReturn(" + sla.fieldName() + ").when("
                    + subject + ")." + sla.locatorMethod() + "(any());";
            if (isOwnAccessibleMethod(sla.locatorMethod(), m)) {
                sb.append(i(indent)).append(locatorStub).append("\n");
            } else {
                sb.append(i(indent)).append("// ").append(locatorStub)
                  .append(" // TODO: ").append(sla.locatorMethod())
                  .append("() is from parent class — verify access before enabling\n");
            }

            // Stub each detected method call on the repo — prevents NPE and covers DB call lines
            for (com.testgen.parser.ServiceLocatorAccess.RepoCall call : sla.repoCalls()) {
                String matchers = call.params().stream()
                        .map(p -> mockitoMatcher(p.type()))
                        .collect(Collectors.joining(", "));
                String returnVal = repoReturnValue(call.returnType(), m);
                sb.append(i(indent))
                  .append("lenient().when(").append(sla.fieldName()).append(".")
                  .append(call.methodName()).append("(").append(matchers).append("))")
                  .append(".thenReturn(").append(returnVal).append(");\n");
            }
        }
        return sb.toString();
    }

    /**
     * Returns a compilable return value for a repository mock stub.
     *
     * For collection/optional types: return empty instances.
     * For single entity/domain types: use mock(Type.class) — always compiles,
     *   prevents JPA lifecycle hooks, doesn't require a TestData class to exist.
     * For primitives: use typed literals.
     */
    private String repoReturnValue(String returnType, ClassMetadata m) {
        if (returnType == null || returnType.isBlank()) return "null";
        String raw = returnType.replaceAll("<.*>", "").trim();

        // Extract the inner type for generics (e.g. "Optional<TPIBCasCounter>" → "TPIBCasCounter")
        String inner = returnType.contains("<")
                ? returnType.substring(returnType.indexOf('<') + 1, returnType.lastIndexOf('>')).trim()
                : null;

        return switch (raw) {
            case "List"       -> "new java.util.ArrayList<>()";
            case "Set"        -> "new java.util.HashSet<>()";
            case "Collection" -> "new java.util.ArrayList<>()";
            case "Optional"   -> inner != null
                    ? "java.util.Optional.of(mock(" + inner.replaceAll("<.*>","").trim() + ".class))"
                    : "java.util.Optional.empty()";
            case "void"       -> "";
            case "long", "Long", "int", "Integer" -> "0";
            case "boolean", "Boolean" -> "false";
            case "String"     -> "\"testValue\"";
            default -> {
                if (!raw.isEmpty() && Character.isUpperCase(raw.charAt(0))) {
                    // concreteClassNames contains ONLY non-interface, non-abstract classes.
                    // @Repository interfaces are NOT in concreteClassNames — they get mock().
                    // Plain entity/DTO classes ARE in concreteClassNames — they get TestData.
                    if (m.concreteClassNames() != null && m.concreteClassNames().contains(raw)) {
                        yield raw + "TestData.buildValid" + raw + "()";
                    }
                    // Interface, abstract class, @Repository, external type → always mock()
                    yield "mock(" + raw + ".class)";
                }
                yield "null";
            }
        };
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
                          .map(p -> mockitoMatcher(p.type()))
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

        // Only generate branch tests when the condition AFFECTS observable behaviour:
        // - method has a return value (different branch → different output), OR
        // - method has service-locator / repo calls (different branch → different dep called)
        // Skip branch tests for void methods with no dep interactions — not meaningful.
        boolean affectsOutput = mm.hasReturnValue()
                || m.hasServiceLocatorRepos()
                || !m.mockCandidates().isEmpty();
        if (!affectsOutput) return "";

        StringBuilder sb = new StringBuilder();
        String throwsDecl = checkedThrowsClause(mm);

        // Only generate ONE pair per method (most impactful condition).
        // Generating one pair per IfStmt leads to over-mocking with empty assertions.
        if (mm.hasConditionScenarios()) {
            // Use the first detected condition scenario only
            com.testgen.parser.ConditionScenario sc = mm.conditionScenarios().get(0);
            String ownerTestData = m.className() + "TestData";

            // TRUE branch
            String trueName = convention.unitTestMethod(mm.name(), "when_" + sc.trueLabel(), buildParamSuffix(mm));
            sb.append(i(indent)).append("@Test\n");
            sb.append(i(indent)).append("void ").append(trueName).append("()").append(throwsDecl).append(" {\n");
            sb.append(i(indent + 1)).append("// ").append(sc.paramName()).append(".")
              .append(sc.fieldName()).append(" = ").append(sc.trueSetExpr()).append(" → TRUE branch\n");
            sb.append(i(indent + 1)).append(sc.paramType()).append(" ").append(sc.paramName())
              .append(" = ").append(ownerTestData).append(".").append(sc.trueMethodName()).append("();\n");
            if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
            if (mm.hasReturnValue()) sb.append(i(indent + 1)).append("assertNotNull(result);\n");
            else sb.append(i(indent + 1)).append("// TODO: verify dependency interaction for TRUE branch\n");
            sb.append(i(indent)).append("}\n\n");

            // FALSE branch
            String falseName = convention.unitTestMethod(mm.name(), "when_" + sc.falseLabel(), buildParamSuffix(mm));
            sb.append(i(indent)).append("@Test\n");
            sb.append(i(indent)).append("void ").append(falseName).append("()").append(throwsDecl).append(" {\n");
            sb.append(i(indent + 1)).append("// ").append(sc.paramName()).append(".")
              .append(sc.fieldName()).append(" = ").append(sc.falseSetExpr()).append(" → FALSE branch\n");
            sb.append(i(indent + 1)).append(sc.paramType()).append(" ").append(sc.paramName())
              .append(" = ").append(ownerTestData).append(".").append(sc.falseMethodName()).append("();\n");
            if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
            if (mm.hasReturnValue()) sb.append(i(indent + 1)).append("assertNotNull(result);\n");
            else sb.append(i(indent + 1)).append("// TODO: verify dependency interaction for FALSE branch\n");
            sb.append(i(indent)).append("}\n\n");
            return sb.toString();
        }

        // Generic fallback — only 1 pair, only when behaviour is affected
        String trueName = convention.unitTestMethod(mm.name(), "when_condition_isTrue", buildParamSuffix(mm));
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(trueName).append("()").append(throwsDecl).append(" {\n");
        sb.append(i(indent + 1)).append("// TRUE branch — configure input/mock to trigger condition\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());
        sb.append(i(indent + 1)).append("// TODO: set condition field via param or ReflectionTestUtils\n");
        if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
        if (mm.hasReturnValue()) sb.append(i(indent + 1)).append("assertNotNull(result);\n");
        sb.append(i(indent)).append("}\n\n");

        String falseName = convention.unitTestMethod(mm.name(), "when_condition_isFalse", buildParamSuffix(mm));
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(falseName).append("()").append(throwsDecl).append(" {\n");
        sb.append(i(indent + 1)).append("// FALSE branch — configure input/mock for the negative condition\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());
        sb.append(i(indent + 1)).append("// TODO: set condition field via param or ReflectionTestUtils\n");
        if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
        if (mm.hasReturnValue()) sb.append(i(indent + 1)).append("assertNotNull(result);\n");
        else sb.append(i(indent + 1)).append("// TODO: verify dependency interaction for FALSE branch\n");
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

    // JDK classes that should NEVER be mocked statically
    private static final Set<String> JDK_CLASSES = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
            "Math", "System", "Arrays", "Collections", "Objects", "Optional",
            "List", "Map", "Set", "ArrayList", "HashMap", "HashSet", "LinkedList",
            "BigDecimal", "BigInteger", "UUID", "Date", "Calendar",
            "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime", "Instant",
            "NumberFormat", "DecimalFormat", "SimpleDateFormat",
            "StringBuilder", "StringBuffer", "Thread", "Runtime",
            "Class", "Object", "Enum", "Throwable", "Exception", "Error"
    );

    protected String buildStaticMockTests(MethodMetadata mm, String subject,
                                           ClassMetadata m, int indent) {
        if (!mm.hasStaticDependencies()) return "";
        StringBuilder sb = new StringBuilder();
        String throwsDecl = checkedThrowsClause(mm);

        for (String staticClass : mm.staticCallClasses()) {
            // Only mock business/external utilities — never JDK classes
            if (JDK_CLASSES.contains(staticClass)) continue;
            // Skip if no calls detected with tokens (safety check)
            if (mm.staticCallTokens() == null) continue;

            // Only generate if we have a meaningful behavior to add (class in source or known)
            // Skip if the class is likely a JDK utility based on lowercase package hint
            if (staticClass.length() < 3) continue;

            String testName = convention.unitTestMethod(mm.name(),
                    "with_" + staticClass + "_mocked", buildParamSuffix(mm));
            sb.append(i(indent)).append("@Test\n");
            sb.append(i(indent)).append("void ").append(testName).append("()").append(throwsDecl).append(" {\n");
            sb.append(i(indent + 1)).append("try (MockedStatic<").append(staticClass)
              .append("> mockedStatic = mockStatic(").append(staticClass).append(".class)) {\n");
            // Generate type-aware behavior for each detected static call
            boolean hasBehavior = false;
            if (mm.staticCallTokens() != null) {
                for (String token : mm.staticCallTokens()) {
                    if (!token.startsWith(staticClass + ".")) continue;
                    int colon = token.lastIndexOf(':');
                    String methodName = token.substring(staticClass.length() + 1, colon);
                    String key = staticClass + "." + methodName;
                    String retType = m.resolvedStaticTypes() != null
                            ? m.resolvedStaticTypes().get(key) : null;
                    if (retType != null && !"void".equals(retType)) {
                        String retVal = typedReturnValue(retType, m);
                        sb.append(i(indent + 2))
                          .append("mockedStatic.when(() -> ").append(staticClass).append(".")
                          .append(methodName).append("(any())).thenReturn(").append(retVal).append(");\n");
                        hasBehavior = true;
                    }
                }
            }
            if (!hasBehavior) {
                sb.append(i(indent + 2)).append("// TODO: mockedStatic.when(() -> ").append(staticClass)
                  .append(".method(any())).thenReturn(expectedValue);\n");
            }
            buildParamSetup(mm, sb, indent + 2, m.concreteClassNames(), m.paramTypeRegistry());
            if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 2);
            if (mm.hasReturnValue()) {
                sb.append(i(indent + 2)).append("assertNotNull(result);\n");
            }
            sb.append(i(indent + 1)).append("}\n");
            sb.append(i(indent)).append("}\n\n");
        }
        return sb.toString();
    }

    // ── @Entity MockedConstruction test ─────────────────────────────────────

    /**
     * Generates a test that intercepts inline 'new EntityClass()' constructions
     * using Mockito's MockedConstruction.
     *
     * When a method body contains 'new TpibFtPayee()', the entity's JPA lifecycle
     * hooks or validators could fire. MockedConstruction replaces the real object
     * with a mock, preventing any DB-related behaviour during the unit test.
     */
    protected String buildEntityConstructionTest(MethodMetadata mm, String subject,
                                                  ClassMetadata m, int indent) {
        // Only include entity types actually constructed in this specific method
        List<String> methodEntityTypes = mm.constructedTypes().stream()
                .filter(t -> m.entityConstructions().contains(t))
                .toList();
        if (methodEntityTypes.isEmpty()) return "";

        String testName = convention.unitTestMethod(mm.name(),
                "withEntityMock", buildParamSuffix(mm));
        String throwsDecl = checkedThrowsClause(mm);
        StringBuilder sb = new StringBuilder();

        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(testName).append("()").append(throwsDecl).append(" {\n");
        sb.append(i(indent + 1))
          .append("// @Entity types are created inline — MockedConstruction intercepts new X()\n");
        sb.append(i(indent + 1))
          .append("// preventing JPA lifecycle hooks / validators from firing during unit test\n");

        // Open one try-with-resources per entity type
        int extraIndent = 0;
        for (String entityType : methodEntityTypes) {
            sb.append(i(indent + 1 + extraIndent))
              .append("try (MockedConstruction<").append(entityType).append("> mocked")
              .append(entityType).append(" = mockConstruction(").append(entityType).append(".class)) {\n");
            extraIndent++;
        }

        int bodyIndent = indent + 1 + extraIndent;
        buildParamSetup(mm, sb, bodyIndent, m.concreteClassNames(), m.paramTypeRegistry());
        if (!mm.isProtected()) buildDirectCall(mm, subject, sb, bodyIndent);

        // Capture and assert on mocked entity — guard against IndexOutOfBoundsException:
        // constructed() is empty if the method threw before reaching 'new EntityType()'
        for (String entityType : methodEntityTypes) {
            sb.append(i(bodyIndent)).append("// Safe access: verify entity was actually constructed\n");
            sb.append(i(bodyIndent))
              .append("assertFalse(mocked").append(entityType)
              .append(".constructed().isEmpty(), \"Expected new ").append(entityType)
              .append("() to be called — check method conditions\");\n");
            sb.append(i(bodyIndent)).append(entityType).append(" mocked = mocked")
              .append(entityType).append(".constructed().get(0);\n");
            sb.append(i(bodyIndent)).append("assertNotNull(mocked);\n");
            sb.append(i(bodyIndent))
              .append("// TODO: verify(mocked).setField(expectedValue); or verify(repository).save(mocked);\n");
        }

        // Close try-with-resources blocks
        for (int i = extraIndent; i > 0; i--) {
            sb.append(i(indent + i)).append("}\n");
        }
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // ── Testable subclass generation ────────────────────────────────────────

    /**
     * Returns true when a testable subclass is needed because:
     * - The class has protected methods from a different-package parent that are
     *   called by ClassA's own code (need widening to public for test access)
     * - OR the service-locator method (makeDAO) is inherited protected and cannot
     *   be called directly from the test
     */
    protected boolean needsTestablSubclass(ClassMetadata m) {
        if (!m.hasSuperClass()) return false;

        // Service locator from parent — makeDAO can't be called from test package
        if (m.hasServiceLocatorRepos()) {
            boolean locatorFromParent = m.serviceLocatorRepos().stream()
                    .anyMatch(sla -> !isOwnAccessibleMethod(sla.locatorMethod(), m));
            if (locatorFromParent) return true;
        }

        // Protected parent methods that ClassA calls but are inaccessible from test
        if (!m.hasParentChain()) return false;
        Set<String> calledInClassA = m.methods().stream()
                .filter(MethodMetadata::isTestable)
                .flatMap(mm -> mm.helperMethodCalls().stream())
                .collect(Collectors.toSet());
        return m.parentChain().stream().anyMatch(parent -> {
            boolean samePackage = m.packageName().equals(parent.packageName());
            if (samePackage) return false;
            return parent.methods().stream()
                    .filter(mm -> mm.isProtected() && !mm.isPublic())
                    .anyMatch(mm -> calledInClassA.contains(mm.name()));
        });
    }

    /**
     * Returns "Testable" + className — the type used for the spy subject.
     */
    protected String testableName(ClassMetadata m) {
        return "Testable" + m.className();
    }

    /**
     * Generates a static inner testable subclass that:
     * 1. Widens protected methods from the parent to public so the test can call them
     * 2. Overrides service-locator (makeDAO) to return injected mock dependencies
     *
     * Example:
     *   static class TestableClassA extends ClassA {
     *       // Service-locator override — returns injected mocks
     *       final Map<String,Object> _mockDaos = new HashMap<>();
     *
     *       @Override protected Object makeDAO(String beanId) {
     *           Object m = _mockDaos.get(beanId); return m != null ? m : super.makeDAO(beanId);
     *       }
     *
     *       // Widened protected methods
     *       @Override public void saveRecTxn(FTBasevo ftBasevo) throws Exception {
     *           super.saveRecTxn(ftBasevo);
     *       }
     *   }
     */
    protected String buildTestablSubclass(ClassMetadata m, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("/**\n");
        sb.append(i(indent)).append(" * Testable subclass — widens protected parent methods to public\n");
        sb.append(i(indent)).append(" * and overrides service-locator to return injected mock dependencies.\n");
        sb.append(i(indent)).append(" */\n");
        sb.append(i(indent)).append("static class ").append(testableName(m)).append(" extends ")
          .append(m.className()).append(" {\n\n");

        // Service-locator override
        boolean hasLocatorOverride = m.hasServiceLocatorRepos() && m.serviceLocatorRepos().stream()
                .anyMatch(sla -> !isOwnAccessibleMethod(sla.locatorMethod(), m));
        if (hasLocatorOverride) {
            sb.append(i(indent + 1)).append("// Inject mock DAOs here — used by makeDAO override\n");
            sb.append(i(indent + 1)).append("final java.util.Map<String, Object> _mockDaos = new java.util.HashMap<>();\n\n");

            // Find the locator method signature from parent chain
            String locatorMethod = m.serviceLocatorRepos().get(0).locatorMethod();
            sb.append(i(indent + 1)).append("@Override\n");
            sb.append(i(indent + 1)).append("protected Object ").append(locatorMethod).append("(String beanId) {\n");
            sb.append(i(indent + 2)).append("Object mock = _mockDaos.get(beanId);\n");
            sb.append(i(indent + 2)).append("return mock != null ? mock : null; // return null if not found (avoids real context)\n");
            sb.append(i(indent + 1)).append("}\n\n");
        }

        // Widen ALL protected inherited methods from different-package parents.
        // Visibility rule:
        //   protected in superclass + test in different package
        //   → do NOT stub with Mockito (can't call from test package)
        //   → override here with ISOLATED implementation (no super call)
        //
        // Isolation strategy:
        //   void method  → empty body  (no side effects, fully controlled)
        //   return type  → typed default return (no parent logic executed)
        //   service loc  → handled above via _mockDaos map
        if (m.hasParentChain()) {
            Set<String> added = new HashSet<>();
            for (ClassMetadata parent : m.parentChain()) {
                if (m.packageName().equals(parent.packageName())) continue;
                for (MethodMetadata pm : parent.methods()) {
                    if (!pm.isProtected() || pm.isPublic()) continue;
                    if (pm.isFinal()) continue;
                    if (!added.add(pm.name())) continue;

                    String params = pm.parameters().stream()
                            .map(p -> p.type() + " " + p.name())
                            .collect(Collectors.joining(", "));
                    String throwsDecl = pm.throwsExceptions()
                            ? " throws " + String.join(", ", pm.thrownExceptions()) : "";
                    sb.append(i(indent + 1)).append("@Override\n");
                    sb.append(i(indent + 1)).append("public ")
                      .append(pm.returnType()).append(" ").append(pm.name())
                      .append("(").append(params).append(")").append(throwsDecl).append(" {\n");
                    if (pm.hasReturnValue()) {
                        // Typed default return — parent logic NOT executed
                        String retVal = typedReturnValue(pm.returnType(), m);
                        sb.append(i(indent + 2)).append("return ").append(retVal)
                          .append("; // isolated — parent ").append(parent.className())
                          .append(".").append(pm.name()).append("() NOT called\n");
                    } else {
                        // void — empty body, fully isolated
                        sb.append(i(indent + 2)).append("// void isolated — parent ")
                          .append(parent.className()).append(".").append(pm.name())
                          .append("() NOT called\n");
                    }
                    sb.append(i(indent + 1)).append("}\n\n");
                }
            }
        }

        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    /** Returns the name of the best dependency mock to use for triggering an exception.
     *  Prefers service-locator repos → injected mocks → null if none available. */
    private String pickExceptionTriggerDep(ClassMetadata m) {
        // Prefer service-locator repos (most likely to be the trigger in BAU code)
        if (m.hasServiceLocatorRepos()) {
            return m.serviceLocatorRepos().get(0).fieldName();
        }
        // Fallback to first non-ApplicationContext mock
        return m.mockCandidates().stream()
                .filter(f -> !f.isApplicationContext())
                .map(FieldMetadata::name)
                .findFirst()
                .orElse(null);
    }

    // ── Pattern H: exception flow test ──────────────────────────────────────

    protected String buildExceptionFlowTest(MethodMetadata mm, String subject,
                                             ClassMetadata m, int indent) {
        if (!mm.hasTryCatch() && !mm.throwsExceptions()) return "";
        String exType  = primaryException(mm) != null ? primaryException(mm) : "Exception";
        String testName = convention.unitTestMethod(mm.name(), "whenExceptionOccurs", buildParamSuffix(mm));
        StringBuilder sb = new StringBuilder();

        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(testName).append("() throws Exception {\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());

        // Trigger exception via a DEPENDENCY mock — not via the subject itself.
        // Stubbing the subject would bypass the code under test (testing Mockito, not your code).
        String depForThrow = pickExceptionTriggerDep(m);
        if (depForThrow != null) {
            sb.append(i(indent + 1))
              .append("// Trigger ").append(exType).append(" by making a dependency throw\n");
            sb.append(i(indent + 1))
              .append("doThrow(new ").append(exType).append("(\"test\"))")
              .append(".when(").append(depForThrow).append(").anyMethod(any());\n");
            sb.append(i(indent + 1)).append("// TODO: replace anyMethod() with the actual dep method that causes the throw\n");
        } else {
            sb.append(i(indent + 1))
              .append("// TODO: configure a dependency to throw ").append(exType)
              .append(" — do NOT stub subject.").append(mm.name()).append("() directly\n");
        }

        sb.append(i(indent + 1)).append("assertThrows(").append(exType).append(".class, () ->\n");
        if (mm.isProtected()) {
            sb.append(i(indent + 2)).append("ReflectionTestUtils.invokeMethod(subject, \"")
              .append(mm.name()).append("\"")
              .append(mm.parameters().isEmpty() ? "" : ", " + paramNames(mm)).append("));\n");
        } else {
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
        sb.append(i(indent + 1)).append("// Test public method — private methods execute naturally via this call\n");
        sb.append(i(indent + 1)).append("// Control behaviour by mocking dependencies (DAO, static utils, repos)\n");
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

        // Branch tests — only when condition affects behaviour
        sb.append(buildBranchTests(mm, subject, m, indent));

        // Additional tests ONLY for high-value methods (complex logic, not simple getters)
        if (isHighValueMethod(mm)) {
            // Numeric boundary tests
            sb.append(buildBoundaryTests(mm, subject, m, indent));
            // Static dependency mock tests (non-JDK only)
            sb.append(buildStaticMockTests(mm, subject, m, indent));
            // Exception flow test (try/catch)
            if (mm.hasTryCatch()) {
                sb.append(buildExceptionFlowTest(mm, subject, m, indent));
            }
        }

        // @Entity inline construction — use MockedConstruction to intercept new X()
        if (mm.hasConstructedTypes() && m.hasEntityConstructions()) {
            sb.append(buildEntityConstructionTest(mm, subject, m, indent));
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
          .append(convention.exceptionTestMethod(mm.name(), exType)).append("() throws Exception {\n");
        sb.append(i(indent + 1)).append("// given\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());

        // Trigger exception via DEPENDENCY — not by stubbing the subject.
        // doThrow().when(subject).method() tests Mockito stubbing, not your code.
        String depForThrow2 = pickExceptionTriggerDep(m);
        if (depForThrow2 != null) {
            sb.append(i(indent + 1))
              .append("doThrow(new ").append(exType).append("(\"test\"))")
              .append(".when(").append(depForThrow2).append(").anyMethod(any());\n");
            sb.append(i(indent + 1)).append("// TODO: replace anyMethod() with the actual dep method\n");
        } else {
            sb.append(i(indent + 1))
              .append("// TODO: configure a dependency to throw ").append(exType).append("\n");
        }

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
        // Service-locator repo verify hints — actual method names from detection
        if (m.hasServiceLocatorRepos()) {
            sb.append(i(indent)).append("// Verify service-locator @Repository interactions:\n");
            for (com.testgen.parser.ServiceLocatorAccess sla : m.serviceLocatorRepos()) {
                if (sla.repoCalls().isEmpty()) {
                    // Fallback when no specific calls detected
                    sb.append(i(indent))
                      .append("// verify(").append(sla.fieldName()).append(").save(any());\n");
                } else {
                    for (com.testgen.parser.ServiceLocatorAccess.RepoCall call : sla.repoCalls()) {
                        String matchers = call.params().stream()
                                .map(p -> mockitoMatcher(p.type()))
                                .collect(Collectors.joining(", "));
                        sb.append(i(indent))
                          .append("// verify(").append(sla.fieldName()).append(").")
                          .append(call.methodName()).append("(").append(matchers).append(");\n");
                    }
                }
            }
        }
        if (!mm.parameters().isEmpty()) {
            sb.append(i(indent)).append("// Verify interactions using initialized params:\n");
            for (MethodMetadata.ParameterMetadata p : mm.parameters()) {
                String rawType = p.type().replaceAll("<.*>", "").trim();
                boolean isDomain = defaultValue(p.type()).startsWith("null");
                String matcher = isDomain ? mockitoMatcher(rawType) : p.name();
                sb.append(i(indent))
                  .append("// verify(<mockDep>).<method>(").append(matcher).append(");\n");
            }
        }
        if (mm.parameters().isEmpty() && !m.hasServiceLocatorRepos()) {
            sb.append(i(indent)).append("// TODO: verify(mockDep).someMethod();\n");
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
                          .append(toSetterSuffix(f.name(), f.type())).append("(").append(fv).append(");\n");
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
    /**
     * Converts a field name to the JavaBeans setter suffix.
     *
     * Boolean fields starting with 'is' lose the prefix (Lombok convention):
     *   isHoldRequired  (boolean)  →  HoldRequired   → setHoldRequired
     *   holdRequired    (boolean)  →  HoldRequired   → setHoldRequired
     *   isActive        (boolean)  →  Active         → setActive
     *   transactionId   (String)   →  TransactionId  → setTransactionId
     */
    private String toSetterSuffix(String fieldName) {
        return toSetterSuffix(fieldName, null);
    }

    private String toSetterSuffix(String fieldName, String fieldType) {
        if (fieldName == null || fieldName.isEmpty()) return fieldName;

        // Boolean field with 'is' prefix — strip it (Lombok / IDE convention)
        boolean isBool = "boolean".equals(fieldType) || "Boolean".equals(fieldType);
        if (isBool && fieldName.startsWith("is") && fieldName.length() > 2
                && Character.isUpperCase(fieldName.charAt(2))) {
            return fieldName.substring(2); // isHoldRequired → HoldRequired
        }

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

    // ── Test quality filter ──────────────────────────────────────────────────

    /**
     * Returns true for methods worth generating additional pattern tests (boundary, static, exception).
     *
     * A method is HIGH-VALUE if it has real logic:
     *   - Has conditionals, try/catch, numeric comparisons, static calls, or helper calls
     *   - Not a simple getter (getName) or setter (setName)
     *
     * Simple getters/setters with no logic → skip pattern tests, just generate the success stub.
     */
    protected boolean isHighValueMethod(MethodMetadata mm) {
        // Explicitly skip simple getter / setter patterns
        String name = mm.name();
        if ((name.startsWith("get") || name.startsWith("is")) && mm.parameters().isEmpty()) return false;
        if (name.startsWith("set") && mm.parameters().size() == 1) return false;

        // High-value: has any form of complexity
        return mm.hasConditionals()
                || mm.hasTryCatch()
                || mm.hasNumericComparisons()
                || mm.hasStaticDependencies()
                || mm.hasHelperCalls()
                || !mm.thrownExceptions().isEmpty()
                || mm.parameters().size() > 1;
    }

    // ── Mockito matcher helpers ──────────────────────────────────────────────

    /**
     * Returns the correct Mockito ArgumentMatcher for a given parameter type.
     *
     * Primitives require specific matchers — any(int.class) does NOT compile:
     *   int/Integer   → anyInt()
     *   long/Long     → anyLong()
     *   double/Double → anyDouble()
     *   float/Float   → anyFloat()
     *   boolean/Bool  → anyBoolean()
     *   byte/Byte     → anyByte()
     *   short/Short   → anyShort()
     *   char/Char     → anyChar()
     *   String        → anyString()
     *   Object/other  → any(TypeName.class)
     */
    protected String mockitoMatcher(String rawType) {
        String type = rawType.replaceAll("<.*>", "").trim();
        return switch (type) {
            case "int",     "Integer"   -> "anyInt()";
            case "long",    "Long"      -> "anyLong()";
            case "double",  "Double"    -> "anyDouble()";
            case "float",   "Float"     -> "anyFloat()";
            case "boolean", "Boolean"   -> "anyBoolean()";
            case "byte",    "Byte"      -> "anyByte()";
            case "short",   "Short"     -> "anyShort()";
            case "char",    "Character" -> "anyChar()";
            case "String"               -> "anyString()";
            default                     -> "any(" + type + ".class)";
        };
    }

    protected boolean isPrimitive(String type) {
        return Set.of("int", "Integer", "long", "Long", "double", "Double",
                "float", "Float", "boolean", "Boolean", "byte", "Byte",
                "short", "Short", "char", "Character").contains(type);
    }
}
