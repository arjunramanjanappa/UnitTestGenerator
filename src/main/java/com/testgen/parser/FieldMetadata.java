package com.testgen.parser;

import java.util.List;
import java.util.Map;

public record FieldMetadata(
        String name,
        String type,
        String simpleType,
        List<String> annotations,
        boolean isInjected,
        boolean isValue,
        String valueKey,
        boolean isFinal,
        boolean isApplicationContext,
        boolean isConstructorInjected,   // injected via constructor (no @Autowired on field)
        Map<String, String> constraints  // validation annotation name → value (e.g. "Min"->"1")
) {
    public boolean isMockCandidate() {
        return isInjected && !isValue;
    }

    public boolean requiresReflectionSetup() {
        return isValue;
    }

    public boolean hasConstraints() {
        return constraints != null && !constraints.isEmpty();
    }
}
