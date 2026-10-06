# Discord

Two independent features. Both are configured on the **server**, never in the clients.

## 1. Live announcements (webhook)

When a host goes live, the server posts to a Discord channel:

> 🔴 **Ana** is live on StreamingService!
> Room code: `ABC234`
> Watch: https://stream.example.com/?room=ABC234

and edits the message to "⚫ **Ana** ended the stream." when the stream stops.

1. In Discord: channel settings → **Integrations** → **Webhooks** → **New Webhook** → **Copy Webhook URL**.
2. Start the server with it (and its public address, so the message can link to the web app):
   ```bash
   DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/…" PUBLIC_URL="https://stream.example.com" ./gradlew :server:run
   ```
3. In the app: **Settings** → turn on **Announce on Discord**, and set a **Display name**.

Notes:
- The webhook URL stays on the server. Treat it as a secret: anyone who has it can post to the channel.
- A host only gets announced if it opted in (the setting above) *and* the server has a webhook.
- Messages use `allowed_mentions: {parse: []}`, so they can never ping `@everyone`, `@here` or roles, and display
  names are stripped of Markdown.
- At most 5 announcements per minute are sent; the rest are skipped.

## 2. Discord Activity (web app inside a call)

Runs the web app inside a Discord voice call. Everyone in the call is placed in one room automatically:
the room code is derived from the Activity's instance id and the instance id is the room password, so only
participants of that call can watch or host it. Viewers who arrive before the host wait and start watching as soon as
someone shares.

1. Deploy the server **over HTTPS** with the web app (see the Docker section of the README).
2. Create an application at the [Discord Developer Portal](https://discord.com/developers/applications).
3. **Activities** → enable Activities, then under **URL Mappings** map the root `/` to your server's host
   (for example `stream.example.com`, without `https://`).
4. Copy the application's **Application ID** and start the server with it:
   ```bash
   DISCORD_CLIENT_ID="123456789012345678" STATIC_DIR=… ./gradlew :server:run
   ```
5. Launch the Activity from a voice channel (it appears in the Activity shelf once the app is set up; for testing,
   enable *Developer Mode* in Discord and make sure your account is a member of the application's team).

How it works under the hood: inside Discord the page is loaded in an iframe and every request has to go through
Discord's proxy, under `/.proxy`. The web app detects the `frame_id` URL parameter, reads the client id from
`/api/config`, starts the [Embedded App SDK](https://discord.com/developers/docs/developer-tools/embedded-app-sdk)
(bundled into the web app, because Discord's content security policy forbids external scripts) and talks to the relay
through `wss://<client id>.discordsays.com/.proxy/ws/...`.

Limits to know about:
- Watching works everywhere. Sharing needs the browser's screen capture (`getDisplayMedia`) to be allowed inside
  Discord's iframe; that is up to Discord and has not been verified here.
- If the server has no `DISCORD_CLIENT_ID`, or the SDK fails to start, the web app silently falls back to its normal mode.
- This integration was built from Discord's documentation and tested only up to the web app itself: it has not been
  run inside a real Activity.
