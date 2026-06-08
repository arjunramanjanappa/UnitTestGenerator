package com.testgen.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.testgen.classifier.ClassType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JavaClassParser {

    private static final Set<String> INJECT_ANNOTATIONS =
            Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> APP_CTX_TYPES =
            Set.of("ApplicationContext", "ConfigurableApplicationContext",
                    "GenericApplicationContext", "WebApplicationContext");
    private static final Set<String> CONSTRAINT_ANNOTATIONS =
            Set.of("NotNull", "NotBlank", "NotEmpty", "Null",
                   "Min", "Max", "Size", "DecimalMin", "DecimalMax",
                   "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
                   "Email", "Pattern", "AssertTrue", "AssertFalse",
                   "Past", "PastOrPresent", "Future", "FutureOrPresent",
                   "Digits", "Valid", "NotZero");

    // ── Public API ──────────────────────────────────────────────────────────

    public Optional<ClassMetadata> parse(Path filePath) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(filePath);

            String packageName = cu.getPackageDeclaration()
                    .map(PackageDeclaration::getNameAsString)
                    .orElse("");

            List<String> imports = cu.getImports().stream()
                    .map(ImportDeclaration::getNameAsString)
                    .toList();

            Optional<ClassOrInterfaceDeclaration> classDecl =
                    cu.findFirst(ClassOrInterfaceDeclaration.class);

            if (classDecl.isEmpty() || classDecl.get().isInterface()) {
                return Optional.empty();
            }

            ClassOrInterfaceDeclaration cls = classDecl.get();
            String className  = cls.getNameAsString();
            List<String> annotations = extractAnnotationNames(cls);

            String superClass = cls.getExtendedTypes().isEmpty() ? null
                    : cls.getExtendedTypes().get(0).getNameAsString();

            List<String> genericTypeParams = new ArrayList<>();
            if (!cls.getExtendedTypes().isEmpty()) {
                cls.getExtendedTypes().get(0).getTypeArguments()
                        .ifPresent(args -> args.stream().map(Type::asString).forEach(genericTypeParams::add));
            }

            List<String> interfaces = cls.getImplementedTypes().stream()
                    .map(ClassOrInterfaceType::getNameAsString)
                    .toList();

            boolean hasLombok  = imports.stream().anyMatch(i -> i.startsWith("lombok."));
            boolean hasBuilder = annotations.contains("Builder")
                    || annotations.contains("Data")
                    || annotations.contains("SuperBuilder");

            // Detect constructor-injected field types (public constructors only)
            Set<String> ctorInjectedTypes = detectConstructorInjectedTypes(cls);

            List<FieldMetadata>  fields  = parseFields(cls, ctorInjectedTypes);
            List<MethodMetadata> methods = parseMethods(cls);

            return Optional.of(new ClassMetadata(
                    className, packageName, filePath.toString(),
                    ClassType.POJO,   // Classifier will override
                    annotations, fields, methods, imports,
                    superClass, interfaces,
                    cls.isAbstract(), false,
                    hasLombok, hasBuilder, genericTypeParams, null,
                    List.of(), List.of(), Set.of(), Map.of(), Set.of(), List.of(), Map.of(), List.of(), Map.of(), Map.of()  // parentChain/ifaceDefaults/concrete/paramRegistry/entities/serviceLocator/staticTypes/appCtxRepos
            ));

        } catch (IOException e) {
            log.error("Failed to parse {}: {}", filePath, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error parsing {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses an interface file and returns its default methods as MethodMetadata.
     * Returns empty list on any error.
     */
    public List<MethodMetadata> parseInterfaceDefaultMethods(Path interfaceFile) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(interfaceFile);
            Optional<ClassOrInterfaceDeclaration> decl =
                    cu.findFirst(ClassOrInterfaceDeclaration.class);
            if (decl.isEmpty() || !decl.get().isInterface()) return List.of();

            return decl.get().getMethods().stream()
                    .filter(MethodDeclaration::isDefault)
                    .map(this::toMethodMetadata)
                    .toList();

        } catch (Exception e) {
            log.warn("Could not parse interface {}: {}", interfaceFile, e.getMessage());
            return List.of();
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private Set<String> detectConstructorInjectedTypes(ClassOrInterfaceDeclaration cls) {
        Set<String> types = new HashSet<>();
        cls.getConstructors().stream()
                .filter(ConstructorDeclaration::isPublic)
                .forEach(ctor -> ctor.getParameters()
                        .forEach(p -> types.add(p.getTypeAsString().replaceAll("<.*>", "").trim())));
        return types;
    }

    private List<FieldMetadata> parseFields(ClassOrInterfaceDeclaration cls,
                                            Set<String> ctorInjectedTypes) {
        List<FieldMetadata> result = new ArrayList<>();

        for (FieldDeclaration field : cls.getFields()) {
            List<String> annotations = extractAnnotationNames(field);

            boolean isInjected = annotations.stream().anyMatch(INJECT_ANNOTATIONS::contains)
                    || (field.isFinal() && !field.isStatic());

            boolean isValue  = annotations.contains("Value");
            String  valueKey = "";
            if (isValue) {
                valueKey = field.getAnnotationByName("Value")
                        .map(Object::toString)
                        .orElse("");
            }

            // Extract validation constraints
            Map<String, String> constraints = extractConstraints(field);

            for (VariableDeclarator var : field.getVariables()) {
                String type       = var.getTypeAsString();
                String simpleType = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
                boolean isAppCtx  = APP_CTX_TYPES.contains(simpleType);
                boolean isCtorInj = ctorInjectedTypes.contains(simpleType) && !isInjected;

                // A constructor-injected dep that isn't already marked via @Autowired / final
                boolean effectivelyInjected = isInjected || isCtorInj;

                result.add(new FieldMetadata(
                        var.getNameAsString(), type, simpleType, annotations,
                        effectivelyInjected, isValue, valueKey,
                        field.isFinal(), field.isStatic(), isAppCtx,
                        isCtorInj, constraints
                ));
            }
        }
        return result;
    }

    private Map<String, String> extractConstraints(FieldDeclaration field) {
        Map<String, String> constraints = new LinkedHashMap<>();
        for (AnnotationExpr ann : field.getAnnotations()) {
            String name = ann.getNameAsString();
            if (!CONSTRAINT_ANNOTATIONS.contains(name)) continue;

            if (ann instanceof SingleMemberAnnotationExpr single) {
                constraints.put(name, single.getMemberValue().toString().replace("\"", ""));
            } else if (ann instanceof NormalAnnotationExpr normal) {
                String pairs = normal.getPairs().stream()
                        .map(p -> p.getNameAsString() + "=" + p.getValue().toString().replace("\"", ""))
                        .collect(Collectors.joining(","));
                constraints.put(name, pairs);
            } else {
                constraints.put(name, "");
            }
        }
        return constraints.isEmpty() ? Map.of() : constraints;
    }

    private List<MethodMetadata> parseMethods(ClassOrInterfaceDeclaration cls) {
        return cls.getMethods().stream()
                .map(this::toMethodMetadata)
                .toList();
    }

    // No name-prefix filter — any internal method call in this class is a structural helper candidate

    private MethodMetadata toMethodMetadata(MethodDeclaration method) {
        List<String> annotations = extractAnnotationNames(method);
        List<String> thrown      = method.getThrownExceptions().stream()
                .map(ReferenceType::asString)
                .toList();
        List<MethodMetadata.ParameterMetadata> params = method.getParameters().stream()
                .map(p -> new MethodMetadata.ParameterMetadata(p.getTypeAsString(), p.getNameAsString()))
                .toList();

        List<MethodCallExpr> allCalls = method.findAll(MethodCallExpr.class);

        // Pattern A: super.xxx() calls
        List<String> superCalls = allCalls.stream()
                .filter(call -> call.getScope().filter(s -> s instanceof SuperExpr).isPresent())
                .map(MethodCallExpr::getNameAsString)
                .distinct()
                .toList();

        // Pattern A: static dependency calls — scope is an uppercase-starting NameExpr
        List<MethodCallExpr> staticCalls = allCalls.stream()
                .filter(call -> call.getScope()
                        .filter(s -> s instanceof NameExpr)
                        .map(s -> ((NameExpr) s).getNameAsString())
                        .filter(n -> !n.isEmpty() && Character.isUpperCase(n.charAt(0)))
                        .isPresent())
                .toList();

        List<String> staticCallClasses = staticCalls.stream()
                .map(call -> ((NameExpr) call.getScope().get()).getNameAsString())
                .distinct()
                .toList();

        // "ClassName.methodName" tokens — used to look up return types from source
        List<String> staticCallTokens = staticCalls.stream()
                .map(call -> ((NameExpr) call.getScope().get()).getNameAsString()
                             + "." + call.getNameAsString()
                             + ":" + call.getArguments().size()) // argCount for overload resolution
                .distinct()
                .toList();

        // Pattern D: internal method calls — any call with no scope or explicit 'this' scope
        // (structural detection: name-agnostic; any same-class call is a potential helper to stub)
        List<String> helperCalls = allCalls.stream()
                .filter(call -> call.getScope().isEmpty()
                        || call.getScope().filter(s -> s instanceof ThisExpr).isPresent())
                .map(MethodCallExpr::getNameAsString)
                .filter(name -> !name.equals(method.getNameAsString())) // exclude self-recursive
                .distinct()
                .toList();

        // Pattern C: conditional logic
        boolean hasConditionals = !method.findAll(IfStmt.class).isEmpty()
                || !method.findAll(ConditionalExpr.class).isEmpty()
                || !method.findAll(SwitchStmt.class).isEmpty();

        // Pattern G: numeric comparisons
        boolean hasNumericComparisons = method.findAll(BinaryExpr.class).stream()
                .anyMatch(b -> b.getOperator() == BinaryExpr.Operator.LESS
                        || b.getOperator() == BinaryExpr.Operator.GREATER
                        || b.getOperator() == BinaryExpr.Operator.LESS_EQUALS
                        || b.getOperator() == BinaryExpr.Operator.GREATER_EQUALS)
                || allCalls.stream().anyMatch(c -> c.getNameAsString().equals("compareTo"));

        // Pattern H: try/catch blocks
        boolean hasTryCatch = !method.findAll(TryStmt.class).isEmpty();

        // Condition scenario extraction — analyze conditions for coverage-focused test data
        List<ConditionScenario> conditionScenarios =
                extractConditionScenarios(method, params);

        // Inline object constructions: new X() — caller supplies these type names;
        // TestOrchestrator filters to @Entity types for MockedConstruction generation
        List<String> constructedTypes = method.findAll(ObjectCreationExpr.class).stream()
                .map(expr -> expr.getType().getNameAsString())
                .filter(name -> !name.isEmpty() && Character.isUpperCase(name.charAt(0)))
                .distinct()
                .toList();

        // Cast expressions: (SomeType) expr — detects service-locator pattern
        //   TPIBFTPayeeRepo repo = (TPIBFTPayeeRepo) makeDAO(BEAN_ID)
        // Also tracks which methods are called on each cast variable so we can generate
        // proper when(repo.getPayeeDetails(any(), any())).thenReturn(...) stubs.
        List<String> castToTypes = method.findAll(CastExpr.class).stream()
                .map(cast -> cast.getType().asString().replaceAll("<.*>", "").trim())
                .filter(name -> !name.isEmpty() && Character.isUpperCase(name.charAt(0)))
                .distinct()
                .toList();

        // Map varName → castType for method-call detection below
        // e.g. "repo" → "TPIBFTPayeeRepo"
        java.util.Map<String, String> castVarToType = new java.util.HashMap<>();
        method.findAll(VariableDeclarator.class).forEach(var ->
                var.getInitializer()
                   .filter(init -> init instanceof CastExpr)
                   .map(init -> (CastExpr) init)
                   .ifPresent(cast -> {
                       String ct = cast.getType().asString().replaceAll("<.*>", "").trim();
                       if (!ct.isEmpty() && Character.isUpperCase(ct.charAt(0))) {
                           castVarToType.put(var.getNameAsString(), ct);
                       }
                   }));

        // Store repoType → [methodName,argCount] for calls like repo.getPayeeDetails(a,b)
        // Stored as: "TPIBFTPayeeRepo|getPayeeDetails|2"  (simple string for MethodMetadata)
        // TestOrchestrator resolves parameter types from the interface source
        List<String> repoMethodCallTokens = method.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getScope()
                        .filter(s -> s instanceof NameExpr)
                        .map(s -> castVarToType.containsKey(((NameExpr) s).getNameAsString()))
                        .orElse(false))
                .map(call -> {
                    String varName = ((NameExpr) call.getScope().get()).getNameAsString();
                    return castVarToType.get(varName) + "|" + call.getNameAsString()
                           + "|" + call.getArguments().size();
                })
                .distinct()
                .toList();

        // Detect class field accesses (for private method isolation documentation)
        List<String> accessedFields = method.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .filter(name -> !name.isEmpty() && Character.isLowerCase(name.charAt(0)))
                .filter(name -> !name.equals(method.getNameAsString()))
                .distinct()
                .toList();

        // Detect service-locator lookups by Class arg, e.g.
        //   ApplicationContext.getBean(X.class) / getBean("name", X.class)
        //   ApplicationContextBean.getService(X.class)
        // These represent DAO/service lookups via Spring context — need separate mocks
        List<String> getBeanTypes = method.findAll(MethodCallExpr.class).stream()
                .filter(call -> isBeanLocatorMethod(call.getNameAsString())
                        && !call.getArguments().isEmpty())
                .flatMap(call -> call.getArguments().stream())
                .filter(arg -> arg instanceof ClassExpr)
                .map(arg -> ((ClassExpr) arg).getType().asString().replaceAll("<.*>", "").trim())
                .filter(t -> !t.isEmpty() && Character.isUpperCase(t.charAt(0)))
                .distinct()
                .toList();

        // Calls on injected fields: payeeRepo.findById(x) → "payeeRepo:findById:1"
        // Scope is a lowercase-starting NameExpr (a field/variable reference).
        List<String> fieldCallTokens = allCalls.stream()
                .filter(call -> call.getScope().filter(s -> s instanceof NameExpr).isPresent())
                .map(call -> {
                    String scope = ((NameExpr) call.getScope().get()).getNameAsString();
                    return scope + ":" + call.getNameAsString() + ":" + call.getArguments().size();
                })
                .filter(t -> !t.isEmpty() && Character.isLowerCase(t.charAt(0)))
                .distinct()
                .toList();

        return new MethodMetadata(
                method.getNameAsString(),
                method.getTypeAsString(),
                params, thrown, annotations,
                method.isPublic(), method.isProtected(),
                method.isStatic(), method.isAbstract(), method.isFinal(),
                annotations.contains("Override"),
                false,
                superCalls, staticCallClasses, staticCallTokens, helperCalls,
                hasConditionals, hasNumericComparisons, hasTryCatch,
                conditionScenarios, constructedTypes, castToTypes, repoMethodCallTokens,
                accessedFields, getBeanTypes, fieldCallTokens
        );
    }

    /**
     * Service-locator accessor methods that resolve a typed bean from a Class argument,
     * e.g. ctx.getBean(X.class) or ApplicationContextBean.getService(X.class).
     */
    static boolean isBeanLocatorMethod(String name) {
        return "getBean".equals(name) || "getService".equals(name);
    }

    // ── Condition scenario extraction ────────────────────────────────────────

    /**
     * Analyses a method's IfStmt nodes and extracts condition scenarios.
     * Each scenario describes:
     *  - which parameter is involved (name + type)
     *  - which field (getter → fieldName)
     *  - what value makes the condition TRUE vs FALSE
     *
     * Supports:
     *  - NULL_CHECK:    param.getField() == null  /  param.getField() != null
     *  - BOOLEAN_CHECK: param.isField()
     *  - EQUALS_CHECK:  param.getField().equals("value")
     *  - NUMERIC_CHECK: param.getField() > 0 / < value / etc.
     */
    private List<ConditionScenario> extractConditionScenarios(
            MethodDeclaration method,
            List<MethodMetadata.ParameterMetadata> params) {

        if (params.isEmpty()) return List.of();

        // Build param name → simple type map for lookup
        Map<String, String> paramTypeMap = params.stream()
                .collect(Collectors.toMap(
                        MethodMetadata.ParameterMetadata::name,
                        p -> p.type().replaceAll("<.*>", "").trim()));

        List<ConditionScenario> scenarios = new ArrayList<>();
        Set<String> seen = new HashSet<>(); // dedup: paramName + fieldName

        for (IfStmt ifStmt : method.findAll(IfStmt.class)) {
            analyseCondition(ifStmt.getCondition(), method.getNameAsString(),
                    paramTypeMap, scenarios, seen);
        }
        return scenarios;
    }

    private void analyseCondition(Expression condition, String methodName,
                                   Map<String, String> paramTypeMap,
                                   List<ConditionScenario> scenarios,
                                   Set<String> seen) {
        // ── NULL_CHECK: param.getXxx() == null OR param.getXxx() != null ──
        if (condition instanceof BinaryExpr binary) {
            BinaryExpr.Operator op = binary.getOperator();
            boolean isNullOp = op == BinaryExpr.Operator.EQUALS
                    || op == BinaryExpr.Operator.NOT_EQUALS;

            if (isNullOp && binary.getRight() instanceof NullLiteralExpr
                    && binary.getLeft() instanceof MethodCallExpr getter
                    && getter.getScope().filter(s -> s instanceof NameExpr).isPresent()) {

                String paramName = ((NameExpr) getter.getScope().get()).getNameAsString();
                String paramType = paramTypeMap.get(paramName);
                if (paramType != null && isPotentialDomainType(paramType)) {
                    String fieldName  = getterToField(getter.getNameAsString());
                    String setterName = fieldToSetter(fieldName);
                    String key = paramName + "." + fieldName;
                    if (seen.add(key)) {
                        boolean equalsNull = op == BinaryExpr.Operator.EQUALS;
                        scenarios.add(new ConditionScenario(
                                methodName, paramName, paramType,
                                fieldName, setterName,
                                ConditionScenario.ConditionType.NULL_CHECK,
                                equalsNull ? paramName + cap(fieldName) + "Null"
                                           : paramName + cap(fieldName) + "Present",
                                equalsNull ? paramName + cap(fieldName) + "Present"
                                           : paramName + cap(fieldName) + "Null",
                                equalsNull ? "null" : "/* non-null value */",
                                equalsNull ? "/* non-null value */" : "null"
                        ));
                    }
                }
            }

            // ── NUMERIC_CHECK: param.getXxx() > 0 / < value / etc. ──────
            boolean isNumericOp = op == BinaryExpr.Operator.GREATER
                    || op == BinaryExpr.Operator.LESS
                    || op == BinaryExpr.Operator.GREATER_EQUALS
                    || op == BinaryExpr.Operator.LESS_EQUALS;

            if (isNumericOp
                    && binary.getLeft() instanceof MethodCallExpr getter
                    && getter.getScope().filter(s -> s instanceof NameExpr).isPresent()) {

                String paramName = ((NameExpr) getter.getScope().get()).getNameAsString();
                String paramType = paramTypeMap.get(paramName);
                String rhsText   = binary.getRight().toString();
                if (paramType != null && isPotentialDomainType(paramType)) {
                    String fieldName  = getterToField(getter.getNameAsString());
                    String setterName = fieldToSetter(fieldName);
                    String key = paramName + "." + fieldName + ".numeric";
                    if (seen.add(key)) {
                        scenarios.add(new ConditionScenario(
                                methodName, paramName, paramType,
                                fieldName, setterName,
                                ConditionScenario.ConditionType.NUMERIC_CHECK,
                                paramName + cap(fieldName) + "AboveThreshold",
                                paramName + cap(fieldName) + "BelowThreshold",
                                rhsText + " + 1",   // above threshold
                                rhsText + " - 1"    // below threshold
                        ));
                    }
                }
            }
        }

        // ── BOOLEAN_CHECK: param.isField() ────────────────────────────────
        if (condition instanceof MethodCallExpr boolCall
                && boolCall.getNameAsString().startsWith("is")
                && boolCall.getScope().filter(s -> s instanceof NameExpr).isPresent()) {

            String paramName = ((NameExpr) boolCall.getScope().get()).getNameAsString();
            String paramType = paramTypeMap.get(paramName);
            if (paramType != null && isPotentialDomainType(paramType)) {
                String getterName = boolCall.getNameAsString();
                String fieldName  = Character.toLowerCase(getterName.charAt(2))
                        + getterName.substring(3);
                String setterName = "set" + getterName.substring(2);
                String key = paramName + "." + fieldName + ".bool";
                if (seen.add(key)) {
                    scenarios.add(new ConditionScenario(
                            methodName, paramName, paramType,
                            fieldName, setterName,
                            ConditionScenario.ConditionType.BOOLEAN_CHECK,
                            paramName + cap(fieldName) + "True",
                            paramName + cap(fieldName) + "False",
                            "true", "false"
                    ));
                }
            }
        }

        // ── EQUALS_CHECK: param.getField().equals("value") ────────────────
        if (condition instanceof MethodCallExpr equalsCall
                && equalsCall.getNameAsString().equals("equals")
                && equalsCall.getScope().filter(s -> s instanceof MethodCallExpr).isPresent()
                && !equalsCall.getArguments().isEmpty()) {

            MethodCallExpr getter = (MethodCallExpr) equalsCall.getScope().get();
            if (getter.getScope().filter(s -> s instanceof NameExpr).isPresent()) {
                String paramName = ((NameExpr) getter.getScope().get()).getNameAsString();
                String paramType = paramTypeMap.get(paramName);
                String compareVal = equalsCall.getArgument(0).toString();
                if (paramType != null && isPotentialDomainType(paramType)) {
                    String fieldName  = getterToField(getter.getNameAsString());
                    String setterName = fieldToSetter(fieldName);
                    String key = paramName + "." + fieldName + ".eq";
                    if (seen.add(key)) {
                        scenarios.add(new ConditionScenario(
                                methodName, paramName, paramType,
                                fieldName, setterName,
                                ConditionScenario.ConditionType.EQUALS_CHECK,
                                paramName + cap(fieldName) + "Matches",
                                paramName + cap(fieldName) + "NoMatch",
                                compareVal,           // "CONFIRMED" — true
                                "\"OTHER\""           // false
                        ));
                    }
                }
            }
        }
    }

    /** Returns true for types that are likely domain objects (not primitives or standard types). */
    private boolean isPotentialDomainType(String type) {
        return !Set.of("String", "int", "Integer", "long", "Long", "double", "Double",
                "float", "Float", "boolean", "Boolean", "byte", "Byte", "short", "Short",
                "char", "Character", "Object", "void").contains(type);
    }

    /** Converts getter name to field name: getAmount → amount, isActive → active */
    private String getterToField(String getter) {
        if (getter.startsWith("get") && getter.length() > 3) {
            return Character.toLowerCase(getter.charAt(3)) + getter.substring(4);
        }
        if (getter.startsWith("is") && getter.length() > 2) {
            return Character.toLowerCase(getter.charAt(2)) + getter.substring(3);
        }
        return getter;
    }

    /** Converts field name to setter: amount → setAmount */
    private String fieldToSetter(String field) {
        return "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
    }

    private String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private List<String> extractAnnotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();
    }
}
