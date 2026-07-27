# Changes from Upstream

Fork of [`spring-petclinic/spring-petclinic-microservices`](https://github.com/spring-petclinic/spring-petclinic-microservices), adapted to serve as the business-service layer for an agent ops-troubleshooting sandbox.

Companion repos: [`lab-environment`](https://github.com/Jeromefromcn/lab-environment) (orchestration, chaos scenarios, evaluation) and [`ops-agent-toolkit-mcp`](https://github.com/Jeromefromcn/ops-agent-toolkit-mcp) (MCP tools the agent uses to query this system). See `lab-environment/CLAUDE.md`'s "Cross-repo contract" section for every value (KV paths, DB names, service names) shared across the three repos — that file is the source of truth, not this one.

**Status legend:** ✅ done and committed · 📋 planned, not implemented yet.

## Summary

| Area | Upstream | This fork | Status |
|---|---|---|---|
| Service discovery | Eureka (`discovery-server` module) | Consul (`spring-cloud-starter-consul-discovery`) | ✅ |
| Configuration | Config Server + Git-backed config repo | Consul KV (`spring-cloud-starter-consul-config`) | ✅ |
| Database | H2 (default) / MySQL (optional profile) | PostgreSQL | 📋 |
| Trace chain | Gateway → single service | Cross-service call so traces span 2+ hops | 📋 |
| Fault injection | None | Consul-KV-driven chaos toggles per service | 📋 |

## Detailed Changes

### 1. Removed Eureka + Config Server ✅

- Deleted `spring-petclinic-discovery-server` and `spring-petclinic-config-server` modules
- Removed Eureka client dependencies from all services
- Added `spring-cloud-starter-consul-discovery` + `spring-cloud-starter-consul-config` to each service's `pom.xml`
- Replaced `eureka.client.*` properties with `spring.cloud.consul.*` in each service's config
- Applied to all 6 remaining Maven modules, including `admin-server` and `genai-service` — required project-wide once Config Server was deleted, even though `lab-environment` never builds or deploys those two (see the contract table)
- Design/plan: `docs/superpowers/specs/2026-07-27-consul-migration-design.md`, `docs/superpowers/plans/2026-07-27-consul-migration.md`

### 2. Database: PostgreSQL 📋 planned, not implemented

- Not started. No `postgres` profile, no `postgresql` JDBC dependency, and no `db/postgres/` schema/data SQL exist yet in any service
- `lab-environment/scripts/init-consul-kv.sh` already seeds `config/<service>/data/db.host|db.port|db.name|db.user|db.password` per service — this fork does not read those keys yet
- Remaining work: add PostgreSQL driver dependency, a datasource profile that resolves `${db.host}`/`${db.port}`/`${db.name}` etc. from Consul KV, and translate `db/mysql/*.sql` to Postgres dialect, for `customers-service`, `vets-service`, `visits-service`

### 3. Chaos toggles 📋 planned, not implemented

- No `chaos/` Java package exists in any service yet
- What *has* landed: a guard excluding the `dataSource` bean from `ConfigurationPropertiesRebinder` (prevents a crash when Consul KV is written to), and a manual trigger script (`scripts/chaos/call_chaos.sh`)
- Remaining work: implement the toggle-reading watcher and the actual fault behavior (slow query, Redis timeout, forced downstream error) in `customers-service` and `visits-service`
- Convention already fixed (see `lab-environment/CLAUDE.md`): toggles read from Consul KV at `chaos/<service>/<name>` — a separate top-level prefix, not under `config/`, so Spring Cloud Consul Config's own watch mechanism never sees these writes. Must use a dedicated, lightweight Consul KV watcher (not `@ConfigurationProperties`/`ContextRefresher`) so changes apply without a restart
- Toggle names already reserved (must match `lab-environment/scripts/init-consul-kv.sh` and `scenarios/scenarios.yaml` once implemented):
  - `slow-query-enabled` — adds artificial delay before a DB query
  - `redis-timeout` — simulates Redis connection timeout
  - `downstream-error` — forces a cross-service call to return an error

### 4. Aggregation endpoint 📋 planned, not implemented

- Not started. Intent: add an endpoint in `customers-service` that calls `visits-service` server-side to assemble an owner's full visit history, to produce a real multi-hop trace (currently calls are single-hop through the gateway only)

## Explicitly Not Changed

- Core domain model (Owner, Pet, Vet, Visit) — untouched
- API Gateway routing logic — untouched except for Consul-based service resolution
- Frontend — untouched
- `admin-server`, `genai-service` — Consul-migrated for build consistency only (see item 1); not built or deployed by `lab-environment`, no further changes planned

## Rationale

See `ROADMAP.md` in the `lab-environment` repo, Phase 0–1, for why each of these changes was made.
