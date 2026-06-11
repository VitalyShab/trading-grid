# Trading Grid — Project Specification

## Overview

Trading Grid is a Spring Boot application that implements a **grid trading strategy** engine capable of operating across multiple trading markets (crypto, forex, stocks, etc.).

Grid trading is a systematic strategy that places buy and sell orders at predefined price intervals above and below a set price level, forming a "grid" of orders. Profits are captured from price oscillations within the grid range without requiring directional market prediction.

---

## Core Concepts

**Grid** — a configuration that defines:
- The market/instrument being traded (e.g. BTC/USDT, EUR/USD)
- Upper and lower price bounds
- Number of grid levels (determines spacing between orders)
- Order size per level
- Active/paused/stopped status

**Grid Level** — a single price step within the grid, each associated with one pending buy order and one pending sell order.

**Order** — a buy or sell instruction placed at a specific grid level price. Orders can be in states: `PENDING`, `FILLED`, `CANCELLED`.

**Position** — tracks the current open position resulting from filled orders on a grid.

---

## Functional Requirements

### Grid Management
- Create, update, pause, resume, and stop grids
- Support multiple concurrent grids across different markets
- Each grid operates independently with its own price range and order sizing

### Order Execution
- Place limit buy/sell orders at each grid level on grid start
- When a buy order fills: place a corresponding sell order one level above
- When a sell order fills: place a corresponding buy order one level below
- Cancel all open orders when a grid is stopped

### Market Integration
- Abstract market connector interface to support multiple exchanges/brokers
- Initial support: single configurable market (pluggable via configuration)
- Market data feed: receive real-time price updates to trigger order fills (simulation or live)

### Monitoring & Reporting
- Track total profit/loss per grid
- Track number of completed grid cycles (buy fill → sell fill pair)
- Expose grid status and statistics via REST API

---

## REST API (planned)

| Method | Path | Description |
| ------ | ---- | ----------- |
| `POST` | `/grids` | Create a new grid |
| `GET` | `/grids` | List all grids |
| `GET` | `/grids/{id}` | Get grid details and statistics |
| `PUT` | `/grids/{id}/pause` | Pause a running grid |
| `PUT` | `/grids/{id}/resume` | Resume a paused grid |
| `DELETE` | `/grids/{id}` | Stop and remove a grid |

---

## Data Model (planned)

- `Grid` — top-level aggregate (instrument, bounds, level count, status, PnL)
- `GridLevel` — price level within a grid (price, buy order ref, sell order ref)
- `Order` — individual market order (side, price, size, status, fill timestamp)

---

## Non-Functional Requirements

- All grid state persisted via Spring Data JPA (H2 for dev, production DB TBD)
- Stateless REST layer; all business logic in service layer
- Market connector is an interface — concrete implementations are swappable without changing core logic
- Grid engine must handle concurrent price updates safely
