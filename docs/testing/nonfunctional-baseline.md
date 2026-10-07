# Non-functional baseline

Measured 2026-10-04 and 2026-10-05. This is a description of how the platform behaved on one machine, not
a pass or fail: no targets have been agreed yet, and a run without targets can only be described.

## Environment

- One laptop: Intel Core Ultra 7 155H (16 cores, 22 threads), 32 GB, Windows 11, Docker Desktop with 22 CPUs and about 16 GB.
- The stack of `docker-compose.yml`: one MongoDB 7 node, one RabbitMQ 3.13 node, one console container
  (units api, ingest), one to three engine containers (all other units).
- The load generator, the database, the broker and the platform share the same machine, so they compete for CPU.
- Every external system (sanctions, accounts, fraud, compliance, FX, liquidity, posting, clearing) is the
  built-in simulator, which answers at once. Real systems will be slower, and the figures will change with them.

## Method

`perf/orvanta_perf.py` builds pain.001 files of SEPA credit transfers with a fixed error mix (about 3.2 %
fail validation, about 1 % are rejected by the clearing simulator), submits them over the API, waits until
every payment has a final status, and then reconciles:

- every payment exists exactly once,
- each has the outcome its data calls for,
- each sent payment is in exactly one outbound file,
- each posting is POSTED, or REVERSED where the payment was rejected after posting.

`perf/scenarios.py` drives the stack (scaling, fault injection, memory readings) and stores one JSON result
per run in `target/perf/`. "Final" below means ACCEPTED, REJECTED_BY_APPLICATION or REJECTED_BY_EXTERNAL.

## Results

### Sanity

A fresh stack processed one file of 20 payments end to end in 5.3 s; reconciliation OK.

### Latency of a single payment (20 samples each, idle system)

| Path | p50 | p95 | max |
|---|---|---|---|
| Bulk SEPA credit transfer, submit to ACCEPTED | 3.9 s | 4.9 s | 4.9 s |
| SEPA Instant, submit to ACCEPTED | 0.98 s | 1.03 s | 1.04 s |

The bulk figure is mostly waiting for the bulking and acknowledgement cycles, not processing: in the soak
run the time from file received to ROUTED was 0.09 s at p50.

### Throughput and scalability (8 files of 1,000 payments, submitted at once)

| Engines | Consumers per queue | Time | Payments per second | File received to final, p95 |
|---|---|---|---|---|
| 1 | 1 | 33.2 s | 241 | 29.1 s |
| 1 | 4 | 21.8 s | 368 | 20.3 s |
| 2 | 4 | 15.7 s | 510 | 14.6 s |
| 3 | 4 | 14.1 s | 566 | 13.8 s |

Throughput rises with engines but not in proportion: the second engine added 39 %, the third 11 %. What
limits it beyond two engines was not identified. Candidates are the single database node, the single
console container that ingests the files, and the load generator sharing the CPUs.

### Stress (30 files of 1,000 payments at once, 3 engines)

30,000 payments in 54.5 s, 550 per second, p95 to final 51.9 s, 855 outbound files, reconciliation OK.
The rate equals the 8,000-payment run, so the backlog only made payments wait; nothing failed. The
breaking point was not reached, so this run does not say where it is.

### Soak (10 minutes at 50 payments per second, 2 engines)

30,000 payments in 300 files over 606.6 s; reconciliation OK. File received to final: p50 1.3 s, p95 3.5 s, max 4.2 s.

| Container | Memory before | Memory after |
|---|---|---|
| engine 1 | 1.350 GiB | 1.358 GiB |
| engine 2 | 1.304 GiB | 1.310 GiB |
| console | 1.528 GiB | 1.535 GiB |
| rabbitmq | 211.9 MiB | 210.0 MiB |
| mongo | 1.178 GiB | 1.281 GiB |

No growth worth noting in the platform processes over ten minutes. Ten minutes cannot show a slow leak;
the roadmap asks for 8 to 24 hours.

### Availability and recovery (6 files of 1,000 payments, 2 engines, fault injected 6 s into the run)

Without a fault this load takes about 12 s.

| Fault | Time to all final | p95 to final | Lost | Duplicated |
|---|---|---|---|---|
| One engine container killed | 78.7 s | 16.7 s | 0 | 0 |
| Broker restarted | 83.1 s | 73.8 s | 0 | 0 |
| Database restarted | 16.1 s | 15.1 s | 0 | 0 |
| Every engine killed, started again after 20 s | 77.1 s | 48.1 s | 0 | 0 |

