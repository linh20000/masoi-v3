# THE VEIL — Werewolf Card Distribution

Responsive HOST/PLAYER app for private role dealing. The UI follows the Google Stitch project `18075582992733824381` (four mobile screens and The Veil design system).

## Run

Requirements: Docker Compose v2, Node.js 22+, and npm. Docker runs MySQL only; run the backend and frontend directly on the machine. Copy `env.example` to `.env` and set unique MySQL passwords (and `HOST_ORIGIN` when opening the app from a phone or non-localhost browser origin):

```sh
cp env.example .env
docker compose up -d mysql
```

Start these in separate terminals from the project root:

```sh
cd backend && npm ci && npm run dev
```

```sh
cd frontend && npm ci && npm run dev
```

Open <http://localhost:5173>. Backend health: <http://localhost:8080/actuator/health>. MySQL stores application data in the `mysql_data` volume. REST and authenticated WebSocket paths are unchanged.

### Copy existing PostgreSQL data

To copy data from an existing PostgreSQL database, keep that database running and reachable from the machine. Back it up first, start MySQL, then apply the MySQL schema and copy existing room data from a backend terminal:

```sh
docker compose up -d mysql
cd backend
npm ci
npm run migrate
LEGACY_PG_URL='postgresql://user:password@localhost:5432/theveil' npm run migrate:postgres-data
```

The copy is repeatable and leaves PostgreSQL untouched. `LEGACY_PG_URL` can point to a local or remote PostgreSQL database.

The host secret and player secret are returned once when creating/joining a room. Keep them private. The browser keeps the active session in session storage. A room code alone does not grant access to dealt roles.

## Local development

The root `.env` file is loaded by the backend. Configure `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, and `HOST_ORIGIN` as needed. Vite proxies API and WebSocket requests to `http://127.0.0.1:8080` by default; set `VITE_BACKEND_URL` in `frontend/.env.local` to override it.

## Checks

```sh
cd backend && npm run build
cd frontend && npm ci && npm run build
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), and [docs/API.md](docs/API.md).
