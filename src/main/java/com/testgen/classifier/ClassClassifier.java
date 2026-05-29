package com.testgen.classifier;

import com.testgen.parser.ClassMetadata;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ClassClassifier {

    public ClassMetadata classify(ClassMetadata metadata) {
        return metadata.withClassType(determine(metadata));
    }

    private ClassType determine(ClassMetadata m) {
        List<String> annotations = m.annotations();
        String superClass = m.superClassName();

        if (isRouteBuilder(superClass, m.imports())) return ClassType.CAMEL_ROUTE;
        if (annotations.contains("RestController"))  return ClassType.REST_CONTROLLER;
        if (annotations.contains("Controller"))      return ClassType.CONTROLLER;
        if (annotations.contains("Service"))         return ClassType.SERVICE;
        if (annotations.contains("Repository"))      return ClassType.REPOSITORY;
        if (annotations.contains("Configuration"))   return ClassType.CONFIGURATION;
        if (annotations.contains("Component"))       return ClassType.COMPONENT;
        if (m.isAbstract())                          return ClassType.ABSTRACT;

        return ClassType.POJO;
    }

    private boolean isRouteBuilder(String superClass, List<String> imports) {
        if (superClass == null) return false;
        return superClass.equals("RouteBuilder")
                || superClass.equals("SpringRouteBuilder")
                || (imports.stream().anyMatch(i -> i.contains("camel"))
                        && superClass.endsWith("RouteBuilder"));
    }
}
