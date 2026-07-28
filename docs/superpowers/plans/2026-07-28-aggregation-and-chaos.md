# Aggregation Endpoint + Chaos Toggles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the two remaining 📋 items from `CHANGES.md`: a cross-service aggregation endpoint in `customers-service`, and Consul-KV-driven chaos toggles in `customers-service` and `visits-service` (including a real Redis cache-aside in `visits-service` for the `redis-timeout` toggle).

**Architecture:** Aggregation endpoint first (new `OwnerVisitsResource` in customers-service calls visits-service's existing `pets/visits` endpoint via a new `@LoadBalanced RestTemplate`), then chaos toggles (a per-service `chaos` package with an in-memory toggle holder + a `@Scheduled` Consul KV poller, wired into the read paths of both services).

**Tech Stack:** Spring Boot 4.0.1 / Spring Cloud 2025.1.0, `com.ecwid.consul:consul-api` (already on classpath via `spring-cloud-starter-consul-config`), `spring-boot-starter-data-redis` (Lettuce, new dependency in visits-service only), JUnit 5 / Mockito / AssertJ / MockMvc / `MockRestServiceServer`.

## Global Constraints

- Chaos toggle names, KV prefixes, service names, and the `data.redis.host`/`data.redis.port` KV keys are fixed by `lab-environment/CLAUDE.md`'s cross-repo contract table — do not rename or invent new ones. Toggle names in scope: `slow-query-enabled` + `downstream-error` (customers-service), `slow-query-enabled` + `redis-timeout` (visits-service).
- Chaos toggles read Consul KV under `chaos/<service>/` directly via `com.ecwid.consul.v1.ConsulClient` — never through `spring-cloud-starter-consul-config` / `@ConfigurationProperties` / `ContextRefresher`.
- Chaos toggles default OFF (fail-safe) — if Consul is unreachable, `ChaosToggles.isEnabled(...)` must return `false`.
- Per-service `chaos` package, duplicated in both services — no shared module (matches existing per-service duplication of `config/MetricConfig`).
- No changes to `docker-compose.yml`, `init-consul-kv.sh`, or `scenarios.yaml` in `lab-environment` — out of scope for this plan.
- Full spec: `docs/superpowers/specs/2026-07-28-aggregation-and-chaos-design.md`.

---

## Task 1: `ChaosToggles` in customers-service

**Files:**
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/chaos/ChaosToggles.java`
- Test: `spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/chaos/ChaosTogglesTest.java`

**Interfaces:**
- Produces: `ChaosToggles` — `public boolean isEnabled(String name)`, `public void set(String name, boolean enabled)`. Toggles default to `false` when unset. Later tasks in customers-service inject this as a constructor dependency.

- [ ] **Step 1: Write the failing test**

```java
package org.springframework.samples.petclinic.customers.chaos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosTogglesTest {

    @Test
    void isEnabledDefaultsToFalseForUnknownToggle() {
        ChaosToggles chaosToggles = new ChaosToggles();

        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isFalse();
    }

    @Test
    void isEnabledReflectsLastSetValue() {
        ChaosToggles chaosToggles = new ChaosToggles();

        chaosToggles.set("slow-query-enabled", true);
        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isTrue();

        chaosToggles.set("slow-query-enabled", false);
        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=ChaosTogglesTest`
Expected: FAIL (compilation error — `ChaosToggles` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package org.springframework.samples.petclinic.customers.chaos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ChaosToggles {

    private final Map<String, Boolean> toggles = new ConcurrentHashMap<>();

    public boolean isEnabled(String name) {
        return toggles.getOrDefault(name, false);
    }

    public void set(String name, boolean enabled) {
        toggles.put(name, enabled);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=ChaosTogglesTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/chaos/ChaosToggles.java spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/chaos/ChaosTogglesTest.java
git commit -m "Add in-memory ChaosToggles holder to customers-service"
```

---

## Task 2: `VisitsServiceClient` (customers-service calls visits-service)

**Files:**
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/VisitResponse.java`
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/DownstreamServiceException.java`
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/VisitsServiceClient.java`
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/config/RestTemplateConfig.java`
- Test: `spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/web/VisitsServiceClientTest.java`

**Interfaces:**
- Consumes: `ChaosToggles.isEnabled(String)` from Task 1.
- Produces: `VisitResponse(Integer id, Date date, String description, int petId)` — a record, reused later (Task 3) as the visit shape embedded in the aggregation response. `VisitsServiceClient.getVisitsForPets(List<Integer> petIds)` returns `List<VisitResponse>`, throws `DownstreamServiceException` on failure or when the `downstream-error` toggle is on. `DownstreamServiceException` is `@ResponseStatus(BAD_GATEWAY)`.

- [ ] **Step 1: Write the failing test**

```java
package org.springframework.samples.petclinic.customers.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.samples.petclinic.customers.chaos.ChaosToggles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VisitsServiceClientTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private MockRestServiceServer server;
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private VisitsServiceClient client;

    @BeforeEach
    void setUp() {
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new VisitsServiceClient(restTemplate, chaosToggles);
    }

    @Test
    void returnsEmptyListWithoutCallingVisitsServiceWhenNoPetIds() {
        List<VisitResponse> visits = client.getVisitsForPets(List.of());

        assertThat(visits).isEmpty();
        server.verify();
    }

    @Test
    void fetchesVisitsFromVisitsService() {
        server.expect(requestTo("http://visits-service/pets/visits?petId=111,222"))
            .andRespond(withSuccess(
                "{\"items\":[{\"id\":1,\"date\":\"2024-01-01\",\"description\":\"desc\",\"petId\":111}]}",
                MediaType.APPLICATION_JSON));

        List<VisitResponse> visits = client.getVisitsForPets(List.of(111, 222));

        assertThat(visits).hasSize(1);
        assertThat(visits.get(0).petId()).isEqualTo(111);
        server.verify();
    }

    @Test
    void wrapsDownstreamFailureInDownstreamServiceException() {
        server.expect(requestTo("http://visits-service/pets/visits?petId=111"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.getVisitsForPets(List.of(111)))
            .isInstanceOf(DownstreamServiceException.class);
        server.verify();
    }

    @Test
    void throwsImmediatelyWithoutCallingVisitsServiceWhenDownstreamErrorToggleEnabled() {
        chaosToggles.set("downstream-error", true);

        assertThatThrownBy(() -> client.getVisitsForPets(List.of(111)))
            .isInstanceOf(DownstreamServiceException.class);
        server.verify();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=VisitsServiceClientTest`
Expected: FAIL (compilation error — `VisitResponse`, `VisitsServiceClient`, `DownstreamServiceException` do not exist)

- [ ] **Step 3: Write minimal implementation**

`VisitResponse.java`:
```java
package org.springframework.samples.petclinic.customers.web;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.Date;

record VisitResponse(
    Integer id,

    @JsonFormat(pattern = "yyyy-MM-dd")
    Date date,

    String description,

    int petId
) {
}
```

`DownstreamServiceException.java`:
```java
package org.springframework.samples.petclinic.customers.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.BAD_GATEWAY)
public class DownstreamServiceException extends RuntimeException {

    public DownstreamServiceException(String message) {
        super(message);
    }

    public DownstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`VisitsServiceClient.java`:
```java
package org.springframework.samples.petclinic.customers.web;

import org.springframework.samples.petclinic.customers.chaos.ChaosToggles;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static java.util.stream.Collectors.joining;

@Component
class VisitsServiceClient {

    private static final String VISITS_SERVICE_URL = "http://visits-service/pets/visits?petId={petIds}";

    private final RestTemplate restTemplate;
    private final ChaosToggles chaosToggles;

    VisitsServiceClient(RestTemplate restTemplate, ChaosToggles chaosToggles) {
        this.restTemplate = restTemplate;
        this.chaosToggles = chaosToggles;
    }

    List<VisitResponse> getVisitsForPets(List<Integer> petIds) {
        if (petIds.isEmpty()) {
            return List.of();
        }

        if (chaosToggles.isEnabled("downstream-error")) {
            throw new DownstreamServiceException(
                "Simulated downstream error calling visits-service (chaos toggle 'downstream-error' enabled)");
        }

        try {
            VisitsWireResponse response = restTemplate.getForObject(
                VISITS_SERVICE_URL, VisitsWireResponse.class, joinIds(petIds));
            return response == null ? List.of() : response.items();
        } catch (RestClientException e) {
            throw new DownstreamServiceException("Failed to fetch visits from visits-service", e);
        }
    }

    private String joinIds(List<Integer> petIds) {
        return petIds.stream().map(String::valueOf).collect(joining(","));
    }

    private record VisitsWireResponse(List<VisitResponse> items) {
    }
}
```

`RestTemplateConfig.java`:
```java
package org.springframework.samples.petclinic.customers.config;

import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
            .connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5))
            .build();
    }
}
```

`RestTemplateBuilder` (`org.springframework.boot.restclient.RestTemplateBuilder`) and `@LoadBalanced` (`org.springframework.cloud.client.loadbalancer.LoadBalanced`) are already on the compile classpath transitively via `spring-cloud-starter-consul-config`/`-discovery` — no `pom.xml` change needed. Verify with `./mvnw -pl spring-petclinic-customers-service dependency:tree | grep -i "restclient\|loadbalancer"` if the build fails to resolve either type.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=VisitsServiceClientTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/VisitResponse.java spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/DownstreamServiceException.java spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/VisitsServiceClient.java spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/config/RestTemplateConfig.java spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/web/VisitsServiceClientTest.java
git commit -m "Add VisitsServiceClient for server-side calls to visits-service"
```

