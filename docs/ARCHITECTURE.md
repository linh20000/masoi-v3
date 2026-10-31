# Architecture

The application is a modular monolith: React + TypeScript + Vite serves the mobile-first ritual-table UI; Spring Boot 3 / Java 21 exposes REST endpoints and an authenticated WebSocket endpoint; PostgreSQL stores durable room, player, card, and deal state. Redis is available as an optional ephemeral coordination/cache service and is not the source of truth.

Each room has a random six-character join code and a random host secret. Player sessions receive independent random secrets. Only SHA-256 digests of these secrets are stored. The API authenticates a room session with `Authorization: Bearer ...`; host-only actions require the host session. Player role data is exposed only from `/rooms/{code}/me` for the matching session. Public room state never contains player roles. The frontend proxies `/api` and `/ws` through its own origin so phone clients do not resolve `localhost` to the phone itself; set `HOST_ORIGIN` to the browser origin for WebSocket origin validation.

Room mutations run in database transactions with pessimistic room locking and a monotonically increasing version. Flyway owns schema evolution. Private role assignment is performed server-side, and no room-wide event includes private role payloads. WebSocket room notifications are public roster/version updates; clients refetch their own private role over authenticated REST.

The interface follows Stitch project `18075582992733824381`: #090909/#141313 surfaces, wine crimson #8F1D27, antique brass #B79A62, EB Garamond headings, Manrope body, and 2:3 occult card treatment. The currently available Stitch designs cover landing/join, host deck selection, host room management, and player card reveal.
