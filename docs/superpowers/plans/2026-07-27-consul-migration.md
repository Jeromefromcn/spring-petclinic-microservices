# Consul Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Eureka (`spring-petclinic-discovery-server`) and Spring Cloud Config Server (`spring-petclinic-config-server`) with a single HashiCorp Consul agent providing both service discovery and configuration import for every remaining service.

**Architecture:** A single-node Consul agent (`hashicorp/consul:1.20`, dev mode) runs as one Docker Compose service. Every business service (`customers-service`, `visits-service`, `vets-service`, `api-gateway`, `admin-server`, `genai-service`) swaps `spring-cloud-starter-config` + the Eureka client starter for `spring-cloud-starter-consul-config` + `spring-cloud-starter-consul-discovery`, and swaps `spring.config.import: optional:configserver:...` for `spring.config.import: optional:consul:` plus explicit `spring.cloud.consul.host`/`port`. No Java code changes are needed — `api-gateway` and `admin-server` already use the discovery-agnostic `@EnableDiscoveryClient` annotation, and no other service has any Eureka-specific annotation.

**Tech Stack:** Spring Boot 4 / Spring Cloud 2025.1.0 ("Oakwood") / `spring-cloud-consul-dependencies` 5.0.0 (already pulled in transitively by the existing `spring-cloud-dependencies` BOM import — no new version needs to be declared) / Docker Compose / HashiCorp Consul 1.20.

## Global Constraints

- No Consul KV seeding in this plan — Consul KV stays empty until the (separate, later) chaos-toggles sub-project writes `config/<service>/data/chaos.*` keys. All config values go directly into each service's `application.yml`.
- Consul runs single-node in **dev mode** (`consul agent -dev -client=0.0.0.0`) — no persistence volume, no ACLs, no clustering.
- Default profile → `localhost:8500` (with `CONSUL_HOST`/`CONSUL_PORT` env var overrides); `docker` profile → hardcoded `consul:8500`. This mirrors the existing `spring.config.import` profile-split pattern already in every `application.yml`.
- `spring-cloud-starter-consul-discovery` / `spring-cloud-starter-consul-config` need no explicit `<version>` — they come from the `spring-cloud-dependencies` BOM already imported in the root `pom.xml` at `${spring-cloud.version}` (2025.1.0).
- Test profiles must disable Consul entirely with the single property `spring.cloud.consul.enabled: false` (confirmed present via `ConditionalOnConsulEnabled` in `spring-cloud-consul-core:5.0.0`) — this replaces the old pair `spring.cloud.config.enabled: false` + `eureka.client.enabled: false`.

---

### Task 1: Delete Eureka + Config Server modules

**Files:**
- Delete: `spring-petclinic-discovery-server/` (entire directory)
- Delete: `spring-petclinic-config-server/` (entire directory)
- Modify: `pom.xml:14-22` (root)

**Interfaces:**
- Produces: a root reactor build (`./mvnw clean package`) containing only the 6 remaining modules — every later task assumes these two modules and their directories no longer exist.

- [ ] **Step 1: Delete the two module directories**

```bash
git rm -r spring-petclinic-discovery-server spring-petclinic-config-server
```

- [ ] **Step 2: Remove the two `<module>` entries from the root `pom.xml`**

Current (`pom.xml`):
```xml
    <modules>
        <module>spring-petclinic-admin-server</module>
        <module>spring-petclinic-customers-service</module>
        <module>spring-petclinic-vets-service</module>
        <module>spring-petclinic-visits-service</module>
        <module>spring-petclinic-genai-service</module>
        <module>spring-petclinic-config-server</module>
        <module>spring-petclinic-discovery-server</module>
        <module>spring-petclinic-api-gateway</module>
    </modules>
```

New:
```xml
    <modules>
        <module>spring-petclinic-admin-server</module>
        <module>spring-petclinic-customers-service</module>
        <module>spring-petclinic-vets-service</module>
        <module>spring-petclinic-visits-service</module>
        <module>spring-petclinic-genai-service</module>
        <module>spring-petclinic-api-gateway</module>
    </modules>
```

- [ ] **Step 3: Verify the reactor still builds without the deleted modules**