---

## Task 3: `OwnerVisitsResource` — `GET /owners/{ownerId}/visits`

**Files:**
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/PetVisitsResponse.java`
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerVisitsResponse.java`
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerVisitsResource.java`
- Test: `spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/web/OwnerVisitsResourceTest.java`

**Interfaces:**
- Consumes: `VisitsServiceClient.getVisitsForPets(List<Integer>)` → `List<VisitResponse>` (Task 2); `ResourceNotFoundException` (existing, `@ResponseStatus(NOT_FOUND)`); `OwnerRepository.findById(int)` (existing).
- Produces: `GET /owners/{ownerId}/visits` → `OwnerVisitsResponse`. Later tasks do not depend on this endpoint's internals.

- [ ] **Step 1: Write the failing test**

```java
package org.springframework.samples.petclinic.customers.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.samples.petclinic.customers.model.Owner;
import org.springframework.samples.petclinic.customers.model.OwnerRepository;
import org.springframework.samples.petclinic.customers.model.Pet;
import org.springframework.samples.petclinic.customers.model.PetType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerVisitsResource.class)
@ActiveProfiles("test")
class OwnerVisitsResourceTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OwnerRepository ownerRepository;

    @MockitoBean
    VisitsServiceClient visitsServiceClient;

    @Test
    void shouldReturnOwnerWithPetsAndVisits() throws Exception {
        Owner owner = new Owner();
        owner.setId(1);
        owner.setFirstName("George");
        owner.setLastName("Franklin");
        owner.setAddress("110 W. Liberty St.");
        owner.setCity("Madison");
        owner.setTelephone("6085551023");

        Pet pet = new Pet();
        pet.setId(2);
        pet.setName("Leo");
        PetType type = new PetType();
        type.setId(1);
        type.setName("cat");
        pet.setType(type);
        owner.addPet(pet);

        given(ownerRepository.findById(1)).willReturn(Optional.of(owner));
        given(visitsServiceClient.getVisitsForPets(List.of(2)))
            .willReturn(List.of(new VisitResponse(10, null, "checkup", 2)));

        mvc.perform(get("/owners/1/visits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.firstName").value("George"))
            .andExpect(jsonPath("$.pets[0].name").value("Leo"))
            .andExpect(jsonPath("$.pets[0].visits[0].description").value("checkup"));
    }

    @Test
    void shouldReturn404WhenOwnerNotFound() throws Exception {
        given(ownerRepository.findById(999)).willReturn(Optional.empty());

        mvc.perform(get("/owners/999/visits"))
            .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnEmptyPetsWithoutCallingVisitsServiceWhenOwnerHasNoPets() throws Exception {
        Owner owner = new Owner();
        owner.setId(3);
        owner.setFirstName("Jane");
        owner.setLastName("Doe");
        owner.setAddress("1 Main St.");
        owner.setCity("Springfield");
        owner.setTelephone("1234567890");

        given(ownerRepository.findById(3)).willReturn(Optional.of(owner));
        given(visitsServiceClient.getVisitsForPets(List.of())).willReturn(List.of());

        mvc.perform(get("/owners/3/visits"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pets").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=OwnerVisitsResourceTest`
Expected: FAIL (compilation error — `OwnerVisitsResource`, `PetVisitsResponse`, `OwnerVisitsResponse` do not exist)

- [ ] **Step 3: Write minimal implementation**

`PetVisitsResponse.java`:
```java
package org.springframework.samples.petclinic.customers.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.samples.petclinic.customers.model.PetType;

import java.util.Date;
import java.util.List;

record PetVisitsResponse(
    Integer id,

    String name,

    @DateTimeFormat(pattern = "yyyy-MM-dd")
    Date birthDate,

    PetType type,

    List<VisitResponse> visits
) {
}
```

`OwnerVisitsResponse.java`:
```java
package org.springframework.samples.petclinic.customers.web;

import java.util.List;

record OwnerVisitsResponse(
    Integer id,
    String firstName,
    String lastName,
    String address,
    String city,
    String telephone,
    List<PetVisitsResponse> pets
) {
}
```

`OwnerVisitsResource.java`:
```java
package org.springframework.samples.petclinic.customers.web;

import jakarta.validation.constraints.Min;
import org.springframework.samples.petclinic.customers.model.Owner;
import org.springframework.samples.petclinic.customers.model.OwnerRepository;
import org.springframework.samples.petclinic.customers.model.Pet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

@RequestMapping("/owners")
@RestController
class OwnerVisitsResource {

    private final OwnerRepository ownerRepository;
    private final VisitsServiceClient visitsServiceClient;

    OwnerVisitsResource(OwnerRepository ownerRepository, VisitsServiceClient visitsServiceClient) {
        this.ownerRepository = ownerRepository;
        this.visitsServiceClient = visitsServiceClient;
    }

    @GetMapping("/{ownerId}/visits")
    public OwnerVisitsResponse getOwnerVisits(@PathVariable("ownerId") @Min(1) int ownerId) {
        Owner owner = ownerRepository.findById(ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("Owner " + ownerId + " not found"));

        List<Pet> pets = owner.getPets();
        List<Integer> petIds = pets.stream().map(Pet::getId).toList();

        Map<Integer, List<VisitResponse>> visitsByPetId = visitsServiceClient.getVisitsForPets(petIds).stream()
            .collect(groupingBy(VisitResponse::petId));

        List<PetVisitsResponse> petResponses = pets.stream()
            .map(pet -> new PetVisitsResponse(
                pet.getId(), pet.getName(), pet.getBirthDate(), pet.getType(),
                visitsByPetId.getOrDefault(pet.getId(), List.of())))
            .toList();

        return new OwnerVisitsResponse(owner.getId(), owner.getFirstName(), owner.getLastName(),
            owner.getAddress(), owner.getCity(), owner.getTelephone(), petResponses);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=OwnerVisitsResourceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/PetVisitsResponse.java spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerVisitsResponse.java spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerVisitsResource.java spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/web/OwnerVisitsResourceTest.java
git commit -m "Add GET /owners/{ownerId}/visits aggregation endpoint"
```

---

## Task 4: Wire `slow-query-enabled` into `OwnerResource`

**Files:**
- Modify: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java`
- Create: `spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/web/OwnerResourceTest.java` (no test previously existed for this resource)

**Interfaces:**
- Consumes: `ChaosToggles.isEnabled(String)` from Task 1.
- Produces: nothing new consumed by later tasks.

- [ ] **Step 1: Write the failing test**

```java
package org.springframework.samples.petclinic.customers.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.samples.petclinic.customers.chaos.ChaosToggles;
import org.springframework.samples.petclinic.customers.model.Owner;
import org.springframework.samples.petclinic.customers.model.OwnerRepository;
import org.springframework.samples.petclinic.customers.web.mapper.OwnerEntityMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerResource.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "chaos.slow-query-delay-ms=1")
class OwnerResourceTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OwnerRepository ownerRepository;

    @MockitoBean
    OwnerEntityMapper ownerEntityMapper;

    @MockitoBean
    ChaosToggles chaosToggles;

    @Test
    void shouldFetchOwnerAndCheckSlowQueryToggle() throws Exception {
        given(ownerRepository.findById(1)).willReturn(Optional.of(new Owner()));

        mvc.perform(get("/owners/1"))
            .andExpect(status().isOk());

        verify(chaosToggles).isEnabled("slow-query-enabled");
    }

    @Test
    void shouldStillFetchOwnerWhenSlowQueryToggleEnabled() throws Exception {
        given(chaosToggles.isEnabled("slow-query-enabled")).willReturn(true);
        given(ownerRepository.findById(1)).willReturn(Optional.of(new Owner()));

        mvc.perform(get("/owners/1"))
            .andExpect(status().isOk());
    }

    @Test
    void shouldFetchAllOwnersAndCheckSlowQueryToggle() throws Exception {
        given(ownerRepository.findAll()).willReturn(List.of(new Owner()));

        mvc.perform(get("/owners"))
            .andExpect(status().isOk());

        verify(chaosToggles).isEnabled("slow-query-enabled");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=OwnerResourceTest`
Expected: FAIL — `OwnerResource`'s constructor doesn't accept `ChaosToggles`/the delay property yet, context fails to load

- [ ] **Step 3: Write minimal implementation**

Modify `OwnerResource.java` — add the `ChaosToggles` + delay dependency and a `simulateSlowQueryIfEnabled()` guard called at the top of `findOwner` and `findAll`:

```java
package org.springframework.samples.petclinic.customers.web;

import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.customers.chaos.ChaosToggles;
import org.springframework.samples.petclinic.customers.web.mapper.OwnerEntityMapper;
import org.springframework.samples.petclinic.customers.model.Owner;
import org.springframework.samples.petclinic.customers.model.OwnerRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RequestMapping("/owners")
@RestController
@Timed("petclinic.owner")
class OwnerResource {

    private static final Logger log = LoggerFactory.getLogger(OwnerResource.class);
    private static final String SLOW_QUERY_TOGGLE = "slow-query-enabled";

    private final OwnerRepository ownerRepository;
    private final OwnerEntityMapper ownerEntityMapper;
    private final ChaosToggles chaosToggles;
    private final long slowQueryDelayMs;

    OwnerResource(OwnerRepository ownerRepository, OwnerEntityMapper ownerEntityMapper, ChaosToggles chaosToggles,
                  @Value("${chaos.slow-query-delay-ms:3000}") long slowQueryDelayMs) {
        this.ownerRepository = ownerRepository;
        this.ownerEntityMapper = ownerEntityMapper;
        this.chaosToggles = chaosToggles;
        this.slowQueryDelayMs = slowQueryDelayMs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Owner createOwner(@Valid @RequestBody OwnerRequest ownerRequest) {
        Owner owner = ownerEntityMapper.map(new Owner(), ownerRequest);
        return ownerRepository.save(owner);
    }

    @GetMapping(value = "/{ownerId}")
    public Optional<Owner> findOwner(@PathVariable("ownerId") @Min(1) int ownerId) {
        simulateSlowQueryIfEnabled();
        return ownerRepository.findById(ownerId);
    }

    @GetMapping
    public List<Owner> findAll() {
        simulateSlowQueryIfEnabled();
        return ownerRepository.findAll();
    }

    @PutMapping(value = "/{ownerId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateOwner(@PathVariable("ownerId") @Min(1) int ownerId, @Valid @RequestBody OwnerRequest ownerRequest) {
        final Owner ownerModel = ownerRepository.findById(ownerId).orElseThrow(() -> new ResourceNotFoundException("Owner " + ownerId + " not found"));

        ownerEntityMapper.map(ownerModel, ownerRequest);
        log.info("Saving owner {}", ownerModel);
        ownerRepository.save(ownerModel);
    }

    private void simulateSlowQueryIfEnabled() {
        if (chaosToggles.isEnabled(SLOW_QUERY_TOGGLE)) {
            try {
                Thread.sleep(slowQueryDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=OwnerResourceTest`
Expected: PASS

- [ ] **Step 5: Run the full customers-service test suite to check for regressions**

Run: `./mvnw -pl spring-petclinic-customers-service test`
Expected: PASS (includes `PetResourceTest`, `VisitsServiceClientTest`, `OwnerVisitsResourceTest`, `ChaosTogglesTest`)

- [ ] **Step 6: Commit**

```bash
git add spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/web/OwnerResource.java spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/web/OwnerResourceTest.java
git commit -m "Wire slow-query-enabled chaos toggle into OwnerResource"
```

---

## Task 5: `ChaosToggleWatcher` for customers-service (Consul KV polling)

**Files:**
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/chaos/ChaosConfig.java`
- Create: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/chaos/ChaosToggleWatcher.java`
- Modify: `spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/CustomersServiceApplication.java`
- Test: `spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/chaos/ChaosToggleWatcherTest.java`

**Interfaces:**
- Consumes: `ChaosToggles.set(String, boolean)` from Task 1; `com.ecwid.consul.v1.ConsulClient` (from `consul-api`, already on the compile classpath via `spring-cloud-starter-consul-config`).
- Produces: nothing consumed by later tasks — this closes out customers-service's chaos wiring.

- [ ] **Step 1: Write the failing test**

```java
package org.springframework.samples.petclinic.customers.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ChaosToggleWatcherTest {

    private final ConsulClient consulClient = mock(ConsulClient.class);
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private final ChaosToggleWatcher watcher = new ChaosToggleWatcher(consulClient, chaosToggles);

    @Test
    void updatesTogglesFromConsulKvResponse() {
        GetValue enabled = new GetValue();
        enabled.setKey("chaos/customers-service/slow-query-enabled");
        enabled.setValue(Base64.getEncoder().encodeToString("true".getBytes()));

        GetValue disabled = new GetValue();
        disabled.setKey("chaos/customers-service/downstream-error");
        disabled.setValue(Base64.getEncoder().encodeToString("false".getBytes()));

        given(consulClient.getKVValues("chaos/customers-service/"))
            .willReturn(new Response<>(List.of(enabled, disabled), null, null, null));

        watcher.poll();

        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isTrue();
        assertThat(chaosToggles.isEnabled("downstream-error")).isFalse();
    }

    @Test
    void leavesTogglesUnchangedWhenConsulReturnsNoKeys() {
        given(consulClient.getKVValues("chaos/customers-service/"))
            .willReturn(new Response<>(null, null, null, null));

        chaosToggles.set("slow-query-enabled", true);
        watcher.poll();

        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isTrue();
    }

    @Test
    void swallowsExceptionsFromConsulClient() {
        given(consulClient.getKVValues("chaos/customers-service/"))
            .willThrow(new RuntimeException("connection refused"));

        assertThatCode(watcher::poll).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=ChaosToggleWatcherTest`
Expected: FAIL (compilation error — `ChaosToggleWatcher` does not exist)

- [ ] **Step 3: Write minimal implementation**

`ChaosConfig.java`:
```java
package org.springframework.samples.petclinic.customers.chaos;

import com.ecwid.consul.v1.ConsulClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChaosConfig {

    @Bean
    ConsulClient chaosConsulClient(
            @Value("${spring.cloud.consul.host:localhost}") String consulHost,
            @Value("${spring.cloud.consul.port:8500}") int consulPort) {
        return new ConsulClient(consulHost, consulPort);
    }
}
```

`ChaosToggleWatcher.java`:
```java
package org.springframework.samples.petclinic.customers.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChaosToggleWatcher {

    private static final Logger log = LoggerFactory.getLogger(ChaosToggleWatcher.class);
    private static final String PREFIX = "chaos/customers-service/";

    private final ConsulClient consulClient;
    private final ChaosToggles chaosToggles;

    public ChaosToggleWatcher(ConsulClient consulClient, ChaosToggles chaosToggles) {
        this.consulClient = consulClient;
        this.chaosToggles = chaosToggles;
    }

    @Scheduled(fixedDelayString = "${chaos.poll-interval-ms:5000}")
    public void poll() {
        try {
            Response<List<GetValue>> response = consulClient.getKVValues(PREFIX);
            List<GetValue> values = response.getValue();
            if (values == null) {
                return;
            }
            for (GetValue value : values) {
                String name = value.getKey().substring(PREFIX.length());
                if (name.isEmpty()) {
                    continue;
                }
                chaosToggles.set(name, Boolean.parseBoolean(value.getDecodedValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to poll chaos toggles from Consul KV at '{}', keeping last known state", PREFIX, e);
        }
    }
}
```

Modify `CustomersServiceApplication.java` to enable `@Scheduled` processing:

```java
package org.springframework.samples.petclinic.customers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableDiscoveryClient
@EnableScheduling
@SpringBootApplication
public class CustomersServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CustomersServiceApplication.class, args);
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-customers-service test -Dtest=ChaosToggleWatcherTest`
Expected: PASS

- [ ] **Step 5: Run the full customers-service build to check for regressions**

Run: `./mvnw -pl spring-petclinic-customers-service verify`
Expected: PASS — this is the last customers-service task, so this is the module's full gate.

- [ ] **Step 6: Commit**

```bash
git add spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/chaos/ChaosConfig.java spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/chaos/ChaosToggleWatcher.java spring-petclinic-customers-service/src/main/java/org/springframework/samples/petclinic/customers/CustomersServiceApplication.java spring-petclinic-customers-service/src/test/java/org/springframework/samples/petclinic/customers/chaos/ChaosToggleWatcherTest.java
git commit -m "Poll Consul KV chaos toggles for customers-service"
```

---

## Task 6: `ChaosToggles` in visits-service

**Files:**
- Create: `spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/chaos/ChaosToggles.java`
- Test: `spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/chaos/ChaosTogglesTest.java`

**Interfaces:**
- Produces: `ChaosToggles` — identical shape to Task 1's, but in the `org.springframework.samples.petclinic.visits.chaos` package (separate, duplicated per service — no shared module). Later visits-service tasks inject this as a constructor dependency.

- [ ] **Step 1: Write the failing test**

```java
package org.springframework.samples.petclinic.visits.chaos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosTogglesTest {

    @Test
    void isEnabledDefaultsToFalseForUnknownToggle() {
        ChaosToggles chaosToggles = new ChaosToggles();

        assertThat(chaosToggles.isEnabled("redis-timeout")).isFalse();
    }

    @Test
    void isEnabledReflectsLastSetValue() {
        ChaosToggles chaosToggles = new ChaosToggles();

        chaosToggles.set("redis-timeout", true);
        assertThat(chaosToggles.isEnabled("redis-timeout")).isTrue();

        chaosToggles.set("redis-timeout", false);
        assertThat(chaosToggles.isEnabled("redis-timeout")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=ChaosTogglesTest`
Expected: FAIL (compilation error — `ChaosToggles` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package org.springframework.samples.petclinic.visits.chaos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ChaosToggles {

    private final Map<String, Boolean> toggles = new ConcurrentHashMap<>();

    public boolean isEnabled(String name) {
        return toggles.getOrDefault(name, false);
    }

    public void set(String name, boolean enabled) {
        toggles.put(name, enabled);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=ChaosTogglesTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/chaos/ChaosToggles.java spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/chaos/ChaosTogglesTest.java
git commit -m "Add in-memory ChaosToggles holder to visits-service"
```

---

## Task 7: Redis cache-aside — `VisitCacheService`

**Files:**
- Modify: `spring-petclinic-visits-service/pom.xml` — add, inside `<dependencies>`, alongside the other Spring Boot starters:
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>
  ```
- Create: `spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/config/RedisConfig.java`
- Create: `spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/cache/VisitCacheService.java`
- Test: `spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/cache/VisitCacheServiceTest.java`

**Interfaces:**
- Consumes: `VisitRepository.findByPetIdIn(Collection<Integer>)` (existing); `ChaosToggles.isEnabled(String)` from Task 6.
- Produces: `VisitCacheService` — `public List<Visit> findByPetIdIn(Collection<Integer> petIds)`, `public void evict(int petId)`. Task 8 wires both methods into `VisitResource`.

- [ ] **Step 1: Add the Redis dependency**

Edit `spring-petclinic-visits-service/pom.xml`, adding the dependency shown above right after the `spring-boot-starter-data-jpa` entry. Run `./mvnw -pl spring-petclinic-visits-service dependency:resolve` to confirm it downloads cleanly.

- [ ] **Step 2: Write the failing test**

```java
package org.springframework.samples.petclinic.visits.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class VisitCacheServiceTest {

    private final VisitRepository visitRepository = mock(VisitRepository.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private VisitCacheService cacheService;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        cacheService = new VisitCacheService(visitRepository, redisTemplate, chaosToggles, 1L);
    }

    @Test
    void queriesDatabaseAndPopulatesCacheOnMiss() {
        Visit visit = Visit.VisitBuilder.aVisit().id(1).petId(111).build();
        given(valueOperations.get("visits:pet:111")).willReturn(null);
        given(visitRepository.findByPetIdIn(List.of(111))).willReturn(List.of(visit));

        List<Visit> visits = cacheService.findByPetIdIn(List.of(111));

        assertThat(visits).containsExactly(visit);
        verify(valueOperations).set("visits:pet:111", List.of(visit), Duration.ofSeconds(60));
    }

    @Test
    void returnsCachedVisitsWithoutQueryingDatabaseOnHit() {
        Visit visit = Visit.VisitBuilder.aVisit().id(1).petId(111).build();
        given(valueOperations.get("visits:pet:111")).willReturn(List.of(visit));

        List<Visit> visits = cacheService.findByPetIdIn(List.of(111));

        assertThat(visits).containsExactly(visit);
        verify(visitRepository, never()).findByPetIdIn(any());
    }

    @Test
    void fallsBackToDatabaseWhenRedisReadFails() {
        given(valueOperations.get("visits:pet:111")).willThrow(new RedisConnectionFailureException("boom"));
        Visit visit = Visit.VisitBuilder.aVisit().id(1).petId(111).build();
        given(visitRepository.findByPetIdIn(List.of(111))).willReturn(List.of(visit));

        List<Visit> visits = cacheService.findByPetIdIn(List.of(111));

        assertThat(visits).containsExactly(visit);
    }

    @Test
    void evictsCacheEntryForPet() {
        cacheService.evict(111);

        verify(redisTemplate).delete("visits:pet:111");
    }

    @Test
    void throwsSimulatedTimeoutWithoutTouchingRedisOrDatabaseWhenToggleEnabled() {
        chaosToggles.set("redis-timeout", true);

        assertThatThrownBy(() -> cacheService.findByPetIdIn(List.of(111)))
            .isInstanceOf(io.lettuce.core.RedisCommandTimeoutException.class);

        verify(visitRepository, never()).findByPetIdIn(any());
        verify(valueOperations, never()).get(any());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=VisitCacheServiceTest`
Expected: FAIL (compilation error — `VisitCacheService` does not exist)

- [ ] **Step 4: Write minimal implementation**

`RedisConfig.java`:
```java
package org.springframework.samples.petclinic.visits.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
```

`VisitCacheService.java`:
```java
package org.springframework.samples.petclinic.visits.cache;

import io.lettuce.core.RedisCommandTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;

@Component
public class VisitCacheService {

    private static final Logger log = LoggerFactory.getLogger(VisitCacheService.class);
    private static final String KEY_PREFIX = "visits:pet:";
    private static final Duration TTL = Duration.ofSeconds(60);
    private static final String REDIS_TIMEOUT_TOGGLE = "redis-timeout";

    private final VisitRepository visitRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ChaosToggles chaosToggles;
    private final long redisTimeoutDelayMs;

    public VisitCacheService(VisitRepository visitRepository, RedisTemplate<String, Object> redisTemplate,
                              ChaosToggles chaosToggles,
                              @Value("${chaos.redis-timeout-delay-ms:3000}") long redisTimeoutDelayMs) {
        this.visitRepository = visitRepository;
        this.redisTemplate = redisTemplate;
        this.chaosToggles = chaosToggles;
        this.redisTimeoutDelayMs = redisTimeoutDelayMs;
    }

    public List<Visit> findByPetIdIn(Collection<Integer> petIds) {
        if (chaosToggles.isEnabled(REDIS_TIMEOUT_TOGGLE)) {
            simulateRedisTimeout();
        }

        List<Visit> result = new ArrayList<>();
        List<Integer> misses = new ArrayList<>();

        for (Integer petId : petIds) {
            List<Visit> cached = getFromCache(petId);
            if (cached != null) {
                result.addAll(cached);
            } else {
                misses.add(petId);
            }
        }

        if (!misses.isEmpty()) {
            Map<Integer, List<Visit>> byPetId = visitRepository.findByPetIdIn(misses).stream()
                .collect(groupingBy(Visit::getPetId));
            for (Integer petId : misses) {
                List<Visit> visits = byPetId.getOrDefault(petId, List.of());
                putInCache(petId, visits);
                result.addAll(visits);
            }
        }

        return result;
    }

    public void evict(int petId) {
        try {
            redisTemplate.delete(KEY_PREFIX + petId);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, skipping cache eviction for pet {}", petId, e);
        }
    }

    private void simulateRedisTimeout() {
        try {
            Thread.sleep(redisTimeoutDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new RedisCommandTimeoutException(
            "Simulated Redis timeout (chaos toggle 'redis-timeout' enabled)");
    }

    @SuppressWarnings("unchecked")
    private List<Visit> getFromCache(int petId) {
        try {
            Object cached = redisTemplate.opsForValue().get(KEY_PREFIX + petId);
            return (List<Visit>) cached;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, falling back to database for pet {}", petId, e);
            return null;
        }
    }

    private void putInCache(int petId, List<Visit> visits) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + petId, visits, TTL);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable, skipping cache write for pet {}", petId, e);
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=VisitCacheServiceTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add spring-petclinic-visits-service/pom.xml spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/config/RedisConfig.java spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/cache/VisitCacheService.java spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/cache/VisitCacheServiceTest.java
git commit -m "Add Redis cache-aside for visit lookups with redis-timeout chaos toggle"
```

---

## Task 8: Wire `VisitCacheService` and `slow-query-enabled` into `VisitResource`

**Files:**
- Modify: `spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/web/VisitResource.java`
- Modify: `spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/web/VisitResourceTest.java`

**Interfaces:**
- Consumes: `VisitCacheService.findByPetIdIn(Collection<Integer>)`, `VisitCacheService.evict(int)` (Task 7); `ChaosToggles.isEnabled(String)` (Task 6).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test (replaces the existing `VisitResourceTest`)**

```java
package org.springframework.samples.petclinic.visits.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.samples.petclinic.visits.cache.VisitCacheService;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;


import static java.util.Arrays.asList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VisitResource.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "chaos.slow-query-delay-ms=1")
class VisitResourceTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VisitRepository visitRepository;

    @MockitoBean
    VisitCacheService visitCacheService;

    @MockitoBean
    ChaosToggles chaosToggles;

    @Test
    void shouldFetchVisits() throws Exception {
        given(visitCacheService.findByPetIdIn(asList(111, 222)))
            .willReturn(
                asList(
                    Visit.VisitBuilder.aVisit()
                        .id(1)
                        .petId(111)
                        .build(),
                    Visit.VisitBuilder.aVisit()
                        .id(2)
                        .petId(222)
                        .build(),
                    Visit.VisitBuilder.aVisit()
                        .id(3)
                        .petId(222)
                        .build()
                )
            );

        mvc.perform(get("/pets/visits?petId=111,222"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(1))
            .andExpect(jsonPath("$.items[1].id").value(2))
            .andExpect(jsonPath("$.items[2].id").value(3))
            .andExpect(jsonPath("$.items[0].petId").value(111))
            .andExpect(jsonPath("$.items[1].petId").value(222))
            .andExpect(jsonPath("$.items[2].petId").value(222));

        verify(chaosToggles).isEnabled("slow-query-enabled");
    }

    @Test
    void shouldStillFetchVisitsWhenSlowQueryToggleEnabled() throws Exception {
        given(chaosToggles.isEnabled("slow-query-enabled")).willReturn(true);
        given(visitCacheService.findByPetIdIn(asList(111))).willReturn(asList());

        mvc.perform(get("/pets/visits?petId=111"))
            .andExpect(status().isOk());
    }

    @Test
    void shouldEvictCacheWhenVisitCreated() throws Exception {
        mvc.perform(post("/owners/5/pets/{petId}/visits", 111)
                .contentType("application/json")
                .content("{\"description\":\"a visit\"}"))
            .andExpect(status().isCreated());

        verify(visitCacheService).evict(111);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=VisitResourceTest`
Expected: FAIL — `VisitResource`'s constructor doesn't accept `VisitCacheService`/`ChaosToggles`/the delay property yet, and `read(List<Integer>)` still calls `visitRepository` directly

- [ ] **Step 3: Write minimal implementation**

Modify `VisitResource.java`:

```java
package org.springframework.samples.petclinic.visits.web;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.visits.cache.VisitCacheService;
import org.springframework.samples.petclinic.visits.chaos.ChaosToggles;
import org.springframework.samples.petclinic.visits.model.Visit;
import org.springframework.samples.petclinic.visits.model.VisitRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Timed("petclinic.visit")
class VisitResource {

    private static final Logger log = LoggerFactory.getLogger(VisitResource.class);
    private static final String SLOW_QUERY_TOGGLE = "slow-query-enabled";

    private final VisitRepository visitRepository;
    private final VisitCacheService visitCacheService;
    private final ChaosToggles chaosToggles;
    private final long slowQueryDelayMs;

    VisitResource(VisitRepository visitRepository, VisitCacheService visitCacheService, ChaosToggles chaosToggles,
                  @Value("${chaos.slow-query-delay-ms:3000}") long slowQueryDelayMs) {
        this.visitRepository = visitRepository;
        this.visitCacheService = visitCacheService;
        this.chaosToggles = chaosToggles;
        this.slowQueryDelayMs = slowQueryDelayMs;
    }

    @PostMapping("owners/*/pets/{petId}/visits")
    @ResponseStatus(HttpStatus.CREATED)
    public Visit create(
        @Valid @RequestBody Visit visit,
        @PathVariable("petId") @Min(1) int petId) {

        visit.setPetId(petId);
        log.info("Saving visit {}", visit);
        Visit saved = visitRepository.save(visit);
        visitCacheService.evict(petId);
        return saved;
    }

    @GetMapping("owners/*/pets/{petId}/visits")
    public List<Visit> read(@PathVariable("petId") @Min(1) int petId) {
        return visitRepository.findByPetId(petId);
    }

    @GetMapping("pets/visits")
    public Visits read(@RequestParam("petId") List<Integer> petIds) {
        simulateSlowQueryIfEnabled();
        final List<Visit> visits = visitCacheService.findByPetIdIn(petIds);
        return new Visits(visits);
    }

    private void simulateSlowQueryIfEnabled() {
        if (chaosToggles.isEnabled(SLOW_QUERY_TOGGLE)) {
            try {
                Thread.sleep(slowQueryDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    record Visits(
        List<Visit> items
    ) {
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=VisitResourceTest`
Expected: PASS

- [ ] **Step 5: Run the full visits-service test suite to check for regressions**

Run: `./mvnw -pl spring-petclinic-visits-service test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/web/VisitResource.java spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/web/VisitResourceTest.java
git commit -m "Wire VisitCacheService and slow-query-enabled toggle into VisitResource"
```

---

## Task 9: `ChaosToggleWatcher` for visits-service (Consul KV polling)

**Files:**
- Create: `spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/chaos/ChaosConfig.java`
- Create: `spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/chaos/ChaosToggleWatcher.java`
- Modify: `spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/VisitsServiceApplication.java`
- Test: `spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/chaos/ChaosToggleWatcherTest.java`

**Interfaces:**
- Consumes: `ChaosToggles.set(String, boolean)` from Task 6; `com.ecwid.consul.v1.ConsulClient`.
- Produces: nothing consumed by later tasks — this closes out visits-service's chaos wiring.

- [ ] **Step 1: Write the failing test**

```java
package org.springframework.samples.petclinic.visits.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ChaosToggleWatcherTest {

    private final ConsulClient consulClient = mock(ConsulClient.class);
    private final ChaosToggles chaosToggles = new ChaosToggles();
    private final ChaosToggleWatcher watcher = new ChaosToggleWatcher(consulClient, chaosToggles);

    @Test
    void updatesTogglesFromConsulKvResponse() {
        GetValue enabled = new GetValue();
        enabled.setKey("chaos/visits-service/redis-timeout");
        enabled.setValue(Base64.getEncoder().encodeToString("true".getBytes()));

        GetValue disabled = new GetValue();
        disabled.setKey("chaos/visits-service/slow-query-enabled");
        disabled.setValue(Base64.getEncoder().encodeToString("false".getBytes()));

        given(consulClient.getKVValues("chaos/visits-service/"))
            .willReturn(new Response<>(List.of(enabled, disabled), null, null, null));

        watcher.poll();

        assertThat(chaosToggles.isEnabled("redis-timeout")).isTrue();
        assertThat(chaosToggles.isEnabled("slow-query-enabled")).isFalse();
    }

    @Test
    void leavesTogglesUnchangedWhenConsulReturnsNoKeys() {
        given(consulClient.getKVValues("chaos/visits-service/"))
            .willReturn(new Response<>(null, null, null, null));

        chaosToggles.set("redis-timeout", true);
        watcher.poll();

        assertThat(chaosToggles.isEnabled("redis-timeout")).isTrue();
    }

    @Test
    void swallowsExceptionsFromConsulClient() {
        given(consulClient.getKVValues("chaos/visits-service/"))
            .willThrow(new RuntimeException("connection refused"));

        assertThatCode(watcher::poll).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=ChaosToggleWatcherTest`
Expected: FAIL (compilation error — `ChaosToggleWatcher` does not exist)

- [ ] **Step 3: Write minimal implementation**

`ChaosConfig.java`:
```java
package org.springframework.samples.petclinic.visits.chaos;

import com.ecwid.consul.v1.ConsulClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChaosConfig {

    @Bean
    ConsulClient chaosConsulClient(
            @Value("${spring.cloud.consul.host:localhost}") String consulHost,
            @Value("${spring.cloud.consul.port:8500}") int consulPort) {
        return new ConsulClient(consulHost, consulPort);
    }
}
```

`ChaosToggleWatcher.java`:
```java
package org.springframework.samples.petclinic.visits.chaos;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChaosToggleWatcher {

    private static final Logger log = LoggerFactory.getLogger(ChaosToggleWatcher.class);
    private static final String PREFIX = "chaos/visits-service/";

    private final ConsulClient consulClient;
    private final ChaosToggles chaosToggles;

    public ChaosToggleWatcher(ConsulClient consulClient, ChaosToggles chaosToggles) {
        this.consulClient = consulClient;
        this.chaosToggles = chaosToggles;
    }

    @Scheduled(fixedDelayString = "${chaos.poll-interval-ms:5000}")
    public void poll() {
        try {
            Response<List<GetValue>> response = consulClient.getKVValues(PREFIX);
            List<GetValue> values = response.getValue();
            if (values == null) {
                return;
            }
            for (GetValue value : values) {
                String name = value.getKey().substring(PREFIX.length());
                if (name.isEmpty()) {
                    continue;
                }
                chaosToggles.set(name, Boolean.parseBoolean(value.getDecodedValue()));
            }
        } catch (Exception e) {
            log.warn("Failed to poll chaos toggles from Consul KV at '{}', keeping last known state", PREFIX, e);
        }
    }
}
```

Modify `VisitsServiceApplication.java`:

```java
package org.springframework.samples.petclinic.visits;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableDiscoveryClient
@EnableScheduling
@SpringBootApplication
public class VisitsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisitsServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl spring-petclinic-visits-service test -Dtest=ChaosToggleWatcherTest`
Expected: PASS

- [ ] **Step 5: Run the full visits-service build to check for regressions**

Run: `./mvnw -pl spring-petclinic-visits-service verify`
Expected: PASS — this is the last visits-service task, so this is the module's full gate.

- [ ] **Step 6: Commit**

```bash
git add spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/chaos/ChaosConfig.java spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/chaos/ChaosToggleWatcher.java spring-petclinic-visits-service/src/main/java/org/springframework/samples/petclinic/visits/VisitsServiceApplication.java spring-petclinic-visits-service/src/test/java/org/springframework/samples/petclinic/visits/chaos/ChaosToggleWatcherTest.java
git commit -m "Poll Consul KV chaos toggles for visits-service"
```

---

## Task 10: Update `CHANGES.md` and run the full cross-module build

**Files:**
- Modify: `CHANGES.md`

**Interfaces:**
- Consumes: nothing (documentation only).
- Produces: nothing (terminal task).

- [ ] **Step 1: Update the summary table**

In `CHANGES.md`, change:
```markdown
| Trace chain | Gateway → single service | Cross-service call so traces span 2+ hops | 📋 |
| Fault injection | None | Consul-KV-driven chaos toggles per service | 📋 |
```
to:
```markdown
| Trace chain | Gateway → single service | Cross-service call so traces span 2+ hops | ✅ |
| Fault injection | None | Consul-KV-driven chaos toggles per service | ✅ |
```

- [ ] **Step 2: Rewrite the "Chaos toggles" and "Aggregation endpoint" sections**

Replace the entire "### 3. Chaos toggles 📋 planned, not implemented" section with:

```markdown
### 3. Chaos toggles ✅

- Per-service `chaos` package in `customers-service` and `visits-service` (duplicated, not a shared module — matches the existing per-service duplication of `config/MetricConfig`): `ChaosToggles` (in-memory holder, default OFF) + `ChaosToggleWatcher` (`@Scheduled` poll of `chaos/<service>/` via `com.ecwid.consul.v1.ConsulClient` directly, bypassing Spring Cloud Consul Config/`ContextRefresher` entirely)
- `customers-service`: `slow-query-enabled` (delay before `OwnerRepository` reads in `OwnerResource`), `downstream-error` (forces `VisitsServiceClient` to fail immediately instead of calling visits-service)
- `visits-service`: `slow-query-enabled` (delay before the cached visits read in `VisitResource`), `redis-timeout` (see item 5 below)
- Design/plan: `docs/superpowers/specs/2026-07-28-aggregation-and-chaos-design.md`, `docs/superpowers/plans/2026-07-28-aggregation-and-chaos.md`

### 4. Aggregation endpoint ✅

- `GET /owners/{ownerId}/visits` in `customers-service` (`OwnerVisitsResource`) assembles an owner's pets with each pet's visit history by calling visits-service server-side via a new `@LoadBalanced RestTemplate` (`VisitsServiceClient`) — produces a real multi-hop trace (previously all calls were single-hop through the gateway)
- Downstream failures (timeout, 5xx, connection refused) surface as `DownstreamServiceException` → HTTP 502

### 5. Redis cache-aside in visits-service ✅

- `visits-service` gained a real Redis dependency (`spring-boot-starter-data-redis`, Lettuce) and a cache-aside (`VisitCacheService`) in front of the `pets/visits?petId=` read path: key `visits:pet:{petId}`, 60s TTL, evicted on visit creation
- Config via `spring.data.redis.host/port: ${data.redis.host}/${data.redis.port}` — already seeded by `lab-environment/scripts/init-consul-kv.sh`, no cross-repo change needed
- Genuine Redis unavailability falls back to the database silently (ordinary cache-aside hygiene); the `redis-timeout` chaos toggle deliberately does *not* fall back — it sleeps then throws, so the failure stays visible for RCA training. Real network-layer sabotage (actually breaking the TCP connection) is deferred to the future Toxiproxy phase in `lab-environment`'s ROADMAP
```

Also check the "Explicitly Not Changed" section for any bullet that references the aggregation endpoint or chaos toggles as still-unimplemented, and remove it if present — as of this plan's writing there is no such bullet, only the summary table and detailed sections above reference 📋 status.

- [ ] **Step 3: Run the full build for both modules**

Run: `./mvnw -pl spring-petclinic-customers-service,spring-petclinic-visits-service verify`
Expected: PASS — all tests from Tasks 1–9 plus existing `PetResourceTest`/`VetResourceTest` (unaffected) pass, both modules package successfully.

- [ ] **Step 4: Commit**

```bash
git add CHANGES.md
git commit -m "Mark chaos toggles and aggregation endpoint as done in CHANGES.md"
```

---

## Self-Review Notes

- **Spec coverage:** §1 aggregation endpoint → Tasks 2–3; §2 chaos toggle infrastructure → Tasks 1, 5, 6, 9; customers-service toggles → Task 4 (`slow-query-enabled`), Task 2 (`downstream-error`); visits-service toggles → Task 8 (`slow-query-enabled`), Task 7 (`redis-timeout`); §3 Redis cache-aside (real client, fallback-on-genuine-failure, no-fallback-on-toggle) → Task 7; `CHANGES.md` update → Task 10. No gaps found.
- **Placeholder scan:** no TBD/TODO markers; every step has runnable code and exact commands.
- **Type consistency:** `ChaosToggles.isEnabled(String)`/`.set(String, boolean)` used identically in both services' watchers and resources; `VisitResponse` defined once in Task 2 and reused unchanged in Task 3; `VisitCacheService.findByPetIdIn(Collection<Integer>)`/`.evict(int)` signatures match their Task 8 call sites exactly.
