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
                    List.of(), List.of(), Set.of(), Map.of()  // parentChain / interfaceDefaults / concreteNames / paramTypeRegistry — resolved later
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
                        effectivelyInjected, isValue, valueKey, field.isFinal(), isAppCtx,
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
        List<String> staticCallClasses = allCalls.stream()
                .filter(call -> call.getScope()
                        .filter(s -> s instanceof NameExpr)
                        .map(s -> ((NameExpr) s).getNameAsString())
                        .filter(n -> !n.isEmpty() && Character.isUpperCase(n.charAt(0)))
                        .isPresent())
                .map(call -> ((NameExpr) call.getScope().get()).getNameAsString())
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

        return new MethodMetadata(
                method.getNameAsString(),
                method.getTypeAsString(),
                params, thrown, annotations,
                method.isPublic(), method.isProtected(),
                method.isStatic(), method.isAbstract(), method.isFinal(),
                annotations.contains("Override"),
                false,
                superCalls, staticCallClasses, helperCalls,
                hasConditionals, hasNumericComparisons, hasTryCatch
        );
    }

    private List<String> extractAnnotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();
    }
}
