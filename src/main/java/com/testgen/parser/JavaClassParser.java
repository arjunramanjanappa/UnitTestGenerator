package com.testgen.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodesets.NodeWithAnnotations;
import com.github.javaparser.ast.type.ReferenceType;
import com.testgen.classifier.ClassType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Component
public class JavaClassParser {

    private static final Set<String> INJECT_ANNOTATIONS =
            Set.of("Autowired", "Inject", "Resource");
    private static final Set<String> APP_CTX_TYPES =
            Set.of("ApplicationContext", "ConfigurableApplicationContext",
                    "GenericApplicationContext", "WebApplicationContext");

    public Optional<ClassMetadata> parse(Path filePath) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(filePath);

            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            List<String> imports = cu.getImports().stream()
                    .map(i -> i.getNameAsString())
                    .toList();

            Optional<ClassOrInterfaceDeclaration> classDecl =
                    cu.findFirst(ClassOrInterfaceDeclaration.class);

            if (classDecl.isEmpty() || classDecl.get().isInterface()) {
                return Optional.empty();
            }

            ClassOrInterfaceDeclaration cls = classDecl.get();
            String className = cls.getNameAsString();
            List<String> annotations = extractAnnotationNames(cls);

            String superClass = cls.getExtendedTypes().isEmpty() ? null
                    : cls.getExtendedTypes().get(0).getNameAsString();

            // Extract generic type params from parent e.g. BaseService<Order, Long>
            List<String> genericTypeParams = new ArrayList<>();
            if (!cls.getExtendedTypes().isEmpty()) {
                cls.getExtendedTypes().get(0).getTypeArguments()
                        .ifPresent(args -> args.forEach(a -> genericTypeParams.add(a.asString())));
            }

            List<String> interfaces = cls.getImplementedTypes().stream()
                    .map(t -> t.getNameAsString())
                    .toList();

            boolean hasLombok = imports.stream().anyMatch(i -> i.startsWith("lombok."));
            boolean hasBuilder = annotations.contains("Builder")
                    || annotations.contains("Data")
                    || annotations.contains("SuperBuilder");

            List<FieldMetadata> fields = parseFields(cls);
            List<MethodMetadata> methods = parseMethods(cls);

            return Optional.of(new ClassMetadata(
                    className, packageName, filePath.toString(),
                    ClassType.POJO, // Classifier will override
                    annotations, fields, methods, imports,
                    superClass, interfaces,
                    cls.isAbstract(), cls.isInterface(),
                    hasLombok, hasBuilder, genericTypeParams, null
            ));

        } catch (IOException e) {
            log.error("Failed to parse {}: {}", filePath, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Unexpected error parsing {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    private List<FieldMetadata> parseFields(ClassOrInterfaceDeclaration cls) {
        List<FieldMetadata> result = new ArrayList<>();

        for (FieldDeclaration field : cls.getFields()) {
            List<String> annotations = extractAnnotationNames(field);

            boolean isInjected = annotations.stream().anyMatch(INJECT_ANNOTATIONS::contains)
                    || (field.isFinal() && !field.isStatic()); // final non-static = constructor injection

            boolean isValue = annotations.contains("Value");
            String valueKey = "";
            if (isValue) {
                valueKey = field.getAnnotationByName("Value")
                        .map(a -> a.toString())
                        .orElse("");
            }

            for (VariableDeclarator var : field.getVariables()) {
                String type = var.getTypeAsString();
                String simpleType = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
                boolean isAppCtx = APP_CTX_TYPES.contains(simpleType);

                result.add(new FieldMetadata(
                        var.getNameAsString(), type, simpleType, annotations,
                        isInjected, isValue, valueKey, field.isFinal(), isAppCtx
                ));
            }
        }
        return result;
    }

    private List<MethodMetadata> parseMethods(ClassOrInterfaceDeclaration cls) {
        List<MethodMetadata> result = new ArrayList<>();

        for (MethodDeclaration method : cls.getMethods()) {
            List<String> annotations = extractAnnotationNames(method);
            List<String> thrown = method.getThrownExceptions().stream()
                    .map(ReferenceType::asString)
                    .toList();
            List<MethodMetadata.ParameterMetadata> params = method.getParameters().stream()
                    .map(p -> new MethodMetadata.ParameterMetadata(
                            p.getTypeAsString(), p.getNameAsString()))
                    .toList();

            result.add(new MethodMetadata(
                    method.getNameAsString(),
                    method.getTypeAsString(),
                    params, thrown, annotations,
                    method.isPublic(), method.isProtected(),
                    method.isStatic(), method.isAbstract(), method.isFinal(),
                    annotations.contains("Override"),
                    false
            ));
        }
        return result;
    }

    private List<String> extractAnnotationNames(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .toList();
    }
}
