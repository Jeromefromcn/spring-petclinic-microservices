# CLAUDE.md

Keep any additions to this file short and direct.

## Project

Fork of `spring-petclinic/spring-petclinic-microservices`, adapted as the business-service layer for an agent ops-troubleshooting sandbox. Orchestration and chaos scenarios live in the sibling `lab-environment` repo.

## Cross-repo contract

This repo, `lab-environment`, and `ops-agent-toolkit-mcp` don't share Claude Code session context — a session working here has no memory of what the other two assume. Before touching Consul KV paths, DB names, service names, or chaos toggle names, read `lab-environment/CLAUDE.md`'s "Cross-repo contract" table — that file is the single source of truth, not this one.

## What changed from upstream

See `CHANGES.md` for the full list. Summary: replaced Eureka + Config Server with Consul (discovery + KV config), switched to PostgreSQL, added chaos toggles driven by Consul KV, added a cross-service aggregation endpoint for a longer trace chain.

## Key commands

- `./mvnw clean package -pl <module>` — build a single service module
- `./mvnw clean package` — build all modules
- No compose file lives here. Images are built and tagged from `lab-environment/scripts/build.sh`.

## Conventions

- Chaos toggles live in a `chaos/` package per service, read from Consul KV at `chaos/<service>/<name>` — a separate top-level prefix from `config/`, so Spring Cloud Consul Config's built-in watch/refresh never triggers on toggle changes. A dedicated lightweight watcher (not `@ConfigurationProperties`/`ContextRefresher`) polls this prefix directly and updates an in-memory flag, so toggles apply live without a restart and without the app-wide config-rebind cost.
- Chaos behavior is always gated behind a Consul KV flag, default OFF — never hardcode it on.
- This is a fork, not a rewrite. Prefer small, localized diffs that stay easy to compare against upstream. Log every deviation in `CHANGES.md`.
