# UnitTestGenerator

Auto-generates **JUnit 5 + Mockito + Apache Camel** test cases for Java classes using the **UFW (Unit / Functional / Wire)** model.  
Supports Spring Boot projects with inheritance-heavy BAU class designs.

> **Testing Guide** → [TESTING.md](TESTING.md) — step-by-step verification for every class type, edge cases, and regression checklist.

---

## Version History

| Version | Date | Summary |
|---------|------|---------|
| [v1.1](#v11) | 2026-05-30 | Replaced JavaFX UI with Spring Boot web UI — run with `mvn spring-boot:run` |
| [v1.0](#v10) | 2026-05-30 | Initial release — full UFW generation, Camel, inheritance, JavaFX UI |

---

## Quick Start

```bash
git clone https://github.com/arjunramanjanappa/UnitTestGenerator.git
cd UnitTestGenerator
mvn spring-boot:run
```

Open **http://localhost:8080** in your browser.

---

## v1.1

### What Changed
JavaFX removed entirely. The tool now runs as a standard Spring Boot web application.

| Area | v1.0 | v1.1 |
|------|------|------|
| Launch command | `mvn javafx:run` | `mvn spring-boot:run` |
| UI runtime | JavaFX desktop window | Browser at `http://localhost:8080` |
| Dependencies | JavaFX 21 + OS classifiers | `spring-boot-starter-web` + Thymeleaf |
| OS setup | Platform classifier required | None — works on any OS with JDK 21 |

### API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/` | Serves the UI |
| `POST` | `/api/scan` | Scan source folder, return class list |
| `POST` | `/api/generate` | Generate tests — streams real-time progress via SSE |
| `POST` | `/api/preview` | Preview generated test for a single class |
| `GET`  | `/api/report` | Fetch last generation report as JSON |
| `GET`  | `/api/report/download` | Download report as `.txt` |

---

## v1.0

### Features

#### Core Generation
| Feature | Detail |
|---------|--------|
| **UFW Model** | Each class gets a single `ClassNameTest.java` with `@Nested Unit`, `@Nested Functional`, `@Nested Wire` inner classes |
| **Class Detection** | `@Service`, `@RestController`, `@Controller`, `@Repository`, `@Component`, `@Configuration`, `extends RouteBuilder`, Abstract, POJO |
| **Method Stubs** | One `@Test` per public/protected method with typed default values (`"testValue"`, `1`, `1L`, `true`, `List.of()`, etc.) |
| **Exception Tests** | Auto-generated `assertThrows` test for every declared `throws` on a method |
| **Parameterized Tests** | `@ParameterizedTest` + `@CsvSource` stub for methods with primitive or `String` params |
| **Naming Conventions** | 3 selectable styles: `test_method_scenario`, `methodName_shouldDoX`, `given_when_then` |
| **TestData Builder** | Separate `ClassNameTestData.java` per class — Lombok `.builder()` chain if `@Builder`/`@Data` detected, else setter pattern |
| **Overwrite Mode** | Existing test files are overwritten by default; toggle off to skip |
| **Dry-Run Mode** | Computes and previews all files that would be generated without writing to disk |

#### Inheritance / BAU Design Pattern
| Feature | Detail |
|---------|--------|
| **Parent class stubbing** | Direct parent stubbed via `Mockito.spy()` + `doReturn()` on overridden methods |
| **Inherited method skipping** | Non-overridden inherited methods excluded — covered by parent's own test |
| **Protected methods** | Accessed via `ReflectionTestUtils.invokeMethod()` — BAU source classes never modified |
| **Abstract classes** | Tested via `Mockito.mock(AbstractClass.class, CALLS_REAL_METHODS)` |
| **`@PostConstruct` in parent** | Detected and flagged in `@BeforeEach` with a verification comment |
| **`@Value` fields** | Set via `ReflectionTestUtils.setField()` in `setUp()` — no source modification |
| **Configurable depth** | UI spinner controls how many levels of parent chain to consider |

#### ApplicationContext
| Feature | Detail |
|---------|--------|
| **Always mocked** | `ApplicationContext` is `@Mock` (Unit) / `@MockBean` (Functional/Wire) — never loads real context |
| **Pre-stubbed calls** | `getBean(Class)`, `getBean(String, Class)`, `containsBean(String)` stubs auto-generated in `setUp()` |

#### Apache Camel
| Feature | Detail |
|---------|--------|
| **Java DSL routes** | Regex extracts `routeId("...")`, `from("...")`, `.to("...")` from source file |
| **XML routes** | DOM-parses `src/main/resources/**/*.xml` for `<route id=...>`, `<from uri=...>`, `<to uri=...>` |
| **Unit tests** | `extends CamelTestSupport`, `AdviceWith.adviceWith()` with extracted route ID, `MockEndpoint` assertions |
| **Functional tests** | `@CamelSpringBootTest` + `ProducerTemplate` |
| **Wire tests** | Full context, asserts `camelContext.getRoutes()` is not empty |

#### Web UI (v1.1)
| Feature | Detail |
|---------|--------|
| **Folder browser** | Source and target path inputs with auto-fill |
| **Include / Exclude filters** | Comma-separated package or class name patterns |
| **Naming convention selector** | Dropdown to pick test method naming style |
| **Inheritance depth spinner** | `0` = direct parent only, `N` = up to N levels |
| **Dry-run checkbox** | Preview without writing |
| **Overwrite checkbox** | Control whether existing tests are replaced |
| **Scan** | Walks source tree, populates class tree grouped by package |
| **Class preview panel** | Click any class to preview its generated test in a dark code panel |
| **SSE progress bar** | Real-time file-by-file progress streamed from server |
| **Live log tab** | Timestamped log entries streamed during generation |
| **Report tab** | Post-run summary: scanned / generated / skipped / failed counts |
| **Export Report** | Download report as `.txt` file |

---

### Tech Stack

| Concern | Library / Version |
|---------|-------------------|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| Source parsing | JavaParser 3.25.9 |
| UI | Thymeleaf + Bootstrap 5 (browser) |
| Generated test runtime | JUnit 5, Mockito, Apache Camel Test |
| Build | Maven |

---

### Prerequisites

- JDK 21+
- Maven 3.8+

---

### Project Structure

```
src/main/java/com/testgen/
├── ui/
│   ├── TestGeneratorUIApp.java      ← Spring Boot main + @EnableAsync
│   ├── MainController.java          ← REST API (/api/scan, /api/generate, ...)
│   └── PreviewController.java       ← Serves GET / → index.html
├── scanner/FileScanner.java         ← Walks src/main/java with include/exclude
├── parser/
│   ├── ClassMetadata.java           ← Parsed class model (record)
│   ├── FieldMetadata.java           ← Field model — injection, @Value, AppCtx detection
│   ├── MethodMetadata.java          ← Method model — override, abstract, throws
│   └── JavaClassParser.java         ← JavaParser AST → ClassMetadata
├── classifier/
│   ├── ClassType.java               ← Enum: SERVICE, CONTROLLER, CAMEL_ROUTE, ...
│   └── ClassClassifier.java         ← Annotation-based class type detection
├── camel/
│   ├── CamelRouteMetadata.java      ← Route ID, from/to URIs, source type
│   └── CamelXmlRouteParser.java     ← DOM parser for Camel XML routes
├── generator/
│   ├── GeneratedTest.java           ← Output record (fileName, packageName, content)
│   ├── NamingConvention.java        ← 3 test naming styles (enum)
│   ├── TestOrchestrator.java        ← Main pipeline: scan→parse→classify→generate→write
│   ├── strategy/
│   │   ├── TestStrategy.java        ← Interface
│   │   ├── AbstractTestStrategy.java← Shared code-gen helpers (mocks, stubs, methods)
│   │   ├── ServiceTestStrategy.java
│   │   ├── ControllerTestStrategy.java
│   │   ├── RepositoryTestStrategy.java
│   │   ├── CamelRouteTestStrategy.java
│   │   ├── ComponentTestStrategy.java
│   │   └── DefaultTestStrategy.java ← POJO, Config, Abstract
│   └── builder/DataBuilderGenerator.java ← *TestData.java (Lombok-aware)
├── writer/TestFileWriter.java       ← Writes to src/test/java, overwrite-aware
└── report/GenerationReport.java     ← Summary text + file export

src/main/resources/
├── templates/index.html             ← Bootstrap 5 SPA (scan, generate, preview, report)
└── application.properties
```

---

### How to Use (Step-by-Step)

1. **Start** the app: `mvn spring-boot:run`
2. **Open** `http://localhost:8080`
3. **Enter Source Path** — target project's `src/main/java`
4. **Enter Target Path** — auto-filled as `src/test/java`
5. **Set Filters** _(optional)_ — include/exclude by package or class name
6. **Pick Naming Convention** — from dropdown
7. **Set Inheritance Depth** — `1` stubs only direct parent (recommended)
8. **Toggle options** — Overwrite / Dry-run
9. **Click Scan** — class tree populates grouped by package
10. **Click any class** — preview panel shows the generated test
11. **Click Generate Tests** — progress bar + live log update in real time
12. **Review Report tab** — counts of generated / skipped / failed
13. **Export Report** — saves summary as `.txt`

---

### Generated File Structure (example)

Given `com.example.service.OrderService`:

```
src/test/java/com/example/service/
├── OrderServiceTest.java       ← @Nested Unit / Functional / Wire
└── OrderServiceTestData.java   ← Test data builder
```

### Inheritance Example

```java
// OrderService extends BaseService
class OrderServiceTest {
    @Nested class Unit {
        @Spy BaseService parent;         // direct parent spied
        @InjectMocks OrderService subject;

        // Overridden methods tested here
        // Inherited-non-overridden → skipped (covered by BaseServiceTest)
    }
}
```

### Camel Route Example

```java
class OrderRouteTest {
    @Nested class Unit extends CamelTestSupport {
        @Test void test_order_route_success() throws Exception {
            AdviceWith.adviceWith(context, "order-route", a -> {
                a.replaceFromWith("direct:start");
                a.mockEndpoints("*");
            });
            MockEndpoint mock = getMockEndpoint("mock:...");
            mock.expectedMessageCount(1);
            template.sendBody("direct:start", "test body");
            MockEndpoint.assertIsSatisfied(context);
        }
    }
}
```

---

### Roadmap

| Version | Planned |
|---------|---------|
| v1.2 | _(next increment — TBD)_ |
