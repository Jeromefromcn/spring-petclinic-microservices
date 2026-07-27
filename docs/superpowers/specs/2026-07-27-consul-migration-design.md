# Consul Migration Design

Sub-project 1 of 4 from `CHANGES.md` (Eureka + Config Server → Consul). This is the
first of a sequenced set of changes; PostgreSQL migration, chaos toggles, and the
aggregation endpoint follow in later specs.

## Context

The repo currently has:
- `spring-petclinic-discovery-server` — Eureka server (`@EnableEurekaServer`)
- `spring-petclinic-config-server` — Spring Cloud Config Server (`@EnableConfigServer`),
  backed by the external git repo `spring-petclinic/spring-petclinic-microservices-config`
- All other services (`customers-service`, `visits-service`, `vets-service`,
  `api-gateway`, `admin-server`, `genai-service`) depend on both via
  `spring-cloud-starter-netflix-eureka-client` + `spring-cloud-starter-config`, and
  import config via `spring.config.import: optional:configserver:...`

None of the local `application.yml` files override actual values (Eureka URLs,
datasource strings) — those come from the external config repo + Spring Cloud
defaults. Since we're dropping the config-server indirection, those values move
into each service's own `application.yml`, split by profile (default = localhost,
`docker` profile = container hostnames), matching the current pattern already used
for `spring.config.import`.

`api-gateway` and `admin-server` use the discovery-mechanism-agnostic
`@EnableDiscoveryClient` annotation, so no Java code changes are needed in them —
only dependency and configuration changes are required anywhere in the repo.

`spring-cloud-consul-dependencies` 5.0.0 (providing `spring-cloud-starter-consul-discovery`
and `spring-cloud-starter-consul-config`) is part of the `spring-cloud-dependencies`
BOM already imported at version 2025.1.0, so no extra version needs to be declared.

## Design

### Consul deployment
- Single-node Consul in dev mode: official `hashicorp/consul` image, running
  `consul agent -dev -client=0.0.0.0`.
- Replaces the `config-server` and `discovery-server` services in `docker-compose.yml`.
- No KV seeding script for this sub-project — Consul KV stays empty until the
  chaos-toggles sub-project starts writing `config/<service>/data/chaos.*` keys.
  Static config lives directly in each service's `application.yml`, not in Consul KV.

### Module removal
- Delete `spring-petclinic-discovery-server/` and `spring-petclinic-config-server/`
  entirely.
- Remove both `<module>` entries from the root `pom.xml`.

### Per-service changes (customers-service, visits-service, vets-service,
api-gateway, admin-server, genai-service)
- `pom.xml`: remove `spring-cloud-starter-config` and the Eureka client/server
  starter; add `spring-cloud-starter-consul-discovery` and
  `spring-cloud-starter-consul-config`.
- `application.yml`: replace `spring.config.import: optional:configserver:...`
  with the Consul config equivalent, and add
  `spring.cloud.consul.host` / `spring.cloud.consul.port`
  (default `localhost:8500`; `docker` profile → `consul:8500`, matching the existing
  profile-split pattern already in each file).

### Other files
- Root `pom.xml`: drop the two module entries.
- `docker-compose.yml`: remove `config-server`/`discovery-server` service blocks,
  add a `consul` service with a healthcheck, repoint every other service's
  `depends_on` at `consul`.
- `scripts/run_all.sh`, `scripts/pushImages.sh`, `scripts/tagImages.sh`: drop the
  config-server/discovery-server build/push/tag/launch steps.
- `README.md`: remove references to the two deleted modules/ports where present.

## Testing
- `./mvnw clean package -pl <module>` succeeds for every touched module.
- `docker compose up consul customers-service visits-service vets-service
  api-gateway admin-server` — verify services register in the Consul UI (port 8500)
  and that `api-gateway`'s `lb://` routes still resolve and forward correctly.

## Out of scope (future sub-projects, per CHANGES.md)
- PostgreSQL migration
- Chaos toggles (Consul-KV-driven, `@RefreshScope`)
- Cross-service aggregation endpoint
