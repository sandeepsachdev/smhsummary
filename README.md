# smhsummary

Two ways to read the Sydney Morning Herald: a single-file browser app, or a
self-hosted Spring Boot service that also emails the briefing to you on
startup.

Both pull every non-sport SMH section feed, dedupe by article slug, and ask
Claude Haiku 4.5 to write one-line summaries and group the day's stories
into themes with a short editor's-note explaining each grouping.

---

## 1. Browser app — `smh.html`

Open `smh.html` in any modern browser, paste an Anthropic API key, click
*Load Today's Articles*. Key persists in `localStorage`. Falls back to a
keyword classifier if no key is supplied or the Claude call fails.

The key is sent only to `api.anthropic.com` from the browser via the
`anthropic-dangerous-direct-browser-access` header — fine for personal use,
not for a public site.

## 2. Spring Boot service — `pom.xml` / `Dockerfile`

Server-side equivalent of the browser app, plus:
- Renders the briefing as HTML on the server (no API key in the client)
- Emails the rendered briefing on every startup via [Resend](https://resend.com)
- One-click container deploy

### Run locally

```sh
export ANTHROPIC_API_KEY=sk-ant-...
export RESEND_API_KEY=re_...
export EMAIL_FROM='Briefing <you@yourdomain.com>'
export EMAIL_TO='you@example.com'

./mvnw spring-boot:run    # or: mvn spring-boot:run
```

Visit http://localhost:8080.

### Endpoints

| Path        | Method | Purpose                                                 |
|-------------|--------|---------------------------------------------------------|
| `/`         | GET    | Renders the cached briefing (builds on first request)   |
| `/refresh`  | GET    | Rebuilds the briefing and returns the fresh page        |
| `/healthz`  | GET    | JSON status: ready, article count, theme count, etc.    |

`/refresh` does **not** send a new email — emails are only sent on app
startup so restarts aren't spammy. To force a fresh email, restart the
container.

### Configuration

All settings are environment variables:

| Variable            | Required | Default                                | Notes                                  |
|---------------------|----------|----------------------------------------|----------------------------------------|
| `ANTHROPIC_API_KEY` | no       | _(none)_                               | If unset, falls back to keyword themes |
| `RESEND_API_KEY`    | no       | _(none)_                               | If unset, the startup email is skipped |
| `EMAIL_FROM`        | no       | `Briefing <onboarding@resend.dev>`     | Resend's shared sandbox sender works for testing |
| `EMAIL_TO`          | no       | _(none)_                               | Comma-separated list of recipients     |

The app starts up cleanly even with zero env vars — it'll show keyword-grouped
themes and skip the email step.

### Docker

```sh
docker build -t smh-summary .
docker run --rm -p 8080:8080 \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  -e RESEND_API_KEY=re_... \
  -e EMAIL_FROM='Briefing <you@yourdomain.com>' \
  -e EMAIL_TO='you@example.com' \
  smh-summary
```

The image is a multi-stage build: Maven + Temurin 21 for compilation, Temurin
21 JRE for runtime. The final image is ~280 MB.

### Deploy targets

Works out of the box on any platform that builds from a `Dockerfile`:
[Fly.io](https://fly.io), [Railway](https://railway.com),
[Render](https://render.com), [Google Cloud Run](https://cloud.google.com/run),
[AWS App Runner](https://aws.amazon.com/apprunner/),
[Azure Container Apps](https://azure.microsoft.com/en-us/products/container-apps).

The app listens on `0.0.0.0:8080`; set `PORT` via your platform's env if it
expects a different port and override `server.port` accordingly.

---

## Behaviour shared by both

- **Sport filtered out** before reaching Claude — saves tokens and keeps the
  briefing focused. Filter is keyword-based on title/description plus SMH's
  own `<category>` field.
- **Dedup** is on SMH's stable article slug (`-p5n2x4.html` pattern), so the
  same story appearing in multiple section feeds doesn't render twice.
- **Theme grouping** is via structured outputs on Claude Haiku 4.5
  (`output_config.format` with a JSON schema). Each theme carries a short
  reasoning note explaining what ties the articles together.
- **Sections collapsed by default**, click to expand. (Web only — the email
  always shows everything expanded.)

---

## Prompts that built this

Every prompt used during development, in order:

1. *how are the summary generated in this web page*
2. *can you build app in javascript which will use claude to give better grouping of articles and better one line summaries*
3. *apply the filter to all articles in the smh feed not just the ones since midnight but do not include any sporting related articles*
4. *only picking up 10 stories. think you are not going back far enough*
5. *(screenshot attached) Fix the duplicate articles appearing*
6. *Display some notes about the reasoning used to group articles on the page perhaps under each section*
7. *Have each section collapsed by default and expandable if the user clicks on it.*
8. *As well as generating the standalone html page add the same functionality as a Java spring web app which can be deployed easily to render with a Dockerfile. Each time the app starts up it should also send all information it displays online as an email using resend to send the emails*
9. *Add all prompts to the readme*
