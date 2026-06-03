package com.testgen.generator.builder;

import com.testgen.generator.GeneratedTest;
import com.testgen.parser.ClassMetadata;
import com.testgen.parser.ConditionScenario;
import com.testgen.parser.FieldMetadata;
import com.testgen.parser.MethodMetadata;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Generates a *TestData.java file for each class.
 *
 * Produces multiple named factory methods:
 *   buildValid<Class>()    — all constraint-satisfying values
 *   buildInvalid<Class>()  — null / out-of-range for each constrained field
 *   buildBoundary<Class>() — boundary values for @Min/@Max/@Size fields
 *   build<Class>List()     — single-element list
 *   build<DepType>()       — per injected dependency type
 */
@Component
public class DataBuilderGenerator {

    private static final String I1 = "    ";
    private static final String I2 = "        ";
    private static final String I3 = "            ";

    public GeneratedTest generate(ClassMetadata m) {
        return new GeneratedTest(
                m.className() + "TestData.java",
                m.packageName(),
                buildFile(m),
                GeneratedTest.GeneratedTestType.TEST_DATA
        );
    }

    // ── File assembly ───────────────────────────────────────────────────────

    private String buildFile(ClassMetadata m) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(m.packageName()).append(";\n\n");
        sb.append("import java.math.BigDecimal;\n");
        sb.append("import java.math.BigInteger;\n");
        sb.append("import java.time.LocalDate;\n");
        sb.append("import java.time.LocalDateTime;\n");
        sb.append("import java.util.*;\n");
        // Resolve imports for domain types referenced in this TestData file.
        // Uses the source class's own imports as the authoritative FQN mapping —
        // avoids ambiguity when multiple classes share the same simple name.
        sb.append(buildResolvedImports(m));
        sb.append("\n");

        sb.append("/**\n");
        sb.append(" * Test data factory for {@link ").append(m.className()).append("}.\n");
        sb.append(" * Generated — modify freely to add domain-specific test data.\n");
        sb.append(" *\n");
        sb.append(" * Scenarios:\n");
        sb.append(" *   buildValid").append(m.className()).append("()    — all fields satisfy constraints\n");
        sb.append(" *   buildInvalid").append(m.className()).append("()  — constraint violations for negative tests\n");
        sb.append(" *   buildBoundary").append(m.className()).append("() — boundary values for numeric/size constraints\n");
        sb.append(" */\n");
        sb.append("public class ").append(m.className()).append("TestData {\n\n");

        sb.append(buildValidFactory(m));
        sb.append(buildInvalidFactory(m));
        sb.append(buildBoundaryFactory(m));
        sb.append(buildListFactory(m));
        sb.append(buildConditionScenarioFactories(m));
        sb.append(buildDependencyFactories(m));

