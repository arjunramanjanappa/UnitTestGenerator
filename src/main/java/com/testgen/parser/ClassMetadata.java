package com.testgen.parser;

import com.testgen.classifier.ClassType;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
        String springBootVersion,
        List<ClassMetadata> parentChain,             // resolved parent classes (direct → grandparent …)
        List<MethodMetadata> interfaceDefaultMethods, // default methods from implemented interfaces
        Set<String> concreteClassNames,              // all non-interface class names in the scanned source root
        Map<String, ClassMetadata> paramTypeRegistry, // parsed metadata for types used in method params
        Set<String> entityConstructions,             // @Entity types instantiated inline via new X()
        List<ServiceLocatorAccess> serviceLocatorRepos, // @Repository types obtained via service locator cast
        Map<String, String> resolvedStaticTypes,     // "ClassName.methodName" → returnType from source
        List<ServiceLocatorAccess> appContextRepos, // repos obtained via ApplicationContext.getBean(X.class)
        Map<String, String> fieldCallReturnTypes    // "fieldName.methodName" → returnType (for stubbing mock returns)
) {
    public String fullClassName() {
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    public List<FieldMetadata> injectedFields() {
        return fields.stream().filter(FieldMetadata::isMockCandidate).toList();
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
        return methods.stream().filter(MethodMetadata::isTestable).toList();
    }

    /** Methods this class overrides from a parent. */
    public List<MethodMetadata> overriddenMethods() {
        return methods.stream()
                .filter(MethodMetadata::isOverride)
                .filter(MethodMetadata::isTestable)
                .toList();
    }

    /** Own methods that are NOT overrides (or all own methods when no parent). */
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

    public boolean hasParentChain() {
        return parentChain != null && !parentChain.isEmpty();
    }

    public boolean hasInterfaceDefaultMethods() {
        return interfaceDefaultMethods != null && !interfaceDefaultMethods.isEmpty();
    }

    // ── Wither methods ──────────────────────────────────────────────────────

    public ClassMetadata withClassType(ClassType type) {
        return new ClassMetadata(className, packageName, sourceFilePath, type,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames, paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    public ClassMetadata withSpringBootVersion(String version) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                version, parentChain, interfaceDefaultMethods, concreteClassNames, paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    public ClassMetadata withParentChain(List<ClassMetadata> chain) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, chain, interfaceDefaultMethods, concreteClassNames, paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    public ClassMetadata withInterfaceDefaultMethods(List<MethodMetadata> defaults) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, defaults, concreteClassNames, paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    public ClassMetadata withConcreteClassNames(Set<String> names) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, names, paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    public ClassMetadata withParamTypeRegistry(Map<String, ClassMetadata> registry) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames, registry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    public ClassMetadata withEntityConstructions(Set<String> entities) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames, paramTypeRegistry, entities, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    public boolean hasEntityConstructions() {
        return entityConstructions != null && !entityConstructions.isEmpty();
    }

    public boolean hasServiceLocatorRepos() {
        return serviceLocatorRepos != null && !serviceLocatorRepos.isEmpty();
    }

    public ClassMetadata withResolvedStaticTypes(Map<String, String> types) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames,
                paramTypeRegistry, entityConstructions, serviceLocatorRepos, types, appContextRepos, fieldCallReturnTypes);
    }

    public boolean hasAppContextRepos() {
        return appContextRepos != null && !appContextRepos.isEmpty();
    }

    public ClassMetadata withAppContextRepos(List<ServiceLocatorAccess> repos) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames,
                paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, repos, fieldCallReturnTypes);
    }

    public ClassMetadata withFieldCallReturnTypes(Map<String, String> types) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames,
                paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, types);
    }

    public ClassMetadata withServiceLocatorRepos(List<ServiceLocatorAccess> repos) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames,
                paramTypeRegistry, entityConstructions, repos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    /** Override package — used when placing companion TestData in a different package. */
    public ClassMetadata withPackageName(String pkg) {
        return new ClassMetadata(className, pkg, sourceFilePath, classType,
                annotations, fields, methods, imports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames, paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }

    /** Override imports — used to supply the owning class's import list for FQN resolution. */
    public ClassMetadata withImports(List<String> newImports) {
        return new ClassMetadata(className, packageName, sourceFilePath, classType,
                annotations, fields, methods, newImports, superClassName, interfaces,
                isAbstract, isInterface, hasLombok, hasBuilder, genericTypeParams,
                springBootVersion, parentChain, interfaceDefaultMethods, concreteClassNames, paramTypeRegistry, entityConstructions, serviceLocatorRepos, resolvedStaticTypes, appContextRepos, fieldCallReturnTypes);
    }
}
