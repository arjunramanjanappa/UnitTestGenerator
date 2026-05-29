package com.testgen.generator.builder;

import com.testgen.generator.GeneratedTest;
import com.testgen.parser.ClassMetadata;
import com.testgen.parser.FieldMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a *TestData.java file for each class.
 * Lombok-aware: uses .builder() chain if @Builder/@Data detected, else field setters.
 */
@Component
public class DataBuilderGenerator {

    private static final String I1 = "    ";
    private static final String I2 = "        ";
    private static final String I3 = "            ";

    public GeneratedTest generate(ClassMetadata m) {
        String content = buildFile(m);
        return new GeneratedTest(
                m.className() + "TestData.java",
                m.packageName(),
                content,
                GeneratedTest.GeneratedTestType.TEST_DATA
        );
    }

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
        sb.append(" */\n");
        sb.append("public class ").append(m.className()).append("TestData {\n\n");

        sb.append(buildDefaultFactory(m));
        sb.append(buildListFactory(m));
        sb.append(buildDependencyFactories(m));

        sb.append("}\n");
        return sb.toString();
    }

    private String buildDefaultFactory(ClassMetadata m) {
        StringBuilder sb = new StringBuilder();
        sb.append(I1).append("public static ").append(m.className()).append(" build").append(m.className()).append("() {\n");

        if (m.hasBuilder()) {
            // Lombok @Builder / @SuperBuilder
            sb.append(I2).append("return ").append(m.className()).append(".builder()\n");
            for (FieldMetadata f : nonStaticFields(m)) {
                sb.append(I3).append(".").append(f.name()).append("(").append(defaultValue(f.type())).append(")\n");
            }
            sb.append(I3).append(".build();\n");
        } else {
            // No builder — use setter pattern
            sb.append(I2).append(m.className()).append(" obj = new ").append(m.className()).append("();\n");
            for (FieldMetadata f : nonStaticFields(m)) {
                String setter = "set" + cap(f.name());
                sb.append(I2).append("// obj.").append(setter).append("(").append(defaultValue(f.type())).append(");\n");
            }
            sb.append(I2).append("return obj;\n");
        }
        sb.append(I1).append("}\n\n");
        return sb.toString();
    }

    private String buildListFactory(ClassMetadata m) {
        return I1 + "public static List<" + m.className() + "> build" + m.className() + "List() {\n"
             + I2 + "return List.of(build" + m.className() + "());\n"
             + I1 + "}\n\n";
    }

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

    private List<FieldMetadata> nonStaticFields(ClassMetadata m) {
        return m.fields().stream()
                .filter(f -> !f.annotations().contains("Autowired"))
                .filter(f -> !f.isApplicationContext())
                .filter(f -> !f.isValue())
                .toList();
    }

    private String defaultValue(String rawType) {
        String type = rawType.replaceAll("<.*>", "").trim();
        return switch (type) {
            case "String"        -> "\"testValue\"";
            case "int","Integer" -> "1";
            case "long","Long"   -> "1L";
            case "double","Double" -> "1.0";
            case "float","Float" -> "1.0f";
            case "boolean","Boolean" -> "true";
            case "BigDecimal"    -> "BigDecimal.ONE";
            case "LocalDate"     -> "LocalDate.now()";
            case "LocalDateTime" -> "LocalDateTime.now()";
            case "UUID"          -> "UUID.randomUUID()";
            case "List"          -> "List.of()";
            case "Map"           -> "Map.of()";
            case "Set"           -> "Set.of()";
            default              -> "null";
        };
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
