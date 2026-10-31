# THE VEIL — Werewolf Card Distribution

Responsive HOST/PLAYER app for private role dealing. The UI follows the Google Stitch project `18075582992733824381` (four mobile screens and The Veil design system).

## Run

Requirements: Docker Compose v2. From the project root, copy the visible `env.example` to `.env` and set a unique `POSTGRES_PASSWORD` (and `HOST_ORIGIN` when opening the app from a phone or non-localhost browser origin):

```sh
cp env.example .env
docker compose up --build
```

Open <http://localhost:5173>. Backend health: <http://localhost:8080/actuator/health>. PostgreSQL data is stored in the `postgres_data` volume. Redis is included for future session/pub-sub use; PostgreSQL remains authoritative.

The host secret and player secret are returned once when creating/joining a room. Keep them private. The browser keeps the active session in session storage. A room code alone does not grant access to dealt roles.

## Local development

Run PostgreSQL and Redis with `docker compose up postgres redis -d`, then run the backend with `cd backend && mvn spring-boot:run` and frontend with `cd frontend && npm install && npm run dev`. Configure `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `VITE_API_URL` as needed.

## Checks

```sh
cd backend && mvn verify
cd frontend && npm ci && npm run build
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), and [docs/API.md](docs/API.md).
