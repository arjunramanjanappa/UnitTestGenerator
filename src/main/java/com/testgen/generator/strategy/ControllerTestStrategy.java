package com.testgen.generator.strategy;

import com.testgen.generator.GeneratedTest;
import com.testgen.generator.NamingConvention;
import com.testgen.parser.ClassMetadata;
import com.testgen.parser.MethodMetadata;

import java.util.List;

public class ControllerTestStrategy extends AbstractTestStrategy {

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
        sb.append("import org.springframework.test.web.servlet.MockMvc;\n");
        sb.append("import org.springframework.test.web.servlet.setup.MockMvcBuilders;\n");
        sb.append("import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;\n");
        sb.append("import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;\n");
        sb.append("import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;\n");
        sb.append("import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;\n\n");

        sb.append("class ").append(cls).append("Test {\n\n");
        sb.append(buildUnitNested(m));
        sb.append(buildFunctionalNested(m));
        sb.append(buildWireNested(m,
                "@Autowired\n" + i(2) + "private MockMvc mockMvc;\n\n"
                + i(2) + "// subject: " + cls + " wired via full Spring context", 1));
        sb.append("}\n");
        return sb.toString();
    }

    private String buildUnitNested(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@ExtendWith(MockitoExtension.class)\n");
        sb.append(i(1)).append("class Unit {\n\n");

        sb.append(i(2)).append("private MockMvc mockMvc;\n\n");
        sb.append(buildMockDeclarations(m.mockCandidates(), 2));

        if (m.hasSuperClass()) {
            sb.append(i(2)).append("@Spy\n");
            sb.append(i(2)).append("private ").append(m.superClassName()).append(" parent;\n\n");
        }

        sb.append(i(2)).append("@InjectMocks\n");
        sb.append(i(2)).append("private ").append(cls).append(" subject;\n\n");

        sb.append(i(2)).append("@BeforeEach\n");
        sb.append(i(2)).append("void setUp() {\n");
        sb.append(i(3)).append("mockMvc = MockMvcBuilders.standaloneSetup(subject).build();\n");
        for (var f : m.valueFields()) {
            sb.append(i(3)).append("ReflectionTestUtils.setField(subject, \"")
              .append(f.name()).append("\", \"testValue\");\n");
        }
        if (m.hasApplicationContext()) {
            sb.append(buildAppCtxStubs(m, 3));
        }
        if (m.hasSuperClass()) {
            sb.append(buildSuperClassStubs(m, 3));
        }
        sb.append(i(2)).append("}\n\n");

        for (MethodMetadata mm : m.ownPublicMethods()) {
            sb.append(i(2)).append("@Test\n");
            sb.append(i(2)).append("void ").append(convention.unitTestMethod(mm.name(), "success")).append("() throws Exception {\n");
            sb.append(i(3)).append("// TODO: mockMvc.perform(get(\"/your-path\"))\n");
            sb.append(i(3)).append("//       .andExpect(status().isOk());\n");
            sb.append(i(2)).append("}\n\n");
        }

        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }

    private String buildFunctionalNested(ClassMetadata m) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@WebMvcTest(").append(cls).append(".class)\n");
        sb.append(i(1)).append("class Functional {\n\n");

        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private MockMvc mockMvc;\n\n");
        sb.append(buildMockBeanDeclarations(m.mockCandidates(), 2));
        sb.append(buildBeforeEach(m, "mockMvc", true, 2));

        for (MethodMetadata mm : m.ownPublicMethods()) {
            if (!mm.isProtected()) {
                sb.append(i(2)).append("@Test\n");
                sb.append(i(2)).append("void ").append(convention.unitTestMethod(mm.name(), "functional")).append("() throws Exception {\n");
                sb.append(i(3)).append("// TODO: mockMvc.perform(get(\"/path\")).andExpect(status().isOk());\n");
                sb.append(i(2)).append("}\n\n");
            }
        }
        sb.append(buildFunctionalAopTestMethods(m, 2));
        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }
}
