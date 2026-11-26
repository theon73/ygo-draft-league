# YGO Draft League

Fantasy-style platform for Yu-Gi-Oh draft leagues: create a league, configure the card pool and rules, then run the draft, transactions, deck validation and standings.

See [PLANNING.md](PLANNING.md) for the plan.

## Quick start

```bash
docker compose up -d                       # Postgres on :5432

cd backend
./gradlew syncCatalog                      # first time: load ~14k cards from YGOPRODeck (~10 s)
./gradlew bootRun -Pdev                    # API on :8080, with dev login + test users (Windows: gradlew.bat bootRun -Pdev)

cd ../frontend
npm install
npm run dev                                # UI on :5173
```

Check: http://localhost:8080/api/health and http://localhost:5173 (use "Dev: log in as…" in the header).

### Google sign-in (optional locally)

1. In [Google Cloud Console](https://console.cloud.google.com/), create a project, configure the OAuth consent screen (External, Testing; add yourself as a test user), then create an **OAuth client ID** of type *Web application*.
2. Authorized redirect URI: `http://localhost:5173/api/login/oauth2/code/google`. Authorized JavaScript origin: `http://localhost:5173`.
3. Copy `backend/.env.example` to `backend/.env` (gitignored) and fill in the credentials:
   ```
   GOOGLE_CLIENT_ID=...apps.googleusercontent.com
   GOOGLE_CLIENT_SECRET=...
   ```
4. Restart the backend and use **Sign in with Google**. To make yourself a site admin, copy the identity shown when hovering your name (`google:<number>`) into `backend/.env` as `APP_ADMINS=google:<number>` and restart.

Backend tests: `cd backend && ./gradlew test` (needs Docker running for Testcontainers). Prereqs: JDK 21, Docker, Node 22 LTS (Vite 7 needs 20.19+ or 22.12+). The Gradle wrapper downloads Gradle itself, so no separate install is needed.
