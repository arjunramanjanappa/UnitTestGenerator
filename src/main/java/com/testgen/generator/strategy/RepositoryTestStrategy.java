package com.testgen.generator.strategy;

import com.testgen.generator.GeneratedTest;
import com.testgen.generator.NamingConvention;
import com.testgen.parser.ClassMetadata;

import java.util.List;

public class RepositoryTestStrategy extends AbstractTestStrategy {

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
        sb.append("import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;\n");
        sb.append("import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;\n\n");

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
        sb.append(i(2)).append("@InjectMocks\n");
        sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");
        sb.append(buildBeforeEach(m, "subject", false, 2));
        sb.append(buildTestMethods(m, "subject", 2));

        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }

    private String buildFunctionalNested(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@DataJpaTest\n");
        sb.append(i(1)).append("class Functional {\n\n");

        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private TestEntityManager entityManager;\n\n");
        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");

        sb.append(i(2)).append("@Test\n");
        sb.append(i(2)).append("void contextLoads() {\n");
        sb.append(i(3)).append("assertNotNull(subject);\n");
        sb.append(i(2)).append("}\n\n");

        for (var mm : m.ownPublicMethods()) {
            if (!mm.isProtected()) {
                sb.append(i(2)).append("@Test\n");
                sb.append(i(2)).append("void ")
                  .append(convention.unitTestMethod(mm.name(), "jpa")).append("() {\n");
                sb.append(i(3)).append("// TODO: persist test data via entityManager then invoke subject\n");
                sb.append(i(2)).append("}\n\n");
            }
        }
        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }
}
