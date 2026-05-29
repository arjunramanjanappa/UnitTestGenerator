package com.testgen.generator.strategy;

import com.testgen.generator.GeneratedTest;
import com.testgen.generator.NamingConvention;
import com.testgen.parser.ClassMetadata;

import java.util.List;

/**
 * Handles POJO, CONFIGURATION, ABSTRACT, and any unrecognised class types.
 */
public class DefaultTestStrategy extends AbstractTestStrategy {

    @Override
    public List<GeneratedTest> generate(ClassMetadata m, NamingConvention conv) {
        this.convention = conv;
        return List.of(new GeneratedTest(
                m.className() + "Test.java", m.packageName(),
                buildFile(m), GeneratedTest.GeneratedTestType.TEST_CLASS));
    }

    private String buildFile(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(m.packageName()).append(";\n\n");
        sb.append(commonImports());
        sb.append("\n");
        sb.append("class ").append(cls).append("Test {\n\n");

        // Unit nested
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@ExtendWith(MockitoExtension.class)\n");
        sb.append(i(1)).append("class Unit {\n\n");

        if (m.isAbstract()) {
            sb.append(i(2)).append("// Abstract class — cannot instantiate directly.\n");
            sb.append(i(2)).append("// Using CALLS_REAL_METHODS to test concrete methods.\n");
            sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");
            sb.append(i(2)).append("@BeforeEach\n");
            sb.append(i(2)).append("void setUp() {\n");
            sb.append(i(3)).append("subject = mock(").append(cls).append(".class, CALLS_REAL_METHODS);\n");
            sb.append(i(2)).append("}\n\n");
        } else {
            sb.append(buildMockDeclarations(m.mockCandidates(), 2));
            if (m.hasSuperClass()) {
                sb.append(i(2)).append("@Spy\n");
                sb.append(i(2)).append("private ").append(m.superClassName()).append(" parent;\n\n");
            }
            sb.append(i(2)).append("@InjectMocks\n");
            sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");
            sb.append(buildBeforeEach(m, "subject", false, 2));
        }

        sb.append(buildTestMethods(m, "subject", 2));

        if (m.hasSuperClass()) {
            sb.append(i(2)).append("// Inherited non-overridden methods covered by ")
              .append(m.superClassName()).append("Test\n\n");
        }

        sb.append(i(1)).append("}\n\n");

        // Functional nested — lightweight Spring context
        sb.append(i(1)).append("@Nested\n");
        if (!m.isAbstract()) {
            sb.append(i(1)).append("@SpringBootTest(classes = {").append(cls).append(".class})\n");
        } else {
            sb.append(i(1)).append("@SpringBootTest\n");
        }
        sb.append(i(1)).append("class Functional {\n\n");
        sb.append(buildMockBeanDeclarations(m.mockCandidates(), 2));
        sb.append(i(2)).append("@Test\n");
        sb.append(i(2)).append("void contextLoads() {\n");
        sb.append(i(3)).append("// TODO: assert context loaded correctly\n");
        sb.append(i(2)).append("}\n");
        sb.append(i(1)).append("}\n\n");

        // Wire nested
        sb.append(buildWireNested(m, "// Wire — verify full Spring context integration", 1));

        sb.append("}\n");
        return sb.toString();
    }
}
