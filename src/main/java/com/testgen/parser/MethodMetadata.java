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
        List<String> superMethodCalls,   // super.xxx() calls in body
        List<String> staticCallClasses,  // Uppercase-scoped method calls → likely static
        List<String> helperMethodCalls,  // populate*/build*/create*/map*/assemble* internal calls
        boolean hasConditionals,         // if/else/switch/ternary
        boolean hasNumericComparisons,   // >, <, >=, <=, compareTo
        boolean hasTryCatch             // try/catch blocks
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

    public record ParameterMetadata(String type, String name) {}
}
