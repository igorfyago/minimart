# minimart

**An agentic subscription-commerce platform running a simulated economy.** Raw Java 21, no frameworks.

A consultation agent that recommends. A real subscription billing engine with proration and dunning. Cohort
analytics computed from live events. All of it settled on a double-entry ledger.

And then the part that makes it unusual: **it has no human customers, so the customers were built too.** A
population of LLM-backed agents with personas, budgets and patience browse the catalog, take the consultation,
subscribe, complain, ask for refunds and churn, driving the platform through its real HTTP API. They are used to
stress the system and to run pricing and retention experiments end to end.

**No real money is ever connected.** That is deliberate, and it is the point: a complete commercial system with a
simulated economy can run experiments no company with real users could ethically or practically run.

## What it is honestly

Agent customers are not humans. This system legitimately proves **logic, correctness and stress**: that renewals
charge exactly once when the scheduler double-fires, that inventory never oversells under concurrency, that the
dunning ladder recovers a failed payment, that the funnel is instrumented end to end, that the ledger balances at
every instant. It does **not** predict real-world conversion rates, and it does not claim to.

## Lineage

Built on the mechanisms proven in [minibank](https://github.com/igorfyago/minibank): the double-entry ledger,
caller-minted idempotency keys, the authorize/capture/release hold lifecycle (reused here as inventory
reservation), the transactional outbox, and the human-in-the-loop agent pattern where the agent proposes and a
human authorises.

## Stack

Java 21 (virtual threads, raw JDBC, JDK HttpServer) · PostgreSQL 16 · Apache Kafka · Redis · Flyway ·
Prometheus and Grafana · Docker.

## Running it

```bash
docker compose up -d          # postgres :5436, redis :6380, kafka :9093
mvn -q compile exec:java      # the platform on :8081
```

## Status

Early. See the build log below as slices land.

## Build log

**Slice 1 · the spine (done, 4/4 lessons green).** `submit`, `fulfil`, `abort` as one local ACID transaction each.
Inventory is a second double-entry ledger sharing the same tables: a stock account's currency is `UNIT:{variantId}`
and its balance is whole units, so "do not oversell" and "do not overdraw" are the same rule, enforced once by a
non-negative CHECK under an ordered row lock.

- *50 concurrent buyers against 10 units* → exactly 10 sold, 40 refused, shelf never negative.
- *the same order id submitted 20x concurrently* → one order, one reservation, four entries, money moved once.
- *fulfil* → posts SIMEUR legs and UNIT legs in one transaction, each summing to zero.
- *abort, run twice* → wallet and shelf restored exactly, compensation idempotent.

Time is a parameter everywhere (`business_at`); no business logic reads the wall clock, which is what will let a
simulated year run in minutes without the logic noticing.

### Next
- **Slice 2 · the race.** Reservation expiry sweeper vs capture, refereed by a row lock and a state CAS. The pooled
  reserved account means derived transaction ids alone cannot settle that race.
- **Slice 3.** Catalog, cart, multi-line, HTTP surface.
- **Slice 4.** Order saga over Kafka via the transactional outbox.
- **Slices 5-6.** MRR movement ledger, then billing (a renewal is just an order with a derived id).
- **Slices 7-8.** The synthetic customer population, then experiments with A/A calibration.
