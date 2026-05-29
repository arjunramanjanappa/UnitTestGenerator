# Testing Guide — UnitTestGenerator

This guide covers how to test the generator itself end-to-end, what to verify for each class type, and known edge cases to validate.

---

## 1. Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK | 21+ |
| Maven | 3.8+ |
| A target Spring Boot project | Any (with `src/main/java`) |

---

## 2. Build the Generator

```bash
cd UnitTestGenerator
mvn clean package -DskipTests
```

Expected output:
```
BUILD SUCCESS
target/unit-test-generator-1.0.0.jar
```

---

## 3. Launch the UI

```bash
mvn javafx:run
```

The JavaFX window should open with:
- Source / Target path inputs
- Include / Exclude filter fields
- Naming convention dropdown (3 options)
- Inheritance depth spinner
- Overwrite + Dry-run checkboxes
- Scan, Generate, Export Report buttons
- Class tree (left) + Preview panel (right)
- Progress bar + Live log + Report tabs at the bottom

---

## 4. Smoke Test — Dry Run Against Any Project

Use the generator on its own source code as a quick smoke test.

**Steps:**
1. Set **Source** → `<repo>/src/main/java`
2. Set **Target** → any temp folder e.g. `C:\temp\test-output`
3. Check **Dry-run**
4. Click **Scan**
5. Verify class tree populates with `com.testgen.*` packages
6. Click a class (e.g. `JavaClassParser`) → preview panel should show generated test content
7. Click **Generate Tests**
8. Verify Report tab shows `Generated > 0`, `Failed = 0`
9. No files written to disk (dry-run)

---

## 5. Full Generation Test

**Steps:**
1. Uncheck **Dry-run**
2. Check **Overwrite**
3. Click **Generate Tests**
4. Navigate to the target folder and verify:
   - One `*Test.java` per source class
   - One `*TestData.java` per source class
   - Package folder structure matches source

---

## 6. Class-Type Verification

Test each class type by pointing the generator at a project containing them.

### 6.1 `@Service`

**Source class:**
```java
@Service
public class OrderService {
    @Autowired private OrderRepository repo;
    public Order getOrder(Long id) throws OrderNotFoundException { ... }
    protected void validate(Order order) { ... }
}
```

**Expected in `OrderServiceTest.java`:**

| Check | Expected |
|-------|----------|
| Outer class | `class OrderServiceTest` |
| Unit nested | `@Nested @ExtendWith(MockitoExtension.class) class Unit` |
| Mock | `@Mock OrderRepository repo;` |
| InjectMocks | `@InjectMocks OrderService subject;` |
| Success test | `void test_getOrder_success()` with `Long id = 1L;` |
| Exception test | `void test_getOrder_throwsOrderNotFound()` with `assertThrows(OrderNotFoundException.class, ...)` |
| Protected test | `ReflectionTestUtils.invokeMethod(subject, "validate", order)` |
| Functional nested | `@SpringBootTest(classes = {OrderService.class})` + `@MockBean OrderRepository` |
| Wire nested | `@SpringBootTest @ActiveProfiles("test")` |
| TestData file | `OrderServiceTestData.java` with `buildOrderService()` method |

---

### 6.2 `@RestController`

**Source class:**
```java
@RestController
@RequestMapping("/orders")
public class OrderController {
    @Autowired private OrderService orderService;
    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable Long id) { ... }
}
```

**Expected in `OrderControllerTest.java`:**

| Check | Expected |
|-------|----------|
| Unit nested | `MockMvc` setup via `MockMvcBuilders.standaloneSetup(subject)` |
| Functional nested | `@WebMvcTest(OrderController.class)` + `@Autowired MockMvc` |
| Wire nested | `@SpringBootTest @AutoConfigureMockMvc` |
| Test method | `mockMvc.perform(...)` comment stub |

---

### 6.3 `@Repository`

**Expected in `OrderRepositoryTest.java`:**

| Check | Expected |
|-------|----------|
| Functional nested | `@DataJpaTest` + `@Autowired TestEntityManager` |
| Test method stub | Comment to persist via `entityManager` then invoke subject |

---

### 6.4 Camel `RouteBuilder` — Java DSL

**Source class:**
```java
public class OrderRoute extends RouteBuilder {
    @Override
    public void configure() {
        from("direct:order-input")
            .routeId("order-processing-route")
            .to("bean:orderProcessor")
            .to("direct:order-output");
    }
}
```

**Expected in `OrderRouteTest.java`:**

| Check | Expected |
|-------|----------|
| Unit nested | `class Unit extends CamelTestSupport` |
| Route ID | `AdviceWith.adviceWith(context, "order-processing-route", ...)` |
| From URI | `a.replaceFromWith("direct:start")` |
| MockEndpoint | `getMockEndpoint("mock:...")` |
| Functional nested | `@CamelSpringBootTest @SpringBootTest` + `ProducerTemplate` |
| Wire nested | `assertFalse(camelContext.getRoutes().isEmpty())` |

---

### 6.5 Camel XML Route

Place this in `src/main/resources/camel-routes.xml` of the target project:
```xml
<routes>
  <route id="payment-route">
    <from uri="direct:payment-in"/>
    <to uri="bean:paymentProcessor"/>
    <to uri="direct:payment-out"/>
  </route>
</routes>
```

**Expected:** Generator picks up `payment-route` ID, `direct:payment-in` as from URI, generates `AdviceWith` targeting `"payment-route"`.

---

### 6.6 Inheritance (BAU Pattern)

**Source classes:**
```java
public abstract class BaseService {
    @Autowired protected ApplicationContext applicationContext;
    public String fetchConfig(String key) { ... }
    protected void audit(String action) { ... }
}

public class PaymentService extends BaseService {
    @Autowired private PaymentRepository repo;
    @Override
    public String fetchConfig(String key) { ... }   // overridden
    public void processPayment(Payment p) throws PaymentException { ... }
}
```

