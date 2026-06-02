package com.testgen.generator.builder;

import com.testgen.generator.GeneratedTest;
import com.testgen.parser.ClassMetadata;
import com.testgen.parser.FieldMetadata;
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
        sb.append("import java.util.*;\n\n");

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
        sb.append(buildDependencyFactories(m));

        sb.append("}\n");
        return sb.toString();
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
            for (FieldMetadata f : nonStaticFields(m)) {
                String setter = "set" + cap(f.name());
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
            for (FieldMetadata f : nonStaticFields(m)) {
                sb.append(I2).append("obj.").append("set").append(cap(f.name()))
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
            for (FieldMetadata f : nonStaticFields(m)) {
                sb.append(I2).append("obj.").append("set").append(cap(f.name()))
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
            case "UUID"                  -> "UUID.randomUUID()";
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
