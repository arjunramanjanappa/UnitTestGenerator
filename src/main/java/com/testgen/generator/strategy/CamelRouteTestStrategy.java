package com.testgen.generator.strategy;

import com.testgen.camel.CamelRouteMetadata;
import com.testgen.generator.GeneratedTest;
import com.testgen.generator.NamingConvention;
import com.testgen.parser.ClassMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CamelRouteTestStrategy extends AbstractTestStrategy {

    private static final Pattern ROUTE_ID_PATTERN =
            Pattern.compile("routeId\\(\"([^\"]+)\"\\)");
    private static final Pattern FROM_URI_PATTERN =
            Pattern.compile("from\\(\"([^\"]+)\"\\)");
    private static final Pattern TO_URI_PATTERN =
            Pattern.compile("\\.to\\(\"([^\"]+)\"\\)");

    private final List<CamelRouteMetadata> xmlRoutes;

    public CamelRouteTestStrategy(List<CamelRouteMetadata> xmlRoutes) {
        this.xmlRoutes = xmlRoutes;
    }

    public CamelRouteTestStrategy() {
        this.xmlRoutes = List.of();
    }

    @Override
    public List<GeneratedTest> generate(ClassMetadata m, NamingConvention conv) {
        this.convention = conv;
        List<CamelRouteMetadata> routes = extractJavaDslRoutes(m);
        routes.addAll(xmlRoutes.stream()
                .filter(r -> r.sourceFile().contains(m.className()))
                .toList());

        return List.of(new GeneratedTest(
                m.className() + "Test.java", m.packageName(),
                buildFile(m, routes), GeneratedTest.GeneratedTestType.TEST_CLASS));
    }

    private List<CamelRouteMetadata> extractJavaDslRoutes(ClassMetadata m) {
        List<CamelRouteMetadata> routes = new ArrayList<>();
        try {
            String src = Files.readString(Path.of(m.sourceFilePath()));
            Matcher routeIdMatcher = ROUTE_ID_PATTERN.matcher(src);
            Matcher fromMatcher    = FROM_URI_PATTERN.matcher(src);
            Matcher toMatcher      = TO_URI_PATTERN.matcher(src);

            List<String> routeIds = new ArrayList<>();
            while (routeIdMatcher.find()) routeIds.add(routeIdMatcher.group(1));

            List<String> fromUris = new ArrayList<>();
            while (fromMatcher.find()) fromUris.add(fromMatcher.group(1));

            List<String> toUris = new ArrayList<>();
            while (toMatcher.find()) toUris.add(toMatcher.group(1));

            for (int i = 0; i < Math.max(1, Math.max(routeIds.size(), fromUris.size())); i++) {
                routes.add(new CamelRouteMetadata(
                        i < routeIds.size() ? routeIds.get(i) : null,
                        i < fromUris.size() ? fromUris.get(i) : "direct:start",
                        toUris,
                        m.sourceFilePath(),
                        CamelRouteMetadata.RouteSourceType.JAVA_DSL
                ));
            }
        } catch (IOException e) {
            routes.add(new CamelRouteMetadata(null, "direct:start", List.of(),
                    m.sourceFilePath(), CamelRouteMetadata.RouteSourceType.JAVA_DSL));
        }
        return routes;
    }

    private String buildFile(ClassMetadata m, List<CamelRouteMetadata> routes) {
        String cls = m.className();
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(m.packageName()).append(";\n\n");
        sb.append(commonImports());
        sb.append("import org.apache.camel.CamelContext;\n");
        sb.append("import org.apache.camel.ProducerTemplate;\n");
        sb.append("import org.apache.camel.builder.AdviceWith;\n");
        sb.append("import org.apache.camel.builder.RouteBuilder;\n");
        sb.append("import org.apache.camel.component.mock.MockEndpoint;\n");
        sb.append("import org.apache.camel.test.junit5.CamelTestSupport;\n");
        sb.append("import org.apache.camel.test.spring.junit5.CamelSpringBootTest;\n\n");

        sb.append("class ").append(cls).append("Test {\n\n");
        sb.append(buildUnitNested(m, cls, routes));
        sb.append(buildFunctionalNested(m, cls, routes));
        sb.append(buildCamelWireNested(m, cls));
        sb.append("}\n");
        return sb.toString();
    }

    private String buildUnitNested(ClassMetadata m, String cls, List<CamelRouteMetadata> routes) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("class Unit extends CamelTestSupport {\n\n");

        sb.append(buildMockDeclarations(m.mockCandidates(), 2));

        sb.append(i(2)).append("@Override\n");
        sb.append(i(2)).append("protected RouteBuilder createRouteBuilder() {\n");
        sb.append(i(3)).append("return new ").append(cls).append("();\n");
        sb.append(i(2)).append("}\n\n");

        for (CamelRouteMetadata route : routes) {
            String routeIdArg = route.hasRouteId()
                    ? "\"" + route.routeId() + "\""
                    : "context.getRouteDefinitions().get(0).getId()";
            String fromUri = route.fromUri().isBlank() ? "direct:start" : route.fromUri();
            String toUri = route.toUris().isEmpty() ? "mock:end" : "mock:" + sanitize(route.toUris().get(0));

            sb.append(i(2)).append("@Test\n");
            sb.append(i(2)).append("void ").append(convention.unitTestMethod(
                    route.hasRouteId() ? route.routeId().replace("-", "_") : "route", "success"))
              .append("() throws Exception {\n");
            sb.append(i(3)).append("AdviceWith.adviceWith(context, ").append(routeIdArg).append(", a -> {\n");
            sb.append(i(4)).append("a.replaceFromWith(\"direct:start\");\n");
            sb.append(i(4)).append("a.mockEndpoints(\"*\");\n");
            sb.append(i(3)).append("});\n\n");
            sb.append(i(3)).append("MockEndpoint mockEnd = getMockEndpoint(\"").append(toUri).append("\");\n");
            sb.append(i(3)).append("mockEnd.expectedMessageCount(1);\n\n");
            sb.append(i(3)).append("template.sendBody(\"direct:start\", \"test body\");\n\n");
            sb.append(i(3)).append("MockEndpoint.assertIsSatisfied(context);\n");
            sb.append(i(2)).append("}\n\n");
        }

        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }

    private String buildFunctionalNested(ClassMetadata m, String cls, List<CamelRouteMetadata> routes) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@CamelSpringBootTest\n");
        sb.append(i(1)).append("@SpringBootTest\n");
        sb.append(i(1)).append("@ActiveProfiles(\"test\")\n");
        sb.append(i(1)).append("class Functional {\n\n");

        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private ProducerTemplate producerTemplate;\n\n");
        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private CamelContext camelContext;\n\n");
        sb.append(buildMockBeanDeclarations(m.mockCandidates(), 2));

        for (CamelRouteMetadata route : routes) {
            String fromUri = route.fromUri().isBlank() ? "direct:start" : route.fromUri();
            sb.append(i(2)).append("@Test\n");
            sb.append(i(2)).append("void ").append(convention.unitTestMethod(
                    route.hasRouteId() ? route.routeId().replace("-", "_") : "route", "functional"))
              .append("() throws Exception {\n");
            sb.append(i(3)).append("// Given: configure mock beans / stubs\n");
            sb.append(i(3)).append("// When:\n");
            sb.append(i(3)).append("Object result = producerTemplate.requestBody(\"")
              .append(fromUri).append("\", \"test body\");\n");
            sb.append(i(3)).append("// Then:\n");
            sb.append(i(3)).append("// TODO: assert result\n");
            sb.append(i(2)).append("}\n\n");
        }

        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }

    private String buildCamelWireNested(ClassMetadata m, String cls) {
        StringBuilder sb = new StringBuilder();
        sb.append(i(1)).append("@Nested\n");
        sb.append(i(1)).append("@CamelSpringBootTest\n");
        sb.append(i(1)).append("@SpringBootTest\n");
        sb.append(i(1)).append("@ActiveProfiles(\"test\")\n");
        sb.append(i(1)).append("class Wire {\n\n");
        sb.append(i(2)).append("@Autowired\n");
        sb.append(i(2)).append("private CamelContext camelContext;\n\n");
        sb.append(i(2)).append("@Test\n");
        sb.append(i(2)).append("void contextLoads() {\n");
        sb.append(i(3)).append("assertNotNull(camelContext);\n");
        sb.append(i(2)).append("}\n\n");
        sb.append(i(2)).append("@Test\n");
        sb.append(i(2)).append("void routeRegistered() {\n");
        sb.append(i(3)).append("assertFalse(camelContext.getRoutes().isEmpty());\n");
        sb.append(i(2)).append("}\n");
        sb.append(i(1)).append("}\n\n");
        return sb.toString();
    }

    private String sanitize(String uri) {
        return uri.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
