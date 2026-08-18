# sentry-seer-buddy

A hackweek project to bring sentry's seer capabilities closer to actual development.

## Building & Running
To build or run the project, use one of the following tasks:


| Task | Description |
|------|-------------|
| `./gradlew test`    | Run the tests     |
| `./gradlew build`   | Build the project |
| `./gradlew run`     | Run the server    |

If the server starts successfully, you'll see the following output:
```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

## Configuration

| Environment variable | Necessary for | Notes |
|---|---|---|
| `SENTRY_AUTH_TOKEN` | the Sentry issues and all Seer calls | A **user** auth token with the scope `org:read`. |
| `SENTRY_ORG` | all Seer calls | The organization **slug**, e.g. `sentry-sdks`. |
| `SENTRY_PROJECT_ID` | the `project` parameter of the Seer run link | E.g. `5428559`. Optional. |

Without `SENTRY_AUTH_TOKEN` or `SENTRY_ORG` the server operates, but it makes no Seer
recommendations, and resolving a recommendation only marks it `RESOLVED` and gives no
`seer_run_url`.

`SENTRY_AUTH_TOKEN` must be a **user** auth token, not an internal-integration (organization)
token. An organization token can start a run but cannot continue it and cannot make a pull request.
The account that owns the token owns every run the server starts, and one account must own a run
from beginning to end — a second account gets `403` "this conversation belongs to another user".

### Organization prerequisites

The Seer run that a resolve starts can only write code if the organization is set up for it:

| Item | Kind | Why |
|---|---|---|
| `gen-ai-features` | feature | Base Seer access. |
| `hideAiFeatures` off | org option | Base Seer access. |
| The Seer agreement accepted | org state | Base Seer access. |
| `organizations:seer-explorer` | feature flag | Necessary for every chat call. |
| `allow_joinleave` | org flag | Part of the access gate (open team membership). |
| `sentry:enable_seer_coding` | org option | Necessary for code, and for a pull request. |
| `organizations:seer-explorer-chat-coding` | feature flag | Second condition for code. |

Coding needs the last two **together**. With coding off, resolving a recommendation still gives a
valid `seer_run_url` and the run still opens, but the run will only talk about the change: it writes
no patches, and no "Create PR" button appears.

### Network exposure

The server listens on `0.0.0.0:8080` and no endpoint asks for authentication. Anything that reaches
that port can start Seer runs in the name of the owner of `SENTRY_AUTH_TOKEN` and spend that
account's Seer quota. Bind it to localhost, or put it behind something that authenticates, before
you run it on a network you do not control.
