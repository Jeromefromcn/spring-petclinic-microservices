# PostgreSQL Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch `customers-service`, `vets-service`, and `visits-service` from HSQLDB (default) / MySQL (optional profile) to PostgreSQL, with connection details sourced from Consul KV keys `lab-environment` already seeds.

**Architecture:** Each service keeps its existing `spring-boot-starter-data-jpa` + `spring.sql.init` (schema.sql/data.sql) pattern, repointed at PostgreSQL. `spring.datasource.url`/`username`/`password` interpolate `${db.host}`/`${db.port}`/`${db.name}`/`${db.user}`/`${db.password}`, which resolve from Consul KV at `config/<service>/data/db.*` via the `spring-cloud-starter-consul-config` import already wired in the Consul migration. `spring.sql.init.mode: always` keeps today's behavior of a freshly reset, deterministically seeded database on every start.

**Tech Stack:** Spring Boot 4 / Spring Cloud 2025.1.0 / PostgreSQL 16 (`org.postgresql:postgresql` JDBC driver, version inherited from the Spring Boot parent BOM — no explicit `<version>` needed, matching how the removed `mysql-connector-j` was declared) / HSQLDB (stays, `test` scope only, unchanged for `application-test.yml`).

## Global Constraints

- Scope is exactly `customers-service`, `vets-service`, `visits-service`. `admin-server` and `genai-service` have no database role in `lab-environment` and must not be touched (confirmed via `lab-environment/CLAUDE.md`'s cross-repo contract: those two are never built into `ops-lab/*` images).
- Consul KV keys are already seeded by `lab-environment/scripts/init-consul-kv.sh` — this plan only adds the fork-side code that reads them: `config/<service>/data/db.host`, `db.port`, `db.name`, `db.user`, `db.password`. Do not invent new key names.
- `spring.sql.init.mode: always` (not `never`) — every service start drops and recreates its schema, then reseeds. This is intentional (matches `lab-environment/ROADMAP.md`'s "run scenario → wipe → next scenario" model) and must not be changed to `never` in any task below.
- `db/postgresql/schema.sql` uses `DROP TABLE IF EXISTS` + `CREATE TABLE` (not `CREATE TABLE IF NOT EXISTS`) — required for `mode: always` to actually reseed on every start.
- Every `data.sql` ends with `ALTER SEQUENCE ... RESTART WITH <n>` for every table with an identity column, sized to `max(seeded id) + 1` for that table — omitting this causes the first record a user creates through the running app to collide with a seeded id.
- `application-test.yml` in all 3 services is out of scope — tests keep running against embedded HSQLDB, untouched.
- The `mysql` Spring profile, `db/mysql/` files, and the README's MySQL instructions are removed entirely in this plan, not deprecated or kept alongside.

---

### Task 1: Migrate `customers-service` to PostgreSQL

**Files:**
- Modify: `spring-petclinic-customers-service/pom.xml`
- Modify: `spring-petclinic-customers-service/src/main/resources/application.yml`
- Create: `spring-petclinic-customers-service/src/main/resources/db/postgresql/schema.sql`
- Create: `spring-petclinic-customers-service/src/main/resources/db/postgresql/data.sql`
- Delete: `spring-petclinic-customers-service/src/main/resources/db/mysql/` (entire directory)

**Interfaces:**
- Consumes: Consul KV keys `config/customers-service/data/db.{host,port,name,user,password}` (already seeded by `lab-environment`).
- Produces: nothing consumed by later tasks — each service task is independent.

- [ ] **Step 1: Swap the JDBC driver dependency in `pom.xml`**

Current (`spring-petclinic-customers-service/pom.xml`, inside the "Third parties" block):
```xml
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.hsqldb</groupId>
            <artifactId>hsqldb</artifactId>
            <scope>runtime</scope>
        </dependency>
```

New:
```xml
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.hsqldb</groupId>
            <artifactId>hsqldb</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Point `application.yml`'s default document at PostgreSQL, and delete the `mysql` profile document**

Current default document (`spring-petclinic-customers-service/src/main/resources/application.yml`):
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
    refresh:
      never-refreshable: dataSource,com.zaxxer.hikari.HikariDataSource
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
```

New default document:
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
    refresh:
      never-refreshable: dataSource,com.zaxxer.hikari.HikariDataSource
  datasource:
    url: jdbc:postgresql://${db.host}:${db.port}/${db.name}
    username: ${db.user}
    password: ${db.password}
  sql:
    init:
      schema-locations: classpath*:db/postgresql/schema.sql
      data-locations: classpath*:db/postgresql/data.sql
      mode: always
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
```

Then delete the final `---` document at the end of the file (the `mysql` profile):
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
The `docker` and `chaos-monkey` profile documents in between are untouched.

- [ ] **Step 3: Create `db/postgresql/schema.sql`**

```sql
DROP TABLE IF EXISTS pets;
DROP TABLE IF EXISTS types;
DROP TABLE IF EXISTS owners;

CREATE TABLE types (
  id   INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  name VARCHAR(80)
);
CREATE INDEX types_name ON types (name);

CREATE TABLE owners (
  id         INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  first_name VARCHAR(30),
  last_name  VARCHAR(30),
  address    VARCHAR(255),
  city       VARCHAR(80),
  telephone  VARCHAR(20)
);
CREATE INDEX owners_last_name ON owners (last_name);

CREATE TABLE pets (
  id         INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  name       VARCHAR(30),
  birth_date DATE,
  type_id    INTEGER NOT NULL,
  owner_id   INTEGER NOT NULL
);
ALTER TABLE pets ADD CONSTRAINT fk_pets_owners FOREIGN KEY (owner_id) REFERENCES owners (id);
ALTER TABLE pets ADD CONSTRAINT fk_pets_types FOREIGN KEY (type_id) REFERENCES types (id);
CREATE INDEX pets_name ON pets (name);
```

(`owners.telephone` is `VARCHAR(20)` here, not the `VARCHAR(12)` in `db/hsqldb/schema.sql` — carried over from `db/mysql/schema.sql`'s wider column, which matches upstream's actual intended width.)

- [ ] **Step 4: Create `db/postgresql/data.sql`**

```sql
INSERT INTO types VALUES (1, 'cat');
INSERT INTO types VALUES (2, 'dog');
INSERT INTO types VALUES (3, 'lizard');
INSERT INTO types VALUES (4, 'snake');
INSERT INTO types VALUES (5, 'bird');
INSERT INTO types VALUES (6, 'hamster');

INSERT INTO owners VALUES (1, 'George', 'Franklin', '110 W. Liberty St.', 'Madison', '6085551023');
INSERT INTO owners VALUES (2, 'Betty', 'Davis', '638 Cardinal Ave.', 'Sun Prairie', '6085551749');
INSERT INTO owners VALUES (3, 'Eduardo', 'Rodriquez', '2693 Commerce St.', 'McFarland', '6085558763');
INSERT INTO owners VALUES (4, 'Harold', 'Davis', '563 Friendly St.', 'Windsor', '6085553198');
INSERT INTO owners VALUES (5, 'Peter', 'McTavish', '2387 S. Fair Way', 'Madison', '6085552765');
INSERT INTO owners VALUES (6, 'Jean', 'Coleman', '105 N. Lake St.', 'Monona', '6085552654');
INSERT INTO owners VALUES (7, 'Jeff', 'Black', '1450 Oak Blvd.', 'Monona', '6085555387');
INSERT INTO owners VALUES (8, 'Maria', 'Escobito', '345 Maple St.', 'Madison', '6085557683');
INSERT INTO owners VALUES (9, 'David', 'Schroeder', '2749 Blackhawk Trail', 'Madison', '6085559435');
INSERT INTO owners VALUES (10, 'Carlos', 'Estaban', '2335 Independence La.', 'Waunakee', '6085555487');

INSERT INTO pets VALUES (1, 'Leo', '2010-09-07', 1, 1);
INSERT INTO pets VALUES (2, 'Basil', '2012-08-06', 6, 2);
INSERT INTO pets VALUES (3, 'Rosy', '2011-04-17', 2, 3);
INSERT INTO pets VALUES (4, 'Jewel', '2010-03-07', 2, 3);
INSERT INTO pets VALUES (5, 'Iggy', '2010-11-30', 3, 4);
INSERT INTO pets VALUES (6, 'George', '2010-01-20', 4, 5);
INSERT INTO pets VALUES (7, 'Samantha', '2012-09-04', 1, 6);
INSERT INTO pets VALUES (8, 'Max', '2012-09-04', 1, 6);
INSERT INTO pets VALUES (9, 'Lucky', '2011-08-06', 5, 7);
INSERT INTO pets VALUES (10, 'Mulligan', '2007-02-24', 2, 8);
INSERT INTO pets VALUES (11, 'Freddy', '2010-03-09', 5, 9);
INSERT INTO pets VALUES (12, 'Lucky', '2010-06-24', 2, 10);
INSERT INTO pets VALUES (13, 'Sly', '2012-06-08', 1, 10);

ALTER SEQUENCE types_id_seq RESTART WITH 7;
ALTER SEQUENCE owners_id_seq RESTART WITH 11;
ALTER SEQUENCE pets_id_seq RESTART WITH 14;
```

- [ ] **Step 5: Delete the `db/mysql` directory**

```bash
git rm -r spring-petclinic-customers-service/src/main/resources/db/mysql
```

- [ ] **Step 6: Build and test the module**

Run: `./mvnw clean package -pl spring-petclinic-customers-service`
Expected: `BUILD SUCCESS`. Existing tests still pass unchanged — `application-test.yml` sets `spring.cloud.consul.enabled: false` and its own `db/hsqldb/schema.sql`/`data.sql` locations, independent of the default document changed in Step 2.

- [ ] **Step 7: Commit**

```bash
git add spring-petclinic-customers-service/pom.xml \
        spring-petclinic-customers-service/src/main/resources/application.yml \
        spring-petclinic-customers-service/src/main/resources/db/postgresql
git commit -m "Migrate customers-service to PostgreSQL"
```

---

### Task 2: Migrate `vets-service` to PostgreSQL

**Files:**
- Modify: `spring-petclinic-vets-service/pom.xml`
- Modify: `spring-petclinic-vets-service/src/main/resources/application.yml`
- Create: `spring-petclinic-vets-service/src/main/resources/db/postgresql/schema.sql`
- Create: `spring-petclinic-vets-service/src/main/resources/db/postgresql/data.sql`
- Delete: `spring-petclinic-vets-service/src/main/resources/db/mysql/` (entire directory)

**Interfaces:**
- Consumes: Consul KV keys `config/vets-service/data/db.{host,port,name,user,password}`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Swap the JDBC driver dependency in `pom.xml`**

Current (`spring-petclinic-vets-service/pom.xml`, inside the "Third parties" block):
```xml
		<dependency>
			<groupId>org.hsqldb</groupId>
			<artifactId>hsqldb</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
			<scope>runtime</scope>
		</dependency>
```

New:
```xml
		<dependency>
			<groupId>org.hsqldb</groupId>
			<artifactId>hsqldb</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
			<scope>runtime</scope>
		</dependency>
```

- [ ] **Step 2: Point `application.yml`'s default document at PostgreSQL, and delete the `mysql` profile document**

Current default document (`spring-petclinic-vets-service/src/main/resources/application.yml`):
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
    refresh:
      never-refreshable: dataSource,com.zaxxer.hikari.HikariDataSource
  cache:
    cache-names: vets
  profiles:
    active: production
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
```

New default document:
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
    refresh:
      never-refreshable: dataSource,com.zaxxer.hikari.HikariDataSource
  cache:
    cache-names: vets
  profiles:
    active: production
  datasource:
    url: jdbc:postgresql://${db.host}:${db.port}/${db.name}
    username: ${db.user}
    password: ${db.password}
  sql:
    init:
      schema-locations: classpath*:db/postgresql/schema.sql
      data-locations: classpath*:db/postgresql/data.sql
      mode: always
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
```

Then delete the final `---` document at the end of the file (the `mysql` profile):
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
The `docker` and `chaos-monkey` profile documents in between are untouched.

- [ ] **Step 3: Create `db/postgresql/schema.sql`**

```sql
DROP TABLE IF EXISTS vet_specialties;
DROP TABLE IF EXISTS vets;
DROP TABLE IF EXISTS specialties;

CREATE TABLE vets (
  id         INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  first_name VARCHAR(30),
  last_name  VARCHAR(30)
);
CREATE INDEX vets_last_name ON vets (last_name);

CREATE TABLE specialties (
  id   INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  name VARCHAR(80)
);
CREATE INDEX specialties_name ON specialties (name);

CREATE TABLE vet_specialties (
  vet_id       INTEGER NOT NULL,
  specialty_id INTEGER NOT NULL,
  UNIQUE (vet_id, specialty_id)
);
ALTER TABLE vet_specialties ADD CONSTRAINT fk_vet_specialties_vets FOREIGN KEY (vet_id) REFERENCES vets (id);
ALTER TABLE vet_specialties ADD CONSTRAINT fk_vet_specialties_specialties FOREIGN KEY (specialty_id) REFERENCES specialties (id);
```

(`vet_specialties` gets a `UNIQUE (vet_id, specialty_id)` constraint here, carried over from `db/mysql/schema.sql` — `db/hsqldb/schema.sql` lacks it.)

- [ ] **Step 4: Create `db/postgresql/data.sql`**

```sql
INSERT INTO vets VALUES (1, 'James', 'Carter');
INSERT INTO vets VALUES (2, 'Helen', 'Leary');
INSERT INTO vets VALUES (3, 'Linda', 'Douglas');
INSERT INTO vets VALUES (4, 'Rafael', 'Ortega');
INSERT INTO vets VALUES (5, 'Henry', 'Stevens');
INSERT INTO vets VALUES (6, 'Sharon', 'Jenkins');

INSERT INTO specialties VALUES (1, 'radiology');
INSERT INTO specialties VALUES (2, 'surgery');
INSERT INTO specialties VALUES (3, 'dentistry');

INSERT INTO vet_specialties VALUES (2, 1);
INSERT INTO vet_specialties VALUES (3, 2);
INSERT INTO vet_specialties VALUES (3, 3);
INSERT INTO vet_specialties VALUES (4, 2);
INSERT INTO vet_specialties VALUES (5, 1);

ALTER SEQUENCE vets_id_seq RESTART WITH 7;
ALTER SEQUENCE specialties_id_seq RESTART WITH 4;
```

(`vet_specialties` has no identity column, so it needs no sequence restart.)

- [ ] **Step 5: Delete the `db/mysql` directory**

```bash
git rm -r spring-petclinic-vets-service/src/main/resources/db/mysql
```

- [ ] **Step 6: Build and test the module**

Run: `./mvnw clean package -pl spring-petclinic-vets-service`
Expected: `BUILD SUCCESS`. Existing tests still pass unchanged (same reasoning as Task 1, Step 6).

- [ ] **Step 7: Commit**

```bash
git add spring-petclinic-vets-service/pom.xml \
        spring-petclinic-vets-service/src/main/resources/application.yml \
        spring-petclinic-vets-service/src/main/resources/db/postgresql
git commit -m "Migrate vets-service to PostgreSQL"
```

---

### Task 3: Migrate `visits-service` to PostgreSQL

**Files:**
- Modify: `spring-petclinic-visits-service/pom.xml`
- Modify: `spring-petclinic-visits-service/src/main/resources/application.yml`
- Create: `spring-petclinic-visits-service/src/main/resources/db/postgresql/schema.sql`
- Create: `spring-petclinic-visits-service/src/main/resources/db/postgresql/data.sql`
- Delete: `spring-petclinic-visits-service/src/main/resources/db/mysql/` (entire directory)

**Interfaces:**
- Consumes: Consul KV keys `config/visits-service/data/db.{host,port,name,user,password}`.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Swap the JDBC driver dependency in `pom.xml`**

Current (`spring-petclinic-visits-service/pom.xml`, inside the "Third parties" block):
```xml
        <dependency>
            <groupId>org.hsqldb</groupId>
            <artifactId>hsqldb</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.jolokia</groupId>
            <artifactId>jolokia-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
```

New:
```xml
        <dependency>
            <groupId>org.hsqldb</groupId>
            <artifactId>hsqldb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.jolokia</groupId>
            <artifactId>jolokia-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: Point `application.yml`'s default document at PostgreSQL, and delete the `mysql` profile document**

Current default document (`spring-petclinic-visits-service/src/main/resources/application.yml`):
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
    refresh:
      never-refreshable: dataSource,com.zaxxer.hikari.HikariDataSource
  sql:
    init:
      schema-locations: classpath*:db/hsqldb/schema.sql
      data-locations: classpath*:db/hsqldb/data.sql
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
```

New default document:
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
    refresh:
      never-refreshable: dataSource,com.zaxxer.hikari.HikariDataSource
  datasource:
    url: jdbc:postgresql://${db.host}:${db.port}/${db.name}
    username: ${db.user}
    password: ${db.password}
  sql:
    init:
      schema-locations: classpath*:db/postgresql/schema.sql
      data-locations: classpath*:db/postgresql/data.sql
      mode: always
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
```

Then delete the final `---` document at the end of the file (the `mysql` profile):
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
The `docker` and `chaos-monkey` profile documents in between are untouched.

- [ ] **Step 3: Create `db/postgresql/schema.sql`**

```sql
DROP TABLE IF EXISTS visits;

CREATE TABLE visits (
  id          INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  pet_id      INTEGER NOT NULL,
  visit_date  DATE,
  description VARCHAR(8192)
);
CREATE INDEX visits_pet_id ON visits (pet_id);
```

- [ ] **Step 4: Create `db/postgresql/data.sql`**

```sql
INSERT INTO visits VALUES (1, 7, '2013-01-01', 'rabies shot');
INSERT INTO visits VALUES (2, 8, '2013-01-02', 'rabies shot');
INSERT INTO visits VALUES (3, 8, '2013-01-03', 'neutered');
INSERT INTO visits VALUES (4, 7, '2013-01-04', 'spayed');

ALTER SEQUENCE visits_id_seq RESTART WITH 5;
```

- [ ] **Step 5: Delete the `db/mysql` directory**

```bash
git rm -r spring-petclinic-visits-service/src/main/resources/db/mysql
```

- [ ] **Step 6: Build and test the module**

Run: `./mvnw clean package -pl spring-petclinic-visits-service`
Expected: `BUILD SUCCESS`. Existing tests still pass unchanged (same reasoning as Task 1, Step 6).

- [ ] **Step 7: Commit**

```bash
git add spring-petclinic-visits-service/pom.xml \
        spring-petclinic-visits-service/src/main/resources/application.yml \
        spring-petclinic-visits-service/src/main/resources/db/postgresql
git commit -m "Migrate visits-service to PostgreSQL"
```

---

### Task 4: Update `README.md`'s database configuration section

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing (documentation-only task).

- [ ] **Step 1: Replace the "Database configuration" section**

Current (`README.md:133-162`):
```markdown
## Database configuration

In its default configuration, Petclinic uses an in-memory database (HSQLDB) which gets populated at startup with data.
A similar setup is provided for MySql in case a persistent database configuration is needed.
Dependency for Connector/J, the MySQL JDBC driver is already included in the `pom.xml` files.

### Start a MySql database

You may start a MySql database with docker:

```
docker run -e MYSQL_ROOT_PASSWORD=petclinic -e MYSQL_DATABASE=petclinic -p 3306:3306 mysql:8.4.5
```
or download and install the MySQL database (e.g., MySQL Community Server 8.4.5 LTS), which can be found here: https://dev.mysql.com/downloads/

### Use the Spring 'mysql' profile

To use a MySQL database, you have to start 3 microservices (`visits-service`, `customers-service` and `vets-services`)
with the `mysql` Spring profile. Add the `--spring.profiles.active=mysql` as program argument.

By default, at startup, database schema will be created and data will be populated.
You may also manually create the PetClinic database and data by executing the `"db/mysql/{schema,data}.sql"` scripts of each 3 microservices. 
In the `mysql` profile document of each service's own `src/main/resources/application.yml`, set the `spring.sql.init.mode` to `never`.

If you are running the microservices with Docker, you have to add the `mysql` profile into the [Dockerfile](docker/Dockerfile):
```
ENV SPRING_PROFILES_ACTIVE docker,mysql
```
In the `mysql` profile document of each service's own `src/main/resources/application.yml`, you have to change 
the host and port of your MySQL JDBC connection string. 
```

New:
```markdown
## Database configuration

`customers-service`, `vets-service`, and `visits-service` use PostgreSQL. Schema and seed data
(`src/main/resources/db/postgresql/{schema,data}.sql`) are applied automatically on every service
startup (`spring.sql.init.mode: always`), so each start resets to a known, deterministic dataset.

Connection details (`db.host`, `db.port`, `db.name`, `db.user`, `db.password`) are not stored in
this repo — they're read from Consul KV at `config/<service>/data/*` via
`spring-cloud-starter-consul-config`. See the sibling
[`lab-environment`](https://github.com/Jeromefromcn/lab-environment) repo's `docker-compose.yml`
(provisions the `postgres` container and its 3 per-service databases) and
`scripts/init-consul-kv.sh` (seeds the KV keys above) for how these are provisioned.

Tests run independently against an embedded HSQLDB instance
(`src/test/resources/application-test.yml`), unaffected by the PostgreSQL setup above.
```

- [ ] **Step 2: Verify no stale MySQL references remain in the database section**

Run: `grep -n "mysql\|MySql\|MySQL" README.md`
Expected: no output (empty match) — the only prior matches were inside the section just replaced.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Update README's database configuration section for PostgreSQL"
```

---

### Task 5: Full-stack integration verification

**Files:** none (verification only).

**Interfaces:**
- Consumes: everything from Tasks 1-4, plus `lab-environment`'s existing `docker-compose.yml`/`init-consul-kv.sh` (unmodified — this fork now consumes KV keys that were already being seeded).
- Produces: final confirmation the migration works end-to-end.

- [ ] **Step 1: Build every touched module**

Run: `./mvnw clean package -pl spring-petclinic-customers-service,spring-petclinic-vets-service,spring-petclinic-visits-service`
Expected: `BUILD SUCCESS` for all 3 modules.

- [ ] **Step 2: Build Docker images**

From `lab-environment`:
```bash
./scripts/build.sh
```
Expected: `BUILD SUCCESS`, and `docker images` lists fresh `ops-lab/customers-service:dev`, `ops-lab/vets-service:dev`, `ops-lab/visits-service:dev` images.

- [ ] **Step 3: Bring up the environment and seed Consul KV**

From `lab-environment`:
```bash
docker compose up -d consul postgres customers-service vets-service visits-service
sleep 20
./scripts/init-consul-kv.sh
docker compose restart customers-service vets-service visits-service
sleep 20
```
(Restart is needed because Consul config is read at startup; the first boot happens before KV is seeded.)

- [ ] **Step 4: Verify each service actually queries PostgreSQL, not just that it's healthy**

```bash
docker compose ps customers-service vets-service visits-service   # all "healthy" or "running"
curl -s http://localhost:180/api/customer/owners | python3 -m json.tool   # non-empty, 10 seeded owners
curl -s http://localhost:180/api/vet/vets | python3 -m json.tool          # non-empty, 6 seeded vets
curl -s http://localhost:180/api/visit/owners/1/pets/1/visits | python3 -m json.tool   # non-empty, seeded visits for pet 1
```
Expected: real seeded data in every response, not empty arrays or 5xx errors.

- [ ] **Step 5: Verify the sequence-restart fix — create a new owner and confirm no id collision**

```bash
curl -s -X POST http://localhost:180/api/customer/owners \
  -H "Content-Type: application/json" \
  -d '{"firstName":"Test","lastName":"Sequence","address":"1 Test St","city":"Testville","telephone":"1234567890"}' \
  | python3 -m json.tool
```
Expected: HTTP 201 with a new owner whose `id` is `11` (the seeded data has owners `1`-`10`), not a primary-key-violation error and not `id: 1`.

- [ ] **Step 6: Verify `mode: always` reset behavior survives a restart**

```bash
docker compose restart customers-service
sleep 15
curl -s http://localhost:180/api/customer/owners | python3 -m json.tool
```
Expected: back to exactly the 10 seeded owners — the test owner created in Step 5 is gone, confirming schema/data reset on every start (not accidentally left on `mode: never`).

- [ ] **Step 7: Tear down**

```bash
docker compose down
```

No commit for this task — it's a verification-only pass. If any step fails, return to the relevant earlier task and fix it there (with its own commit) rather than patching ad hoc.
