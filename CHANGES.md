# Changes from Upstream

Fork of [`spring-petclinic/spring-petclinic-microservices`](https://github.com/spring-petclinic/spring-petclinic-microservices), adapted to serve as the business-service layer for an agent ops-troubleshooting sandbox.

Companion repos: [`lab-environment`](https://github.com/Jeromefromcn/lab-environment) (orchestration, chaos scenarios, evaluation) and [`ops-agent-toolkit-mcp`](https://github.com/Jeromefromcn/ops-agent-toolkit-mcp) (MCP tools the agent uses to query this system). See `lab-environment/CLAUDE.md`'s "Cross-repo contract" section for every value (KV paths, DB names, service names) shared across the three repos — that file is the source of truth, not this one.

**Status legend:** ✅ done and committed · 📋 planned, not implemented yet.

## Summary

| Area | Upstream | This fork | Status |
|---|---|---|---|
| Service discovery | Eureka (`discovery-server` module) | Consul (`spring-cloud-starter-consul-discovery`) | ✅ |
| Configuration | Config Server + Git-backed config repo | Consul KV (`spring-cloud-starter-consul-config`) | ✅ |
| Database | H2 (default) / MySQL (optional profile) | PostgreSQL | ✅ |
| Trace chain | Gateway → single service | Cross-service call so traces span 2+ hops | ✅ |
| Fault injection | None | Consul-KV-driven chaos toggles per service | ✅ |

## Detailed Changes

### 1. Removed Eureka + Config Server ✅

- Deleted `spring-petclinic-discovery-server` and `spring-petclinic-config-server` modules
- Removed Eureka client dependencies from all services
- Added `spring-cloud-starter-consul-discovery` + `spring-cloud-starter-consul-config` to each service's `pom.xml`
- Replaced `eureka.client.*` properties with `spring.cloud.consul.*` in each service's config
- Applied to all 6 remaining Maven modules, including `admin-server` and `genai-service` — required project-wide once Config Server was deleted, even though `lab-environment` never builds or deploys those two (see the contract table)
- Design/plan: `docs/superpowers/specs/2026-07-27-consul-migration-design.md`, `docs/superpowers/plans/2026-07-27-consul-migration.md`

### 2. Database: PostgreSQL ✅

- `customers-service`, `vets-service`, and `visits-service` now run against PostgreSQL. Added the `postgresql` JDBC driver dependency, removed the `mysql` profile and `db/mysql/*.sql` files entirely, and added `db/postgresql/{schema,data}.sql` (applied via `spring.sql.init.mode: always`, so every service start resets to a known dataset)
- `lab-environment/scripts/init-consul-kv.sh` seeds `config/<service>/data/db.host|db.port|db.name|db.user|db.password` per service. Spring Cloud Consul Config's KEY_VALUE format derives property names from the KV path relative to `config/<service>/`, so KV key `config/<service>/data/db.host` becomes Spring property `data.db.host` — **not** `db.host`. `application.yml`'s datasource block references `${data.db.host}`/`${data.db.port}`/`${data.db.name}`/`${data.db.user}`/`${data.db.password}` accordingly (see comment above `spring.datasource` in each service's `application.yml`)
- `spring.config.import` stays `"optional:consul:"` (not a hard `consul:` import) even for these datasource properties: if Consul is unreachable or unseeded, the service still fails to start (Hikari rejects an incomplete/empty JDBC URL) — that's intentional, since this fork is an ops-troubleshooting sandbox where a Consul-dependency failure is itself a realistic scenario worth reproducing, not a defect to engineer away
- Design/plan: `docs/superpowers/specs/2026-07-28-postgresql-migration-design.md`, `docs/superpowers/plans/2026-07-28-postgresql-migration.md`

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

## Explicitly Not Changed

- Core domain model (Owner, Pet, Vet, Visit) — untouched
- API Gateway routing logic — untouched except for Consul-based service resolution
- Frontend — untouched
- `admin-server`, `genai-service` — Consul-migrated for build consistency only (see item 1); not built or deployed by `lab-environment`, no further changes planned

## Rationale

See `ROADMAP.md` in the `lab-environment` repo, Phase 0–1, for why each of these changes was made.
