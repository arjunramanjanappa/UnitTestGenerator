# UnitTestGenerator

Auto-generates **JUnit 5 + Mockito + Apache Camel** test cases for Java classes using the **UFW (Unit / Functional / Wire)** model.  
Supports Spring Boot projects with inheritance-heavy BAU class designs.

> **Testing Guide** → [TESTING.md](TESTING.md) — step-by-step verification for every class type, edge cases, and regression checklist.

---

## Version History

| Version | Date | Summary |
|---------|------|---------|
| [v1.0](#v10) | 2026-05-30 | Initial release — full UFW generation, Camel, inheritance, JavaFX UI |

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
| **Inherited method skipping** | Non-overridden inherited methods excluded — covered by parent's own `ParentTest.java` |
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

#### UI (JavaFX)
| Feature | Detail |
|---------|--------|
| **Folder browser** | Browse buttons for source (`src/main/java`) and target (`src/test/java`) paths |
| **Auto-fill target** | Target path auto-derived from source when typed |
| **Include / Exclude filters** | Comma-separated package or class name patterns |
| **Naming convention selector** | Dropdown to pick test method naming style |
| **Inheritance depth spinner** | 0 = direct parent only, N = up to N levels |
| **Dry-run checkbox** | Preview without writing |
| **Overwrite checkbox** | Control whether existing tests are replaced |
| **Scan** | Walks source tree, populates class tree view with package hierarchy |
| **Class preview panel** | Click any class in the tree to preview the test that would be generated |
| **Progress bar + live log** | Real-time file-by-file progress during generation |
| **Report tab** | Post-run summary: scanned / generated / skipped / failed counts |
| **Export report** | Save report as `.txt` file |

#### Project Awareness
| Feature | Detail |
|---------|--------|
| **Spring Boot version detection** | Reads target project's `pom.xml` to detect Spring Boot version |
| **Lombok detection** | Detects `@Builder`, `@Data`, `@SuperBuilder` to switch `TestData` generation strategy |

---

### Tech Stack

| Concern | Library / Version |
|---------|-------------------|
| Language | Java 21 |
| Framework | Spring Boot 3.3.0 |
| Source parsing | JavaParser 3.25.9 |
| UI | JavaFX 21.0.2 |
| Generated test runtime | JUnit 5, Mockito, Apache Camel Test |
| Build | Maven |

---

### Prerequisites

- JDK 21+
- Maven 3.8+
- JavaFX 21 (bundled via Maven dependency — no separate install needed)

---

### How to Build

```bash
git clone https://github.com/arjunramanjanappa/UnitTestGenerator.git
cd UnitTestGenerator
mvn clean package -DskipTests
```

---

### How to Run

#### Option A — JavaFX UI (recommended)
```bash
mvn javafx:run
```

#### Option B — Run the packaged jar
```bash
java --module-path /path/to/javafx-sdk/lib \
     --add-modules javafx.controls,javafx.fxml \
     -jar target/unit-test-generator-1.0.0.jar
```

---

### How to Use (Step-by-Step)

1. **Launch** the application via `mvn javafx:run`
2. **Enter Source Path** — point to the target project's `src/main/java`  
   _(e.g. `C:\myproject\src\main\java`)_
3. **Enter Target Path** — auto-filled as `src/test/java`; adjust if needed
4. **Set Filters** _(optional)_
   - Include: `com.example.service` to only generate for that package
   - Exclude: `Config,Constants,Dto` to skip those classes
5. **Pick Naming Convention** — choose from dropdown
6. **Set Inheritance Depth** — `1` means stub only the direct parent (recommended default)
7. **Toggle options**
   - ☑ Overwrite — replaces existing test files
   - ☐ Dry-run — preview only, nothing written to disk
8. **Click Scan** — tree view populates with all discovered classes
9. **Click any class** in the tree to preview its generated test in the right panel
10. **Click Generate Tests** — progress bar and live log show real-time progress
11. **Review the Report tab** — see counts of generated / skipped / failed
12. **Click Export Report** to save the summary as a `.txt` file

---

### Generated File Structure (example)

Given `com.example.service.OrderService`:

```
src/test/java/
└── com/example/service/
    ├── OrderServiceTest.java       ← UFW @Nested Unit / Functional / Wire
    └── OrderServiceTestData.java   ← Test data builder
```

#### Sample `OrderServiceTest.java` structure

```java
class OrderServiceTest {

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Unit {
        @Mock OrderRepository orderRepository;
        @InjectMocks OrderService subject;

        @BeforeEach void setUp() { ... }

        @Test void test_createOrder_success() { ... }
        @Test void test_createOrder_throwsOrderException() { ... }
        @ParameterizedTest @CsvSource(...) void test_createOrder_parameterized(...) { ... }
    }

    @Nested
    @SpringBootTest(classes = {OrderService.class})
    class Functional {
        @MockBean OrderRepository orderRepository;
        @Autowired OrderService subject;
        @Test void test_createOrder_functional() { ... }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class Wire {
        @Autowired OrderService subject;
        @Test void contextLoads() { ... }
    }
}
```

---

### Inheritance Example

Given `OrderService extends BaseService`:

```java
class OrderServiceTest {
    @Nested
    class Unit {
        @Spy BaseService parent;           // parent spied — not re-tested here
        @InjectMocks OrderService subject;

        @BeforeEach void setUp() {
            // Inherited methods covered by BaseServiceTest
            // doReturn(...).when(subject).overriddenMethod(...); // stub overrides
        }

        // Only tests OrderService's own + overridden methods
        // Inherited-non-overridden → skipped (BaseServiceTest covers them)
    }
}
```

---

### Camel Route Example

Given `OrderRoute extends RouteBuilder` with `routeId("order-route")`:

```java
class OrderRouteTest {
    @Nested
    class Unit extends CamelTestSupport {
        @Test void test_order_route_success() throws Exception {
            AdviceWith.adviceWith(context, "order-route", a -> {
                a.replaceFromWith("direct:start");
                a.mockEndpoints("*");
            });
            MockEndpoint mockEnd = getMockEndpoint("mock:...");
            mockEnd.expectedMessageCount(1);
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
| v1.1 | _(next increment — TBD)_ |
