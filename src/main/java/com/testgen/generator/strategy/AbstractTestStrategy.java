package com.testgen.generator.strategy;

import com.testgen.generator.NamingConvention;
import com.testgen.parser.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Shared code-generation utilities for all strategies.
 * Each concrete strategy calls these helpers to build test file content.
 */
public abstract class AbstractTestStrategy implements TestStrategy {

    protected NamingConvention convention = NamingConvention.TEST_METHOD_SCENARIO;

    // ── Indentation helper ──────────────────────────────────────────────────

    protected String i(int n) {
        return "    ".repeat(n);
    }

    // ── Default value literals ──────────────────────────────────────────────

    protected String defaultValue(String rawType) {
        String type = rawType.replaceAll("<.*>", "").trim();
        return switch (type) {
            case "String"        -> "\"testValue\"";
            case "int",
                 "Integer"       -> "1";
            case "long",
                 "Long"          -> "1L";
            case "double",
                 "Double"        -> "1.0";
            case "float",
                 "Float"         -> "1.0f";
            case "boolean",
                 "Boolean"       -> "true";
            case "byte",
                 "Byte"          -> "(byte) 1";
            case "short",
                 "Short"         -> "(short) 1";
            case "char",
                 "Character"     -> "'a'";
            case "BigDecimal"    -> "BigDecimal.ONE";
            case "BigInteger"    -> "BigInteger.ONE";
            case "LocalDate"     -> "LocalDate.now()";
            case "LocalDateTime" -> "LocalDateTime.now()";
            case "UUID"          -> "UUID.randomUUID()";
            case "void"          -> "";
            case "List"          -> "List.of()";
            case "Map"           -> "Map.of()";
            case "Set"           -> "Set.of()";
            case "Optional"      -> "Optional.empty()";
            default              -> "null /* TODO: provide " + rawType + " */";
        };
    }

    // ── Common import blocks ────────────────────────────────────────────────

    protected String commonImports() {
        return """
                import org.junit.jupiter.api.*;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.CsvSource;
                import org.junit.jupiter.params.provider.EnumSource;
                import org.mockito.*;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.boot.test.mock.mockito.MockBean;
                import org.springframework.context.ApplicationContext;
                import org.springframework.test.context.ActiveProfiles;
                import org.springframework.test.util.ReflectionTestUtils;
                import java.math.BigDecimal;
                import java.math.BigInteger;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import java.util.*;
                import static org.junit.jupiter.api.Assertions.*;
                import static org.mockito.Mockito.*;
                import static org.mockito.ArgumentMatchers.*;
                """;
    }

    // ── Mock / MockBean declarations ────────────────────────────────────────

    protected String buildMockDeclarations(List<FieldMetadata> fields, int indent) {
        StringBuilder sb = new StringBuilder();
        for (FieldMetadata f : fields) {
            if (f.isApplicationContext()) {
                sb.append(i(indent)).append("@Mock\n");
                sb.append(i(indent)).append("ApplicationContext ").append(f.name()).append(";\n\n");
            } else if (f.isMockCandidate()) {
                sb.append(i(indent)).append("@Mock\n");
                sb.append(i(indent)).append(f.type()).append(" ").append(f.name()).append(";\n\n");
            }
        }
        return sb.toString();
    }

    protected String buildMockBeanDeclarations(List<FieldMetadata> fields, int indent) {
        StringBuilder sb = new StringBuilder();
        for (FieldMetadata f : fields) {
            if (f.isApplicationContext()) {
                sb.append(i(indent)).append("@MockBean\n");
                sb.append(i(indent)).append("ApplicationContext ").append(f.name()).append(";\n\n");
            } else if (f.isMockCandidate()) {
                sb.append(i(indent)).append("@MockBean\n");
                sb.append(i(indent)).append(f.type()).append(" ").append(f.name()).append(";\n\n");
            }
        }
        return sb.toString();
    }

    // ── ApplicationContext stubs ────────────────────────────────────────────

