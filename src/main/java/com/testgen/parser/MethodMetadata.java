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
        boolean isConstructor
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

    public record ParameterMetadata(String type, String name) {}
}
