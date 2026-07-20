# minimart

**A subscription-commerce platform whose customers are software.** Raw Java 21, no frameworks.

Four services, four PostgreSQL databases, one Kafka cluster, and no query that crosses between them:

- **minimart**, the merchant. Orders, inventory and subscriptions, with a billing engine that runs a dunning
  ladder in business time. Inventory is a second double-entry ledger sharing the same tables, so "do not
  oversell" and "do not overdraw" are one rule.
- **minipay**, the processor. Its own database and its own ledger, reached only over HTTP, with a Stripe-shaped
  idempotency layer that distinguishes a retry from a conflicting reuse from a call still in flight.
- **minianalytics**, the reporting service. Owns no goods and no money, learns everything it knows from the event
  stream, and stores MRR as a ledger of movements rather than a total anyone can overwrite.
- **minifreight**, the logistics arm. Learns an order exists from the topic, gets a shipping label from simulated
  external carriers over HTTP, and runs the fulfilment saga: journaled carrier calls, HMAC-signed tracking
  webhooks, and a driver that never re-fires an unknown outcome blind. When every carrier says no, it says so on
  its own topic, and the merchant compensates on its own books.

The customers are software too: a seeded population drives the same public HTTP API a browser would, with no
privileged path for the simulation, while a compressed clock runs months past in seconds.

**No real money is ever connected.** That is deliberate, and it is the point: a complete commercial system with a
simulated economy can run experiments no company with real users could ethically or practically run.

## What it is honestly

**The customers are authored, not learned.** Every choice a simulated customer makes is a pure function of
`(runId, agentId, tick, step)`. They have traits, budgets and propensities, and they are a load and logic oracle,
not a forecast. There is no language model in this repo and nothing here infers how a real person would behave.

So this system legitimately proves **logic, correctness and stress**: that renewals charge exactly once when the
scheduler double-fires, that inventory never oversells under concurrency, that the dunning ladder recovers a
failed payment, that a failed capture is never recorded as revenue, that the ledger balances at every instant,
and that a reporting service can prove which events it never received. It does **not** predict real-world
conversion rates, and no number it produces should be read as one.

Where a figure is displayed, its provenance is displayed with it: **MEASURED** if it was read off an event that
really happened, **DERIVED** if it was computed from those, **AUTHORED** if a human or a seed made it up.

## Lineage

