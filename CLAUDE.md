# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A Ktor (Kotlin) server generated via the Ktor Project Generator (start.ktor.io). Currently a minimal
skeleton: content negotiation + kotlinx.serialization plugins and two demo routes.

## Commands

- `./gradlew run` — start the server (default: http://0.0.0.0:8080)
- `./gradlew test` — run all tests
- `./gradlew test --tests "io.sentry.ServerTest.test root endpoint"` — run a single test
- `./gradlew build` — full build (compile + test)

## Rules

- Keep the implementation minimal.
- Do not build features nobody requested.

## Architecture

Ktor apps wire up via **application modules** referenced by fully-qualified function name in
`src/main/resources/application.yaml` (`ktor.application.modules`), not via a central routing table
in code. Each module is an `Application.() -> Unit` extension function:

- `Serialization.kt` — `configureSerialization()` installs the `ContentNegotiation` plugin (JSON).
- `Routing.kt` — `configureRouting()` defines all HTTP routes inside `routing { ... }`.
- `main.kt` — entry point delegates to `io.ktor.server.netty.EngineMain`, which reads
  `application.yaml` and invokes the configured modules in order.

To add a new concern (e.g. auth, DB), create a new `ConfigureX.kt` file with an `Application.configureX()`
function and register it in `application.yaml` under `ktor.application.modules`, following the existing
`io.sentry.<FileName>Kt.<functionName>` naming pattern.

Tests use Ktor's `testApplication { }` test host (`ServerTest.kt`), which loads `application.yaml` by
default (`configure()` in the test loads the same modules used in production) and exposes a `client`
for making in-process HTTP requests without a running server.
