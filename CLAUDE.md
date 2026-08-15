# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`sifen-wrapper` is a multi-tenant Spring Boot REST API that wraps [`rshk-jsifenlib`](https://github.com/roshkadev/rshk-jsifenlib) for Paraguayan electronic invoicing (SIFEN / SET — DNIT). Multiple companies ("empresas") operate against SIFEN independently, each with its own PFX certificate, CSC, and ambiente (DEV/PROD). Tenant isolation is by `companyId`, resolved from the JWT or API Key on every request — never trust a `companyId` from the request body.

## Commands

```bash
# Run locally (Flyway migrates on startup; requires local Postgres db `sifen`)
mvn spring-boot:run

# Build a deployable jar
mvn clean package -DskipTests

# Compile only
mvn compile
```

There is no test suite in this repo (`src/test` does not exist) — do not assume `mvn test` verifies behavior.

Java 17, Maven, Spring Boot 3.2.3. Lombok is used throughout (`@Slf4j`, `@RequiredArgsConstructor`, `@Builder`, `@Getter/@Setter`) — check for it before writing boilerplate by hand.

## Architecture

### Request flow / multi-tenancy

Two parallel auth mechanisms feed a single tenant context:

- **JWT** (`security/jwt/JwtAuthenticationFilter`) — for panel users managing companies, users, API keys (`/companies/**`, requires `ROLE_ADMIN`).
- **API Key** (`security/apikey/ApiKeyAuthenticationFilter`, header `X-API-Key`) — for server-to-server invoicing (`/invoices/**`, `ROLE_USER`). The key alone determines the tenant; no `companyId` header is needed or trusted.

Both filters put the resolved `companyId` into the Spring `Authentication`'s *credentials*. `security/TenantFilter` runs after both and copies that into `security/TenantContext`, a `ThreadLocal<Long>` cleared at the end of every request. Services read the current tenant via `TenantContext.get()` — this is the mechanism that keeps companies isolated, not a `companyId` parameter passed around explicitly. See `SecurityConfig` for the exact filter chain and route authorization rules.

### SIFEN library integration is single-lock, cache-per-tenant

`rshk-jsifenlib` keeps its config in a **static field** (`Sifen.setSifenConfig(...)`), so it is not thread-safe across tenants. `SifenConfigFactory`:
- builds a `SifenConfig` per company (cert PFX written to a temp file, CSC, ambiente, NT13 flag — cert password and CSC are decrypted via `EncryptionService`), cached in a Caffeine cache (5 min TTL, keyed by `companyId`); call `evict(companyId)` after changing a company's cert/CSC/ambiente.
- exposes `withSifenConfig(config, callable)`, which acquires a single global `ReentrantLock`, sets the static config, runs the callable, and releases the lock. **Every** call into `Sifen.*` (recepcionDE, recepcionLoteDE, consultaDE, consultaLoteDE, consultaRUC, recepcionEvento) must go through this method — calling the library directly bypasses the lock and risks cross-tenant config corruption.

`EncryptionService` does AES-256-GCM for cert passwords and CSC values at rest (`security.encryption.key`, 32-byte base64).

### The two invoicing flows

`POST /invoices/emit` (synchronous, direct to SIFEN) is **deprecated in PROD** — SIFEN doesn't enable synchronous reception for most emitters there. The supported production path is prepare + async batch:

