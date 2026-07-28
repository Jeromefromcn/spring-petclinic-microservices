# Aggregation Endpoint + Chaos Toggles — Design

Status: approved. Covers the two remaining 📋 items in `CHANGES.md`: the cross-service
aggregation endpoint and the Consul-KV-driven chaos toggles.

## Context

- `CHANGES.md` items 3 and 4 are the only planned-but-not-implemented changes left in this fork.
- The aggregation endpoint is built first because the `downstream-error` chaos toggle
  (customers-service) needs a real cross-service call to target — there isn't one yet.
- Every naming convention here (KV prefixes, toggle names, service names, DB/redis KV keys)
  is fixed by `lab-environment/CLAUDE.md`'s cross-repo contract table, not decided locally.

## 1. Aggregation endpoint (customers-service)

**Endpoint**: `GET /owners/{ownerId}/visits`

**Response shape**: owner fields + `pets[]`, each pet carrying its `visits[]`. New DTOs in
`web`: `OwnerVisitsResponse`, `PetVisitsResponse`, `VisitResponse` — kept separate from the
JPA entities (`Owner`, `Pet`) so cross-service response shape doesn't leak into the domain model.

**Flow**:
1. `OwnerVisitsResource` loads the `Owner` via the existing `OwnerRepository`, 404s via the
   existing `ResourceNotFoundException` if not found.
2. Collects pet IDs, calls `VisitsServiceClient.getVisitsForPets(petIds)`.
3. `VisitsServiceClient` is a new `@Component` using a `@LoadBalanced RestTemplate` bean
   (new bean, customers-service has none yet; `spring-cloud-starter-loadbalancer` is already
   on the classpath transitively via `spring-cloud-starter-consul-discovery`) — calls
   `http://visits-service/pets/visits?petId=...`, same endpoint the gateway already uses.
   Mirrors `api-gateway`'s `VisitsServiceClient`, blocking instead of reactive since
   customers-service is a plain Web MVC app.
4. Merges visits onto pets by `petId`; pets with no visits get an empty list.

**Timeouts & errors**: `RestTemplateBuilder` gets explicit `connectTimeout(2s)` /
`readTimeout(5s)` so a dead or slow visits-service can't hang the request indefinitely.
Any `RestClientException` (timeout, connection refused, 5xx) is wrapped in a new
`DownstreamServiceException`, annotated `@ResponseStatus(HttpStatus.BAD_GATEWAY)` — same
pattern as the existing `ResourceNotFoundException`.

**Testing**: `@WebMvcTest` for `OwnerVisitsResource` (mocked `VisitsServiceClient`), plus a
unit test for `VisitsServiceClient` against a mocked `RestTemplate`/`MockRestServiceServer`.

## 2. Chaos toggles (customers-service + visits-service)

Per-service `chaos/` package, duplicated in both services (not a shared module — matches
the existing per-service duplication of `config/MetricConfig`). Each service gets:

- `ChaosToggles` — in-memory `ConcurrentHashMap<String, Boolean>`, all toggles default
  `false` until the first successful poll (fail-safe: chaos is off if Consul is unreachable).
  Exposes `isEnabled(String name)`.
- `ChaosToggleWatcher` — `@Scheduled(fixedDelay = 5000)` bean that polls Consul KV under
  `chaos/<service>/` directly via `com.ecwid.consul.v1.ConsulClient` (already on the classpath
  via `spring-cloud-starter-consul-config`), **not** through Spring Cloud Consul Config /
  `ContextRefresher` — this is the point of the separate `chaos/` prefix per the cross-repo
  contract. Builds the `ConsulClient` from the same `spring.cloud.consul.host/port` properties
  already configured for discovery/config.
- Toggle names are exactly those reserved in `lab-environment/CLAUDE.md`'s contract table;
  nothing new is introduced here.

**customers-service**:
- `slow-query-enabled` — `OwnerResource`'s repository reads (`findOwner`, `findAll`) sleep
  ~3s when enabled.
- `downstream-error` — `VisitsServiceClient` checks the toggle first; if enabled, throws
  `DownstreamServiceException` immediately without calling visits-service. Deterministic —
  doesn't depend on any real network fault injection.

**visits-service**:
- `slow-query-enabled` — `VisitResource.read` (the `pets/visits?petId=` endpoint, used by
  both the gateway and the new aggregation call) sleeps ~3s before the repository call.
- `redis-timeout` — see below.

## 3. Redis cache-aside (visits-service)

Real Redis client, per your choice — not simulated end to end, but the *timeout itself* is
deterministically injected (see below), since genuine network-layer sabotage is explicitly
deferred to the future Toxiproxy phase in `lab-environment`'s ROADMAP.

- New dependency: `spring-boot-starter-data-redis` (Lettuce).
- Config: `spring.data.redis.host/port: ${data.redis.host}/${data.redis.port}` — this KV
  path is **already seeded** by `lab-environment/scripts/init-consul-kv.sh`
  (`config/visits-service/data/redis.host|redis.port`); no cross-repo change needed.
- Cache-aside wraps `VisitResource.read`'s repository call (`findByPetIdIn`): key
  `visits:pet:{petId}`, JSON-serialized visit list, 60s TTL. Cache is queried per pet ID;
  only misses hit the DB; misses get written back to cache.
- `VisitResource.create()` evicts `visits:pet:{petId}` for the affected pet on write, so the
  cache can't go stale and produce a correctness bug unrelated to any chaos toggle.
- **Genuine** Redis unavailability (e.g., local dev without Redis running) is caught around
  the real cache calls and falls back to the DB silently, logged at `warn` — ordinary
  cache-aside hygiene, orthogonal to the chaos toggle.
- **`redis-timeout` toggle path** is checked before the real cache-aside logic runs: when
  enabled, sleeps ~3s then throws (not falls back) — surfacing a real, visible 500/slow
  response. Deliberately not masked by the fallback-to-DB path above: a chaos toggle whose
  effect is invisible defeats the purpose of an RCA-training scenario.

**Testing**: unit tests for the cache-aside component (embedded/mocked Redis or a fake
`RedisTemplate`) covering hit/miss/eviction/toggle-on paths; `VisitResource` tests updated
for the new collaborator.

## Explicitly out of scope

- Toxiproxy / real connection-layer fault injection — future `lab-environment` phase.
- Any change to `docker-compose.yml`, `init-consul-kv.sh`, or `scenarios.yaml` — those live
  in `lab-environment` and are already seeded/correct for what this spec implements.
- `vets-service` — no chaos toggles reserved for it in the contract table.
