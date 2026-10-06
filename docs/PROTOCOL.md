# Wire protocol

Clients and the relay server talk over plain WebSockets. The reference implementation is the `protocol` module
(`protocol/src/commonMain`), which both sides use, so this document and the code cannot drift apart.

## Endpoints

| Endpoint | Purpose |
| -------- | ------- |
| `WS /ws/host` | Start a stream, or reclaim one after a dropped connection |
| `WS /ws/watch/{code}` | Watch the stream of room `{code}` |
| `GET /health` | Returns `ok` |
| `GET /api/config` | `{"discordClientId": "…" \| null}` for the web app |

### `/ws/host` query parameters

| Parameter | Meaning |
| --------- | ------- |
| `room` | Ask for a specific 6-character code (otherwise the server picks one). Used by Discord Activities |
| `token` | Room token, to take back an existing room after a dropped connection |
| `password` | Optional password viewers must give |
| `announce` | `1` to announce on Discord (needs a webhook on the server) |
| `name` | Display name for the announcement (max 32 characters) |

### `/ws/watch/{code}` query parameters

| Parameter | Meaning |
| --------- | ------- |
| `password` | The room password, if it has one |

## Room codes

Six characters from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (no `0/O/1/I`). Case-insensitive on input.

## Text messages (server → client)

| Message | Sent to | Meaning |
| ------- | ------- | ------- |
| `room:<code>:<token>` | host | Room is open. Keep the token to reconnect |
| `viewers:<n>` | host | The audience size changed |
| `joined` | viewer | You are in; frames follow |
| `ended` | viewer | The host stopped |
| `error:<reason>` | either | Refused, then the server closes the connection |

`reason` is one of `not_found`, `bad_room`, `room_taken`, `room_full`, `bad_password`, `server_busy`.

Client → server text: `bye` (host only), meaning "I am stopping on purpose": the room closes at once instead of
waiting for the host to return.

## Video frames (binary messages)

```text
byte 0      version (1)
bytes 1-2   width,  unsigned big-endian
bytes 3-4   height, unsigned big-endian
bytes 5..   JPEG image
```

The host sends them, the server validates (version, size, JPEG magic bytes, at most 4 MiB) and relays them unchanged
to every viewer. Each viewer has a two-frame buffer that drops the oldest frame, so a slow viewer never slows the host
or other viewers down. A viewer that joins mid-stream immediately gets the latest frame.

## Reconnecting

If a host's connection drops without `bye`, the room stays open for the grace period (`HOST_GRACE_SECONDS`, default 20).
The host reconnects to `/ws/host?room=<code>&token=<token>` and viewers never notice. After the grace period the room
ends and viewers receive `ended`.
