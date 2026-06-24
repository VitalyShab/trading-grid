# Trading Grid — Project Specification

## Overview

`trading_grid` is a Spring Boot 4.0.6 application (Java 26) that implements an automated **grid trading bot** for cryptocurrency markets. The bot places a ladder of limit BUY orders below the current market price and, once a BUY is filled, places a SELL order at a profit markup above the weighted-average cost of all filled positions. The same strategy logic runs against a live exchange (Binance) in production and against a locally stored candle series in backtesting mode, enabled by a `MarketGateway` abstraction layer.

---

## Tech Stack

| Layer | Technology |
| --- | --- |
| Framework | Spring Boot 4.0.6 |
| Language | Java 26 |
| Web | Spring MVC (`spring-boot-starter-webmvc`) |
| Persistence | Spring Data JPA + Hibernate |
| Database (runtime) | H2 in-memory (schema managed by Flyway) |
| Schema migrations | Flyway (`classpath:db/migration`) |
| Code generation | Lombok |
| Build | Gradle (Groovy DSL `build.gradle`) |
| Testing | JUnit 5 via `junit-platform-launcher` |
| Dev reload | Spring Boot DevTools (hot restart) |

---

## Configuration

All settings live in `src/main/resources/application.yaml`.

### Binance credentials (`spring.web.client.binance`)

| Key | Description |
| --- | --- |
| `base-url` | Binance REST base URL (`https://api.binance.com`) |
| `api-key` | Binance API key — sent as `X-MBX-APIKEY` header |
| `api-secret` | Binance secret — used for HMAC-SHA256 request signing |

Bound to `BinanceProperties` (`@ConfigurationProperties`). Application fails fast at startup if any value is blank.

### Historical data (`trading-grid.historical-data.download`)

| Key | Default | Description |
| --- | --- | --- |
| `page-size` | `1000` | Candles per Binance pagination page |
| `throttle-ms` | `250` | Sleep between paginated pages (ms) |
| `storage-dir` | `historical-data` | Root directory for JSON candle files |

Bound to `HistoricalDataProperties`.

### Datasource

H2 in-memory (`jdbc:h2:mem:trading_grid_db`). Flyway applies `V1__create_trading_pair.sql` on every boot. The H2 web console is available at `/h2-console`.

### Logging

Rolling file logs written to `logs/trading-grid.log`. Files are gzip-rotated daily, capped at 10 MB per file, 30-day history, 1 GB total. Application package logged at `DEBUG`, everything else at `INFO`.

---

## Database Schema

Managed by Flyway migration `V1__create_trading_pair.sql`.

### `trading_pair`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGINT AUTO_INCREMENT` | Primary key |
| `symbol` | `VARCHAR(20)` | e.g. `ETHUSDT` |
| `gateway` | `VARCHAR(50)` | Bean name of the `MarketGateway` to use (`binance`, `backtest`) |
| `wallet` | `DECIMAL(19,8)` | Available cash balance for this pair |
| `active` | `BOOLEAN` | Whether the strategy engine processes this pair |
| `order_count` | `INT` | Number of grid BUY levels to place |
| `spend_per_order` | `DECIMAL(19,8)` | Base spend per order (unused in current engine — engine derives spend from wallet) |
| `profit_percent` | `DECIMAL(5,2)` | Target profit percentage for the SELL order |

### `orders`

| Column | Type | Notes |
| --- | --- | --- |
| `id` | `BIGINT IDENTITY` | Primary key |
| `trading_pair_id` | `BIGINT` | FK → `trading_pair.id` |
| `market_order_id` | `VARCHAR(100)` | Exchange-assigned order ID |
| `type` | `VARCHAR(10)` | `BUY` or `SELL` |
| `price` | `DECIMAL(20,8)` | Limit price |
| `qty` | `DECIMAL(20,8)` | Quantity |
| `status` | `VARCHAR(30)` | See Order Status Lifecycle below |
| `created_at` | `TIMESTAMP` | Set automatically on insert |
| `updated_at` | `TIMESTAMP` | Set automatically on insert/update |

### Order Status Lifecycle

```
OPEN  ──(price reached)──►  FILL  ──(sell completes cycle)──►  COMPLETE
  │                                                                  ▲
  └──(cancelled by engine)──►  CLOSE                               │
                                                                    │
SELL order: OPEN ──(price reached)──► triggers cycle close ────────┘
STUCK — reserved for stalled state (currently unused in live flow)
```