**Expected in `PaymentServiceTest.java`:**

| Check | Expected |
|-------|----------|
| Parent spy | `@Spy BaseService parent;` |
| Overridden section | `// --- Overridden methods (parent BaseService stubbed ...) ---` |
| Parent stub comment | `// doReturn(...).when(subject).fetchConfig(...); // overridden` |
| Skip comment | `// Inherited non-overridden methods → covered by BaseServiceTest` |
| Protected audit test | `ReflectionTestUtils.invokeMethod(subject, "audit", action)` |
| AppCtx mock (Unit) | `@Mock ApplicationContext applicationContext;` |
| AppCtx stubs | `when(applicationContext.getBean(any(Class.class))).thenReturn(mock(Object.class));` |
| AppCtx mock (Functional) | `@MockBean ApplicationContext applicationContext;` |

**Expected in `BaseServiceTest.java`:**

| Check | Expected |
|-------|----------|
| All methods tested | `fetchConfig`, `audit` both have test stubs |
| No parent spy | No `@Spy` (no parent) |

---

### 6.7 Abstract Class

**Source class:**
```java
public abstract class BaseValidator<T> {
    public abstract boolean validate(T input);
    protected String buildErrorMessage(String field) { ... }
}
```

**Expected in `BaseValidatorTest.java`:**

| Check | Expected |
|-------|----------|
| Mock strategy | `subject = mock(BaseValidator.class, CALLS_REAL_METHODS);` |
| Protected test | `ReflectionTestUtils.invokeMethod(subject, "buildErrorMessage", field)` |

---

## 7. Filter Testing

### Include Filter
1. Set **Include** → `service`
2. Scan and Generate
3. Verify only classes in packages/names containing `service` are processed

### Exclude Filter
1. Set **Exclude** → `Config,Constants`
2. Scan and Generate
3. Verify `*Config.java` and `*Constants.java` classes are skipped

---

## 8. Naming Convention Testing

Run generation three times, each with a different naming convention, and verify method names:

| Convention | `processPayment` success test | `processPayment` exception test |
|---|---|---|
| `test_method_scenario` | `test_processPayment_success` | `test_processPayment_throwsPayment` |
| `methodName_shouldDoX` | `processPayment_shouldSuccess` | `processPayment_shouldThrowPayment` |
| `given_when_then` | `given_success_when_processPayment_then_success` | `given_invalid_when_processPayment_then_throwsPayment` |

---

## 9. TestData Builder Verification

**With Lombok `@Builder`:**
```java
// Expected in PaymentTestData.java
public static Payment buildPayment() {
    return Payment.builder()
        .amount(BigDecimal.ONE)
        .status("testValue")
        .build();
}
```

**Without Lombok:**
```java
public static Payment buildPayment() {
    Payment obj = new Payment();
    // obj.setAmount(BigDecimal.ONE);
    return obj;
}
```

---

## 10. Overwrite Behaviour

| Scenario | Overwrite ON | Overwrite OFF |
|----------|-------------|---------------|
| File doesn't exist | Written | Written |
| File exists | Overwritten | Skipped (counted in Skipped) |

**To test:**
1. Generate once (files created)
2. Generate again with **Overwrite OFF**
3. Report should show `Generated = 0`, `Skipped = <total>`

---

## 11. Report Verification

After generation, **Report tab** should show:
```
=== Unit Test Generator — Report ===
Generated at : 2026-05-30 ...
Source       : .../src/main/java
Target       : .../src/test/java

Classes Scanned  : N
Tests Generated  : N * 2  (Test + TestData per class)
Skipped          : 0
Failed           : 0
```

**Export Report:**
1. Click **Export Report**
2. Save as `report.txt`
3. Open and verify it matches Report tab content

---

## 12. Known Edge Cases

| Scenario | Expected Behaviour |
|----------|--------------------|
| Interface `.java` file | Skipped silently — no test generated |
| `package-info.java` | Skipped by scanner |
| Class with no public/protected methods | Empty test bodies — file still created |
| Class with only static methods | Stubs generated but flagged `// static` |
| Nested/inner classes | Outer class only parsed — inner classes skipped |
| Generic class `Repo<T>` | TestData uses `null` for type param |
| Source dir does not exist | Alert shown, generation aborted |
| Malformed `.java` file | Logged as failed in report, generation continues |
| Camel route with no `routeId` | Falls back to `context.getRouteDefinitions().get(0).getId()` |

---

## 13. Regression Checklist (run after each new version)

- [ ] Smoke dry-run completes with zero failures
- [ ] `@Service` generates Unit + Functional + Wire + TestData
- [ ] `@RestController` generates MockMvc stubs in Unit + `@WebMvcTest` in Functional
- [ ] `@Repository` generates `@DataJpaTest` in Functional
- [ ] Camel `RouteBuilder` generates `CamelTestSupport` + `AdviceWith`
- [ ] Camel XML routes detected and route ID extracted
- [ ] Inheritance: parent spied, overridden tested, inherited skipped
- [ ] Protected methods use `ReflectionTestUtils`
- [ ] Abstract class uses `CALLS_REAL_METHODS`
- [ ] `ApplicationContext` always `@Mock` / `@MockBean` with stubs
- [ ] `@Value` fields set via `ReflectionTestUtils.setField()`
- [ ] Lombok `@Builder` class generates `.builder()` chain in TestData
- [ ] All 3 naming conventions produce correct method names
- [ ] Include / Exclude filters applied correctly
- [ ] Overwrite OFF skips existing files
- [ ] Dry-run produces zero written files
- [ ] Report counts are accurate
- [ ] Export report writes a readable `.txt` file
