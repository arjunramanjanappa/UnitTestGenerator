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
        sb.append(commonImports(m.springBootVersion()));
        sb.append(buildDependencyImports(m));
        sb.append("\n");
        sb.append("class ").append(cls).append("Test {\n\n");
        if (needsTestablSubclass(m)) sb.append(buildTestablSubclass(m, 1));

        // Unit nested
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@ExtendWith(MockitoExtension.class)\n");
        sb.append(i(1)).append("class Unit {\n\n");

        if (m.isAbstract()) {
            sb.append(i(2)).append("// Abstract class — cannot instantiate directly.\n");
            sb.append(i(2)).append("// Using CALLS_REAL_METHODS to test concrete methods.\n");
            sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");
            sb.append(i(2)).append("@BeforeEach\n");
            sb.append(i(2)).append("void setUp() throws Exception {\n");
            sb.append(i(3)).append("subject = mock(").append(cls).append(".class, CALLS_REAL_METHODS);\n");
            sb.append(i(2)).append("}\n\n");
        } else {
            sb.append(buildMockDeclarations(m, 2));
            sb.append(buildSubjectDeclaration(m, 2));
            sb.append(buildBeforeEach(m, "subject", false, 2));
        }

        sb.append(buildTestMethods(m, "subject", 2));

        sb.append(i(1)).append("}\n\n");

        sb.append("}\n");
        return sb.toString();
    }
}
