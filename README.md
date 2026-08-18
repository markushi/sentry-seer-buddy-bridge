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