        sb.append("}\n");
        return sb.toString();
    }

    // ── Import resolution ───────────────────────────────────────────────────

    /**
     * Resolves FQN imports for every domain type referenced in this TestData file.
     *
     * Uses the source class's own import list as the authoritative mapping of
     * simple name → FQN. This handles:
     *  - Types from different packages (com.bank.vo.MSBaseVO vs com.other.MSBaseVO)
     *  - Framework types already imported by the source class
     *  - Multiple classes with the same simple name — picks the one the source class uses
     *
     * Types in the same package as the source class are also emitted as explicit imports
     * so the TestData file is self-contained and compiles anywhere.
     */
    private String buildResolvedImports(ClassMetadata m) {
        // Build simple-name → FQN map from the SOURCE CLASS's own import declarations
        // — ClassA's imports are the ground truth for which MSBaseVO we mean
        Map<String, String> simpleToFqn = new LinkedHashMap<>();
        for (String fqn : m.imports()) {
            String simple = fqn.contains(".")
                    ? fqn.substring(fqn.lastIndexOf('.') + 1)
                    : fqn;
            simpleToFqn.put(simple, fqn);
        }
        // Also register the source class itself (for TestData that reference the main class)
        simpleToFqn.put(m.className(), m.fullClassName());

        // Collect all domain type simple names referenced in this TestData
        Set<String> usedTypes = new LinkedHashSet<>();

        // 1. Condition scenario param types (from buildConditionScenarioFactories)
        m.methods().stream()
                .filter(mm -> mm.conditionScenarios() != null)
                .flatMap(mm -> mm.conditionScenarios().stream())
                .map(ConditionScenario::paramType)
                .forEach(usedTypes::add);

        // 2. Injected field types (from buildDependencyFactories)
        m.mockCandidates().stream()
                .filter(f -> !f.isApplicationContext())
                .map(FieldMetadata::simpleType)
                .forEach(usedTypes::add);

        // 3. Own non-injected field types used in setters (buildValidFactory etc.)
        nonStaticFieldsRaw(m).stream()
                .map(FieldMetadata::simpleType)
                .forEach(usedTypes::add);

        // 4. The class itself (for buildValid<Class>() return type)
        usedTypes.add(m.className());

        // Emit import for each type that was found in the source class's imports
        StringBuilder sb = new StringBuilder();
        for (String type : usedTypes) {
            String fqn = simpleToFqn.get(type);
            if (fqn == null) continue;
            // Skip java.lang — it's always available
            if (fqn.startsWith("java.lang.")) continue;
            // Skip same-package types if class is in same package as the TestData
            // (same package = no import needed, but we emit it anyway for clarity)
            sb.append("import ").append(fqn).append(";\n");
        }
        return sb.toString();
    }

    /** Same as nonStaticFields but without the Autowired filter — used for import collection. */
    private List<FieldMetadata> nonStaticFieldsRaw(ClassMetadata m) {
        return m.fields().stream()
                .filter(f -> !f.isApplicationContext())
                .filter(f -> !f.isValue())
                .toList();
    }

    // ── Valid factory (all constraints satisfied) ───────────────────────────

    private String buildValidFactory(ClassMetadata m) {
        StringBuilder sb = new StringBuilder();
        sb.append(I1).append("/** All fields satisfy validation constraints. */\n");
        sb.append(I1).append("public static ").append(m.className())
          .append(" buildValid").append(m.className()).append("() {\n");

        if (m.hasBuilder()) {
            sb.append(I2).append("return ").append(m.className()).append(".builder()\n");
            for (FieldMetadata f : nonStaticFields(m)) {
                sb.append(I3).append(".").append(f.name())
                  .append("(").append(validValue(f)).append(")\n");
            }
            sb.append(I3).append(".build();\n");
        } else {
            sb.append(I2).append(m.className()).append(" obj = new ").append(m.className()).append("();\n");
            for (FieldMetadata f : settableFields(m)) {   // skip fields with no setter
                String setter = "set" + setterSuffix(f);
                sb.append(I2).append("obj.").append(setter).append("(").append(validValue(f)).append(");\n");
            }
            sb.append(I2).append("return obj;\n");
        }
        sb.append(I1).append("}\n\n");
        return sb.toString();
    }

    // ── Invalid factory (constraint violations for negative tests) ──────────

    private String buildInvalidFactory(ClassMetadata m) {
        List<FieldMetadata> constrained = constrainedFields(m);
        if (constrained.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(I1).append("/** Constraint violations — use for negative / validation failure tests. */\n");
        sb.append(I1).append("public static ").append(m.className())
          .append(" buildInvalid").append(m.className()).append("() {\n");

        if (m.hasBuilder()) {
            sb.append(I2).append("return ").append(m.className()).append(".builder()\n");
            for (FieldMetadata f : nonStaticFields(m)) {
                sb.append(I3).append(".").append(f.name())
                  .append("(").append(invalidValue(f)).append(")\n");
            }
            sb.append(I3).append(".build();\n");
        } else {
            sb.append(I2).append(m.className()).append(" obj = new ").append(m.className()).append("();\n");
            for (FieldMetadata f : settableFields(m)) {   // skip fields with no setter
                sb.append(I2).append("obj.").append("set").append(setterSuffix(f))
                  .append("(").append(invalidValue(f)).append(");\n");
            }
            sb.append(I2).append("return obj;\n");
        }
        sb.append(I1).append("}\n\n");
        return sb.toString();
    }

    // ── Boundary factory (min/max edge values) ──────────────────────────────

    private String buildBoundaryFactory(ClassMetadata m) {
        List<FieldMetadata> bounded = boundedFields(m);
        if (bounded.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(I1).append("/** Boundary values for numeric and size-constrained fields. */\n");
        sb.append(I1).append("public static ").append(m.className())
          .append(" buildBoundary").append(m.className()).append("() {\n");

        if (m.hasBuilder()) {
            sb.append(I2).append("return ").append(m.className()).append(".builder()\n");
            for (FieldMetadata f : nonStaticFields(m)) {
                sb.append(I3).append(".").append(f.name())
                  .append("(").append(boundaryValue(f)).append(")\n");
            }
            sb.append(I3).append(".build();\n");
        } else {
            sb.append(I2).append(m.className()).append(" obj = new ").append(m.className()).append("();\n");
            for (FieldMetadata f : settableFields(m)) {   // skip fields with no setter
                sb.append(I2).append("obj.").append("set").append(setterSuffix(f))
                  .append("(").append(boundaryValue(f)).append(");\n");
            }
            sb.append(I2).append("return obj;\n");
        }
        sb.append(I1).append("}\n\n");
        return sb.toString();
    }

    // ── List factory ────────────────────────────────────────────────────────

    private String buildListFactory(ClassMetadata m) {
        return I1 + "public static List<" + m.className() + "> build" + m.className() + "List() {\n"
             + I2 + "return List.of(buildValid" + m.className() + "());\n"
             + I1 + "}\n\n";
    }

    // ── Coverage-driven scenario factories ──────────────────────────────────

    /**
     * Generates named scenario factory methods derived from condition analysis of
     * the class's own methods. Each detected condition (null check, boolean, equals,
     * numeric) produces TWO factory methods:
     *  - One that makes the condition TRUE  (e.g. amount=null  → triggers exception)
     *  - One that makes the condition FALSE (e.g. amount=1L    → normal execution)
     *
     * These are generated in the CLASS UNDER TEST's TestData so tests can reference:
     *   ClassATestData.build_validate_voAmountNull()       ← triggers exception branch
     *   ClassATestData.build_validate_voAmountPresent()    ← normal execution branch
     */
    private String buildConditionScenarioFactories(ClassMetadata m) {
        List<ConditionScenario> allScenarios = m.methods().stream()
                .filter(MethodMetadata::isTestable)
                .filter(MethodMetadata::hasConditionScenarios)
                .flatMap(mm -> mm.conditionScenarios().stream())
                .toList();

        if (allScenarios.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append(I1).append("// ===== Coverage scenarios from condition analysis =====\n\n");

        Set<String> generated = new HashSet<>();

        for (ConditionScenario sc : allScenarios) {
            // TRUE scenario
            String trueName = sc.trueMethodName();
            if (generated.add(trueName)) {
                sb.append(buildScenarioMethod(m, sc, trueName, sc.trueSetExpr(), sc.trueLabel()));
            }
            // FALSE scenario
            String falseName = sc.falseMethodName();
            if (generated.add(falseName)) {
                sb.append(buildScenarioMethod(m, sc, falseName, sc.falseSetExpr(), sc.falseLabel()));
            }
        }
        return sb.toString();
    }

    private String buildScenarioMethod(ClassMetadata m, ConditionScenario sc,
                                        String methodName, String setExpr, String label) {
        String paramType    = sc.paramType();
        String baseMethod   = "buildValid" + paramType + "()";
        boolean hasTestData = m.concreteClassNames() != null
                && m.concreteClassNames().contains(paramType);

        // For NULL_CHECK: FALSE scenario means "field is NON-null" → base TestData already has it set
        // Just return the base TestData without overriding the field
        boolean isNoOverride = setExpr == null || setExpr.isEmpty()
                || setExpr.startsWith("/*"); // comment placeholder = no meaningful override

        StringBuilder sb = new StringBuilder();
        sb.append(I1).append("/**\n");
        sb.append(I1).append(" * Scenario: ").append(sc.methodName()).append("()")
          .append(" — condition '").append(sc.paramName()).append(".get")
          .append(cap(sc.fieldName())).append("() ")
          .append(conditionDescription(sc.type())).append("' → ").append(label).append("\n");
        if (isNoOverride) {
            sb.append(I1).append(" * Field '").append(sc.fieldName())
              .append("' is already set in buildValid").append(paramType).append("()\n");
        }
        sb.append(I1).append(" */\n");
        sb.append(I1).append("public static ").append(paramType).append(" ").append(methodName).append("() {\n");

        if (isNoOverride && hasTestData) {
            // Field already non-null in base TestData — no override needed
            sb.append(I2).append("return ").append(paramType).append("TestData.").append(baseMethod).append(";\n");
        } else if (hasTestData) {
            sb.append(I2).append(paramType).append(" obj = ").append(paramType).append("TestData.")
              .append(baseMethod).append(";\n");
            sb.append(I2).append("obj.").append(sc.setterName()).append("(").append(setExpr).append(");\n");
            sb.append(I2).append("return obj;\n");
        } else {
            // No TestData — instantiate directly
            sb.append(I2).append(paramType).append(" obj = new ").append(paramType).append("();\n");
            if (!isNoOverride) {
                sb.append(I2).append("obj.").append(sc.setterName()).append("(").append(setExpr).append(");\n");
            }
            sb.append(I2).append("// TODO: set other required fields on ").append(paramType).append("\n");
            sb.append(I2).append("return obj;\n");
        }
        sb.append(I1).append("}\n\n");
        return sb.toString();
    }

    private String conditionDescription(ConditionScenario.ConditionType type) {
        return switch (type) {
            case NULL_CHECK     -> "== null";
            case BOOLEAN_CHECK  -> "== true";
            case EQUALS_CHECK   -> ".equals(value)";
            case NUMERIC_CHECK  -> "comparison";
        };
    }

    // ── Dependency factories ─────────────────────────────────────────────────

    private String buildDependencyFactories(ClassMetadata m) {
        StringBuilder sb = new StringBuilder();
        List<String> seen = new ArrayList<>();
        for (FieldMetadata f : m.injectedFields()) {
            if (f.isApplicationContext() || f.isValue()) continue;
            String type = f.simpleType();
            if (seen.contains(type)) continue;
            seen.add(type);
            sb.append(I1).append("public static ").append(type).append(" build").append(type).append("() {\n");
            sb.append(I2).append("// TODO: construct or stub ").append(type).append("\n");
            sb.append(I2).append("return org.mockito.Mockito.mock(").append(type).append(".class);\n");
            sb.append(I1).append("}\n\n");
        }
        return sb.toString();
    }

    // ── Value generators ────────────────────────────────────────────────────

    /**
     * Returns a value that satisfies the field's constraints.
     */
    private String validValue(FieldMetadata f) {
        Map<String, String> c = f.constraints();
        String type = f.type().replaceAll("<.*>", "").trim();

        if (c.containsKey("Email"))        return "\"test@example.com\"";
        if (c.containsKey("Positive") || c.containsKey("PositiveOrZero")) return positiveDefault(type);
        if (c.containsKey("Negative") || c.containsKey("NegativeOrZero")) return negativeDefault(type);

        if (c.containsKey("Min")) {
            long min = parseLong(c.get("Min"), 1L);
            return numericLiteral(type, min + 1);   // one above min = definitely valid
        }
        if (c.containsKey("Max")) {
            long max = parseLong(c.get("Max"), 100L);
            return numericLiteral(type, max - 1);   // one below max = definitely valid
        }
        if (c.containsKey("Size")) {
            int minLen = parseKeyedInt(c.get("Size"), "min", 1);
            return stringOfLength(type, minLen + 1);
        }
        if (c.containsKey("NotBlank") || c.containsKey("NotEmpty")) return "\"validValue\"";
        if (c.containsKey("NotNull") && isObjectType(type)) return defaultValue(type);

        return defaultValue(type);
    }

    /**
     * Returns a value that violates the field's primary constraint (for negative tests).
     */
    private String invalidValue(FieldMetadata f) {
        Map<String, String> c = f.constraints();
        String type = f.type().replaceAll("<.*>", "").trim();

        if (c.containsKey("NotNull") || c.containsKey("NotBlank") || c.containsKey("NotEmpty"))
            return isObjectType(type) ? "null" : defaultValue(type);
        if (c.containsKey("Min")) {
            long min = parseLong(c.get("Min"), 1L);
            return numericLiteral(type, min - 1);   // one below min = invalid
        }
        if (c.containsKey("Max")) {
            long max = parseLong(c.get("Max"), 100L);
            return numericLiteral(type, max + 1);   // one above max = invalid
        }
        if (c.containsKey("Size")) {
            int minLen = parseKeyedInt(c.get("Size"), "min", 1);
            return stringOfLength(type, Math.max(0, minLen - 1));
        }
        if (c.containsKey("Email"))    return "\"not-an-email\"";
        if (c.containsKey("Positive")) return numericLiteral(type, -1L);
        if (c.containsKey("Negative")) return numericLiteral(type, 1L);

        return defaultValue(type);
    }

    /**
     * Returns the exact boundary value (min/max) for the field.
     */
    private String boundaryValue(FieldMetadata f) {
        Map<String, String> c = f.constraints();
        String type = f.type().replaceAll("<.*>", "").trim();

        if (c.containsKey("Min"))  return numericLiteral(type, parseLong(c.get("Min"), 1L));
        if (c.containsKey("Max"))  return numericLiteral(type, parseLong(c.get("Max"), 100L));
        if (c.containsKey("Size")) {
            int minLen = parseKeyedInt(c.get("Size"), "min", 1);
            return stringOfLength(type, minLen);
        }
        return validValue(f);   // no boundary — fall back to valid value
    }

    // ── Type utilities ───────────────────────────────────────────────────────

    private String defaultValue(String rawType) {
        String type = rawType.replaceAll("<.*>", "").trim();
        return switch (type) {
            case "String"                -> "\"testValue\"";
            case "int", "Integer"        -> "1";
            case "long", "Long"          -> "1L";
            case "double", "Double"      -> "1.0";
            case "float", "Float"        -> "1.0f";
            case "boolean", "Boolean"    -> "true";
            case "BigDecimal"            -> "BigDecimal.ONE";
            case "BigInteger"            -> "BigInteger.ONE";
            case "LocalDate"             -> "LocalDate.now()";
            case "LocalDateTime"         -> "LocalDateTime.now()";
            case "LocalTime"             -> "java.time.LocalTime.now()";
            case "ZonedDateTime"         -> "java.time.ZonedDateTime.now()";
            case "OffsetDateTime"        -> "java.time.OffsetDateTime.now()";
            case "Instant"               -> "java.time.Instant.now()";
            case "UUID"                  -> "UUID.randomUUID()";
            // java.sql — no-arg constructor does not exist
            case "Timestamp",
                 "java.sql.Timestamp"    -> "new java.sql.Timestamp(System.currentTimeMillis())";
            case "Date",
                 "java.sql.Date"         -> "new java.sql.Date(System.currentTimeMillis())";
            case "Time",
                 "java.sql.Time"         -> "new java.sql.Time(System.currentTimeMillis())";
            case "java.util.Date"        -> "new java.util.Date()";
            case "List"                  -> "List.of()";
            case "Map"                   -> "Map.of()";
            case "Set"                   -> "Set.of()";
            default                      -> "null /* TODO: " + rawType + " */";
        };
    }

    private String positiveDefault(String type) {
        return switch (type) {
            case "int", "Integer"   -> "1";
            case "long", "Long"     -> "1L";
            case "double", "Double" -> "0.1";
            case "BigDecimal"       -> "BigDecimal.ONE";
            default                 -> "1";
        };
    }

    private String negativeDefault(String type) {
        return switch (type) {
            case "int", "Integer"   -> "-1";
            case "long", "Long"     -> "-1L";
            case "double", "Double" -> "-0.1";
            case "BigDecimal"       -> "BigDecimal.ONE.negate()";
            default                 -> "-1";
        };
    }

    private String numericLiteral(String type, long value) {
        return switch (type) {
            case "long", "Long"          -> value + "L";
            case "double", "Double"      -> value + ".0";
            case "float", "Float"        -> value + ".0f";
            case "BigDecimal"            -> "new BigDecimal(\"" + value + "\")";
            case "BigInteger"            -> "BigInteger.valueOf(" + value + ")";
            default                      -> String.valueOf(value);
        };
    }

    private String stringOfLength(String type, int length) {
        if (!"String".equals(type)) return defaultValue(type);
        if (length <= 0) return "\"\"";
        return "\"" + "a".repeat(Math.min(length, 50)) + "\"";
    }

    private boolean isObjectType(String type) {
        return !Set.of("int", "long", "double", "float", "boolean",
                       "byte", "short", "char").contains(type);
    }

    private long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private int parseKeyedInt(String pairs, String key, int fallback) {
        if (pairs == null) return fallback;
        for (String pair : pairs.split(",")) {
            String[] kv = pair.trim().split("=");
            if (kv.length == 2 && kv[0].trim().equals(key)) {
                try { return Integer.parseInt(kv[1].trim()); }
                catch (NumberFormatException e) { return fallback; }
            }
        }
        return fallback;
    }

    // ── Field filters ────────────────────────────────────────────────────────

    private List<FieldMetadata> nonStaticFields(ClassMetadata m) {
        return m.fields().stream()
                .filter(f -> !f.annotations().contains("Autowired"))
                .filter(f -> !f.isApplicationContext())
                .filter(f -> !f.isValue())
                .toList();
    }

    /**
     * Returns fields that are safe to set via a setter in the TestData.
     *
     * For Lombok classes (@Data / @Getter+@Setter / @Builder):
     *   All fields are included — Lombok generates setters automatically.
     *
     * For plain classes:
     *   Only fields with an explicitly declared setter in the class are included.
     *   Fields like 'private String fieldName' with no setXxx() method are skipped
     *   to prevent "cannot resolve symbol setFieldName" compile errors.
     */
    private List<FieldMetadata> settableFields(ClassMetadata m) {
        List<FieldMetadata> candidates = nonStaticFields(m);

        // Lombok generates all setters — include everything
        if (m.hasLombok()) return candidates;

        // Build set of explicitly declared setter names in the class
        Set<String> declaredSetters = m.methods().stream()
                .filter(mm -> mm.name().startsWith("set") && !mm.parameters().isEmpty())
                .map(MethodMetadata::name)
                .collect(Collectors.toSet());

        return candidates.stream()
                .filter(f -> {
                    String expectedSetter = "set" + setterSuffix(f);
                    return declaredSetters.contains(expectedSetter);
                })
                .toList();
    }

    private List<FieldMetadata> constrainedFields(ClassMetadata m) {
        return nonStaticFields(m).stream()
                .filter(FieldMetadata::hasConstraints)
                .toList();
    }

    private List<FieldMetadata> boundedFields(ClassMetadata m) {
        return nonStaticFields(m).stream()
                .filter(f -> f.constraints().containsKey("Min")
                        || f.constraints().containsKey("Max")
                        || f.constraints().containsKey("Size"))
                .toList();
    }

    /**
     * Converts a field name to the JavaBeans setter suffix.
     * Handles underscore-separated names by converting to camelCase:
     *   fieldName   → FieldName   → setFieldName
     *   class_Name  → ClassName   → setClassName
     *   my_field_id → MyFieldId   → setMyFieldId
     */
    /**
     * Produces the JavaBeans setter suffix for a field.
     *
     * Special case — boolean fields starting with 'is':
     *   private boolean isHoldRequired  →  setter: setHoldRequired  (strip 'is')
     *   private boolean holdRequired    →  setter: setHoldRequired  (normal cap)
     *
     * This follows Lombok / standard IDEs which strip the 'is' prefix for boolean fields.
     */
    private static String setterSuffix(FieldMetadata f) {
        String name = f.name();
        String type = f.type().replaceAll("<.*>", "").trim();
        boolean isBool = "boolean".equals(type) || "Boolean".equals(type);

        if (isBool && name.startsWith("is") && name.length() > 2
                && Character.isUpperCase(name.charAt(2))) {
            // isHoldRequired → HoldRequired → setHoldRequired
            return name.substring(2);
        }
        return cap(name);
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        if (!s.contains("_")) {
            // Simple case — just uppercase the first letter
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        // Underscore-separated: normalize each segment to lowercase then capitalise first letter
        // CLASS_NAME → Class + Name → ClassName; ftr_amt → Ftr + Amt → FtrAmt
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("_")) {
            if (part.isEmpty()) continue;
            String lower = part.toLowerCase();
            sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return sb.toString();
    }
}
