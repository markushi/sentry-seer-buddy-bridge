# Open URL in local browser

## Purpose

Add an endpoint that lets a client ask the server to open a URL in the local
machine's default browser. Since this server runs a browser-launching side
effect, the set of URLs it will open must be restricted to avoid it being used
to open arbitrary/malicious URLs.

## Scope

- New top-level module `io.sentry.buddy.openurl`, independent of the existing
  `flow` package.
- Allowlist is domain-only: scheme must be `https` and host must be exactly
  `sentry.io` (no subdomains). This covers Sentry issue URLs, trace URLs, and
  `Recommendation.link` values, since all of those are expected to live under
  `sentry.io`.
- No support for self-hosted Sentry domains, `http`, or any other host.

## Components

- `OpenUrlModels.kt`
  - `data class OpenUrlRequest(val url: String)`
- `OpenUrlValidator.kt`
  - `fun validateOpenUrl(url: String): String?` — parses `url` with
    `java.net.URI`. Returns `null` if `scheme == "https"` and
    `host == "sentry.io"`. Otherwise returns a human-readable error string.
    Malformed URLs (parse failure) also return an error string.
- `BrowserLauncher.kt`
  - `interface BrowserLauncher { fun open(uri: URI) }`
  - `class DesktopBrowserLauncher : BrowserLauncher` — uses
    `java.awt.Desktop.getDesktop().browse(uri)`. Throws if
    `Desktop` is unsupported (headless) or `browse` fails.
- `OpenUrlRoutes.kt`
  - `fun Application.openUrlRoutes(browserLauncher: BrowserLauncher)`
  - `POST /v1/open-url` — receives `OpenUrlRequest`, validates, opens via
    launcher.
- `ConfigureOpenUrl.kt`
  - `fun Application.configureOpenUrl(browserLauncher: BrowserLauncher = DesktopBrowserLauncher())`
  - Registered in `application.yaml` under `ktor.application.modules` as
    `io.sentry.buddy.openurl.ConfigureOpenUrlKt.configureOpenUrl`.

## Data flow

Client → `POST /v1/open-url {"url": "..."}` → `validateOpenUrl` → on failure,
`400 Bad Request` with `{"error": "<reason>"}` → on success,
`browserLauncher.open(uri)` → `200 OK`.

## Error handling

- Malformed URL / wrong scheme / wrong host → `400 Bad Request`,
  `{"error": "..."}`.
- `Desktop` unsupported, or `browse` throws → catch and respond
  `500 Internal Server Error`, `{"error": "failed to open url"}`.

## Testing

- Unit tests for `validateOpenUrl`:
  - accepts `https://sentry.io/organizations/acme/issues/123/`
  - rejects `http://sentry.io/...` (wrong scheme)
  - rejects `https://evil.com/...` (wrong host)
  - rejects `https://sub.sentry.io/...` (subdomain, exact host match only)
  - rejects malformed strings (e.g. `"not a url"`)
- Route test via `testApplication` with a fake `BrowserLauncher` injected:
  - valid `sentry.io` URL → `200`, launcher invoked with the expected `URI`
  - disallowed URL → `400`, launcher NOT invoked
