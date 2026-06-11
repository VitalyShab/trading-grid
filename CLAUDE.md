# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Before Every Task

Read all files in the `docs/` folder before starting any task. This folder contains the project specification, domain definitions, and design decisions that are essential context for all work in this repository.

## Project Overview

`trading_grid` is a Spring Boot 4.0.6 application targeting Java 26. It uses Spring Data JPA for persistence and Spring MVC for REST APIs. Currently in initial scaffolding — no domain classes, controllers, or database configuration exist yet.

## Build & Run Commands

```bash
# Build (skip tests)
./gradlew build -x test

# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.vitalys.trading_grid.TradingGridApplicationTests"

# Run a single test method
./gradlew test --tests "com.vitalys.trading_grid.TradingGridApplicationTests.contextLoads"

# Clean build
./gradlew clean build
```

## Tech Stack

| Layer | Technology |
| ----- | ---------- |
| Framework | Spring Boot 4.0.6 |
| Web | Spring MVC (`spring-boot-starter-webmvc`) |
| Persistence | Spring Data JPA (`spring-boot-starter-data-jpa`) |
| Code generation | Lombok |
| Build | Gradle (Kotlin DSL not used — plain Groovy `build.gradle`) |
| Java version | 26 |
| Testing | JUnit 5 via `junit-platform-launcher` |

## Package Structure

All production code lives under `com.vitalys.trading_grid`. The entry point is `TradingGridApplication`. Spring Boot component scan covers the entire `com.vitalys.trading_grid` package tree.

## Configuration

- `src/main/resources/application.yaml` — main config (currently only sets `spring.application.name`)
- No database datasource is configured yet; adding JPA entities will require a datasource URL, driver, and DDL strategy

## Notes

- `TradingGridApplication.main` is declared `static void` (no `public`) — this is intentional and valid for JVM 26 with relaxed access rules
- DevTools (`spring-boot-devtools`) is on the `developmentOnly` classpath for live reload during `bootRun`
