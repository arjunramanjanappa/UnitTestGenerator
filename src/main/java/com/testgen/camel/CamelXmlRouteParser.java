package com.testgen.camel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@Component
public class CamelXmlRouteParser {

    public List<CamelRouteMetadata> parseXmlRoutes(Path resourcesRoot) {
        if (!Files.isDirectory(resourcesRoot)) return List.of();

        List<CamelRouteMetadata> routes = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(resourcesRoot)) {
            stream.filter(p -> p.toString().endsWith(".xml"))
                  .map(this::parseFile)
                  .forEach(routes::addAll);
        } catch (Exception e) {
            log.warn("Could not scan XML routes under {}: {}", resourcesRoot, e.getMessage());
        }
        return routes;
    }

    private List<CamelRouteMetadata> parseFile(Path xmlFile) {
        List<CamelRouteMetadata> routes = new ArrayList<>();
        try (InputStream is = Files.newInputStream(xmlFile)) {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(is);
            doc.getDocumentElement().normalize();

            NodeList routeNodes = doc.getElementsByTagName("route");
            for (int i = 0; i < routeNodes.getLength(); i++) {
                Node node = routeNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) continue;

                Element route = (Element) node;
                String routeId = route.getAttribute("id");

                String fromUri = "";
                NodeList fromNodes = route.getElementsByTagName("from");
                if (fromNodes.getLength() > 0) {
                    fromUri = ((Element) fromNodes.item(0)).getAttribute("uri");
                }

                List<String> toUris = new ArrayList<>();
                NodeList toNodes = route.getElementsByTagName("to");
                for (int j = 0; j < toNodes.getLength(); j++) {
                    toUris.add(((Element) toNodes.item(j)).getAttribute("uri"));
                }

                routes.add(new CamelRouteMetadata(
                        routeId.isBlank() ? null : routeId,
                        fromUri, toUris,
                        xmlFile.toString(),
                        CamelRouteMetadata.RouteSourceType.XML
                ));
            }
        } catch (Exception e) {
            log.warn("Failed to parse XML route file {}: {}", xmlFile, e.getMessage());
        }
        return routes;
    }
}