Reconciliation was OK in all four. The cost of a fault is delay: work that was in flight when a process or
the broker died is picked up by the recovery unit, which runs every 60 s. With one engine killed most
payments were unaffected (p95 16.7 s) and a small tail waited for that cycle (max 78.6 s). After a broker
restart most payments waited for it.

### Console requests on 100,000 payments (measured 2026-10-05)

`perf/console_perf.py` wrote 100,000 made-up payments into a separate MongoDB database (4.2, on the same
laptop, not in Docker), a server was started on it, and each request was sent seven times. Times are in
milliseconds at the client, signed in as an operator. The goal in the roadmap is 200 ms per list interaction.

| Request | Median | Slowest | Matches |
|---|---|---|---|
| First page, newest first | 9.7 | 24.8 | 100,000 |
| Page 100 (offset 5,000) | 11.6 | 34.4 | 100,000 |
| Last page (offset near the end) | 140.4 | 206.2 | 100,000 |
| One status | 29.8 | 34.2 | 1,016 |
| Status and route | 23.3 | 32.8 | 21,439 |
| One day | 31.3 | 36.2 | 14,259 |
| Amount range | 30.3 | 33.9 | 193 |
| Sorted by amount | 30.6 | 32.5 | 100,000 |
| Sorted by last change | 28.4 | 32.0 | 100,000 |
| Search: a whole id | 15.7 | 30.4 | 1 |
| Search: a common name | 254.3 | 290.9 | 8,328 |
| Search: text found nowhere | 18.3 | 30.3 | 0 |
| Search within one status | 191.7 | 288.8 | 86 |
| One payment | 15.8 | 32.0 | |
| Dashboard | 133.4 | 171.6 | |

Three requests are at or over the goal: a search for a word that thousands of payments contain, a search
combined with a status, and the very last page of the whole list. The first measurement, before indexes
were added, had the first page at 193 ms, any search at about 1,700 ms and the dashboard at 843 ms.

What was changed to get here: the list is ordered by the collection key; indexes on time, amount, status
with route and status with time; a word index for search, which is why search matches whole words and not
parts of words; the dashboard counts per status in one grouped query and per day from the status-and-time
index.

Not measured: the time in the browser (only the server's answer), more than one user at a time, a store
larger than 100,000 payments, and the instruction, outbound and approval lists, which are not paged yet.
The dashboard's count by status reads every index entry, so it grows with the number of payments.

## Defects found and fixed during these runs

1. An event published before the consuming process had created its queue was dropped by the broker and
   only picked up by recovery 60 s later. Every process now declares all service queues at start.
2. The clearing simulator did not answer a file delivered by an engine that was killed before it announced
   the delivery. The simulator now also sweeps for sent files without an answer. (Simulator only.)

## Not measured

- Slow, failing or timing-out external systems under load; network faults; a full disk.
- Loss of the console container (the API and ingest have one instance in this stack).
- Database or broker high availability: both are single nodes here, so their restart is an outage that
  the platform waits out. A replica set and a broker cluster were not tested.
- Restore from a backup.
- A soak longer than 10 minutes.
- Where the platform breaks under stress, and what limits scaling beyond two engines.
- SWIFT, direct debit, statements and MT101 input under load; the generator produces SEPA credit transfers
  and SEPA Instant only.
- Any run on hardware like production. Figures from a laptop that also runs the load generator are a
  baseline for comparison between builds, not a capacity statement.

## Run it again

```
docker compose up -d --build
python perf/orvanta_perf.py load --files 2 --size 1000
python perf/orvanta_perf.py latency
python perf/orvanta_perf.py instant
python perf/scenarios.py scale
python perf/scenarios.py stress
python perf/scenarios.py faults
python perf/scenarios.py soak --minutes 10
```

The Console timing needs a MongoDB and no Docker; it refuses to touch the database named in `config/orvanta.yaml`:

```
python perf/console_perf.py seed --database orvanta_perf --count 100000
java -jar orvanta-pay/target/orvanta-pay-0.1.0-all.jar --config=config/orvanta.yaml --store.database=orvanta_perf --units=api --server.port=8499 --workspace.writeBack=false
python perf/console_perf.py time --url http://localhost:8499
python perf/console_perf.py drop --database orvanta_perf
```

Keep the machine from sleeping during long runs; Docker Desktop stops when it does.