    protected String buildAppCtxStubs(ClassMetadata m, int indent) {
        if (!m.hasApplicationContext()) return "";
        String field = m.fields().stream()
                .filter(FieldMetadata::isApplicationContext)
                .map(FieldMetadata::name)
                .findFirst().orElse("applicationContext");
        return i(indent) + "when(" + field + ".getBean(any(Class.class))).thenReturn(mock(Object.class));\n"
             + i(indent) + "when(" + field + ".getBean(anyString(), any(Class.class))).thenReturn(mock(Object.class));\n"
             + i(indent) + "when(" + field + ".containsBean(anyString())).thenReturn(true);\n";
    }

    // ── Parent-class (BAU inheritance) stubs ────────────────────────────────

    /**
     * Stubs overridden parent methods using Mockito spy + doReturn.
     * Only the overridden subset is stubbed; non-overridden inherited methods
     * are intentionally skipped — they are covered by the parent's own test.
     *
     * For overridden methods that contain super.xxx() calls, an additional stub
     * is emitted on `parent` so the spy intercepts the delegation instead of
     * executing the real ClassB implementation.
     */
    protected String buildSuperClassStubs(ClassMetadata m, int indent) {
        if (!m.hasSuperClass()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("// Parent ").append(m.superClassName())
          .append(" — non-overridden inherited methods covered by ").append(m.superClassName()).append("Test\n");

        for (MethodMetadata mm : m.overriddenMethods()) {
            String matcherParams = mm.parameters().stream()
                    .map(p -> "any(" + p.type() + ".class)")
                    .collect(Collectors.joining(", "));

            // Stub on subject (ClassA) to control what the overriding method returns
            if (mm.hasReturnValue()) {
                sb.append(i(indent))
                  .append("// doReturn(").append(defaultValue(mm.returnType()))
                  .append(").when(subject).").append(mm.name()).append("(").append(matcherParams)
                  .append("); // stub overridden method on ClassA\n");
            } else {
                sb.append(i(indent))
                  .append("// doNothing().when(subject).").append(mm.name()).append("(").append(matcherParams)
                  .append("); // stub void overridden method on ClassA\n");
            }

            // If the method body calls super.xxx(), also stub the parent spy so the
            // real ClassB implementation is never invoked during the test
            if (mm.hasSuperCalls()) {
                for (String superCall : mm.superMethodCalls()) {
                    if (mm.name().equals(superCall)) {
                        // super.sameMethod() — stub on parent directly
                        if (mm.hasReturnValue()) {
                            sb.append(i(indent))
                              .append("doReturn(").append(defaultValue(mm.returnType()))
                              .append(").when(parent).").append(superCall).append("(").append(matcherParams)
                              .append("); // intercept super.").append(superCall).append("() — prevents real ").append(m.superClassName()).append(" execution\n");
                        } else {
                            sb.append(i(indent))
                              .append("doNothing().when(parent).").append(superCall).append("(").append(matcherParams)
                              .append("); // intercept super.").append(superCall).append("() — prevents real ").append(m.superClassName()).append(" execution\n");
                        }
                    } else {
                        // super.differentMethod() — stub on parent, return type unknown so use lenient stub
                        sb.append(i(indent))
                          .append("// TODO: stub super.").append(superCall).append("() on parent — ")
                          .append("determine return type and add: doReturn(...).when(parent).").append(superCall).append("(...);\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    // ── @BeforeEach ─────────────────────────────────────────────────────────

    protected String buildBeforeEach(ClassMetadata m, String subject, boolean usesMockBeans, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("@BeforeEach\n");
        sb.append(i(indent)).append("void setUp() {\n");

        // @Value fields — must be set via ReflectionTestUtils (BAU classes not modified)
        for (FieldMetadata f : m.valueFields()) {
            sb.append(i(indent + 1)).append("ReflectionTestUtils.setField(").append(subject)
              .append(", \"").append(f.name()).append("\", \"testValue\");\n");
        }

        if (m.hasApplicationContext()) {
            sb.append(buildAppCtxStubs(m, indent + 1));
        }

        if (!usesMockBeans && m.hasSuperClass()) {
            sb.append(buildSuperClassStubs(m, indent + 1));
        }

        boolean hasPostConstruct = m.methods().stream()
                .anyMatch(mm -> mm.annotations().contains("PostConstruct"));
        if (hasPostConstruct) {
            sb.append(i(indent + 1))
              .append("// @PostConstruct runs on Spring init — verify any side effects below\n");
        }

        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // ── Wire @Nested (shared across all strategies) ─────────────────────────

    protected String buildWireNested(ClassMetadata m, String subjectDecl, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("@Nested\n");
        sb.append(i(indent)).append("@SpringBootTest\n");
        sb.append(i(indent)).append("@ActiveProfiles(\"test\")\n");
        sb.append(i(indent)).append("class Wire {\n\n");
        sb.append(i(indent + 1)).append(subjectDecl).append("\n\n");
        sb.append(i(indent + 1)).append("@Test\n");
        sb.append(i(indent + 1)).append("void contextLoads() {\n");
        sb.append(i(indent + 2)).append("assertNotNull(subject);\n");
        sb.append(i(indent + 1)).append("}\n\n");
        sb.append(i(indent + 1)).append("@Test\n");
        sb.append(i(indent + 1)).append("void ").append(convention.unitTestMethod("beanWiring", "wire")).append("() {\n");
        sb.append(i(indent + 2)).append("assertNotNull(subject);\n");
        sb.append(i(indent + 2)).append("// TODO: verify full Spring context integration\n");
        sb.append(i(indent + 1)).append("}\n");
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    // ── Test method generation ──────────────────────────────────────────────

    protected String buildTestMethods(ClassMetadata m, String subject, int indent) {
        StringBuilder sb = new StringBuilder();

        List<MethodMetadata> overridden = m.overriddenMethods();
        if (!overridden.isEmpty()) {
            sb.append(i(indent))
              .append("// --- Overridden methods (parent ").append(m.superClassName())
              .append(" stubbed, child behavior tested) ---\n\n");
            for (MethodMetadata mm : overridden) {
                sb.append(buildSingleTestMethod(mm, subject, m, indent));
            }
        }

        List<MethodMetadata> own = m.ownNonOverriddenMethods();
        if (!own.isEmpty()) {
            if (m.hasSuperClass()) {
                sb.append(i(indent)).append("// --- Own methods ---\n\n");
            }
            for (MethodMetadata mm : own) {
                sb.append(buildSingleTestMethod(mm, subject, m, indent));
            }
        }

        if (m.hasSuperClass()) {
            sb.append(i(indent)).append("// Inherited non-overridden methods → covered by ")
              .append(m.superClassName()).append("Test\n\n");
        }
        return sb.toString();
    }

    protected String buildSingleTestMethod(MethodMetadata mm, String subject,
                                           ClassMetadata m, int indent) {
        StringBuilder sb = new StringBuilder();

        // Success test
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ")
          .append(convention.unitTestMethod(mm.name(), "success")).append("() {\n");
        sb.append(i(indent + 1)).append("// given\n");
        buildParamSetup(mm, sb, indent + 1);

        if (mm.isProtected()) {
            sb.append(i(indent + 1)).append("// when — protected access via ReflectionTestUtils (BAU class not modified)\n");
            buildReflectionCall(mm, subject, sb, indent + 1);
        } else {
            sb.append(i(indent + 1)).append("// when\n");
            buildDirectCall(mm, subject, sb, indent + 1);
        }

        sb.append(i(indent + 1)).append("// then\n");
        if (mm.hasReturnValue()) {
            sb.append(i(indent + 1)).append("assertNotNull(result);\n");
            sb.append(i(indent + 1)).append("// TODO: add specific assertions\n");
        } else {
            sb.append(i(indent + 1)).append("// TODO: verify interactions — e.g. verify(mockDep).someMethod(any());\n");
        }
        sb.append(i(indent)).append("}\n\n");

        // Exception tests
        for (String ex : mm.thrownExceptions()) {
            sb.append(buildExceptionTestMethod(mm, subject, ex, indent));
        }

        // @ParameterizedTest for primitive / String params
        boolean hasSimpleParam = mm.parameters().stream()
                .anyMatch(p -> isPrimitive(p.type()) || "String".equals(p.type()));
        if (hasSimpleParam && mm.isPublic() && !mm.isProtected()) {
            sb.append(buildParameterizedTestMethod(mm, subject, indent));
        }

        return sb.toString();
    }

    protected String buildExceptionTestMethod(MethodMetadata mm, String subject,
                                              String exType, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(indent)).append("@Test\n");
        sb.append(i(indent)).append("void ")
          .append(convention.exceptionTestMethod(mm.name(), exType)).append("() {\n");
        sb.append(i(indent + 1)).append("// given\n");
        buildParamSetup(mm, sb, indent + 1);
        sb.append(i(indent + 1))
          .append("// TODO: configure mock to throw → doThrow(").append(exType)
          .append(".class).when(mockDep).someMethod(any());\n");
        sb.append(i(indent + 1)).append("// when / then\n");

        String params = paramNames(mm);
        if (mm.isProtected()) {
            String sep = params.isEmpty() ? "" : ", ";
            sb.append(i(indent + 1)).append("assertThrows(").append(exType).append(".class, () ->\n");
            sb.append(i(indent + 2)).append("ReflectionTestUtils.invokeMethod(")
              .append(subject).append(", \"").append(mm.name()).append("\"")
              .append(sep).append(params).append("));\n");
        } else {
            sb.append(i(indent + 1)).append("assertThrows(").append(exType).append(".class, () ->\n");
            sb.append(i(indent + 2)).append(subject).append(".")
              .append(mm.name()).append("(").append(params).append("));\n");
        }
        sb.append(i(indent)).append("}\n\n");
        return sb.toString();
    }

    protected String buildParameterizedTestMethod(MethodMetadata mm, String subject, int indent) {
        String testName = convention.unitTestMethod(mm.name(), "parameterized");
        return i(indent) + "@ParameterizedTest\n"
             + i(indent) + "@CsvSource({\"value1\", \"value2\"}) // TODO: provide representative values\n"
             + i(indent) + "void " + testName + "(String param) {\n"
             + i(indent + 1) + "// TODO: cast param to required type, invoke " + subject + "." + mm.name() + "(...)\n"
             + i(indent) + "}\n\n";
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void buildParamSetup(MethodMetadata mm, StringBuilder sb, int indent) {
        for (MethodMetadata.ParameterMetadata p : mm.parameters()) {
            sb.append(i(indent)).append(p.type()).append(" ").append(p.name())
              .append(" = ").append(defaultValue(p.type())).append(";\n");
        }
    }

    private void buildDirectCall(MethodMetadata mm, String subject, StringBuilder sb, int indent) {
        String params = paramNames(mm);
        if (mm.hasReturnValue()) {
            sb.append(i(indent)).append(mm.returnType()).append(" result = ")
              .append(subject).append(".").append(mm.name()).append("(").append(params).append(");\n");
        } else {
            sb.append(i(indent)).append(subject).append(".").append(mm.name())
              .append("(").append(params).append(");\n");
        }
    }

    private void buildReflectionCall(MethodMetadata mm, String subject, StringBuilder sb, int indent) {
        String params = paramNames(mm);
        String sep = params.isEmpty() ? "" : ", ";
        if (mm.hasReturnValue()) {
            sb.append(i(indent)).append(mm.returnType()).append(" result = ")
              .append("ReflectionTestUtils.invokeMethod(")
              .append(subject).append(", \"").append(mm.name()).append("\"")
              .append(sep).append(params).append(");\n");
        } else {
            sb.append(i(indent)).append("ReflectionTestUtils.invokeMethod(")
              .append(subject).append(", \"").append(mm.name()).append("\"")
              .append(sep).append(params).append(");\n");
        }
    }

    protected String paramNames(MethodMetadata mm) {
        return mm.parameters().stream()
                .map(MethodMetadata.ParameterMetadata::name)
                .collect(Collectors.joining(", "));
    }

    protected boolean isPrimitive(String type) {
        return Set.of("int", "Integer", "long", "Long", "double", "Double",
                "float", "Float", "boolean", "Boolean", "byte", "Byte",
                "short", "Short", "char", "Character").contains(type);
    }
}