| Status | Meaning |
| --- | --- |
| `OPEN` | Order placed on exchange, awaiting fill |
| `FILL` | BUY order price was reached — holding crypto, waiting for SELL |
| `CLOSE` | Order cancelled by the engine (e.g. grid rebuild) |
| `COMPLETE` | Order fully settled as part of a completed profit cycle |
| `STUCK` | Stale state marker (reserved, not used in current live flow) |

---

## Package Structure

```
com.vitalys.trading_grid
├── TradingGridApplication          Entry point
├── config/
│   ├── BinanceProperties           Config binding for Binance credentials
│   ├── HistoricalDataProperties    Config binding for historical data download
│   └── RestClientConfig            Constructs the Binance RestClient bean
├── controller/
│   ├── TradingPairController       POST/PUT /trading-pairs
│   ├── HistoricalDataController    POST /historical-data/{symbol}/download
│   └── BacktestController          POST /backtests
├── cron/
│   └── StrategyScheduler           @Scheduled every 5 s → StrategyEngine.startStrategy()
├── dto/
│   ├── TradingPairRequest          Create/update trading pair input
│   ├── BacktestRequest             Backtest run input
│   ├── BacktestResult              Backtest run output
│   ├── BacktestTradeLogEntry       Per-trade entry in backtest log
│   └── HistoricalDataDownloadResponse
├── gateway/
│   ├── MarketGateway               Interface (getCandles, placeOrder, cancelOrder)
│   ├── dto/
│   │   ├── Candle                  OHLCV candlestick
│   │   ├── PlaceOrderRequest       Order placement params
│   │   └── OrderResponse           Exchange order acknowledgement
│   ├── binance/
│   │   └── BinanceGateway          Live Binance REST implementation (bean: "binance")
│   └── backtest/
│       └── BacktestMarketGateway   In-memory replay implementation (bean: "backtest")
├── model/
│   ├── TradingPair                 JPA entity
│   ├── Order                       JPA entity
│   ├── OrderType                   Enum: BUY, SELL
│   └── OrderStatus                 Enum: OPEN, CLOSE, FILL, COMPLETE, STUCK
├── repository/
│   ├── TradingPairRepository       Spring Data JPA
│   └── OrderRepository             Spring Data JPA
└── service/
    ├── StrategyEngine              Core grid trading logic
    ├── OrderService                Order CRUD + lifecycle transitions
    ├── TradingPairService          Trading pair CRUD
    ├── BacktestService             Backtesting orchestration
    └── HistoricalDataService       Download and persist Binance candle history
```

---

## REST API

### Trading Pairs

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/trading-pairs` | Create a new trading pair with grid parameters |
| `PUT` | `/trading-pairs/{symbol}` | Update an existing trading pair |

**Request body (`TradingPairRequest`)**

| Field | Type | Constraints |
| --- | --- | --- |
| `symbol` | `String` | Not blank, e.g. `ETHUSDT` |
| `gateway` | `String` | Not blank — matches a `MarketGateway` bean name |
| `wallet` | `BigDecimal` | Positive |
| `active` | `boolean` | Enables/disables strategy execution |
| `orderCount` | `int` | ≥ 0 |
| `spendPerOrder` | `BigDecimal` | Positive (optional) |
| `profitPercent` | `BigDecimal` | 0.01 – 999.99 (optional) |

---

### Historical Data

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/historical-data/{symbol}/download?interval=1h` | Trigger async download of the last 365 days of candles |

Returns `202 Accepted` immediately. Download runs in the background. Data is persisted to `historical-data/{symbol}/{interval}.json`.

---

### Backtests

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/backtests` | Run a backtest and return results synchronously |

**Request body (`BacktestRequest`)**

| Field | Type | Constraints |
| --- | --- | --- |
| `symbol` | `String` | Not blank |
| `interval` | `String` | Not blank, e.g. `1h` |
| `from` | `Instant` | Start of replay window |
| `to` | `Instant` | End of replay window |
| `initialWallet` | `BigDecimal` | Positive |
| `orderCount` | `int` | ≥ 1 |
| `spendPerOrder` | `BigDecimal` | Positive |
| `profitPercent` | `BigDecimal` | 0.01 – 999.99 |

**Response body (`BacktestResult`)**

| Field | Description |
| --- | --- |
| `symbol` | Market pair replayed |
| `interval` | Kline interval replayed |
| `candlesProcessed` | Number of candles stepped through |
| `initialWallet` | Starting cash balance |
| `finalWallet` | Ending cash balance |
| `completedCycles` | Number of buy→sell profit cycles completed |
| `totalProfit` | `finalWallet - initialWallet` |
| `tradeLog` | Chronological list of every simulated BUY/SELL fill |

---

## Gateway Layer

`MarketGateway` is the exchange abstraction interface. It has exactly three methods:

- `getCandles(symbol, interval, limit)` — fetch recent OHLCV candles
- `placeOrder(PlaceOrderRequest)` → `OrderResponse` — place a limit order
- `cancelOrder(symbol, orderId)` — cancel an open order

Two implementations are registered as Spring beans:

### `BinanceGateway` (bean: `"binance"`)

Live implementation against `https://api.binance.com`.