Built on the mechanisms proven in [minibank](https://github.com/igorfyago/minibank): the double-entry ledger,
caller-minted idempotency keys, the authorize/capture/release hold lifecycle (reused here as inventory
reservation), the transactional outbox, and the human-in-the-loop agent pattern where the agent proposes and a
human authorises.

## Stack

Java 21 (virtual threads, raw JDBC, JDK HttpServer) · PostgreSQL 16 · Apache Kafka · Redis · Flyway ·
Prometheus and Grafana · Docker.

## Live

- **The shop** · [mart.b4rruf3t.com](https://mart.b4rruf3t.com) · browse, buy, watch inventory and the audits move,
  and press one button to turn a population of agent customers loose on it.
- **The processor** · [pay.b4rruf3t.com](https://pay.b4rruf3t.com) · payment intents, merchant balance, and an
  interactive idempotency demo that fires the same request twice and shows you the identical response.
- **The revenue** · [mart.b4rruf3t.com](https://mart.b4rruf3t.com) → Revenue · served entirely by the analytics
  service, from a database that cannot see the shop's tables. Every movement names the event that caused it.

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

**Subscription billing (done, 4/4 lessons green).** One design rule kept this small: **a renewal is not a special
case, it is an order.** Every period runs through the same checkout a browsing customer uses, with an order id
derived from (subscription, period), so the reservation, the authorisation, the capture and every idempotency gate
are reused exactly as they are. Recurring billing needed no money path of its own.

- *180 compressed days* → 7 billing periods, 553.00 captured at the processor, in milliseconds of wall clock.
- *the scheduler fires five times on the same due date* → one invoice, one charge. The invoice
  `UNIQUE(subscription_id, period_index)` claims the period; the derived order id makes the checkout underneath
  idempotent anyway.
- *a declining card* → three scheduled retries on a backoff ladder in business time, then involuntary churn, with
  no stock consumed by a payment that never succeeded.
- *cancel at period end* → the paid period stands, the next one never bills.

A bug worth recording: the first version rewound `period_index` on failure, so every retry billed a *different*
period and the attempt counter never accumulated, meaning dunning could never give up. `period_index` is now
strictly "the last period successfully billed", and a failed attempt does not move it.

**The transactional outbox (done, 3/3 lessons green).** You cannot commit to Postgres and to Kafka atomically.
Crash between the two and you either have an order nobody was told about or, worse, an announcement for an order
that never happened. So the event is never written to two systems: it is inserted into an outbox table inside the
**same transaction** as the money and the goods, and shipped afterwards by a relay that marks it published only
once the broker has acknowledged it.

- *a committed order leaves one event, a rolled-back order leaves none.* No dual write exists to go wrong.
- *mark after ack.* Crash between send and mark and the event is sent again. At-least-once, never at-most-once,
  because a duplicate can be deduped downstream and a loss can never be detected at all.
- *the same event delivered five times is applied once*, by primary key.

The event key is the producer's job, carried as a Kafka header, and it is a different question from the partition
key: the first says which business event this is, the second says which events must stay in order relative to each
other. Conflating them is how a cancellation overtakes its own placement.

**minianalytics · a third service that owns nothing (done, 5/5 lessons green).** Its own database, its own
consumer group, and no connection to minimart at all. Everything it knows it learned from an event, which is the
position every reporting service is really in and the reason so many of them are quietly wrong.

The events carry the amount and the interval, not just ids, because a consumer that has to call back to the
producer's tables for the missing fields is not a separate service, it is a distributed join. Carrying the values
also makes the event a record of what was true **at the time**, so repricing a product next year cannot silently
rewrite last year's revenue.

- *the same event delivered five times* → one movement. The idempotency claim and the effect it guards commit
  together, in the consumer's own database.
- *three renewals* → **MRR does not move.** A renewal is cash and proof of life, not growth: that revenue was
  counted the month the subscription began. Counting every payment as new MRR is the most common way a dashboard
  shows a business growing 30% a month while the bank disagrees.
- *then the price rises 60 to 90* → **one expansion of 30**, the difference and only the difference.
- *a yearly plan at 600* → contributes 49.32 to MRR, not 600. Mixing intervals in one total is how the same
  number comes to mean different things to finance and to engineering.
- *two of three events handled* → the completeness audit reports 0.67 coverage and **names the missing event**.
  Lag is acceptable and normal; silent loss is not, because an incomplete dashboard is indistinguishable from a
  complete one and gets believed either way.

MRR is stored as movements and never as a total, for the same reason money is stored as entries: a figure you can
overwrite is a figure nobody can audit. The service exposes the same number reached two independent ways and
publishes the drift between them rather than hiding it.

**Live, in production, after 209 renewals:** MRR 4,195.00 and unchanged, cash collected 14,781.00, drift 0.00. A
dashboard that counted renewals as growth would have reported three and a half times the real figure.

**Three defects an architecture review found in shipped code (fixed, 3/3 lessons green).** All three had the same
shape: a place where the system recorded something it had not verified.

- *a failed capture was recorded as paid.* The code called `ship(...)` and threw away the boolean it returned,
  then marked the invoice paid regardless. The books claimed money the processor never released.
- *a cancelled customer could never come back.* The subscription id was derived from (tenant, customer, variant)
  and inserted `ON CONFLICT DO NOTHING`, so a resubscribe silently handed back the dead subscription and told the
  caller nothing. The id now carries a generation, so a retried subscribe is still idempotent and a returning
  customer is genuinely new.
- *one renewal was five separate commits*, so a crash between them left a period claimed and never advanced.

**A defect the live system found that no review did (fixed, 3/3 lessons green).** A customer had subscribed to a
product never stocked at their location. The renewal pass reached them, the ledger threw, the exception left
`renewOnce`, and the **entire batch stopped**: every subscription queued behind that one went unbilled and the
endpoint returned a 500 that said only "no such account".

Each subscription is now its own failure domain, and the guard is careful about whose fault a failure is:

- *one unfulfillable subscription and ten healthy ones* → all ten are billed.
- *an internal fault records no dunning attempt and no failed invoice*, releases its period claim so the next pass
  retries, and is reported as `skipped` rather than folded in with `failed`. Wrapping the loop in a catch that
  marked every error as a failed payment would push customers into the dunning ladder and eventually cancel them
  for a warehouse mistake.
- *a genuine decline still duns*, so the guard did not make the system soft.

**minifreight · the fulfilment saga against carriers that misbehave on purpose (done, 8 + 4 lessons green).** A
fourth service with its own database, learning about orders only from the topic and reaching carriers only over
HTTP. Two simulated carriers are the point: one is reliable and picky, the other writes the shipping label and
THEN returns 500, delivers every webhook twice, and can go entirely silent. What that forces the driver to be:

- *an unknown outcome freezes the carrier ladder* · falling to the next carrier on a timeout is how one parcel
  ships twice on two paid labels, so the driver asks the quiet carrier what it actually did, and **adopts** the
  label that secretly exists rather than minting another.
- *one door for every report* · webhooks and polls funnel through a single monotonic transition, minting the same
  event key for the same fact, so a duplicate is absorbed, a stale report cannot undeliver a parcel, and a
  decided shipment is nobody's to reopen.
- *webhooks are HMAC-signed and verified in constant time* · a forged "delivered" is a forged business event.
- *bounded attempts end at a person* · five unknowable passes park the shipment as `stuck`, announced by event,
  with a repair endpoint that only moves stuck shipments and journals who moved them.

**The compensation leg (done, 4/4 lessons green).** When freight fails a shipment aloud, the merchant reacts on
its own books: `sold` becomes on-hand again, wallet money returns to the customer **in the same commit**, and
card money · which stands at the processor, where no refund rail exists yet · becomes a `refund_cases` row with
the intent id and status `due`. A debt the books can name beats a payment improvised through an API that does not
exist. The audits are asserted healthy at the end of the exchange, not assumed.

### Next
- **agentic-visitors**, a separate service: customers whose decisions come from a language model rather than a
  seed, with the spend as a double-entry ledger so "cannot overspend" is the same non-negative CHECK as "cannot
  oversell", and recorded decisions so a run can be replayed without calling the model again.
- **A refund rail in minipay**, so the `due` cases can settle without a human.
- **Cohort analytics**, and **experiments** with A/A calibration before any readout is believed.
