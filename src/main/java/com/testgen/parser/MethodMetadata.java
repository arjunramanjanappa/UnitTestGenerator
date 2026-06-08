package com.testgen.parser;

import java.util.List;

public record MethodMetadata(
        String name,
        String returnType,
        List<ParameterMetadata> parameters,
        List<String> thrownExceptions,
        List<String> annotations,
        boolean isPublic,
        boolean isProtected,
        boolean isStatic,
        boolean isAbstract,
        boolean isFinal,
        boolean isOverride,
        boolean isConstructor,
        List<String> superMethodCalls,          // super.xxx() calls in body
        List<String> staticCallClasses,         // Uppercase-scoped method calls → likely static
        List<String> staticCallTokens,          // "ClassName.methodName" tokens for return-type lookup
        List<String> helperMethodCalls,         // internal method calls (structural)
        boolean hasConditionals,                // if/else/switch/ternary
        boolean hasNumericComparisons,          // >, <, >=, <=, compareTo
        boolean hasTryCatch,                    // try/catch blocks
        List<ConditionScenario> conditionScenarios, // detected branch conditions with scenarios
        List<String> constructedTypes,          // types instantiated via 'new X()' in body
        List<String> castToTypes,               // types used in cast expressions — potential service-locator repos
        List<String> repoMethodCallTokens,     // "RepoType|methodName|argCount" for calls on cast vars
        List<String> accessedFieldNames,       // class fields accessed inside this method (for private isolation comment)
        List<String> getBeanCallTypes,         // types passed to getBean(X.class) — ApplicationContext pattern
        List<String> fieldCallTokens           // "fieldName:methodName:argCount" — calls on injected fields
) {
    public boolean hasReturnValue() {
        return !"void".equals(returnType);
    }

    public boolean throwsExceptions() {
        return !thrownExceptions.isEmpty();
    }

    public boolean isTestable() {
        return (isPublic || isProtected) && !isConstructor && !isStatic;
    }

    public boolean hasSuperCalls() {
        return superMethodCalls != null && !superMethodCalls.isEmpty();
    }

    public boolean hasStaticDependencies() {
        return staticCallClasses != null && !staticCallClasses.isEmpty();
    }

    public boolean hasHelperCalls() {
        return helperMethodCalls != null && !helperMethodCalls.isEmpty();
    }

    public boolean hasConditionScenarios() {
        return conditionScenarios != null && !conditionScenarios.isEmpty();
    }

    public boolean hasConstructedTypes() {
        return constructedTypes != null && !constructedTypes.isEmpty();
    }

    public record ParameterMetadata(String type, String name) {}
}
