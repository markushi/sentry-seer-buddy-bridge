# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A Ktor (Kotlin) server meant as a bridge between an app and sentry services.

## Commands

- `./gradlew run` — start the server (default: http://0.0.0.0:8080)
- `./gradlew test` — run all tests
- `./gradlew test --tests "io.sentry.ServerTest.test root endpoint"` — run a single test
- `./gradlew build` — full build (compile + test)

## Basic Coding Rules

- Use the superpowers skill set for development: https://github.com/obra/superpowers
- Keep the implementation minimal
- Do not build features nobody requested
- Write meaningful tests

## Architecture

Ktor apps wire up via **application modules** referenced by fully-qualified function name in
`src/main/resources/application.yaml` (`ktor.application.modules`), not via a central routing table
in code.

To add a new concern (e.g. auth, DB), create a new `ConfigureX.kt` file with an `Application.configureX()`
function and register it in `application.yaml` under `ktor.application.modules`, following the existing
`io.sentry.<FileName>Kt.<functionName>` naming pattern.

Tests use Ktor's `testApplication { }` test host (`ServerTest.kt`), which loads `application.yaml` by
default (`configure()` in the test loads the same modules used in production) and exposes a `client`
for making in-process HTTP requests without a running server.
