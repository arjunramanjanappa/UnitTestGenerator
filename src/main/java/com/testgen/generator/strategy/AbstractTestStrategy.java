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

    // â”€â”€ Known AOP / proxy-based annotations â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

    // All well-known annotations â€” anything else on a method is treated as custom AOP
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

    // â”€â”€ Indentation helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    protected String i(int n) {
        return "    ".repeat(n);
    }

    // â”€â”€ Default value literals â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            // Fully-qualified so no extra imports are needed in the generated test
            case "BigDecimal"    -> "java.math.BigDecimal.ONE";
            case "BigInteger"    -> "java.math.BigInteger.ONE";
            case "LocalDate"     -> "java.time.LocalDate.now()";
            case "LocalDateTime" -> "java.time.LocalDateTime.now()";
            case "LocalTime"     -> "java.time.LocalTime.now()";
            case "ZonedDateTime" -> "java.time.ZonedDateTime.now()";
            case "OffsetDateTime"-> "java.time.OffsetDateTime.now()";
            case "Instant"       -> "java.time.Instant.now()";
            case "UUID"          -> "java.util.UUID.randomUUID()";
            case "Timestamp",
                 "java.sql.Timestamp"  -> "new java.sql.Timestamp(System.currentTimeMillis())";
            case "Date",
                 "java.sql.Date"       -> "new java.sql.Date(System.currentTimeMillis())";
            case "Time",
                 "java.sql.Time"       -> "new java.sql.Time(System.currentTimeMillis())";
            case "java.util.Date"      -> "new java.util.Date()";
            case "void"          -> "";
            case "List"          -> "java.util.List.of()";
            case "Map"           -> "java.util.Map.of()";
            case "Set"           -> "java.util.Set.of()";
            case "Optional"      -> "java.util.Optional.empty()";
            default              -> "null";
        };
    }

    // â”€â”€ Dependency import resolution â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

        // Static dependency classes (MockedStatic<MasterUtil> â€” MasterUtil needs import)
        m.methods().stream()
                .filter(mm -> mm.staticCallClasses() != null)
                .flatMap(mm -> mm.staticCallClasses().stream())
                .forEach(usedSimpleNames::add);

        // @Entity types constructed inline (MockedConstruction<TpibFtPayee>)
        if (m.hasEntityConstructions()) {
            usedSimpleNames.addAll(m.entityConstructions());
        }

        // App-context repo types (separate @Mock fields)
        if (m.hasAppContextRepos()) {
            m.appContextRepos().forEach(sla -> usedSimpleNames.add(sla.repoType()));
        }

        // Field-call return types â€” used in mock(ReturnType.class) stubs in success tests.
        // Covers interfaces/entities returned by repo/dao methods that we mock.
        if (m.fieldCallReturnTypes() != null) {
            m.fieldCallReturnTypes().values().forEach(rt -> {
                if (rt == null) return;
                String raw = rt.replaceAll("<.*>", "").trim();
                if (!raw.isEmpty()) usedSimpleNames.add(raw);
                if (rt.contains("<")) {                       // inner of Optional<X>/List<X>
                    String inner = rt.substring(rt.indexOf('<') + 1, rt.lastIndexOf('>'))
                            .replaceAll("<.*>", "").trim();
                    if (!inner.isEmpty()) usedSimpleNames.add(inner);
                }
            });
        }

        // Build FQN â†’ simple-name map.
        // RULE: always infer from source class â€” never guess packages.
        // Priority: explicit imports > static member imports > same-package types
        Map<String, String> simpleToFqn = new LinkedHashMap<>();
        List<String> wildcardPackages = new ArrayList<>(); // "import com.example.*" packages

        for (String fqn : m.imports()) {
            if (fqn.endsWith(".*")) {
                // Wildcard import â€” package will be searched in fileIndex
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
            // Strip generic part if present (e.g. "List<Order>" â†’ "Order")
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

    // â”€â”€ Common import blocks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Spring Boot 3.4 moved @MockBean â†’ @MockitoBean (new package).
     * We pick the right import based on the detected target project version.
     */
    protected String commonImports(String springBootVersion) {
        return commonImports();
    }

    protected String commonImports() {
        // Lean import set. org.mockito.* covers Mock/Spy/InjectMocks/MockedStatic;
        // static Mockito.* covers mock/spy/when/doThrow/verify/mockStatic/RETURNS_DEEP_STUBS.
        return "import org.junit.jupiter.api.*;\n"
             + "import org.junit.jupiter.api.extension.ExtendWith;\n"
             + "import org.mockito.*;\n"
             + "import org.mockito.junit.jupiter.MockitoExtension;\n"
             + "import org.springframework.test.util.ReflectionTestUtils;\n"
             + "import java.util.*;\n"
             + "import static org.junit.jupiter.api.Assertions.*;\n"
             + "import static org.mockito.Mockito.*;\n"
             + "import static org.mockito.ArgumentMatchers.*;\n";
    }

    /** @MockBean removed â€” use @Mock everywhere to avoid spring-boot-test dependency issues. */
    protected String mockBeanAnnotation(String springBootVersion) {
        return "@Mock";
    }

    // â”€â”€ Mock / MockBean declarations (Feature 4: @Spy for concrete types) â”€â”€â”€â”€

    /**
     * Emits @Mock or @Spy for each injected field based on whether the field type
     * is a concrete class found in the scanned source root.
     * â€“ Interface / unknown type â†’ @Mock  (safe, works everywhere)
     * â€“ Concrete class found in source â†’ @Spy  (calls real methods unless stubbed)
     */
    protected String buildMockDeclarations(ClassMetadata m, int indent) {
        StringBuilder sb = new StringBuilder();
        // Rule 1/6: only mock injected fields that are ACTUALLY called somewhere.
        // ApplicationContext is always kept (needed for getBean-style lookups).
        Set<String> called = calledFieldNames(m);
        List<FieldMetadata> usedMocks = m.mockCandidates().stream()
                .filter(f -> f.isApplicationContext() || called.contains(f.name()))
                .toList();
        sb.append(buildMockDeclarations(usedMocks, indent, m.concreteClassNames()));

        // Rule 3: only emit service-locator repos that have PROVEN calls (real pattern).
        if (m.hasServiceLocatorRepos()) {
            for (com.testgen.parser.ServiceLocatorAccess sla : m.serviceLocatorRepos()) {
                if (sla.repoCalls() == null || sla.repoCalls().isEmpty()) continue; // skip fake/unused
                sb.append(i(indent)).append("@Mock\n");
                sb.append(i(indent)).append("private ").append(sla.repoType())
                  .append(" ").append(sla.fieldName()).append(";\n\n");
            }
        }
        if (m.hasAppContextRepos()) {
            for (com.testgen.parser.ServiceLocatorAccess sla : m.appContextRepos()) {
                if (sla.repoCalls() == null || sla.repoCalls().isEmpty()) continue;
                boolean dup = m.hasServiceLocatorRepos() && m.serviceLocatorRepos().stream()
                        .anyMatch(s -> s.repoType().equals(sla.repoType()));
                if (dup) continue;
                sb.append(i(indent)).append("@Mock\n");
                sb.append(i(indent)).append("private ").append(sla.repoType())
                  .append(" ").append(sla.fieldName()).append("; // via ApplicationContext.getBean()\n\n");
            }
        }
        return sb.toString();
    }

    /** Set of injected-field names that are actually called in some method body. */
    private Set<String> calledFieldNames(ClassMetadata m) {
        Set<String> names = new HashSet<>();
        for (MethodMetadata mm : m.methods()) {
            if (mm.fieldCallTokens() == null) continue;
            for (String token : mm.fieldCallTokens()) {
                int c = token.indexOf(':');
                if (c > 0) names.add(token.substring(0, c));
            }
        }
        return names;
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
                    sb.append(i(indent)).append("// Constructor-injected â€” Mockito @InjectMocks wires via constructor\n");
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
        // @MockBean removed â€” @Mock works everywhere without spring-boot-test dependency
        String annotation = "@Mock";
        StringBuilder sb = new StringBuilder();
        for (FieldMetadata f : fields) {
            if (f.isApplicationContext()) {
                sb.append(i(indent)).append(annotation).append("\n");
                sb.append(i(indent)).append("ApplicationContext ").append(f.name()).append(";\n\n");
            } else if (f.isMockCandidate()) {
                if (f.isConstructorInjected()) {
                    sb.append(i(indent)).append("// Constructor-injected â€” wired via Spring context\n");
                }
                sb.append(i(indent)).append(annotation).append("\n");
                sb.append(i(indent)).append("private ").append(f.type()).append(" ").append(f.name()).append(";\n\n");
            }
        }
        return sb.toString();
    }

    // â”€â”€ Multi-level parent spy declarations (Feature 1) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Emits @Spy declarations for every level of the parent chain.
     * Deepest ancestor is declared first so Mockito can resolve injection order.
     * Example for ClassA â†’ ClassB â†’ ClassC:
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

    // â”€â”€ ApplicationContext stubs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    protected String buildAppCtxStubs(ClassMetadata m, int indent) {
        if (!m.hasApplicationContext()) return "";
        String field = m.fields().stream()
                .filter(FieldMetadata::isApplicationContext)
                .map(FieldMetadata::name)
                .findFirst().orElse("applicationContext");
        // lenient() â€” Mockito 5 strict stubbing: these may not be called by every test method
        return i(indent) + "lenient().when(" + field + ".getBean(any(Class.class))).thenReturn(mock(Object.class));\n"
             + i(indent) + "lenient().when(" + field + ".getBean(anyString(), any(Class.class))).thenReturn(mock(Object.class));\n"
             + i(indent) + "lenient().when(" + field + ".containsBean(anyString())).thenReturn(true);\n";
    }

    // â”€â”€ Parent-class (BAU inheritance) stubs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Stubs overridden parent methods using Mockito spy + doReturn for the direct parent.
     * Also handles super.xxx() calls within the method body.
     * For multi-level chains, ancestor stubs are appended after the direct parent stubs.
     */
    /**
     * Emits comments/notes about parent-class methods. Does NOT stub the methods under test.
     *
     * CRITICAL RULE: the method under test must NEVER be stubbed.
     *   - Overridden methods (@Override) are ClassA's OWN code being tested â†’ run for real.
     *     Stubbing them would mean the test exercises the stub, not the real logic.
     *   - super.xxx() calls inside an overridden method run the parent's code â†’ control
     *     that path via dependency mocks (the parent's deps), not by stubbing the method.
     *   - Inherited non-overridden methods â†’ emit as commented hints only.
     */
    protected String buildSuperClassStubs(ClassMetadata m, int indent) {
        if (!m.hasSuperClass()) return "";
        StringBuilder sb = new StringBuilder();

        Set<String> overriddenNames = m.overriddenMethods().stream()
                .map(MethodMetadata::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // â”€â”€ Overridden methods â€” DO NOT STUB (they are the methods under test) â”€â”€
        // For super.xxx() calls, the parent logic runs; note it so the developer
        // knows to control the parent's dependencies, not stub the method itself.
        for (MethodMetadata mm : m.overriddenMethods()) {
            if (mm.hasSuperCalls()) {
                for (String superCall : mm.superMethodCalls()) {
                    sb.append(i(indent))
                      .append("// ").append(mm.name()).append("() calls super.").append(superCall)
                      .append("() â€” parent ").append(m.superClassName())
                      .append(" logic runs; control it via the parent's dependency mocks above\n");
                }
            }
        }

        // Skip parent methods where ClassA has its own same-name method (overload).
        Set<String> ownMethodNames = m.methods().stream()
                .map(MethodMetadata::name)
                .collect(Collectors.toSet());

        // Collect all method names actually CALLED from ClassA's own testable methods.
        // Only inherited methods that ClassA actively invokes need to be stubbed â€”
        // if saveRecTxn is never called in ClassA, there is no execution path reaching it
        // and no stub is needed (and it may not even be accessible from the test package).
        Set<String> calledInClassA = m.methods().stream()
                .filter(MethodMetadata::isTestable)
                .flatMap(mm -> mm.helperMethodCalls().stream())
                .collect(Collectors.toSet());

        // â”€â”€ Inherited non-overridden methods â€” emit as COMMENTS only â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Inherited parent methods are NEVER emitted as active stubs:
        //  - protected-access + package differences can't be reliably resolved
        //  - the method under test is never among these (it's overridden, handled above)
        // Uncomment a hint below only if real parent execution causes test issues.
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
                      .append("// Inherited from ").append(parent.className())
                      .append(" â€” uncomment if real parent execution causes issues:\n");
                    for (MethodMetadata mm : inheritedMethods) {
                        String matchers = mm.parameters().stream()
                                .map(p -> mockitoMatcher(p.type()))
                                .collect(Collectors.joining(", "));
                        // Always a comment â€” never active â€” avoids protected-access compile errors
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
            // parentChain empty â€” parent source is outside the scanned source root
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
     * Returns a typed return value â€” always using inline new Type() with field setters.
     * No TestData class references â€” everything self-contained in the test.
     */
    private String typedReturnValue(String rawReturnType, ClassMetadata m) {
        String rawType = rawReturnType.replaceAll("<.*>", "").trim();
        String base    = defaultValue(rawReturnType);
        if (!base.startsWith("null")) return base; // primitive / standard type

        // Always use new Type() â€” no TestData file references
        if ((m.concreteClassNames() != null && m.concreteClassNames().contains(rawType))
                || (m.paramTypeRegistry() != null && m.paramTypeRegistry().containsKey(rawType))) {
            return "new " + rawType + "()";
        }
        return "null";
    }

    // â”€â”€ @BeforeEach â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Determines whether to use spy() or @InjectMocks for the Unit nested class.
     *
     * spy() is needed when there are actual method calls in the class body that
     * must be intercepted to isolate the logic under test:
     *
     *   a) super.xxx() calls â€” delegate to parent; must be stubbed on spy to prevent
     *      real parent execution (regardless of whether a superclass exists)
     *
     *   b) internal method calls â€” calls to other methods declared in THIS class;
     *      must be stubbed on spy so only the entry-point logic is exercised
     *      (structural detection â€” no name-prefix restriction)
     *
     * @InjectMocks is sufficient when NEITHER applies:
     *   - No super calls, no internal helper calls
     *   - Works even without a no-arg constructor (Mockito handles injection)
     *   - Having a superclass alone is NOT enough â€” only spy if there are actual calls
     */
    protected boolean requiresSpyPattern(ClassMetadata m) {
        // DISABLED â€” always use @InjectMocks. The spy pattern caused three runtime defects:
        //
        //   1) Repositories mocked but never injected â†’ NullPointerException.
        //      The spy path only ReflectionTestUtils-injected calledFieldNames(m); any
        //      other @Mock dependency stayed null on the subject. @InjectMocks injects
        //      EVERY declared @Mock by type/name, so no dependency is left unwired.
        //
        //   2) Stubbing methods that aren't used.
        //      buildHelperMethodStubs / buildSuperClassStubs stubbed internal and parent
        //      methods in setUp() even when a given test path never calls them.
        //
        //   3) Blocking real logic.
        //      lenient().doReturn(...).when(subject).helper() replaced the real helper
        //      implementation, so the unit test exercised a stub instead of the code.
        //
        // With @InjectMocks the real subject runs its real internal/parent logic, and we
        // mock only the external dependencies that the flow actually calls (stubbed at the
        // call site in each test, AFTER confirming the call exists in the method body).
        return false;
    }

    /**
     * Emits the subject field declaration for the Unit class.
     * When @InjectMocks is used, also emits the @InjectMocks annotation.
     */
    protected String buildSubjectDeclaration(ClassMetadata m, int indent) {
        String typeName = m.className();
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
            sb.append(i(indent + 1)).append(subject).append(" = spy(new ")
              .append(m.className()).append("());\n\n");

            // Private method isolation: identify which injected deps private methods use.
            // Private methods run naturally when public methods are called.
            // Controlling the mocked deps below is sufficient to control private behaviour.
            Set<String> mockFieldNames = m.mockCandidates().stream()
                    .map(FieldMetadata::name).collect(Collectors.toSet());
            List<String> privateDeps = m.methods().stream()
                    .filter(mm -> !mm.isPublic() && !mm.isProtected() && !mm.isConstructor())
                    .flatMap(mm -> mm.accessedFieldNames() != null
                            ? mm.accessedFieldNames().stream() : java.util.stream.Stream.empty())
                    .filter(mockFieldNames::contains)
                    .distinct()
                    .toList();
            if (!privateDeps.isEmpty()) {
                sb.append(i(indent + 1)).append("// Private methods covered indirectly via public method calls.\n");
                sb.append(i(indent + 1)).append("// Controlling these mocked deps controls private method behaviour:\n");
                sb.append(i(indent + 1)).append("// ").append(String.join(", ", privateDeps)).append("\n\n");
            }

            // Inject only the mocks we actually declared (the called ones) â€” keeps
            // setUp consistent with the @Mock fields and avoids referencing undeclared vars.
            Set<String> declaredMocks = calledFieldNames(m);
            for (FieldMetadata f : m.mockCandidates()) {
                if (!f.isApplicationContext() && declaredMocks.contains(f.name())) {
                    sb.append(i(indent + 1)).append("ReflectionTestUtils.setField(").append(subject)
                      .append(", \"").append(f.name()).append("\", ").append(f.name()).append(");\n");
                }
            }
        }
        // For @InjectMocks: Mockito extension handles injection â€” nothing to do here

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
            // Service-locator stubs are emitted only when the locator method is
            // ClassA's own (accessible). Parent-inherited protected locators are
            // left alone â€” execution safety over a fragile, uncompilable stub.
            if (m.hasServiceLocatorRepos()) {
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
              .append("// @PostConstruct runs on Spring init â€” verify any side effects below\n");
        }

        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // â”€â”€ Generic accessibility helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns true only if methodName is declared in ClassA's OWN public/protected methods.
     *
     * Generic rule applied to ALL active lenient() stubs:
     *   lenient().when(subject).method() compiles ONLY when 'method' belongs to
     *   ClassA itself â€” because the test class is in ClassA's package.
     *
     *   Inherited methods (from parent ClassB in a different package) with protected
     *   access are NOT callable from the test class, causing compile errors.
     *   â†’ emit as a comment instead of active code.
     */
    protected boolean isOwnAccessibleMethod(String methodName, ClassMetadata m) {
        return m.methods().stream()
                .filter(mm -> mm.isPublic() || mm.isProtected())
                .anyMatch(mm -> mm.name().equals(methodName));
    }

    // â”€â”€ Service-locator repo stubs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Stubs service-locator calls (makeDAO / factory methods) on the spy to return
     * the mocked @Repository instead of hitting the real service locator at runtime.
     *
     *   TPIBFTPayeeRepo repo = (TPIBFTPayeeRepo) makeDAO(BEAN_ID)
     *   â†’  lenient().doReturn(tpibFTPayeeRepo).when(subject).makeDAO(any());
     */
    protected String buildServiceLocatorStubs(ClassMetadata m, String subject, int indent) {
        if (!m.hasServiceLocatorRepos() && !m.hasAppContextRepos()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("// Service-locator stubs â€” return mocked @Repository instead of real DAO\n");
        for (com.testgen.parser.ServiceLocatorAccess sla : m.serviceLocatorRepos()) {
            // Stub the service-locator method (e.g. makeDAO) on the spy ONLY when it is
            // ClassA's own (accessible) method. A parent-inherited protected locator can't
            // be referenced from the test without a fragile subclass override, so it is
            // left as the real call (execution safety over an uncompilable stub).
            if (isOwnAccessibleMethod(sla.locatorMethod(), m)) {
                sb.append(i(indent)).append("lenient().doReturn(").append(sla.fieldName())
                  .append(").when(").append(subject).append(").").append(sla.locatorMethod())
                  .append("(any());\n");
            }

            // Stub each detected method call on the repo â€” prevents NPE and covers DB call lines
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

        // Layer 2: ApplicationContext.getBean(X.class) stubs
        // Stub applicationContext mock to return specific type mocks when requested by type
        if (m.hasAppContextRepos()) {
            sb.append(i(indent)).append("// AppContext pattern: stub getBean(X.class) â†’ specific mock\n");
            String ctxField = m.fields().stream()
                    .filter(com.testgen.parser.FieldMetadata::isApplicationContext)
                    .map(com.testgen.parser.FieldMetadata::name)
                    .findFirst().orElse("applicationContext");
            for (com.testgen.parser.ServiceLocatorAccess sla : m.appContextRepos()) {
                sb.append(i(indent))
                  .append("lenient().when(").append(ctxField).append(".getBean(")
                  .append(sla.repoType()).append(".class)).thenReturn(").append(sla.fieldName()).append(");\n");
                // Also stub method calls on this repo
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
        }
        return sb.toString();
    }

    /**
     * Returns a compilable return value for a repository mock stub.
     *
     * For collection/optional types: return empty instances.
     * For single entity/domain types: use mock(Type.class) â€” always compiles,
     *   prevents JPA lifecycle hooks, doesn't require a TestData class to exist.
     * For primitives: use typed literals.
     */
    private String repoReturnValue(String returnType, ClassMetadata m) {
        if (returnType == null || returnType.isBlank()) return "null";
        String raw = returnType.replaceAll("<.*>", "").trim();

        // Extract the inner type for generics (e.g. "Optional<TPIBCasCounter>" â†’ "TPIBCasCounter")
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
                    // @Repository interfaces are NOT in concreteClassNames â€” they get mock().
                    // Plain entity/DTO classes ARE in concreteClassNames â€” they get TestData.
                    if (m.concreteClassNames() != null && m.concreteClassNames().contains(raw)) {
                        yield "new " + raw + "()";
                    }
                    // Interface, abstract class, @Repository, external type â†’ always mock()
                    yield "mock(" + raw + ".class)";
                }
                yield "null";
            }
        };
    }

    // â”€â”€ Pattern D: internal helper method stubs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    // â”€â”€ Repository field stubs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        sb.append(i(indent)).append("// Repository stubs â€” JPA mocks return null by default; configure as needed\n");
        for (FieldMetadata f : repos) {
            String mock = f.name();
            String entity = f.simpleType().replace("Repository", "");
            sb.append(i(indent))
              .append("// when(").append(mock).append(".findById(any())).thenReturn(Optional.of(new ")
              .append(entity).append("()));\n");
            sb.append(i(indent))
              .append("// when(").append(mock).append(".findAll()).thenReturn(new java.util.ArrayList<>());\n");
            sb.append(i(indent))
              .append("// when(").append(mock).append(".save(any())).thenAnswer(inv -> inv.getArgument(0));\n");
            sb.append(i(indent))
              .append("// doNothing().when(").append(mock).append(").deleteById(any());\n");
        }
        return sb.toString();
    }

    // â”€â”€ Wire @Nested (shared across all strategies) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    // â”€â”€ Pattern C: branch tests (conditional logic) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    protected String buildBranchTests(MethodMetadata mm, String subject,
                                       ClassMetadata m, int indent) {
        // Only generate a branch test when a REAL condition scenario was detected:
        // a parameter field is checked, a concrete value can be assigned, and the
        // two branches differ. No scenario â†’ no branch test (avoids fake/empty tests).
        if (!mm.hasConditionScenarios()) return "";
        com.testgen.parser.ConditionScenario sc = mm.conditionScenarios().get(0);
        // Need a settable field value on both sides to be meaningful
        boolean trueSettable  = sc.trueSetExpr()  != null && !sc.trueSetExpr().startsWith("/*");
        boolean falseSettable = sc.falseSetExpr() != null && !sc.falseSetExpr().startsWith("/*");
        if (!trueSettable || !falseSettable) return "";

        StringBuilder sb = new StringBuilder();
        String throwsDecl = checkedThrowsClause(mm);

        // Single representative branch test â€” the TRUE path (the more interesting one).
        String trueName = convention.unitTestMethod(mm.name(), "when_" + sc.trueLabel(), buildParamSuffix(mm));
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(trueName).append("()").append(throwsDecl).append(" {\n");
        sb.append(i(indent + 1)).append(sc.paramType()).append(" ").append(sc.paramName())
          .append(" = mock(").append(sc.paramType()).append(".class, RETURNS_DEEP_STUBS);\n");
        // Drive the condition via a getter stub on the mock
        sb.append(i(indent + 1)).append("when(").append(sc.paramName()).append(".get")
          .append(cap(sc.fieldName())).append("()).thenReturn(").append(sc.trueSetExpr()).append(");\n");
        sb.append(buildFieldCallStubs(mm, m, indent + 1));
        if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
        sb.append(buildSuccessAssertions(mm, m, indent + 1));
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // â”€â”€ Pattern G: boundary tests (numeric/comparison) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    protected String buildBoundaryTests(MethodMetadata mm, String subject,
                                         ClassMetadata m, int indent) {
        // Only when a NUMERIC condition scenario is detected (real threshold + field).
        if (!mm.hasNumericComparisons() || !mm.hasConditionScenarios()) return "";
        com.testgen.parser.ConditionScenario sc = mm.conditionScenarios().stream()
                .filter(s -> s.type() == com.testgen.parser.ConditionScenario.ConditionType.NUMERIC_CHECK)
                .findFirst().orElse(null);
        if (sc == null) return "";

        StringBuilder sb = new StringBuilder();
        String throwsDecl = checkedThrowsClause(mm);
        String testName = convention.unitTestMethod(mm.name(), "at_" + sc.trueLabel(), buildParamSuffix(mm));
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(testName).append("()").append(throwsDecl).append(" {\n");
        sb.append(i(indent + 1)).append(sc.paramType()).append(" ").append(sc.paramName())
          .append(" = mock(").append(sc.paramType()).append(".class, RETURNS_DEEP_STUBS);\n");
        sb.append(i(indent + 1)).append("when(").append(sc.paramName()).append(".get")
          .append(cap(sc.fieldName())).append("()).thenReturn(").append(sc.trueSetExpr()).append(");\n");
        sb.append(buildFieldCallStubs(mm, m, indent + 1));
        if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 1);
        sb.append(buildSuccessAssertions(mm, m, indent + 1));
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // â”€â”€ Pattern A: static dependency mock tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        if (!mm.hasStaticDependencies() || mm.staticCallTokens() == null
                || m.resolvedStaticTypes() == null) return "";

        // Pick the FIRST static class that has a RESOLVED (known) non-void method.
        // No resolved method â†’ no static test (never mock blindly, never JDK).
        for (String staticClass : mm.staticCallClasses()) {
            if (JDK_CLASSES.contains(staticClass)) continue;

            String methodName = null, retType = null;
            int argCount = 0;
            for (String token : mm.staticCallTokens()) {
                if (!token.startsWith(staticClass + ".")) continue;
                int colon = token.lastIndexOf(':');
                String mName = token.substring(staticClass.length() + 1, colon);
                String rt = m.resolvedStaticTypes().get(staticClass + "." + mName);
                if (rt != null && !"void".equals(rt)) {
                    methodName = mName; retType = rt;
                    argCount = Integer.parseInt(token.substring(colon + 1));
                    break;
                }
            }
            if (methodName == null) continue; // nothing resolved for this class

            // Match the static method's real arity: N any() matchers (or none for 0 args)
            String matchers = java.util.stream.IntStream.range(0, argCount)
                    .mapToObj(x -> "any()").collect(Collectors.joining(", "));

            String throwsDecl = checkedThrowsClause(mm);
            String testName = convention.unitTestMethod(mm.name(),
                    "with_" + staticClass + "_mocked", buildParamSuffix(mm));
            StringBuilder sb = new StringBuilder();
            sb.append(i(indent)).append("@Test\n");
            sb.append(i(indent)).append("void ").append(testName).append("()").append(throwsDecl).append(" {\n");
            sb.append(i(indent + 1)).append("try (MockedStatic<").append(staticClass)
              .append("> mockedStatic = mockStatic(").append(staticClass).append(".class)) {\n");
            sb.append(i(indent + 2)).append("mockedStatic.when(() -> ").append(staticClass).append(".")
              .append(methodName).append("(").append(matchers).append(")).thenReturn(")
              .append(typedReturnValue(retType, m)).append(");\n");
            buildParamSetup(mm, sb, indent + 2, m.concreteClassNames(), m.paramTypeRegistry());
            // Also stub the method's dependency calls + null-check guards so result is non-null
            sb.append(buildConditionGuardStubs(mm, m, indent + 2));
            sb.append(buildFieldCallStubs(mm, m, indent + 2));
            if (!mm.isProtected()) buildDirectCall(mm, subject, sb, indent + 2);
            sb.append(i(indent + 2)).append("mockedStatic.verify(() -> ").append(staticClass).append(".")
              .append(methodName).append("(").append(matchers).append("));\n");
            if (mm.hasReturnValue()) sb.append(i(indent + 2)).append("assertNotNull(result);\n");
            sb.append(i(indent + 1)).append("}\n");
            sb.append(i(indent)).append("}\n\n");
            return sb.toString(); // only ONE static test
        }
        return "";
    }

    // â”€â”€ @Entity MockedConstruction test â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
          .append("// @Entity types are created inline â€” MockedConstruction intercepts new X()\n");
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

        // Capture and assert on mocked entity â€” guard against IndexOutOfBoundsException:
        // constructed() is empty if the method threw before reaching 'new EntityType()'
        for (String entityType : methodEntityTypes) {
            sb.append(i(bodyIndent)).append("// Safe access: verify entity was actually constructed\n");
            sb.append(i(bodyIndent))
              .append("assertFalse(mocked").append(entityType)
              .append(".constructed().isEmpty(), \"Expected new ").append(entityType)
              .append("() to be called â€” check method conditions\");\n");
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

    // â”€â”€ Testable subclass generation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Testable subclass generation is DISABLED.
     *
     * Why: generating @Override stubs for protected parent methods guesses their
     * signature (return type / params / throws). Any mismatch with the real parent
     * method is a hard compile error in the generated test. The _mockDaos / makeDAO
     * override had the same problem and was effectively dead.
     *
     * It is also unnecessary: a class's OWN code can call its inherited protected
     * methods directly (Java access rules), so when ClassA's public method runs it
     * naturally reaches its protected parent methods â€” the test never needs to call
     * or widen them. We favour guaranteed compilation (execution safety) over the
     * marginal coverage a fragile subclass would add.
     */
    protected boolean needsTestablSubclass(ClassMetadata m) {
        return false;
    }


    /** Returns the name of the best dependency mock to use for triggering an exception.
     *  Prefers service-locator repos â†’ injected mocks â†’ null if none available. */
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

    // â”€â”€ Pattern H: exception flow test â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    protected String buildExceptionFlowTest(MethodMetadata mm, String subject,
                                             ClassMetadata m, int indent) {
        if (!mm.hasTryCatch() && !mm.throwsExceptions()) return "";
        String exType  = primaryException(mm) != null ? primaryException(mm) : "Exception";
        String testName = convention.unitTestMethod(mm.name(), "whenExceptionOccurs", buildParamSuffix(mm));
        StringBuilder sb = new StringBuilder();

        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ").append(testName).append("() throws Exception {\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());

        // Trigger exception via a DEPENDENCY mock â€” not via the subject itself.
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
              .append(" â€” do NOT stub subject.").append(mm.name()).append("() directly\n");
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

    // â”€â”€ AOP annotation awareness â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
              .append(") is NOT active in Unit layer â€” verify its behaviour in Functional/Wire\n");
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
                  .append(" â€” ").append(annotationCategory(ann))
                  .append(" is active here (Spring proxy wraps subject)\n");

                if (SPRING_AOP_ANNOTATIONS.contains(ann)) {
                    appendSpringAopStub(ann, mm, sb, indent + 1, m);
                } else if (SECURITY_ANNOTATIONS.contains(ann)) {
                    sb.append(i(indent + 1))
                      .append("// TODO: call subject.").append(mm.name())
                      .append("(...) with insufficient role â†’ expect AccessDeniedException\n");
                    sb.append(i(indent + 1))
                      .append("// TODO: call subject.").append(mm.name())
                      .append("(...) with correct role â†’ expect success\n");
                } else {
                    // custom annotation
                    sb.append(i(indent + 1))
                      .append("// TODO: verify @").append(ann)
                      .append(" aspect behaviour â€” e.g. audit log written, metric recorded\n");
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
                  .append("// TODO: configure mock to throw RuntimeException â†’ assertThrows, then verify rollback\n");
            }
            case "Async" -> {
                sb.append(i(indent)).append("// Async method â€” returns immediately; use CompletableFuture or CountDownLatch\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// TODO: await async completion and assert side-effects\n");
            }
            case "Cacheable", "CachePut" -> {
                sb.append(i(indent)).append("// First call â€” cache miss, real method executes\n");
                sb.append(i(indent)).append(call).append(";\n");
                sb.append(i(indent)).append("// Second call â€” cache hit, real method NOT invoked again\n");
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
                        return "new " + raw + "()";
                    }
                    return value;
                })
                .collect(Collectors.joining(", "));
    }

    // â”€â”€ Test method generation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            sb.append(i(indent)).append("// Inherited non-overridden methods â†’ covered by ")
              .append(m.superClassName()).append("Test\n\n");
        }

        // Feature 2: interface default method tests
        if (m.hasInterfaceDefaultMethods()) {
            sb.append(i(indent)).append("// --- Interface default methods ---\n\n");
            for (MethodMetadata mm : m.interfaceDefaultMethods()) {
                sb.append(i(indent))
                  .append("// Default method from interface â€” exercised via subject (no override needed)\n");
                sb.append(buildSingleTestMethod(mm, subject, m, indent));
            }
        }

        return sb.toString();
    }

    protected String buildSingleTestMethod(MethodMetadata mm, String subject,
                                           ClassMetadata m, int indent) {
        StringBuilder sb = new StringBuilder();
        String paramSuffix  = buildParamSuffix(mm);
        String throwsClause = checkedThrowsClause(mm);

        // â”€â”€ 1 SUCCESS test â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ")
          .append(convention.unitTestMethod(mm.name(), "success", paramSuffix))
          .append("()").append(throwsClause).append(" {\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());
        // Guard null-checks: stub param getters so the happy path executes (no early throw)
        sb.append(buildConditionGuardStubs(mm, m, indent + 1));
        // Stub non-void dependency calls so the method produces a non-null result
        sb.append(buildFieldCallStubs(mm, m, indent + 1));
        if (mm.isProtected()) buildReflectionCall(mm, subject, sb, indent + 1);
        else buildDirectCall(mm, subject, sb, indent + 1);
        // Real assertion(s): verify dependency interactions + assert the result.
        sb.append(buildSuccessAssertions(mm, m, indent + 1));
        sb.append(i(indent)).append("}\n\n");

        // â”€â”€ 1 EXCEPTION test â€” only if we know a concrete dependency method to throw from â”€â”€
        String primaryException = primaryException(mm);
        if (primaryException != null && pickExceptionTriggerCall(mm, m) != null) {
            sb.append(buildExceptionTestMethod(mm, subject, primaryException, indent, m));
        }

        // â”€â”€ 1 BRANCH test pair â€” only for a REAL condition scenario â”€â”€â”€â”€â”€â”€â”€â”€
        if (mm.hasConditionScenarios()) {
            sb.append(buildBranchTests(mm, subject, m, indent));
        }

        // â”€â”€ 1 BOUNDARY test â€” only if a numeric comparison was detected â”€â”€â”€â”€
        if (mm.hasNumericComparisons()) {
            sb.append(buildBoundaryTests(mm, subject, m, indent));
        }

        // â”€â”€ 1 STATIC test â€” only if a resolved static method call exists â”€â”€â”€
        if (mm.hasStaticDependencies() && hasResolvedStaticCall(mm, m)) {
            sb.append(buildStaticMockTests(mm, subject, m, indent));
        }

        return sb.toString();
    }

    /**
     * Builds real assertions for the success test â€” at least one concrete verify/assert,
     * never a TODO:
     *  - verify(...) every injected-field dependency method actually called by mm
     *  - assertNotNull(result) when the method returns a value
     */
    private String buildSuccessAssertions(MethodMetadata mm, ClassMetadata m, int indent) {
        StringBuilder sb = new StringBuilder();
        boolean emitted = false;

        for (String[] fc : injectedFieldCalls(mm, m)) {
            sb.append(i(indent)).append("verify(").append(fc[0]).append(").")
              .append(fc[1]).append("(").append(fcMatchers(fc, m)).append(");\n");
            emitted = true;
        }

        if (mm.hasReturnValue()) {
            sb.append(i(indent)).append("assertNotNull(result);\n");
            emitted = true;
        }

        if (!emitted) {
            String dep = m.mockCandidates().stream()
                    .filter(f -> !f.isApplicationContext())
                    .map(com.testgen.parser.FieldMetadata::name)
                    .findFirst().orElse(null);
            if (dep != null) {
                sb.append(i(indent)).append("verifyNoMoreInteractions(").append(dep).append(");\n");
            }
        }
        return sb.toString();
    }

    /**
     * Stubs non-void calls on injected mock fields to return a non-null value,
     * so the method under test can produce a non-null result for assertNotNull.
     *   when(payeeRepo.findById(any())).thenReturn(mock(Payee.class, RETURNS_DEEP_STUBS));
     */
    private String buildFieldCallStubs(MethodMetadata mm, ClassMetadata m, int indent) {
        if (m.fieldCallReturnTypes() == null || m.fieldCallReturnTypes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String[] fc : injectedFieldCalls(mm, m)) {
            String key = fc[0] + "." + fc[1];
            String retType = m.fieldCallReturnTypes().get(key);
            if (retType == null || "void".equals(retType)) continue;
            String retVal = stubReturnValue(retType, m);
            sb.append(i(indent)).append("when(").append(fc[0]).append(".").append(fc[1])
              .append("(").append(fcMatchers(fc, m)).append(")).thenReturn(").append(retVal).append(");\n");
        }
        return sb.toString();
    }

    /**
     * For each NULL_CHECK condition on a parameter, stubs the checked getter to return
     * a non-null value so the success test runs the happy path instead of the throw branch.
     *   when(input.getAmount()).thenReturn(1L);
     */
    private String buildConditionGuardStubs(MethodMetadata mm, ClassMetadata m, int indent) {
        if (!mm.hasConditionScenarios()) return "";
        StringBuilder sb = new StringBuilder();
        for (com.testgen.parser.ConditionScenario sc : mm.conditionScenarios()) {
            if (sc.type() != com.testgen.parser.ConditionScenario.ConditionType.NULL_CHECK) continue;
            // Resolve the getter's return type from the param VO's fields, if known
            String getterRet = resolveParamFieldType(sc.paramType(), sc.fieldName(), m);
            String value = getterRet != null ? defaultValue(getterRet) : null;
            if (value == null || value.startsWith("null")) {
                // Unknown type â†’ return a deep-stub mock object (non-null) generically
                value = "mock(Object.class)";
            }
            sb.append(i(indent)).append("when(").append(sc.paramName()).append(".get")
              .append(cap(sc.fieldName())).append("()).thenReturn(").append(value).append(");\n");
        }
        return sb.toString();
    }

    /** Looks up a field's declared type within a parameter VO (from paramTypeRegistry). */
    private String resolveParamFieldType(String paramType, String fieldName, ClassMetadata m) {
        if (m.paramTypeRegistry() == null) return null;
        com.testgen.parser.ClassMetadata vo = m.paramTypeRegistry().get(paramType);
        if (vo == null) return null;
        return vo.fields().stream()
                .filter(f -> f.name().equalsIgnoreCase(fieldName))
                .map(com.testgen.parser.FieldMetadata::type)
                .findFirst().orElse(null);
    }

    /** Compilable non-null return value for a stub: deep-stub mock for domain types. */
    private String stubReturnValue(String returnType, ClassMetadata m) {
        String raw = returnType.replaceAll("<.*>", "").trim();
        String base = defaultValue(returnType);
        if (!base.startsWith("null")) return base;          // primitive / standard
        if (raw.isEmpty() || !Character.isUpperCase(raw.charAt(0))) return "null";
        return "mock(" + raw + ".class, RETURNS_DEEP_STUBS)"; // domain / external type
    }

    /**
     * Returns [fieldName, methodName, argCount] for each method call on an INJECTED MOCK
     * field made by mm. Filters fieldCallTokens to scopes matching a mock candidate name.
     */
    /**
     * Type-aware matchers for an injected-field call: resolved from the field type's
     * method signature when available (anyString(), anyInt()), else N generic any().
     */
    private String fcMatchers(String[] fc, ClassMetadata m) {
        String key = fc[0] + "." + fc[1];
        if (m.fieldCallMatchers() != null && m.fieldCallMatchers().containsKey(key)) {
            return m.fieldCallMatchers().get(key);
        }
        int argc = Integer.parseInt(fc[2]);
        return java.util.stream.IntStream.range(0, argc)
                .mapToObj(x -> "any()").collect(Collectors.joining(", "));
    }

    private List<String[]> injectedFieldCalls(MethodMetadata mm, ClassMetadata m) {
        if (mm.fieldCallTokens() == null) return List.of();
        Set<String> mockNames = m.mockCandidates().stream()
                .filter(f -> !f.isApplicationContext())
                .map(com.testgen.parser.FieldMetadata::name)
                .collect(Collectors.toSet());
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String token : mm.fieldCallTokens()) {
            String[] parts = token.split(":");
            if (parts.length != 3) continue;
            if (!mockNames.contains(parts[0])) continue;
            String key = parts[0] + "." + parts[1];
            if (seen.add(key)) out.add(parts);
        }
        return out;
    }

    /** True if at least one static call in this method resolves to a known method/return type. */
    private boolean hasResolvedStaticCall(MethodMetadata mm, ClassMetadata m) {
        if (mm.staticCallTokens() == null || m.resolvedStaticTypes() == null) return false;
        return mm.staticCallTokens().stream().anyMatch(token -> {
            int colon = token.lastIndexOf(':');
            if (colon < 0) return false;
            String key = token.substring(0, colon); // ClassName.methodName
            return m.resolvedStaticTypes().containsKey(key);
        });
    }

    protected String buildExceptionTestMethod(MethodMetadata mm, String subject,
                                              String exType, int indent, ClassMetadata m) {
        // Find a KNOWN dependency method to throw from. If none, no exception test.
        String[] trigger = pickExceptionTriggerCall(mm, m); // [fieldName, methodName, matchers] or null
        if (trigger == null) return "";

        // Always throw an UNCHECKED exception from the dependency: Mockito allows it on
        // any mock method regardless of its declared throws clause, and it propagates
        // through the method-under-test's signature without compile issues.
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ")
          .append(convention.exceptionTestMethod(mm.name(), exType)).append("() {\n");
        buildParamSetup(mm, sb, indent + 1, m.concreteClassNames(), m.paramTypeRegistry());
        // Guard null-checks so execution reaches the dependency call (not an earlier throw)
        sb.append(buildConditionGuardStubs(mm, m, indent + 1));

        sb.append(i(indent + 1))
          .append("doThrow(new RuntimeException(\"boom\")).when(").append(trigger[0])
          .append(").").append(trigger[1]).append("(").append(trigger[2]).append(");\n");

        String params = paramNames(mm);
        sb.append(i(indent + 1)).append("RuntimeException thrown = assertThrows(RuntimeException.class, () ->\n");
        if (mm.isProtected()) {
            String sep = params.isEmpty() ? "" : ", ";
            sb.append(i(indent + 2)).append("ReflectionTestUtils.invokeMethod(")
              .append(subject).append(", \"").append(mm.name()).append("\"")
              .append(sep).append(params).append("));\n");
        } else {
            sb.append(i(indent + 2)).append(subject).append(".")
              .append(mm.name()).append("(").append(params).append("));\n");
        }
        sb.append(i(indent + 1)).append("assertEquals(\"boom\", thrown.getMessage());\n");
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    /**
     * Returns [fieldName, methodName, matchers] for a KNOWN dependency call to throw from,
     * or null if no concrete dependency method is known. Never guesses a method name.
     * Priority: injected-field calls in this method â†’ service-locator â†’ app-context.
     */
    private String[] pickExceptionTriggerCall(MethodMetadata mm, ClassMetadata m) {
        // 1) A method actually called on an injected @Mock field by mm
        for (String[] fc : injectedFieldCalls(mm, m)) {
            return new String[]{ fc[0], fc[1], fcMatchers(fc, m) };
        }
        // 2) Service-locator repo calls
        if (m.hasServiceLocatorRepos()) {
            for (com.testgen.parser.ServiceLocatorAccess sla : m.serviceLocatorRepos()) {
                for (com.testgen.parser.ServiceLocatorAccess.RepoCall call : sla.repoCalls()) {
                    String matchers = call.params().stream()
                            .map(p -> mockitoMatcher(p.type()))
                            .collect(Collectors.joining(", "));
                    return new String[]{ sla.fieldName(), call.methodName(), matchers };
                }
            }
        }
        // 3) App-context repo calls
        if (m.hasAppContextRepos()) {
            for (com.testgen.parser.ServiceLocatorAccess sla : m.appContextRepos()) {
                for (com.testgen.parser.ServiceLocatorAccess.RepoCall call : sla.repoCalls()) {
                    String matchers = call.params().stream()
                            .map(p -> mockitoMatcher(p.type()))
                            .collect(Collectors.joining(", "));
                    return new String[]{ sla.fieldName(), call.methodName(), matchers };
                }
            }
        }
        return null;
    }

    // â”€â”€ Auto-init aware assertion/stub hints â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Emits commented-out mock stub hints using typed matchers â€” any(TypeName.class) â€”
     * so developers know exactly which type to match.
     * Only emits hints for domain-object params (non-primitive / non-standard types).
     */
    private void buildMockStubHints(MethodMetadata mm, ClassMetadata m,
                                     StringBuilder sb, int indent) {
        List<MethodMetadata.ParameterMetadata> domainParams = mm.parameters().stream()
                .filter(p -> defaultValue(p.type()).startsWith("null"))
                .toList();
        if (domainParams.isEmpty()) return;

        sb.append(i(indent)).append("// Stub mock dependencies â€” use typed matcher for initialized params:\n");
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
            // Return type is a domain object â€” suggest field assertions
            com.testgen.parser.ClassMetadata retMeta =
                    m.paramTypeRegistry() != null ? m.paramTypeRegistry().get(rawReturn) : null;
            if (retMeta != null && !retMeta.fields().isEmpty()) {
                sb.append(i(indent)).append("// Assert result fields (auto-initialized type â€” update as needed):\n");
                retMeta.fields().stream()
                        .filter(f -> !f.isInjected() && !f.isApplicationContext() && !f.isValue())
                        .limit(3) // top 3 fields to keep it concise
                        .forEach(f -> sb.append(i(indent))
                                .append("// assertNotNull(result.get").append(toSetterSuffix(f.name())).append("());\n"));
            } else {
                sb.append(i(indent)).append("// assertNotNull(result.getFieldName()); // add field assertions\n");
            }
        } else {
            sb.append(i(indent)).append("// assertEquals(expectedValue, result);\n");
        }
    }

    /**
     * Emits typed verify hints for void methods using initialized params.
     */
    private void buildVerifyHints(MethodMetadata mm, ClassMetadata m,
                                   StringBuilder sb, int indent) {
        // Service-locator repo verify hints â€” actual method names from detection
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
            return "new " + rawType + "()"; // inline â€” no TestData file needed
        }
        com.testgen.parser.ClassMetadata meta =
                m.paramTypeRegistry() != null ? m.paramTypeRegistry().get(rawType) : null;
        if (meta != null) return "new " + rawType + "()";
        return "mock(" + rawType + ".class)";
    }

    // â”€â”€ Method name disambiguation helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns a short param-type suffix for overloaded method disambiguation.
     * Empty string when the method has no params or only one param (no collision risk).
     * e.g. process(MSBaseVO, String) â†’ "MSBaseVO_String"
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

    // â”€â”€ Exception / throws helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns " throws ExType1, ExType2" if the method declares any thrown exceptions.
     * If the root Exception (or Throwable) is already in the list it covers everything â€”
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

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Generates local variable declarations for each method parameter.
     *
     * Resolution order for domain-object types (non-primitive, non-standard):
     *  1. Type is in concreteClassNames (scanned source root, TestData will be generated)
     *       â†’ TypeTestData.buildValidType()
     *  2. Type is in paramTypeRegistry (source found, field metadata available)
     *       â†’ new TypeName() + typed field setters (one line per field)
     *  3. External / unknown type (not in source root at all)
     *       â†’ new TypeName() â€” no-arg constructor; at least avoids NPE
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
                // Primitive / standard type â†’ literal value
                sb.append(i(indent)).append(p.type()).append(" ").append(p.name())
                  .append(" = ").append(value).append(";\n");
            } else {
                // Domain VO â†’ mock with RETURNS_DEEP_STUBS (avoids huge new+setter chains).
                // Deep stubs let chained getters (vo.getX().getY()) return non-null safely.
                sb.append(i(indent)).append(p.type()).append(" ").append(p.name())
                  .append(" = mock(").append(rawType).append(".class, RETURNS_DEEP_STUBS);\n");
            }
        }
    }

    /**
     * Converts a field name to a JavaBeans setter suffix, handling underscores.
     * e.g. "myField" â†’ "MyField", "my_field" â†’ "MyField"
     */
    /**
     * Converts a field name to the JavaBeans setter suffix.
     *
     * Boolean fields starting with 'is' lose the prefix (Lombok convention):
     *   isHoldRequired  (boolean)  â†’  HoldRequired   â†’ setHoldRequired
     *   holdRequired    (boolean)  â†’  HoldRequired   â†’ setHoldRequired
     *   isActive        (boolean)  â†’  Active         â†’ setActive
     *   transactionId   (String)   â†’  TransactionId  â†’ setTransactionId
     */
    /** Capitalises the first character â€” for getter/setter name building. */
    private String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String toSetterSuffix(String fieldName) {
        return toSetterSuffix(fieldName, null);
    }

    private String toSetterSuffix(String fieldName, String fieldType) {
        if (fieldName == null || fieldName.isEmpty()) return fieldName;

        // Boolean field with 'is' prefix â€” strip it (Lombok / IDE convention)
        boolean isBool = "boolean".equals(fieldType) || "Boolean".equals(fieldType);
        if (isBool && fieldName.startsWith("is") && fieldName.length() > 2
                && Character.isUpperCase(fieldName.charAt(2))) {
            return fieldName.substring(2); // isHoldRequired â†’ HoldRequired
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

    // â”€â”€ Test quality filter â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns true for methods worth generating additional pattern tests (boundary, static, exception).
     *
     * A method is HIGH-VALUE if it has real logic:
     *   - Has conditionals, try/catch, numeric comparisons, static calls, or helper calls
     *   - Not a simple getter (getName) or setter (setName)
     *
     * Simple getters/setters with no logic â†’ skip pattern tests, just generate the success stub.
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

    // â”€â”€ Mockito matcher helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns the correct Mockito ArgumentMatcher for a given parameter type.
     *
     * Primitives require specific matchers â€” any(int.class) does NOT compile:
     *   int/Integer   â†’ anyInt()
     *   long/Long     â†’ anyLong()
     *   double/Double â†’ anyDouble()
     *   float/Float   â†’ anyFloat()
     *   boolean/Bool  â†’ anyBoolean()
     *   byte/Byte     â†’ anyByte()
     *   short/Short   â†’ anyShort()
     *   char/Char     â†’ anyChar()
     *   String        â†’ anyString()
     *   Object/other  â†’ any(TypeName.class)
     */
    // Only primitives use anyXxx() (any() would NPE on unboxing). All object types â€”
    // including String and wrappers â€” use any(), which also matches null arguments
    // (deep-stub getters return null for final types; anyString() would not match null).
    protected String mockitoMatcher(String rawType) {
        String type = rawType.replaceAll("<.*>", "").trim();
        return switch (type) {
            case "int"     -> "anyInt()";
            case "long"    -> "anyLong()";
            case "double"  -> "anyDouble()";
            case "float"   -> "anyFloat()";
            case "boolean" -> "anyBoolean()";
            case "byte"    -> "anyByte()";
            case "short"   -> "anyShort()";
            case "char"    -> "anyChar()";
            default        -> "any()";
        };
    }

    protected boolean isPrimitive(String type) {
        return Set.of("int", "Integer", "long", "Long", "double", "Double",
                "float", "Float", "boolean", "Boolean", "byte", "Byte",
                "short", "Short", "char", "Character").contains(type);
    }
}
