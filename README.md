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

**minipay · a small Stripe-shaped payment processor (done, 5/5 lessons green).** Its own database, its own ledger,
its own process, reached only over HTTP. `PaymentIntent` runs authorize, capture and cancel, so authorised money is
held and only becomes the merchant's on capture.

The real work is the idempotency layer, and it distinguishes three cases that are usually conflated:

- *same key, same request* → returns the **byte-identical response**, so a caller cannot tell a retry from the original.
- *same key, different request* → `422`. That is a caller bug and silently doing either thing would be worse.
- *same key, still running* → `409`. The first call owns it.

Proven over a real socket, including twenty concurrent retries of one key charging exactly once. The network is the
point: idempotency keys only mean anything when a response can be lost in flight.

**Slice 2 · the race (done, 3/3 lessons green).** The reserved stock account is pooled per location and variant, so
when the expiry sweeper and a shipment reach the same reservation together, deterministic transaction ids cannot
referee it: the ids differ, both claim, and the ledger still sums to zero while the warehouse goes wrong. The
reservation row is the referee, taken `FOR UPDATE` with a state check by both paths.

- *40 reservations raced by shipment and sweeper* → 20 shipped, 20 released, every order settled exactly one way,
  units conserved, no double settle.
- *audit 3* (pooled reserved balance == units held by live reservations) catches a phantom release that sum-zero and
  cache-drift both sleep through. That is the whole reason it exists.

**The seam · minimart talks to minipay (done, 3/3 lessons green).** The store stopped being its own payment
processor. Checkout is now a small saga across a network: reserve the goods locally in one ACID commit, then
authorise the money at the processor over HTTP with an idempotency key derived from the order, and if that second
step fails **for any reason at all**, give the goods back.

- *happy path* → goods held in minimart, money held in minipay, two databases, neither one holding both.
- *processor unreachable* → every unit returns to the shelf. A store that reserves stock and then loses the network
  must compensate, or it starves its own warehouse with phantom holds.
- *checkout retried* → one reservation and one authorisation.

A bug worth recording, because the design pass predicted this class of thing: the first version decided "did a
processor take the money?" by checking whether `payment_intent_id` was set. But that column is only filled once
authorisation *succeeds*, so the compensation path saw NULL and tried to refund a wallet that was never charged.
How an order is paid is a fact fixed when the order is created, not one inferred afterwards from a success artifact.

**Slice 3 · the HTTP surface (done).** Everything an agent customer will touch is now a real endpoint, with no
privileged back door for the simulation: `GET /api/catalog`, `POST /api/checkout`, `POST /api/orders/{id}/ship`
and `/cancel`, `POST /api/stock`, `GET /api/audit`, and `POST /api/sim/tick`, which runs the time-driven jobs as a
single deterministic pass at a tick boundary instead of a background loop. That is what makes a compressed run
reproducible. `mvn exec:java` boots the merchant on :8081 and the processor on :8082.

**A bug the audits caught, live.** Driving a real purchase through the API turned `/api/audit` red:
`sum_zero_violations: 2`. The supplier account was `supplier:{tenant}` with no variant in the key. An account holds
exactly one currency, so the first delivery pinned it to `UNIT:v-mots-10mg`, and receiving any *other* product then
posted a foreign-currency leg into it. Every existing test passed, because each one used a single product. Audit 1
found it the moment a second product existed. Suppliers are now per variant, with a regression lesson.

**The synthetic customers (done, 4/4 lessons green).** A seeded population of agent customers shops through the
real HTTP API while a compressed clock runs days past in seconds. Every choice is a pure function of
`(runId, agentId, tick, step)`, so a run is reproducible even though agents execute concurrently, and order ids
are *derived* rather than random, which is what makes a replayed window idempotent instead of duplicating orders.

- *same seed, twice* → byte-identical decision digest.
- *different seed* → genuinely different crowd.
- *40 agents over 48 ticks* → 507 orders placed, 402 shipped, 92 abandoned carts reclaimed by the sweeper, every
  audit clean and units conserved for every product.
- *the whole run replayed* → not one new order, not one new ledger entry.

**Two real bugs this slice found.**

*The pool, arriving exactly as minibank taught it.* The first determinism run failed. The cause was not the seeding:
with a connection opened per ledger call, a few hundred simulated customers exhausted the machine's ephemeral TCP
ports and calls began failing with `Address already in use`. Which call failed depended on socket timing, so the
run stopped being reproducible. A missing pool did not merely make things slow, it destroyed determinism. With a
hand-written pool the digests match and the run is twice as fast.

*Half a compensation is a bug.* The sweeper returned abandoned goods to the shelf but left the authorisation
standing at the processor, holding a customer's money for a product they no longer had. An abandoned cart has to
release both sides of the seam.

### Next
- **Slice 4.** Order saga over Kafka via the transactional outbox.
- **Slices 5-6.** MRR movement ledger, then billing (a renewal is just an order with a derived id).