Run: `./mvnw clean package -DskipTests`
Expected: `BUILD SUCCESS`, and the reactor summary lists exactly 6 modules (no `discovery-server` or `config-server`).

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "Remove Eureka discovery-server and Config Server modules"
```

---

### Task 2: Migrate `customers-service` to Consul

**Files:**
- Modify: `spring-petclinic-customers-service/pom.xml`
- Modify: `spring-petclinic-customers-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-customers-service/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: `spring-cloud-consul-dependencies` 5.0.0 BOM (Task 1's build already resolves this transitively).
- Produces: nothing consumed by later tasks — each service task is independent.

- [ ] **Step 1: Swap the Spring Cloud dependencies in `pom.xml`**

Current:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
```

New:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-discovery</artifactId>
        </dependency>
```

- [ ] **Step 2: Point `application.yml` at Consul**

Current:
```yaml
spring:
  application:
    name: customers-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888/}


---
spring:
  config:
    activate:
      on-profile: docker
    import: configserver:http://config-server:8888
```

New:
```yaml
spring:
  application:
    name: customers-service
  config:
    import: "optional:consul:"
  cloud:
    consul:
      host: ${CONSUL_HOST:localhost}
      port: ${CONSUL_PORT:8500}

---
spring:
  config:
    activate:
      on-profile: docker
  cloud:
    consul:
      host: consul
      port: 8500
```

- [ ] **Step 3: Replace the Eureka/Config test overrides**

Current (`src/test/resources/application-test.yml`):
```yaml
spring:
  cloud:
    config:
      enabled: false
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    hibernate:
      ddl-auto: none

eureka:
  client:
    enabled: false

logging.level.org.springframework: INFO
```

New:
```yaml
spring:
  cloud:
    consul:
      enabled: false
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    hibernate:
      ddl-auto: none

logging.level.org.springframework: INFO
```

- [ ] **Step 4: Build and test the module**

Run: `./mvnw clean package -pl spring-petclinic-customers-service`
Expected: `BUILD SUCCESS`, all existing tests pass (they never depended on a live Eureka/Config server, and now don't depend on a live Consul either, since `spring.cloud.consul.enabled: false` is active under the `test` profile).

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-customers-service/pom.xml \
        spring-petclinic-customers-service/src/main/resources/application.yml \
        spring-petclinic-customers-service/src/test/resources/application-test.yml
git commit -m "Migrate customers-service from Eureka/Config Server to Consul"
```

---

### Task 3: Migrate `visits-service` to Consul

**Files:**
- Modify: `spring-petclinic-visits-service/pom.xml`
- Modify: `spring-petclinic-visits-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-visits-service/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: same BOM as Task 2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Swap the Spring Cloud dependencies in `pom.xml`**

Current:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
```

New:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-discovery</artifactId>
        </dependency>
```

- [ ] **Step 2: Point `application.yml` at Consul**

Current:
```yaml
spring:
  application:
    name: visits-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888/}


---
spring:
  config:
    activate:
      on-profile: docker
    import: configserver:http://config-server:8888
```

New:
```yaml
spring:
  application:
    name: visits-service
  config:
    import: "optional:consul:"
  cloud:
    consul:
      host: ${CONSUL_HOST:localhost}
      port: ${CONSUL_PORT:8500}

---
spring:
  config:
    activate:
      on-profile: docker
  cloud:
    consul:
      host: consul
      port: 8500
```

- [ ] **Step 3: Replace the Eureka/Config test overrides**

Current (`src/test/resources/application-test.yml`):
```yaml
spring:
  cloud:
    config:
      enabled: false
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    hibernate:
      ddl-auto: none

eureka:
  client:
    enabled: false

logging.level.org.springframework: INFO
```

New:
```yaml
spring:
  cloud:
    consul:
      enabled: false
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    hibernate:
      ddl-auto: none

logging.level.org.springframework: INFO
```

- [ ] **Step 4: Build and test the module**

Run: `./mvnw clean package -pl spring-petclinic-visits-service`
Expected: `BUILD SUCCESS`, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-visits-service/pom.xml \
        spring-petclinic-visits-service/src/main/resources/application.yml \
        spring-petclinic-visits-service/src/test/resources/application-test.yml
git commit -m "Migrate visits-service from Eureka/Config Server to Consul"
```

---

### Task 4: Migrate `vets-service` to Consul

**Files:**
- Modify: `spring-petclinic-vets-service/pom.xml`
- Modify: `spring-petclinic-vets-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-vets-service/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: same BOM as Task 2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Swap the Spring Cloud dependencies in `pom.xml`**

Current:
```xml
        <!-- Spring Cloud-->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
```

New:
```xml
        <!-- Spring Cloud-->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-discovery</artifactId>
        </dependency>
```

- [ ] **Step 2: Point `application.yml` at Consul (preserving the existing `cache`/`profiles` keys)**

Current:
```yaml
spring:
  application:
    name: vets-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888/}
  cache:
    cache-names: vets
  profiles:
    active: production

---
spring:
  config:
    activate:
      on-profile: docker
    import: configserver:http://config-server:8888
```

New:
```yaml
spring:
  application:
    name: vets-service
  config:
    import: "optional:consul:"
  cloud:
    consul:
      host: ${CONSUL_HOST:localhost}
      port: ${CONSUL_PORT:8500}
  cache:
    cache-names: vets
  profiles:
    active: production

---
spring:
  config:
    activate:
      on-profile: docker
  cloud:
    consul:
      host: consul
      port: 8500
```

- [ ] **Step 3: Replace the Eureka/Config test overrides**

Current (`src/test/resources/application-test.yml`):
```yaml
spring:
  cloud:
    config:
      enabled: false
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    hibernate:
      ddl-auto: none

eureka:
  client:
    enabled: false

vets:
  cache:
    ttl: 10
    heap-size: 10
```

New:
```yaml
spring:
  cloud:
    consul:
      enabled: false
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    hibernate:
      ddl-auto: none

vets:
  cache:
    ttl: 10
    heap-size: 10
```

- [ ] **Step 4: Build and test the module**

Run: `./mvnw clean package -pl spring-petclinic-vets-service`
Expected: `BUILD SUCCESS`, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-vets-service/pom.xml \
        spring-petclinic-vets-service/src/main/resources/application.yml \
        spring-petclinic-vets-service/src/test/resources/application-test.yml
git commit -m "Migrate vets-service from Eureka/Config Server to Consul"
```

---

### Task 5: Migrate `api-gateway` to Consul

**Files:**
- Modify: `spring-petclinic-api-gateway/pom.xml`
- Modify: `spring-petclinic-api-gateway/src/main/resources/application.yml`
- Modify: `spring-petclinic-api-gateway/src/test/resources/application-test.yml`

**Interfaces:**
- Consumes: same BOM as Task 2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Swap the Spring Cloud dependencies in `pom.xml` (keep the circuit breaker and gateway starters untouched)**

Current:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>
```

New:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>
```

- [ ] **Step 2: Point `application.yml` at Consul (add `consul:` as a sibling of the existing `gateway:` key)**

Current:
```yaml
spring:
  application:
    name: api-gateway
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888/}
  cloud:
    gateway:
      server:
        webflux:
          default-filters:
            - name: CircuitBreaker
              args:
                name: defaultCircuitBreaker
                fallbackUri: forward:/fallback
            - name: Retry
              args:
                retries: 1
                statuses: SERVICE_UNAVAILABLE
                methods: POST
          routes:
            - id: vets-service
              uri: lb://vets-service
              predicates:
                - Path=/api/vet/**
              filters:
                - StripPrefix=2
            - id: visits-service
              uri: lb://visits-service
              predicates:
                - Path=/api/visit/**
              filters:
                - StripPrefix=2
            - id: customers-service
              uri: lb://customers-service
              predicates:
                - Path=/api/customer/**
              filters:
                - StripPrefix=2
            - id: genai-service
              uri: lb://genai-service
              predicates:
                - Path=/api/genai/**
              filters:
                - StripPrefix=2
                - CircuitBreaker=name=genaiCircuitBreaker,fallbackUri=/fallback
  reactor:
    context-propagation: auto
---
spring:
  config:
    activate:
      on-profile: docker
    import: configserver:http://config-server:8888
```

New:
```yaml
spring:
  application:
    name: api-gateway
  config:
    import: "optional:consul:"
  cloud:
    consul:
      host: ${CONSUL_HOST:localhost}
      port: ${CONSUL_PORT:8500}
    gateway:
      server:
        webflux:
          default-filters:
            - name: CircuitBreaker
              args:
                name: defaultCircuitBreaker
                fallbackUri: forward:/fallback
            - name: Retry
              args:
                retries: 1
                statuses: SERVICE_UNAVAILABLE
                methods: POST
          routes:
            - id: vets-service
              uri: lb://vets-service
              predicates:
                - Path=/api/vet/**
              filters:
                - StripPrefix=2
            - id: visits-service
              uri: lb://visits-service
              predicates:
                - Path=/api/visit/**
              filters:
                - StripPrefix=2
            - id: customers-service
              uri: lb://customers-service
              predicates:
                - Path=/api/customer/**
              filters:
                - StripPrefix=2
            - id: genai-service
              uri: lb://genai-service
              predicates:
                - Path=/api/genai/**
              filters:
                - StripPrefix=2
                - CircuitBreaker=name=genaiCircuitBreaker,fallbackUri=/fallback
  reactor:
    context-propagation: auto
---
spring:
  config:
    activate:
      on-profile: docker
  cloud:
    consul:
      host: consul
      port: 8500
```

- [ ] **Step 3: Replace the Eureka/Config test overrides**

Current (`src/test/resources/application-test.yml`):
```yaml
spring.cloud.config.enabled: false
eureka.client.enabled: false
```

New:
```yaml
spring.cloud.consul.enabled: false
```

- [ ] **Step 4: Build and test the module**

Run: `./mvnw clean package -pl spring-petclinic-api-gateway`
Expected: `BUILD SUCCESS`, all existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add spring-petclinic-api-gateway/pom.xml \
        spring-petclinic-api-gateway/src/main/resources/application.yml \
        spring-petclinic-api-gateway/src/test/resources/application-test.yml
git commit -m "Migrate api-gateway from Eureka/Config Server to Consul"
```

---

### Task 6: Migrate `admin-server` to Consul

**Files:**
- Modify: `spring-petclinic-admin-server/pom.xml`
- Modify: `spring-petclinic-admin-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes: same BOM as Task 2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Swap the Spring Cloud dependencies in `pom.xml`**

Current:
```xml
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
```

New:
```xml
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-discovery</artifactId>
        </dependency>
```

- [ ] **Step 2: Point `application.yml` at Consul**

Current:
```yaml
spring:
  application:
    name: admin-server
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888/}


---
spring:
  config:
    activate:
      on-profile: docker
    import: configserver:http://config-server:8888
```

New:
```yaml
spring:
  application:
    name: admin-server
  config:
    import: "optional:consul:"
  cloud:
    consul:
      host: ${CONSUL_HOST:localhost}
      port: ${CONSUL_PORT:8500}

---
spring:
  config:
    activate:
      on-profile: docker
  cloud:
    consul:
      host: consul
      port: 8500
```

- [ ] **Step 3: Build the module**

Run: `./mvnw clean package -pl spring-petclinic-admin-server`
Expected: `BUILD SUCCESS` (this module has no `src/test` directory today, so there are no tests to run).

- [ ] **Step 4: Commit**

```bash
git add spring-petclinic-admin-server/pom.xml \
        spring-petclinic-admin-server/src/main/resources/application.yml
git commit -m "Migrate admin-server from Eureka/Config Server to Consul"
```

---

### Task 7: Migrate `genai-service` to Consul

**Files:**
- Modify: `spring-petclinic-genai-service/pom.xml`
- Modify: `spring-petclinic-genai-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: same BOM as Task 2.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Swap the Spring Cloud dependencies in `pom.xml` (keep the circuit breaker and gateway starters untouched)**

Current:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>
```

New:
```xml
        <!-- Spring Cloud -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-consul-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>
```

- [ ] **Step 2: Point `application.yml` at Consul (keep the `spring.ai.*` and `logging.*` keys untouched)**

Current:
```yaml
spring:
  main:
    web-application-type: reactive
  application:
    name: genai-service
  profiles:
    active: production
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888/},optional:classpath:/creds.yaml
  ai:
    chat:
      client:
        enabled: true
    # These apply when using spring-ai-starter-model-azure-openai
    azure:
      openai:
        api-key: ${AZURE_OPENAI_KEY}
        endpoint: ${AZURE_OPENAI_ENDPOINT}
        chat:
          options:
            temperature: 0.7
            deployment-name: gpt-4o
    # These apply when using spring-ai-starter-model-openai
    openai:
      api-key: ${OPENAI_API_KEY:demo}
      chat:
        options:
            temperature: 0.7
            model: gpt-4o-mini


logging:
  level:
    org:
      springframework:
        ai:
          chat:
            client:
              advisor: DEBUG
---
spring:
  config:
    activate:
      on-profile: docker
    import: configserver:http://config-server:8888
```

New:
```yaml
spring:
  main:
    web-application-type: reactive
  application:
    name: genai-service
  profiles:
    active: production
  config:
    import: optional:consul:,optional:classpath:/creds.yaml
  cloud:
    consul:
      host: ${CONSUL_HOST:localhost}
      port: ${CONSUL_PORT:8500}
  ai:
    chat:
      client:
        enabled: true
    # These apply when using spring-ai-starter-model-azure-openai
    azure:
      openai:
        api-key: ${AZURE_OPENAI_KEY}
        endpoint: ${AZURE_OPENAI_ENDPOINT}
        chat:
          options:
            temperature: 0.7
            deployment-name: gpt-4o
    # These apply when using spring-ai-starter-model-openai
    openai:
      api-key: ${OPENAI_API_KEY:demo}
      chat:
        options:
            temperature: 0.7
            model: gpt-4o-mini


logging:
  level:
    org:
      springframework:
        ai:
          chat:
            client:
              advisor: DEBUG
---
spring:
  config:
    activate:
      on-profile: docker
  cloud:
    consul:
      host: consul
      port: 8500
```

- [ ] **Step 3: Build the module**

Run: `./mvnw clean package -pl spring-petclinic-genai-service`
Expected: `BUILD SUCCESS` (this module has no `src/test` directory today, so there are no tests to run).

- [ ] **Step 4: Commit**

```bash
git add spring-petclinic-genai-service/pom.xml \
        spring-petclinic-genai-service/src/main/resources/application.yml
git commit -m "Migrate genai-service from Eureka/Config Server to Consul"
```

---

### Task 8: Replace `config-server`/`discovery-server` with `consul` in `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:**
- Consumes: `docker` profile config from Tasks 2–7 (every service now expects a container named `consul` reachable at `consul:8500`).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Replace the `config-server` and `discovery-server` service blocks with a `consul` service block**

Current (top of `docker-compose.yml`):
```yaml
services:
  config-server:
    image: springcommunity/spring-petclinic-config-server
    container_name: config-server
    deploy:
      resources:
        limits:
          memory: 512M
    healthcheck:
      test: ["CMD", "curl", "-I", "http://config-server:8888"]
      interval: 5s
      timeout: 5s
      retries: 10
    ports:
     - 8888:8888

  discovery-server:
    image: springcommunity/spring-petclinic-discovery-server
    container_name: discovery-server
    deploy:
      resources:
        limits:
          memory: 512M
    healthcheck:
      test: ["CMD", "curl", "-f", "http://discovery-server:8761"]
      interval: 5s
      timeout: 3s
      retries: 10
    depends_on:
      config-server:
        condition: service_healthy
    ports:
     - 8761:8761
```

New:
```yaml
services:
  consul:
    image: hashicorp/consul:1.20
    container_name: consul
    command: "agent -dev -client=0.0.0.0"
    deploy:
      resources:
        limits:
          memory: 512M
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8500/v1/status/leader"]
      interval: 5s
      timeout: 5s
      retries: 10
    ports:
     - 8500:8500
```

- [ ] **Step 2: Repoint every remaining service's `depends_on` at `consul`**

Current (appears identically in the `customers-service`, `visits-service`, `vets-service`, `genai-service`, `api-gateway`, and `admin-server` blocks):
```yaml
    depends_on:
      config-server:
        condition: service_healthy
      discovery-server:
        condition: service_healthy
```

New (applied to all 6 occurrences):
```yaml
    depends_on:
      consul:
        condition: service_healthy
```

- [ ] **Step 3: Validate the compose file**

Run: `docker compose config --quiet`
Expected: exits with status 0 and no output (confirms valid YAML and a resolvable compose model).

- [ ] **Step 4: Bring up Consul alone and confirm it becomes healthy**

Run: `docker compose up -d consul && sleep 5 && docker compose ps consul && curl -sf http://localhost:8500/v1/status/leader && docker compose down`
Expected: `docker compose ps consul` shows `healthy`, and the `curl` call prints a non-empty leader address like `"127.0.0.1:8300"`.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml
git commit -m "Replace config-server/discovery-server with Consul in docker-compose.yml"
```

---

### Task 9: Update build/run scripts

**Files:**
- Modify: `scripts/run_all.sh`
- Modify: `scripts/pushImages.sh`
- Modify: `scripts/tagImages.sh`

**Interfaces:**
- Consumes: the `consul` Docker Compose service name from Task 8.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Remove the config-server/discovery-server launch steps from `run_all.sh` and start Consul as infra**

Current:
```bash
#!/usr/bin/env bash

set -o errexit
set -o errtrace
set -o nounset
set -o pipefail

pkill -9 -f spring-petclinic || echo "Failed to kill any apps"

docker compose kill || echo "No docker containers are running"

echo "Running infra"
docker compose up -d grafana-server prometheus-server tracing-server

echo "Running apps"
mkdir -p target
nohup java -jar spring-petclinic-config-server/target/*.jar --server.port=8888 --spring.profiles.active=chaos-monkey > target/config-server.log 2>&1 &
echo "Waiting for config server to start"
sleep 20
nohup java -jar spring-petclinic-discovery-server/target/*.jar --server.port=8761 --spring.profiles.active=chaos-monkey > target/discovery-server.log 2>&1 &
echo "Waiting for discovery server to start"
sleep 20
nohup java -jar spring-petclinic-customers-service/target/*.jar --server.port=8081 --spring.profiles.active=chaos-monkey > target/customers-service.log 2>&1 &
nohup java -jar spring-petclinic-visits-service/target/*.jar --server.port=8082 --spring.profiles.active=chaos-monkey > target/visits-service.log 2>&1 &
nohup java -jar spring-petclinic-vets-service/target/*.jar --server.port=8083 --spring.profiles.active=chaos-monkey > target/vets-service.log 2>&1 &
nohup java -jar spring-petclinic-genai-service/target/*.jar --server.port=8084 --spring.profiles.active=chaos-monkey > target/genai-service.log 2>&1 &
nohup java -jar spring-petclinic-api-gateway/target/*.jar --server.port=8080 --spring.profiles.active=chaos-monkey > target/gateway-service.log 2>&1 &
nohup java -jar spring-petclinic-admin-server/target/*.jar --server.port=9090 --spring.profiles.active=chaos-monkey > target/admin-server.log 2>&1 &
echo "Waiting for apps to start"
sleep 60
```

New:
```bash
#!/usr/bin/env bash

set -o errexit
set -o errtrace
set -o nounset
set -o pipefail

pkill -9 -f spring-petclinic || echo "Failed to kill any apps"

docker compose kill || echo "No docker containers are running"

echo "Running infra"
docker compose up -d grafana-server prometheus-server tracing-server consul
echo "Waiting for consul to start"
sleep 10

echo "Running apps"
mkdir -p target
nohup java -jar spring-petclinic-customers-service/target/*.jar --server.port=8081 --spring.profiles.active=chaos-monkey > target/customers-service.log 2>&1 &
nohup java -jar spring-petclinic-visits-service/target/*.jar --server.port=8082 --spring.profiles.active=chaos-monkey > target/visits-service.log 2>&1 &
nohup java -jar spring-petclinic-vets-service/target/*.jar --server.port=8083 --spring.profiles.active=chaos-monkey > target/vets-service.log 2>&1 &
nohup java -jar spring-petclinic-genai-service/target/*.jar --server.port=8084 --spring.profiles.active=chaos-monkey > target/genai-service.log 2>&1 &
nohup java -jar spring-petclinic-api-gateway/target/*.jar --server.port=8080 --spring.profiles.active=chaos-monkey > target/gateway-service.log 2>&1 &
nohup java -jar spring-petclinic-admin-server/target/*.jar --server.port=9090 --spring.profiles.active=chaos-monkey > target/admin-server.log 2>&1 &
echo "Waiting for apps to start"
sleep 60
```

- [ ] **Step 2: Remove the config-server/discovery-server lines from `pushImages.sh`**

Current:
```bash
#!/bin/bash
docker push ${REPOSITORY_PREFIX}/spring-petclinic-config-server:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-discovery-server:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-api-gateway:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-visits-service:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-vets-service:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-customers-service:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-admin-server:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-genai-service:${VERSION}
```

New:
```bash
#!/bin/bash
docker push ${REPOSITORY_PREFIX}/spring-petclinic-api-gateway:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-visits-service:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-vets-service:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-customers-service:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-admin-server:${VERSION}
docker push ${REPOSITORY_PREFIX}/spring-petclinic-genai-service:${VERSION}
```

- [ ] **Step 3: Remove the config-server/discovery-server lines from `tagImages.sh`**

Current:
```bash
#!/bin/bash
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-config-server ${REPOSITORY_PREFIX}/spring-petclinic-config-server:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-discovery-server ${REPOSITORY_PREFIX}/spring-petclinic-discovery-server:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-api-gateway ${REPOSITORY_PREFIX}/spring-petclinic-api-gateway:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-visits-service ${REPOSITORY_PREFIX}/spring-petclinic-visits-service:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-vets-service ${REPOSITORY_PREFIX}/spring-petclinic-vets-service:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-customers-service ${REPOSITORY_PREFIX}/spring-petclinic-customers-service:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-admin-server ${REPOSITORY_PREFIX}/spring-petclinic-admin-server:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-genai-service ${REPOSITORY_PREFIX}/spring-petclinic-genai-service:${VERSION}
```

New:
```bash
#!/bin/bash
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-api-gateway ${REPOSITORY_PREFIX}/spring-petclinic-api-gateway:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-visits-service ${REPOSITORY_PREFIX}/spring-petclinic-visits-service:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-vets-service ${REPOSITORY_PREFIX}/spring-petclinic-vets-service:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-customers-service ${REPOSITORY_PREFIX}/spring-petclinic-customers-service:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-admin-server ${REPOSITORY_PREFIX}/spring-petclinic-admin-server:${VERSION}
docker tag ${REPOSITORY_PREFIX}/spring-petclinic-genai-service ${REPOSITORY_PREFIX}/spring-petclinic-genai-service:${VERSION}
```

- [ ] **Step 4: Syntax-check all three scripts**

Run: `bash -n scripts/run_all.sh && bash -n scripts/pushImages.sh && bash -n scripts/tagImages.sh && echo OK`
Expected: prints `OK` with no syntax errors.

- [ ] **Step 5: Commit**

```bash
git add scripts/run_all.sh scripts/pushImages.sh scripts/tagImages.sh
git commit -m "Update build/run scripts for Consul migration"
```

---

### Task 10: Update `README.md`

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing (documentation-only task).

- [ ] **Step 1: Replace the Eureka mention in the intro paragraph**

Current (`README.md:7-8`):
```
To achieve that goal, we use Spring Cloud Gateway, Spring Cloud Circuit Breaker, Spring Cloud Config, Micrometer Tracing, Resilience4j, Open Telemetry 
and the Eureka Service Discovery from the [Spring Cloud Netflix](https://github.com/spring-cloud/spring-cloud-netflix) technology stack.
```

New:
```
To achieve that goal, we use Spring Cloud Gateway, Spring Cloud Circuit Breaker, Micrometer Tracing, Resilience4j, Open Telemetry 
and HashiCorp Consul for service discovery and configuration, via the [Spring Cloud Consul](https://github.com/spring-cloud/spring-cloud-consul) integration.
```

- [ ] **Step 2: Update the "Starting services locally without Docker" section**

Current (`README.md:17-31`):
```
Please note that supporting services (Config and Discovery Server) must be started before any other application (Customers, Vets, Visits and API).
Startup of Tracing server, Admin server, Grafana and Prometheus is optional.
If everything goes well, you can access the following services at given location:
* Discovery Server - http://localhost:8761
* Config Server - http://localhost:8888
* AngularJS frontend (API Gateway) - http://localhost:8080
* Customers, Vets, Visits and GenAI Services - random port, check Eureka Dashboard 
* Tracing Server (Zipkin) - http://localhost:9411/zipkin/ (we use [openzipkin](https://github.com/openzipkin/zipkin/tree/main/zipkin-server))
* Admin Server (Spring Boot Admin) - http://localhost:9090
* Grafana Dashboards - http://localhost:3030
* Prometheus - http://localhost:9091

You can tell Config Server to use your local Git repository by using `native` Spring profile and setting
`GIT_REPO` environment variable, for example:
`-Dspring.profiles.active=native -DGIT_REPO=/projects/spring-petclinic-microservices-config`
```

New:
```
Please note that Consul (`docker compose up -d consul`) must be started before any other application (Customers, Vets, Visits and API).
Startup of Tracing server, Admin server, Grafana and Prometheus is optional.
If everything goes well, you can access the following services at given location:
* Consul UI - http://localhost:8500
* AngularJS frontend (API Gateway) - http://localhost:8080
* Customers, Vets, Visits and GenAI Services - random port, check the Consul UI's Services page
* Tracing Server (Zipkin) - http://localhost:9411/zipkin/ (we use [openzipkin](https://github.com/openzipkin/zipkin/tree/main/zipkin-server))
* Admin Server (Spring Boot Admin) - http://localhost:9090
* Grafana Dashboards - http://localhost:3030
* Prometheus - http://localhost:9091
```

- [ ] **Step 3: Update the Eureka dashboard mention under "Starting services locally with docker-compose"**

Current (`README.md:56-58`):
```
After starting services, it takes a while for API Gateway to be in sync with service registry,
so don't be scared of initial Spring Cloud Gateway timeouts. You can track services availability using Eureka dashboard
available by default at http://localhost:8761.
```

New:
```
After starting services, it takes a while for API Gateway to be in sync with service registry,
so don't be scared of initial Spring Cloud Gateway timeouts. You can track services availability using the Consul UI
available by default at http://localhost:8500.
```

- [ ] **Step 4: Remove the deleted modules from the "Microservices Overview" list**

Current (`README.md:86-88`):
```
- **API Gateway**: Routes client requests to the appropriate services.
- **Config Server**: Centralized configuration management for all services.
- **Discovery Server**: Eureka-based service registry.
```

New:
```
- **API Gateway**: Routes client requests to the appropriate services.
```

- [ ] **Step 5: Merge the "Configuration server" / "Service Discovery" table rows and drop the now-unused link reference**

Current (`README.md:207-208`):
```
| Configuration server            | [Config server properties](spring-petclinic-config-server/src/main/resources/application.yml) and [Configuration repository] |
| Service Discovery               | [Eureka server](spring-petclinic-discovery-server) and [Service discovery client](spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/VetsServiceApplication.java) |
```

New:
```
| Configuration & Service Discovery | [Consul service](docker-compose.yml) and [Service discovery client](spring-petclinic-vets-service/src/main/java/org/springframework/samples/petclinic/vets/VetsServiceApplication.java) |
```

Then remove the now-orphaned reference-style link definition (`README.md:284`, exact line may shift after prior edits — locate by content):
```
[Configuration repository]: https://github.com/spring-petclinic/spring-petclinic-microservices-config
```
Delete this line entirely.

- [ ] **Step 6: Fix the example `docker pull` command**

Current (`README.md:227`):
```
docker pull springcommunity/spring-petclinic-config-server
```

New:
```
docker pull springcommunity/spring-petclinic-customers-service
```

- [ ] **Step 7: Verify no stale references remain**

Run: `grep -in "discovery-server\|config-server\|eureka\|:8761\|:8888" README.md`
Expected: no output (empty match).

- [ ] **Step 8: Commit**

```bash
git add README.md
git commit -m "Update README for Consul migration"
```

---

### Task 11: Full-stack integration check

**Files:** none (verification only).

**Interfaces:**
- Consumes: everything from Tasks 1–10.
- Produces: final confirmation that the sub-project's acceptance criteria (from the design spec) hold.

- [ ] **Step 1: Build every module**

Run: `./mvnw clean package`
Expected: `BUILD SUCCESS` across all 6 modules.

- [ ] **Step 2: Build Docker images for every module**

Run: `./mvnw clean install -P buildDocker`
Expected: `BUILD SUCCESS`, and `docker images` lists a fresh `springcommunity/spring-petclinic-*` image for each of the 6 remaining modules.

- [ ] **Step 3: Bring up Consul and the business services**

Run: `docker compose up -d consul customers-service visits-service vets-service api-gateway admin-server`
Expected: all listed containers reach `running`/`healthy` state within a couple of minutes (`docker compose ps`).

- [ ] **Step 4: Verify service registration in Consul**

Run: `curl -s http://localhost:8500/v1/catalog/services | tr ',' '\n'`
Expected: output includes `customers-service`, `visits-service`, `vets-service`, `api-gateway`, and `admin-server` (alongside Consul's own `consul` entry).

- [ ] **Step 5: Verify the gateway routes resolve through Consul-based load balancing**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/vet/vets`
Expected: `200`.

- [ ] **Step 6: Tear down**

Run: `docker compose down`
Expected: all containers stopped and removed.

No commit for this task — it's a verification-only pass. If any step fails, return to the relevant earlier task and fix it there (with its own commit) rather than patching ad hoc.

---

### Task 12: Restore config-server-provided runtime properties (post-final-review fix)

**Why this task exists:** the final whole-branch review (opus) found that the deleted
`spring-petclinic-config-server` was serving real runtime configuration beyond Eureka
registration — pulled from the external git repo
`spring-petclinic/spring-petclinic-microservices-config` — and Tasks 2-7 only migrated
`spring.cloud.consul.host`/`port`, silently dropping everything else. Verified by cloning
that repo and diffing its `application.yml` (shared) and each `<service>.yml` against
what Tasks 2-7 actually produced. Confirmed empirically: services bind to the Spring Boot
default port (8080) instead of their intended port under the `docker` profile, breaking
every `docker-compose.yml` port mapping except `api-gateway`'s (which happens to already
be 8080) and every Prometheus scrape target; HSQLDB seed data never loads; actuator only
exposes `health`; Zipkin export has no docker-network endpoint; api-gateway's i18n
`messages.basename` and compression are gone; the `mysql` profile has no datasource.

This is a design-phase gap (the design spec assumed the local `application.yml` files
were the full picture, without ever fetching the external config repo's actual content),
not an implementation slip — Tasks 2-7 correctly did everything the plan told them to do.

**Files:**
- Modify: `spring-petclinic-customers-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-visits-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-vets-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-api-gateway/src/main/resources/application.yml`
- Modify: `spring-petclinic-admin-server/src/main/resources/application.yml`
- Modify: `spring-petclinic-genai-service/src/main/resources/application.yml`
- Modify: `README.md` (fix the still-dangling "Configuration repository" references in the
  "Use the Spring 'mysql' profile" section — Task 10 restored the link definition to stop
  it rendering broken, but the instructions themselves still tell the reader to edit a
  config repo that no longer applies to this fork's architecture)

**Reference source (read-only, do not modify):** the external config repo has been cloned
to `/tmp/claude-1001/-home-ubuntu-jerome-spring-petclinic-microservices/825bde82-c544-4313-bbcc-b6c0dae16b6b/scratchpad/config-repo-check/`
for exact property values — `application.yml` (shared, applies to all services) plus
`customers-service.yml`, `visits-service.yml`, `vets-service.yml`, `api-gateway.yml`,
`admin-server.yml`, `genai-service.yml` (per-service overrides). If that path is gone,
re-clone: `git clone --depth 1 https://github.com/spring-petclinic/spring-petclinic-microservices-config`.

**Properties to restore (dropping anything Eureka-specific or Config-Server-specific,
and anything obsolete for the current Spring Boot 4 / Spring Cloud 2025.1.0 stack —
`management.security.enabled`, `spring.cloud.config.allow-override`/`override-none`,
`spring.cloud.refresh.refreshable`, `eureka.*` are all correctly left out):**

Add to the **default/root document** of all 6 services' `application.yml` (alongside the
existing `spring.application.name`/`spring.config.import`/`spring.cloud.consul.*` keys):
```yaml
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
server:
  shutdown: graceful
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    metrics:
      enabled: true
    prometheus:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
  tracing:
    sampling:
      probability: 1
logging:
  level:
    org.springframework: INFO
```
(`spring.sql.init.*` and `spring.jpa.*` only apply to the 4 JPA-backed services —
`customers-service`, `visits-service`, `vets-service`, `genai-service` — Spring Boot
ignores them harmlessly on `api-gateway`/`admin-server` if added there too, but it's
cleaner to only add them where there's a datasource. Use judgment per service; if in
doubt, check the service's `pom.xml` for `spring-boot-starter-data-jpa`.)

Add to the **`docker` profile document** of all 6 services (alongside the existing
`spring.cloud.consul.host: consul` / `port: 8500` block):
```yaml
server:
  port: <service-specific — see table below>
management:
  tracing:
    export:
      zipkin:
        endpoint: "http://tracing-server:9411/api/v2/spans"
```

| Service | docker-profile `server.port` |
|---|---|
| `customers-service` | `8081` |
| `visits-service` | `8082` |
| `vets-service` | `8083` |
| `genai-service` | `8084` |
| `admin-server` | `9090` |
| `api-gateway` | `8080` — but api-gateway's upstream config set this **unconditionally** (not docker-profile-gated) alongside `server.compression.*` and `spring.messages.basename: messages/messages`; add all three to api-gateway's default/root document instead of the docker-profile block, matching upstream exactly:
```yaml
server:
  port: 8080
  compression:
    enabled: true
    mime-types: application/json,text/css,application/javascript
    min-response-size: 2048
spring:
  messages:
    basename: messages/messages
```

Add a **new `chaos-monkey` profile document** to all 6 services (this restores the
pre-existing upstream `chaos-monkey-spring-boot` integration that `scripts/run_all.sh`
already activates via `--spring.profiles.active=chaos-monkey` — unrelated to this fork's
own planned Consul-KV-driven `chaos/` package, which is a separate future sub-project):
```yaml
---
spring:
  config:
    activate:
      on-profile: chaos-monkey
management:
  endpoint:
    chaosmonkey:
      enabled: true
chaos:
  monkey:
    enabled: true
    watcher:
      component: false
      controller: false
      repository: false
      rest-controller: false
      service: false
```

Add a **new `mysql` profile document** to exactly the 3 services README.md's "Use the
Spring 'mysql' profile" section documents — `customers-service`, `visits-service`,
`vets-service` (not `genai-service`, not `api-gateway`/`admin-server`, which have no
datasource):
```yaml
---
spring:
  config:
    activate:
      on-profile: mysql
  datasource:
    url: jdbc:mysql://localhost:3306/petclinic?allowPublicKeyRetrieval=true&useSSL=false
    username: root
    password: petclinic
  sql:
    init:
      schema-locations: classpath*:db/mysql/schema.sql
      data-locations: classpath*:db/mysql/data.sql
      mode: ALWAYS
```

- [ ] **Step 1: Apply the restoration to all 6 services' `application.yml`**

Follow the tables/blocks above exactly. Preserve every existing key already in each
file (the Consul host/port blocks from Tasks 2-7, `vets-service`'s `cache`/`profiles`
keys, `api-gateway`'s gateway routes, `genai-service`'s `spring.ai.*`/`logging.level.org
.springframework.ai.*` keys) — this task only adds new keys/documents, it does not
remove or restructure anything Tasks 1-11 already got right.

- [ ] **Step 2: Fix README.md's "Use the Spring 'mysql' profile" section**

The two remaining `[Configuration repository]` mentions
(`In the application.yml of the [Configuration repository], set the initialization-mode
to never.` and `In the mysql section of the application.yml from the [Configuration
repository], you have to change the host and port...`) should point at the local files
this task just created instead of a config repo that no longer exists in this
architecture. Reword both sentences to reference each service's own
`src/main/resources/application.yml` `mysql` profile document directly, and remove the
now-fully-unused `[Configuration repository]: https://github.com/spring-petclinic/spring-petclinic-microservices-config`
link definition (this time it really is orphaned, once these two mentions no longer use it).

- [ ] **Step 3: Rebuild and re-run the full-stack integration check with real assertions**

This supersedes Task 11's weaker (HTTP-200-only) check per the final review's Important
finding — assert actual content and per-service ports, not just a status code.

```bash
./mvnw clean package
./mvnw clean install -P buildDocker
docker compose up -d consul customers-service visits-service vets-service api-gateway admin-server
sleep 30
```
Then verify, and note the PASS/FAIL of each explicitly in your report:
1. `curl -s http://localhost:8500/v1/catalog/services | tr ',' '\n'` — same 6 entries as Task 11.
2. `curl -s http://localhost:8080/api/vet/vets | python3 -m json.tool` — expect a **non-empty** JSON array (this is the assertion Task 11 was missing).
3. Direct per-service port checks from inside the compose network, e.g.
   `docker compose exec api-gateway curl -sf http://vets-service:8083/vets` (and the analogous check for `customers-service:8081/owners`, `visits-service:8082/*`) — expect success, proving the restored `docker`-profile `server.port` actually took effect (the host-mapped ports may still be unreachable directly from this sandbox for unrelated network-policy reasons noted in Task 11's report — that's fine, this checks the port *inside* the docker network instead).
4. `docker compose exec customers-service curl -sf http://localhost:8081/actuator/prometheus | head -5` — expect real Prometheus-format metric lines, not a 404.
5. **Consul config-import round-trip** (validates the *config* half of the Consul migration, which nothing so far has actually exercised): write a throwaway key to Consul KV, e.g. `curl -X PUT http://localhost:8500/v1/kv/config/customers-service/data/test.marker -d 'hello-from-consul-kv'`, restart `customers-service` (`docker compose restart customers-service`), then `docker compose exec customers-service curl -s http://localhost:8081/actuator/env | grep -A3 test.marker` (or `/actuator/env/test.marker` if actuator is set up for that) and confirm the value `hello-from-consul-kv` is visible with a `consul:` property source. Delete the KV key afterward (`curl -X DELETE http://localhost:8500/v1/kv/config/customers-service/data/test.marker`) so no test artifact is left in Consul KV (this plan's Global Constraints require KV to stay empty).
6. `docker compose down` to tear down.

- [ ] **Step 4: Commit**

```bash
git add spring-petclinic-customers-service/src/main/resources/application.yml \
        spring-petclinic-visits-service/src/main/resources/application.yml \
        spring-petclinic-vets-service/src/main/resources/application.yml \
        spring-petclinic-api-gateway/src/main/resources/application.yml \
        spring-petclinic-admin-server/src/main/resources/application.yml \
        spring-petclinic-genai-service/src/main/resources/application.yml
git commit -m "Restore config-server-provided runtime properties lost in the Consul migration"

git add README.md
git commit -m "Point the mysql-profile README instructions at local application.yml instead of the deleted config repo"
```

---

### Task 13: Fix the Consul-KV-write crash (root cause, verified via systematic debugging)

**Why this task exists:** Task 12's re-review found that writing *any* key to Consul KV
under `config/<service>/` crashes that service. This blocks the next planned sub-project
(chaos toggles), whose entire design assumes `@RefreshScope` config applies live from
Consul KV without a restart, and it also blocks the PostgreSQL sub-project's stated design
("connection properties now read from Consul KV").

**Root cause (reproduced and confirmed empirically, not guessed):**
1. Writing any key under a watched Consul KV prefix makes `ConfigWatch` fire a Spring
   Cloud `RefreshEvent`.
2. `RefreshEventListener` → `ContextRefresher.refreshEnvironment()` → publishes an event
   that `ConfigurationPropertiesRebinder` handles by rebinding **every** registered
   `@ConfigurationProperties` bean app-wide — not just ones related to what changed.
3. On `customers-service`, `visits-service`, `vets-service`, and `genai-service` (the 4
   services with a JPA `DataSource`), the `dataSource` bean is wrapped in a JDK dynamic
   proxy by `datasource-micrometer-spring-boot`'s `DataSourceObservationBeanPostProcessor`
   (confirmed via `/actuator/beans`: `dataSource => jdk.proxy2.$Proxy119`, not
   `com.zaxxer.hikari.HikariDataSource`).
4. `ConfigurationPropertiesRebinder.rebind()` calls `Bindable.withExistingValue()` on the
   Hikari-specific `@ConfigurationProperties` bean using the *current* bean instance — now
   the proxy — and its strict type check throws:
   `IllegalArgumentException: 'existingValue' must be an instance of com.zaxxer.hikari.HikariDataSource`.
5. This is caught by the scheduled task's error handler (doesn't crash the JVM by itself),
   but `ConfigWatch` retries on every poll cycle (~1s, no backoff), forever. Each refresh
   attempt — failed or not — costs real memory: `ContextRefresher.refreshEnvironment()`
   builds a throwaway application context per refresh, and that cost lands mostly in
   non-heap/metaspace, which is uncapped by default. Pre-fix, the ~1/s infinite failed-retry
   loop burns through this budget in under two minutes against the `docker-compose.yml`-
   declared, and actually enforced by this Docker Compose version (v5.1.4) even outside
   Swarm mode, 512M hard limit, and the container is OOM-killed (`OOMKilled=true,
   ExitCode=137`, reproduced twice). **Post-fix, the same non-heap growth still happens on
   every *successful* refresh** — it's a pre-existing sizing/headroom problem this bug
   exposed rather than caused (see the note after the fix below), not something the fix
   itself introduces.
6. `admin-server` (no `DataSource` bean) does **not** exhibit this — confirmed by writing
   to its own KV path and observing no error, no health degradation. `api-gateway` has no
   datasource dependency either, so by the same mechanism it's not expected to be affected.

**Fix (tested minimally, confirmed to resolve the crash without breaking legitimate
refresh):** add
```yaml
spring:
  cloud:
    refresh:
      never-refreshable: dataSource,com.zaxxer.hikari.HikariDataSource
```
to the 4 affected services' `application.yml` (default/root document, alongside the other
`spring.cloud.consul.*` keys). This is a real, current, documented property on
`spring-cloud-context:5.0.0` ("Comma separated list of bean names or class names for beans
to never be refreshed or rebound") — not the older, genuinely-obsolete
`spring.cloud.refresh.refreshable` boolean that Task 12 correctly dropped (confirmed that
property no longer exists in this version's configuration metadata at all).

Decompiling `ConfigurationPropertiesRebinder.rebind()` during review confirmed *why*
Spring Cloud's own built-in guard doesn't already catch this: it defaults
`never-refreshable` to `com.zaxxer.hikari.HikariDataSource` and checks it against
`bean.getClass().getName()` — but only after `AopUtils.isAopProxy(bean)` unwraps genuine
Spring AOP proxies first. The `datasource-micrometer-spring-boot` proxy is a plain JDK
proxy, not a Spring AOP proxy, so it's never unwrapped and the class-name check never
matches. The rebinder separately checks the *bean name* against the same list — that's
the hook this fix actually uses. The fix's value keeps both the original class-name guard
and the new bean-name guard (`dataSource,com.zaxxer.hikari.HikariDataSource`) rather than
replacing the built-in default outright, so anything else that might legitimately need the
class-name guard still gets it.

**Known follow-up, not fixed here (flagged, not blocking):** even after this fix, every
*successful* Consul KV refresh still costs non-heap/metaspace memory via
`ContextRefresher`'s throwaway context, and this container's baseline idle memory
(~450MB) already leaves only a few dozen MB of headroom under the 512M limit — a burst of
~8-10 refreshes in quick succession can still OOM-kill the container even with zero
errors. This is a pre-existing sizing problem, not something this fix introduces, but it
directly matters for the chaos-toggles sub-project (whose entire premise is frequent
Consul-KV-driven refreshes) and should be addressed — raising the affected services'
`deploy.resources.limits.memory` in `docker-compose.yml` and/or bounding JVM metaspace
growth explicitly — before that sub-project starts relying on frequent live KV writes.

Verified with a throwaway container + KV key (`docker run` with
`SPRING_CLOUD_REFRESH_NEVER_REFRESHABLE=dataSource`, bypassing file edits to test the
hypothesis in isolation first): before the fix, writing a KV key produces continuous
`Fail fast is set...IllegalArgumentException` log spam and eventual OOM-kill; after the
fix, the same KV write produces a clean `Refresh keys changed: [data.<key>]` log line, the
health endpoint stays `200`, and the written value is genuinely visible under
`/actuator/env` sourced from `config/<service>/` — proving the config-import + live-refresh
path this whole sub-project exists to deliver actually works end-to-end.

**Files:**
- Modify: `spring-petclinic-customers-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-visits-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-vets-service/src/main/resources/application.yml`
- Modify: `spring-petclinic-genai-service/src/main/resources/application.yml`

(`api-gateway` and `admin-server` have no `DataSource` bean, so they're not touched by
this task — the root cause structurally cannot occur without one.)

- [ ] **Step 1: Add `spring.cloud.refresh.never-refreshable: dataSource` to the default
  document of all 4 affected services' `application.yml`**, alongside the existing
  `spring.cloud.consul.host`/`port` keys added in Tasks 2-4/7.

- [ ] **Step 2: Rebuild and re-run the Consul KV crash reproduction against the real files**

```bash
./mvnw clean package -pl spring-petclinic-customers-service,spring-petclinic-visits-service,spring-petclinic-vets-service,spring-petclinic-genai-service
./mvnw clean install -P buildDocker -pl spring-petclinic-customers-service,spring-petclinic-visits-service,spring-petclinic-vets-service,spring-petclinic-genai-service
docker compose up -d consul customers-service
sleep 20
curl -X PUT http://localhost:8500/v1/kv/config/customers-service/data/test.marker -d 'hello'
sleep 15
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/actuator/health   # expect 200, not 503
docker compose logs customers-service | grep -c "Fail fast is set"              # expect 0
curl -s http://localhost:8081/actuator/env/data.test.marker                     # expect the value to show up
curl -X DELETE http://localhost:8500/v1/kv/config/customers-service/data/test.marker
docker compose down
```
Repeat the same KV-write + health-check spot check for at least one more of the 3 other
affected services (e.g. `vets-service`) to confirm the fix generalizes, not just
`customers-service`.

- [ ] **Step 3: Commit**

```bash
git add spring-petclinic-customers-service/src/main/resources/application.yml \
        spring-petclinic-visits-service/src/main/resources/application.yml \
        spring-petclinic-vets-service/src/main/resources/application.yml \
        spring-petclinic-genai-service/src/main/resources/application.yml
git commit -m "Fix Consul KV write crash: exclude dataSource bean from ConfigurationPropertiesRebinder"
```

---

### Task 14: Raise memory headroom for the 4 JPA-backed services (Task 13's follow-up)

**Why this task exists:** Task 13's review found that even after fixing the crash, every
successful Consul-KV-triggered refresh still costs real memory (`ContextRefresher` builds
a throwaway application context per refresh, landing mostly in non-heap/metaspace), and
`customers-service`/`visits-service`/`vets-service`/`genai-service` already idle at
~450-500MB against the `docker-compose.yml` 512M limit — only a few dozen MB, or under a
dozen refreshes, of headroom. This isn't a code defect to fix, it's a sizing problem to
correct before the chaos-toggles sub-project makes frequent KV writes the normal
interaction pattern.

**Fix:** raise `deploy.resources.limits.memory` from `512M` to `1024M` for exactly the 4
affected services in `docker-compose.yml` (`consul`, `api-gateway`, `admin-server`,
`tracing-server`, `grafana-server`, `prometheus-server` are untouched — their memory
profiles weren't measured as tight and doubling every service's limit isn't warranted by
what was actually observed).

**Verified:** brought up `consul` + `customers-service` fresh with the new limit, then
fired 25 sequential Consul KV writes (roughly 3x the ~8 writes that OOM-killed the old
512M container in Task 13's review) 2 seconds apart. Health stayed `200` throughout;
memory grew from ~489MiB to ~550MiB and the growth rate was clearly flattening (large
early increments, much smaller later ones — consistent with one-time class/proxy loading
costs stabilizing, not an unbounded linear leak) — comfortably inside the new 1024M
ceiling with room to spare. All 25 test keys deleted afterward; `docker compose down` run
clean.

- [ ] **Step 1: Raise the memory limit for the 4 affected services**

In `docker-compose.yml`, change `memory: 512M` to `memory: 1024M` under each of
`customers-service`, `visits-service`, `vets-service`, and `genai-service`'s
`deploy.resources.limits` block. Leave every other service's memory limit untouched.

- [ ] **Step 2: Stress-test the new headroom**

```bash
docker compose up -d consul customers-service
sleep 20
for i in $(seq 1 25); do
  curl -s -X PUT http://localhost:8500/v1/kv/config/customers-service/data/stress.$i -d "value-$i" > /dev/null
  sleep 2
  curl -s -o /dev/null -w "write $i: %{http_code}\n" http://localhost:8081/actuator/health
done
```
Expected: `200` on every write, no crash. Then clean up:
```bash
for i in $(seq 1 25); do curl -s -X DELETE http://localhost:8500/v1/kv/config/customers-service/data/stress.$i > /dev/null; done
docker compose down
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "Raise memory limits for JPA-backed services to give Consul-refresh headroom"
```