- `getCandles` — calls `GET /api/v3/klines` (public, no auth required). Parses the JSON array-of-arrays response by positional index.
- `getHistoricalKlines` — paginates `GET /api/v3/klines` with `startTime`/`endTime` until all candles in the range are fetched. Not part of the `MarketGateway` interface; used only by `HistoricalDataService`.
- `placeOrder` — calls `POST /api/v3/order` with HMAC-SHA256 signed query string. Currently **mocked** (returns a fake `OrderResponse`) — the actual HTTP call is disabled by a `if (true)` guard pending production readiness.
- `cancelOrder` — calls `DELETE /api/v3/order` with signed query string. Also currently **mocked**.
- Signing: a new `Mac` instance is created per call (HMAC-SHA256 is not thread-safe). The signature is appended as the last query parameter as required by Binance.

### `BacktestMarketGateway` (bean: `"backtest"`)

In-memory replay implementation for backtesting. Holds a `List<Candle>` series and a cursor index.

- `loadSeries(candles)` — loads a candle series and resets the cursor.
- `advance()` — moves the simulated clock to the next candle. Returns `false` when the series is exhausted.
- `currentCandle()` — returns the candle the cursor points to.
- `getCandles(symbol, interval, limit)` — returns the last `limit` candles up to the cursor position. This is how `StrategyEngine.fetchCurrentPrice()` reads the "current price" during backtesting.
- `placeOrder` — records the order in an in-memory `openOrders` map and returns a synthetic `OrderResponse` with ID prefix `BT-{n}`.
- `cancelOrder` — removes the order from the `openOrders` map.

---

## Strategy Engine

`StrategyEngine` is the core of the application. It is a Spring `@Component` that implements the grid trading algorithm. It is called every 5 seconds by `StrategyScheduler` (via `@Scheduled(fixedDelay = 5000)`) and also directly during backtesting.

### Entry Points

- `startStrategy()` — iterates all `TradingPair` rows where `active = true` AND `gateway != "backtest"`, calling `execute(pair)` for each. Exceptions per pair are caught and logged so one failing pair does not abort others.
- `execute(TradingPair pair)` — single pair tick, annotated `@Transactional`. Called both by `startStrategy()` and directly by `BacktestService` for each candle step.

### How the Strategy Works

Each tick for a trading pair runs two phases in order:

```
execute(pair)
  ├─ syncFilledOrders(pair, gateway)
  │   ├─ fetchCurrentPrice()
  │   ├─ Check all OPEN orders → mark as FILL/COMPLETE if price reached
  │   └─ placeSellOrder()
  └─ placeGridOrders(pair, gateway)
      ├─ fetchCurrentPrice()
      ├─ rebuildBuyOrdersCheck()   ← cancel grid if price ran away
      └─ placeBuyOrders()          ← only if no OPEN/FILL buy orders exist
```

---

### Phase 1: `syncFilledOrders`

**1a. Detect filled orders**

Fetches the latest 1-minute candle close price via `gateway.getCandles(symbol, "1m", 1)`.

Iterates all `OPEN` orders for the pair:

- A **BUY** order is considered filled when `currentPrice <= order.price`
- A **SELL** order is considered filled when `currentPrice >= order.price`

When a BUY is filled: `OrderService.fillOrder(order)` — transitions status to `FILL`.

When a SELL is filled: `OrderService.proceedSellComplete(order, pair, gateway)`:
1. Marks all `FILL`-status BUY orders as `COMPLETE`
2. Cancels all remaining `OPEN` BUY orders (and returns their reserved cash to `pair.wallet`)
3. Marks the SELL order as `COMPLETE`
4. Adds the SELL proceeds (`qty × price`) back to `pair.wallet`

This constitutes one completed profit cycle.

**1b. Place or update the SELL order**

After fill detection, `placeSellOrder` is called unconditionally.

It only acts if there are FILL-status BUY orders. The SELL price is computed as:

