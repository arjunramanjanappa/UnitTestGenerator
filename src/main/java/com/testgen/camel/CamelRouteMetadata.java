package com.testgen.camel;

import java.util.List;

public record CamelRouteMetadata(
        String routeId,
        String fromUri,
        List<String> toUris,
        String sourceFile,
        RouteSourceType sourceType
) {
    public enum RouteSourceType { JAVA_DSL, XML }

    public boolean hasRouteId() {
        return routeId != null && !routeId.isBlank();
    }
}