1. `POST /invoices/prepare` — validates, generates signed XML + CDC + QR **without contacting SIFEN**, persists an `ElectronicDocument` row with `estado=PREPARADO`, returns in <200ms. `InvoiceService.prepararDE`/`resolveParams` auto-fills emisor `params` from the company's stored `emisorConfig` (`Company.emisorConfig`, `PUT /companies/{id}/emisor`) if not sent explicitly, and always validates the request's `params.ruc` matches the authenticated tenant's RUC (`validateTenantRucMatch`) — a request can never emit as a different company than the one the API Key/JWT belongs to.
2. `BatchSenderService` (`@Scheduled`, `sifen.batch.send-interval`, default 60s) groups `PREPARADO` docs by company + tipoDocumento, sends sublotes of `sifen.batch.max-per-lote` (default 50) via `Sifen.recepcionLoteDE`, moves them to `ENVIADO`.
3. `BatchPollerService` (`@Scheduled`, `sifen.batch.poll-interval`, default 600s) polls `Sifen.consultaLoteDE` for lotes at least `min-wait-before-poll` seconds old. On lote code `0362` (concluido) it reads each CDC's result **directly from the lote response** — it deliberately does not call `consultaDE` per-CDC here, because that endpoint can return a false "no existe" (`0422`) while SIFEN's internal state is still converging (see `docs/erp-polling-rechazado-falso.md` for the incident that caused this). Lotes older than `max-poll-age-hours` (default 48h, SIFEN code `0364`) fall back to individual `consultaDE` per CDC.
4. `GET /invoices/{cdc}/status[?refresh=true]` reads the local DB by default; `refresh=true` only re-queries SIFEN when the doc is still `ENVIADO`, and reproduces the same "check lote state before trusting consultaDE" logic as step 3 (see `InvoiceService.consultarEstadoLocal`).

When a document transitions into an approved state (`APROBADO*`), `InvoiceEmailService` sends the KUDE by email automatically (see `docs/correo-electronico-y-api-key.md`); `POST /invoices/{cdc}/resend-email` re-triggers it manually.

Document states: `PREPARADO → ENVIADO → APROBADO | APROBADO_CON_OBSERVACION | RECHAZADO`, plus `ERROR`, `CANCELADO`, `INUTILIZADO`. `ElectronicDocument.cdc` is unique per company; re-`prepare`-ing the same establecimiento+punto+número+timbrado+fecha combination yields the same CDC and a 400 (see `docs/cdc-duplicado-400.md`) — this is intentional (fiscal correlativos must never be reused), not a bug to "fix" by relaxing the constraint.

### Events (`siRecepEvento`)

`POST /invoices/events` sends the 6 event types the library can emit (cancelación, inutilización, conformidad/disconformidad/desconocimiento del receptor, notificación de recepción) via `EventoService` — `EventoValidator` (business rules: 48h cancellation window fixed and non-configurable, motivo ≥15 chars, inutilización range ≤1000, tenant checks) → `EventoBeanFactory` (pure bean construction) → `SifenEventRecorder` (the only `@Transactional` boundaries in the subdomain, always `REQUIRES_NEW`, called from a separate bean so the annotation can't go inert the way `BatchSenderService.enviarSublote`'s self-invocation does). Every event is persisted in `sifen_events` (`entity/SifenEvent`, migration V16) — a `sifen_event_id_seq` Postgres sequence supplies the `rEve/@Id` (`tdIdEve`) and the row commits **before** the SIFEN call, so a mid-call crash still leaves a record of what may have been sent.

Emisor events (1, 2) require the CDC/range to belong to the authenticated company; receptor events (3-6) act on a DE issued by *another* company, so they deliberately do **not** require a local `ElectronicDocument` — tenant safety there is enforced via `rucReceptor` matching the authenticated company, not via CDC ownership. On approval, cancellation sets the document to `CANCELADO` and inutilización sets in-range non-approved documents to `INUTILIZADO`; receptor events never touch `electronic_documents`.

