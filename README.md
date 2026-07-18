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