```
sellPrice = weightedAverageBuyPrice × (1 + profitPercent / 100)
```

Where `weightedAverageBuyPrice = totalSpent / totalQty` across all FILL'd BUY orders.

If an existing OPEN SELL order already has the exact same price, it is left unchanged. Otherwise, all existing OPEN SELL orders are cancelled and a new one is placed for the combined total quantity at the new sell price.

This means the SELL order is always a single consolidated order covering the full FILL'd position, repriced dynamically as new BUYs fill.

---

### Phase 2: `placeGridOrders`

**2a. Grid rebuild check (`rebuildBuyOrdersCheck`)**

Before placing new buy orders, the engine checks whether the price has moved so far above the grid that the existing grid is no longer relevant.

Conditions for this check to trigger:
- No FILL-status BUY orders exist (i.e., we are not holding any position)
- There are OPEN BUY orders
- `profitPercent` is configured

The trigger threshold is `highestBuyPrice × (1 + (profitPercent × 1.5) / 100)`.

If `currentPrice > triggerPrice`, all OPEN BUY orders are cancelled (their reserved cash returns to `wallet`). This resets the grid so it can be rebuilt from the new price level on the next tick.

**2b. Place new BUY orders**

New BUY orders are only placed when `isNotCompletedBuyOrdersExist` returns false — meaning there are **no** OPEN or FILL-status BUY orders at all.

The engine places `orderCount` limit BUY orders below the current price in a geometric grid:

**Step spacing:** Each level is spaced further below the current price than the previous one, using an exponentially growing step:

```
initialStep = 1%
stepGrowthFactor = 1.25

Level 1: price × (1 - 0.01)
Level 2: price × (1 - 0.01 × 1.25)
Level 3: price × (1 - 0.01 × 1.25²)
...
Level n: price × (1 - 0.01 × 1.25^(n-1))
```

**Spend sizing:** Each level is allocated a larger spend than the previous, following a geometric progression that exhausts the wallet over `orderCount` levels:

```
spendGrowthFactor = 1.05

initialSpend = wallet × (spendGrowthFactor - 1) / (spendGrowthFactor^orderCount - 1)

Level 1: initialSpend
Level 2: initialSpend × 1.05
Level 3: initialSpend × 1.05²
...
Level n: initialSpend × 1.05^(n-1)
```

This is derived from the sum of a geometric series so that the total spend exactly equals the wallet balance.

For each level, `qty = currentSpend / levelPrice`. The reserved cash (`levelPrice × qty`) is subtracted from `pair.wallet` immediately when each BUY order is placed.

Placement stops early if `pair.wallet < currentSpend` (insufficient funds).

---

### Key Constants

| Constant | Value | Role |
| --- | --- | --- |
| `SPEND_INCREASE` | `1.05` | Per-level spend growth factor (5% more per level) |
| `STEP_FRACTION` | `0.01` | Initial grid step (1% below current price) |
| `STEP_GROWTH_FACTOR` | `1.25` | Step expansion per level (steps get 25% wider each level) |
| `QTY_SCALE` | `8` | Decimal scale for quantity arithmetic |
| `PRICE_SCALE` | `8` | Decimal scale for price arithmetic |

---

### Grid Lifecycle Summary

```
No open orders
       │
       ▼
Place N buy orders below current price (geometric grid)
       │
       ▼
Price falls, BUYs fill one by one → status: FILL
       │
       ▼
Consolidated SELL order placed at weighted-avg-cost × (1 + profitPercent%)
       │
       ▼
Price rises to SELL price → SELL fills
       │
       ▼
All FILL'd BUYs → COMPLETE, remaining OPEN BUYs cancelled
Wallet credited with SELL proceeds
       │
       ▼
(back to top — new grid placed next tick)
```

If the price runs up without filling the SELL (e.g. no position was accumulated) and rises `profitPercent × 1.5%` above the highest buy order, all OPEN buy orders are cancelled and the grid rebuilds from the new price.

---

## Backtesting

`BacktestService.run(BacktestRequest)` simulates the full strategy against historical data.

### Flow

1. Load the candle series for `symbol`/`interval` from `historical-data/{symbol}/{interval}.json` via `HistoricalDataService.loadCandles`.
2. Filter to the `[from, to]` range and sort chronologically.
3. Call `backtestMarketGateway.loadSeries(candles)` to initialize the gateway.
4. Create a transient `TradingPair` entity in the database with the parameters from the request (gateway = `"backtest"`).
5. Loop: `while (backtestMarketGateway.advance())` — advance the cursor one candle, then call `strategyEngine.execute(pair)`.
6. Return a `BacktestResult` with final wallet, completed cycles, total profit, and trade log.