A SIFEN timeout on this synchronous call is never retried automatically — the event is marked `INDETERMINADO` and the caller is told to use `POST /invoices/events/{id}/reconcile` instead (which, like `BatchPollerService`, treats `consultaDE`'s `0422` as inconclusive rather than a negative — see `docs/erp-polling-rechazado-falso.md`). There is intentionally no `@Scheduled` poller for `INDETERMINADO` events: SIFEN exposes no event-query service, so reconciliation can only ever confirm a positive for cancelación, and an automatic wrong conclusion on a fiscal event is worse than an unresolved one. See `docs/eventos-sifen.md` for the full design.

### Patched SIFEN types (`patch/` package)

`rshk-jsifenlib` 0.2.4 has serialization bugs that get rejected by SIFEN (e.g. `TgTimbPatched` zero-pads `dNumTim` to 8 digits, which the stock class doesn't). These are workarounds for a fixed library version, not application logic — check `SifenMapper` to see where the patched classes get substituted, and don't "simplify" them back to the unpatched originals.

### Layering

`controller` → `service` → (`repository` for persistence, or the `SifenConfigFactory`/`com.roshka.sifen.Sifen` static API for SIFEN calls) → `mapper.SifenMapper` translates between wrapper DTOs (`dto/request`, `dto/response`) and `rshk-jsifenlib` beans (`com.roshka.sifen.core.*`). `com/roshka/sifen/...` at the repo root (outside `src/`) holds small local overrides of library classes with bugs — treat like the `patch/` package.

All controller responses use the `SifenApiResponse<T>` envelope (`{success, message, data}`); `GlobalExceptionHandler` maps exceptions to it — `SifenServiceException` → 502, `IllegalArgumentException`/validation/bad JSON → 400, `NullPointerException` → 400 (treated as a missing required field, not a bug), anything else → 500.

### Company / user / auth model

- `Company` (RUC, dv, ambiente, cert, CSC, `emisorConfig`) can legitimately repeat the same `ruc`+`dv`+`ambiente`+`nombre` across rows — isolation is by `companyId`, not RUC uniqueness (see `V9`/`V10` migrations).
- `User` ↔ `Company` is many-to-many through `UserCompanyMembership`, which carries the `Role` (`ADMIN`/`USER`) and an `active` flag per membership — a user's role is per-company, not global.
- `ApiKey` belongs to one `Company`; `ApiKeyAuthenticationFilter` grants `ROLE_USER` regardless of the issuing user's role.
- On first boot, `AdminSeeder` creates a default "Administración" company (RUC `00000000`) and `admin@synctema.com` / `admin123` — must be changed before real production use.

### Migrations

Flyway, `src/main/resources/db/migration`, sequential `V<n>__description.sql`. `hibernate.ddl-auto=validate` — entities must match migrations exactly; schema changes always go through a new migration, never by relying on Hibernate to auto-create/alter.

### Config

`security.jwt.secret`, `security.encryption.key`, `DB_PASS` all have insecure dev defaults baked into `application.yml` — production deploys must override them via env vars (`JWT_SECRET`, `ENCRYPTION_KEY`, `DB_PASS`). `sifen.batch.*` controls the scheduler cadence and batch size (see Configuración in [README.md](README.md)).

## Docs worth reading before touching related code

- [docs/erp-integration-guide.md](docs/erp-integration-guide.md) — the prepare/batch/poll contract from the ERP's point of view, including the state machine and CDC-duplicate handling.
- [docs/erp-polling-rechazado-falso.md](docs/erp-polling-rechazado-falso.md) — root cause of a real false-RECHAZADO incident; explains why `BatchPollerService`/`consultarEstadoLocal` read lote results before ever calling `consultaDE`. Read this before changing status-refresh logic.
- [docs/cdc-duplicado-400.md](docs/cdc-duplicado-400.md) — why duplicate CDC is a 400 by design.
- [docs/eventos-sifen.md](docs/eventos-sifen.md) — event subdomain design: persistence, the `sifen_event_id_seq` → `rEve/@Id` contract, emisor vs receptor tenant checks, and why `INDETERMINADO` reconciliation is on-demand only, never automatic.
- [docs/kude.md](docs/kude.md), [docs/metodos-de-pago.md](docs/metodos-de-pago.md), [docs/correo-electronico-y-api-key.md](docs/correo-electronico-y-api-key.md) — field-level contracts for KUDE generation, payment methods, and email/API key usage respectively.
- [DEPLOY_VPS_UBUNTU_NGINX.md](DEPLOY_VPS_UBUNTU_NGINX.md) — production deploy is a systemd service behind Nginx on a VPS (not containerized); build locally with `mvn clean package -DskipTests`, `scp` the jar, `systemctl restart sifen-wrapper`.
