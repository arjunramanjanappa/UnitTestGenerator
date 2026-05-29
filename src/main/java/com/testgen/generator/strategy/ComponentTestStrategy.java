package com.testgen.generator.strategy;

import com.testgen.generator.GeneratedTest;
import com.testgen.generator.NamingConvention;
import com.testgen.parser.ClassMetadata;

import java.util.List;

public class ComponentTestStrategy extends AbstractTestStrategy {

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
        sb.append(buildUnitNested(m));
        sb.append(buildFunctionalNested(m));
        sb.append(buildWireNested(m,
                "@Autowired\n" + i(2) + "private " + cls + " subject;", 1));
        sb.append("}\n");
        return sb.toString();
    }

    private String buildUnitNested(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@ExtendWith(MockitoExtension.class)\n");
        sb.append(i(1)).append("class Unit {\n\n");

        sb.append(buildMockDeclarations(m.mockCandidates(), 2));

        if (m.hasSuperClass()) {
            sb.append(i(2)).append("@Spy\n");
            sb.append(i(2)).append("private ").append(m.superClassName()).append(" parent;\n\n");
        }

        if (m.isAbstract()) {
            sb.append(i(2)).append("// Abstract component — using CALLS_REAL_METHODS mock\n");
            sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");
            sb.append(i(2)).append("@BeforeEach\n");
            sb.append(i(2)).append("void setUp() {\n");
            sb.append(i(3)).append("subject = mock(").append(cls).append(".class, CALLS_REAL_METHODS);\n");
            for (var f : m.valueFields()) {
                sb.append(i(3)).append("ReflectionTestUtils.setField(subject, \"")
                  .append(f.name()).append("\", \"testValue\");\n");
            }
            sb.append(i(2)).append("}\n\n");
        } else {
            sb.append(i(2)).append("@InjectMocks\n");
            sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");
            sb.append(buildBeforeEach(m, "subject", false, 2));
        }

        sb.append(buildTestMethods(m, "subject", 2));
        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }

    private String buildFunctionalNested(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@SpringBootTest(classes = {").append(cls).append(".class})\n");
        sb.append(i(1)).append("class Functional {\n\n");

        sb.append(buildMockBeanDeclarations(m.mockCandidates(), 2));
        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");
        sb.append(buildBeforeEach(m, "subject", true, 2));

        sb.append(i(2)).append("@Test\n");
        sb.append(i(2)).append("void contextLoads() {\n");
        sb.append(i(3)).append("assertNotNull(subject);\n");
        sb.append(i(2)).append("}\n\n");
        sb.append(buildFunctionalAopTestMethods(m, 2));
        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }
}