The `BacktestMarketGateway.getCandles` method returns candles up to the current cursor, so `StrategyEngine.fetchCurrentPrice()` sees only the close price of the current candle — no lookahead.

### Limitations

- The backtest currently creates a hardcoded `TradingPair` (symbol=`ETHUSDT`, wallet=10000, orderCount=20, profitPercent=1%) ignoring the `BacktestRequest` parameters for `initialWallet`, `orderCount`, `spendPerOrder`, `profitPercent`. The `BacktestState` inner class is defined and populated from the request but is not wired into `StrategyEngine` — the engine reads and mutates the JPA `TradingPair` entity instead.
- `BacktestState.completedCycles` and `BacktestState.tradeLog` are never populated, so the returned `BacktestResult` always shows `completedCycles=0` and an empty `tradeLog`.
- The `BacktestResult.finalWallet` reports `BacktestState.wallet` (initial value only) rather than `pair.wallet` from the JPA entity that the engine actually updates.
- These are known in-progress limitations.

---

## Historical Data Service

`HistoricalDataService` downloads and persists Binance kline history to the local filesystem.

### Download flow (`downloadLastYear`)

1. Computes a 365-day window ending at `Instant.now()`.
2. Paginates through `BinanceGateway.getHistoricalKlines` in windows of `pageSize` candles.
3. Sleeps `throttleMs` milliseconds between pages to respect Binance rate limits.
4. For each page, deduplicates against existing candles (by `openTime`) and appends only new candles to the JSON file.

### Storage

Files are stored as `historical-data/{symbol}/{interval}.json`, containing a JSON array of `Candle` objects. Jackson is configured with `JavaTimeModule` and ISO-8601 string dates (not epoch timestamps). Files are merged incrementally — existing candles are preserved.

The `downloadLastYearAsync` entry point (called by the controller) wraps the download in `@Async` so the HTTP response returns immediately with `202 Accepted`.

---

## Order Service

`OrderService` handles all order state transitions and is the single point through which orders are persisted. `StrategyEngine` never calls `orderRepository` directly for writes.

| Method | Description |
| --- | --- |
| `placeBuyOrder` | Calls `gateway.placeOrder`, persists an `OPEN` BUY order, deducts `price × qty` from `pair.wallet` |
| `fillOrder` | Transitions BUY order status: `OPEN` → `FILL` |
| `completeOrder` | Transitions any order status to `COMPLETE` |
| `cancelOrder` | Calls `gateway.cancelOrder`, transitions status to `CLOSE` |
| `proceedSellComplete` | On SELL fill: completes all FILL'd BUYs, cancels all OPEN BUYs (refunding wallet), completes the SELL, credits wallet with `qty × price` |
| `cancelAllOpenBuyOrders` | Cancels all OPEN BUY orders for a pair and refunds reserved cash to wallet |
| `isNotCompletedBuyOrdersExist` | Returns `true` if any BUY order is in `OPEN` or `FILL` status |
| `handleStuckOrders` | Marks FILL BUY + OPEN SELL orders as `STUCK` (called from commented-out code, reserved for future use) |

---

## Scheduled Execution

`StrategyScheduler` runs `StrategyEngine.startStrategy()` every 5 seconds (`fixedDelay = 5000`). The delay is measured from the completion of the previous tick, so if a tick takes longer than 5 seconds (e.g. slow exchange calls), ticks will not overlap.

Only pairs with `active = true` and `gateway != "backtest"` are processed in the live scheduler loop.

---

## Notes and Known Issues

- **Binance order calls are mocked.** `BinanceGateway.placeOrder` and `cancelOrder` contain `if (true) { return mock; }` guards. Real exchange calls are disabled pending production readiness.
- **`spendPerOrder` field is stored but not used by the engine.** The engine computes spend allocation from `pair.wallet` and the geometric series formula. `spendPerOrder` is a legacy field.
- **Backtest result metrics are incomplete.** See the Backtesting Limitations section above.
- **No concurrency control.** The scheduler runs a single thread per Spring task executor. If the executor is configured for multiple threads, concurrent ticks for the same pair could corrupt state. Currently safe because `fixedDelay` serialises ticks within a single pair.
- **H2 in-memory database.** All data is lost on restart. Switching to PostgreSQL or MySQL requires only a datasource config change and Flyway dialect adjustment.
