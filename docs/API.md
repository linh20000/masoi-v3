# API

Base path: `/api`. All room endpoints require `Authorization: Bearer <host-or-player-secret>` except create and join. Secrets are returned only on the create/join response.

* `POST /rooms` `{hostName}` → public room plus `sessionToken`.
* `POST /rooms/{code}/join` `{displayName}` → public room plus player id and `sessionToken`.
* `GET /rooms/{code}` → public roster, deck counts, status, and version (no role names).
* `PUT /rooms/{code}/deck` `{cards:[{roleId,count}]}` → host-only configuration.
* `POST /rooms/{code}/deal` → host-only transactional shuffle/deal; card count must equal connected roster size.
* `POST /rooms/{code}/recall` `{playerIds?:[]}` → host-only selective or all-card recall.
* `POST /rooms/{code}/end` → host-only terminal close; current player secrets can no longer retrieve roles. Recall returns the room to its lobby; ending does not.
* `GET /rooms/{code}/me` → current session's private card only.
* `POST /rooms/{code}/special-choice` → private holder choice for Wild Child/Shadow target or Half-Wolf faction. Actor/Thief return `RESERVE_CARDS_NOT_CONFIGURED` until reserve cards are configured.
* WebSocket `/ws/rooms/{code}` → send `{type:"AUTH",token:"..."}` as the first frame. After authentication, clients receive public state notifications; private role payloads are never broadcast. Tokens are sent in the first frame rather than the URL so access logs do not capture them.

Errors use HTTP status codes and `{code,message}` JSON. Room join links use `/?join=<ROOMCODE>` and the UI also supports manual entry.
