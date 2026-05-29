package com.testgen.parser;

import java.util.List;

public record FieldMetadata(
        String name,
        String type,
        String simpleType,
        List<String> annotations,
        boolean isInjected,
        boolean isValue,
        String valueKey,
        boolean isFinal,
        boolean isApplicationContext
) {
    public boolean isMockCandidate() {
        return isInjected && !isValue;
    }

    public boolean requiresReflectionSetup() {
        return isValue;
    }
}
