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

## VPS deployment

On a VPS, do not use `npm run dev`. Build the backend once, then start the compiled server. `tsx` is a development dependency, which is why it is missing after a production-only install.

1. Install Node.js 22+, Docker Compose, and Nginx. Clone the project, create the root `.env`, and set strong MySQL passwords, `MYSQL_HOST=127.0.0.1`, `MYSQL_PORT=3307`, `MYSQL_BIND_PORT=3307`, `HOST=127.0.0.1`, and `HOST_ORIGIN=https://your-domain.example`.
2. Start the database: `docker compose up -d mysql`.
3. Build and start the backend:

```sh
cd backend
npm ci
npm run build
npm ci --omit=dev
npm start
```

For a persistent service, use the VPS Node.js application manager with startup command `npm start`, or run it under a process manager such as PM2.

4. Build the frontend:

```sh
cd frontend
npm ci
npm run build
```

5. Configure Nginx to serve `frontend/dist` and proxy `/api/` and `/ws/` to `127.0.0.1:8080`. A starter server block is in [deploy/nginx-vps.conf](deploy/nginx-vps.conf); replace the domain and document root, then configure HTTPS for the domain.

The Vite proxy is only for local development. On the VPS, Nginx provides the same-origin proxy, so browser requests stay under your domain while REST and WebSocket traffic reaches the local backend. MySQL is published only on loopback port 3307.

## Checks

```sh
cd backend && npm run build
cd frontend && npm ci && npm run build
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md), and [docs/API.md](docs/API.md).
