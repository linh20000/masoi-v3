# Implementation plan and status

Backend/database transition: Spring Boot + PostgreSQL have been replaced by Node.js/TypeScript + MySQL. REST and WebSocket contracts remain the same. Docker Compose runs MySQL only; backend and frontend run as separate local processes. MySQL migrations create the existing schema and role catalog; `npm run migrate:postgres-data` copies existing PostgreSQL room/session data when the old database is available. Runtime verification is pending with a running MySQL service.

1. Survey source, runtime and Stitch assets — complete. Starting directory contained only `SOURCE_pack.md`; Stitch project is accessible and has four mobile screens plus The Veil design system.
2. Foundation — complete: React/Vite, Spring Boot, PostgreSQL/Redis Compose and environment template.
3. Durable schema, room creation/join and hashed session authorization — complete.
4. Deck configuration, transactional deal/recall and private role access — complete in code; PostgreSQL runtime check awaits Docker daemon.
5. Authenticated real-time public room updates and reconnect refresh — complete in code; WebSocket handshake requires configured browser origin.
6. Stitch-aligned responsive host/player UI and card reveal — implemented; the Stitch-hosted portrait assets are used on role cards, while roles without a Stitch portrait use the occult sigil placeholder.
7. Special-role selections — Wild Child, Half-Wolf and Shadow choices are persisted privately; Actor/Thief reserve-card choices wait for room reserve-card configuration. No role transitions or night automation are inferred.
8. Checks — backend Maven verify and frontend production build pass. Docker runtime unavailable; frontend interaction test dependencies could not be fetched while npm registry DNS was unavailable. `npm audit` reports two Vite/esbuild advisories and the patched major-version toolchain could not be fetched during this run.

The full night/game engine is explicitly out of scope. Thief/Actor choices that depend on unused physical cards and rules variants remain configurable work until the session rules are clarified in product data.
