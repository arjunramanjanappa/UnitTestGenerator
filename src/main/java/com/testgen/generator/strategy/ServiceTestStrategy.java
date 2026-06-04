package com.testgen.generator.strategy;

import com.testgen.generator.GeneratedTest;
import com.testgen.generator.NamingConvention;
import com.testgen.parser.ClassMetadata;

import java.util.List;

public class ServiceTestStrategy extends AbstractTestStrategy {

    @Override
    public List<GeneratedTest> generate(ClassMetadata m, NamingConvention conv) {
        this.convention = conv;
        String content = buildFile(m);
        return List.of(new GeneratedTest(
                m.className() + "Test.java", m.packageName(),
                content, GeneratedTest.GeneratedTestType.TEST_CLASS));
    }

    private String buildFile(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(m.packageName()).append(";\n\n");
        sb.append(commonImports(m.springBootVersion()));
        sb.append(buildDependencyImports(m));
        sb.append("\n");
        sb.append("class ").append(cls).append("Test {\n\n");
        // Generate testable subclass when protected methods from a different-package
        // parent would otherwise cause compile errors in the test
        if (needsTestablSubclass(m)) {
            sb.append(buildTestablSubclass(m, 1));
        }
        sb.append(buildUnitNested(m));
        sb.append("}\n");
        return sb.toString();
    }

    private String buildUnitNested(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@ExtendWith(MockitoExtension.class)\n");
        sb.append(i(1)).append("class Unit {\n\n");

        sb.append(buildMockDeclarations(m, 2));
        sb.append(buildSubjectDeclaration(m, 2));

        sb.append(buildBeforeEach(m, "subject", false, 2));
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

        sb.append(buildMockBeanDeclarations(m.mockCandidates(), 2, m.springBootVersion()));

        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");

        sb.append(buildBeforeEach(m, "subject", true, 2));

        sb.append(i(2)).append("@Test\n");
        sb.append(i(2)).append("void contextLoads() {\n");
        sb.append(i(3)).append("assertNotNull(subject);\n");
        sb.append(i(2)).append("}\n\n");

        for (var mm : m.ownPublicMethods()) {
            if (!mm.isProtected()) {
                sb.append(i(2)).append("@Test\n");
                sb.append(i(2)).append("void ")
                  .append(convention.unitTestMethod(mm.name(), "functional"))
                  .append("()").append(checkedThrowsClause(mm)).append(" {\n");
                sb.append(i(3)).append("// Spring slice test — mocked deps via @MockBean\n");
                sb.append(i(3)).append("// TODO: implement\n");
                sb.append(i(2)).append("}\n\n");
            }
        }
        sb.append(buildFunctionalAopTestMethods(m, 2));
        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }
}
