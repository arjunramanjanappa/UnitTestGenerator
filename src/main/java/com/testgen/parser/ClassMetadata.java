package com.testgen.parser;

import com.testgen.classifier.ClassType;

import java.util.List;

public record ClassMetadata(
        String className,
        String packageName,
        String sourceFilePath,
        ClassType classType,
        List<String> annotations,
        List<FieldMetadata> fields,
        List<MethodMetadata> methods,
        List<String> imports,
        String superClassName,
        List<String> interfaces,
        boolean isAbstract,
        boolean isInterface,
        boolean hasLombok,
        boolean hasBuilder,
        List<String> genericTypeParams,
        String springBootVersion
) {
    public String fullClassName() {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    public List<FieldMetadata> injectedFields() {
        return fields.stream().filter(FieldMetadata::isInjected).toList();
    }

    public List<FieldMetadata> mockCandidates() {
        return fields.stream().filter(FieldMetadata::isMockCandidate).toList();
    }

    public List<FieldMetadata> valueFields() {
        return fields.stream().filter(FieldMetadata::isValue).toList();
    }

    public boolean hasApplicationContext() {
        return fields.stream().anyMatch(FieldMetadata::isApplicationContext);
    }

    /** Methods this class owns + overrides — both public and protected, no constructors. */
    public List<MethodMetadata> ownPublicMethods() {
        return methods.stream()
                .filter(MethodMetadata::isTestable)
                .toList();
    }

    /** Methods this class overrides from parent (only test child behavior, stub parent). */
    public List<MethodMetadata> overriddenMethods() {
        return methods.stream()
                .filter(MethodMetadata::isOverride)
                .filter(MethodMetadata::isTestable)
                .toList();
    }

    /**
     * Own methods that are NOT overrides — or all own methods when there is no parent.
     * Inherited-but-not-overridden methods are intentionally excluded (covered by parent test).
     */
    public List<MethodMetadata> ownNonOverriddenMethods() {
        if (!hasSuperClass()) return ownPublicMethods();
        return methods.stream()
                .filter(MethodMetadata::isTestable)
                .filter(m -> !m.isOverride())
                .toList();
    }

    public boolean hasSuperClass() {
        return superClassName != null && !superClassName.equals("Object");
    }

    public ClassMetadata withClassType(ClassType type) {
        return new ClassMetadata(className, packageName, sourceFilePath, type,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams, springBootVersion);
    }

    public ClassMetadata withSpringBootVersion(String version) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams, version);
    }
}
