# Performance of the GET endpoints

> Task 3: "in production the GET endpoints can get very slow. The database is not co-located with the application
> server, and there's high latency between the two."

With a remote database, the cost of a request is roughly:

```
latency ≈ (database round trips × RTT) + (rows and bytes transferred / bandwidth) + JSON serialisation
```

The RTT term usually dominates, so the goal is a **small, constant number of SQL statements per request** that does not
depend on the page size or the amount of data, plus **bounded result sizes**.

All numbers below were measured against the seed data (100 customers, 10,000 orders) on PostgreSQL 16.2 via
Testcontainers. Statement and entity counts come from Hibernate `Statistics` (enabled in the `test` profile only, read
through `SqlStatementCounter` in the integration tests). Payload sizes are the uncompressed JSON body; gzip sizes are
what the client actually receives now that compression is enabled.

## Findings

| # | Problem | Why it hurts with a remote DB |
|---|---------|-------------------------------|
| 1 | **No pagination.** `GET /customer` and `GET /order` returned every row. | Response time, memory and payload grow linearly with the table. 10,000 orders is already ~1 MB of JSON; a production table would time out or exhaust the heap. |
| 2 | **N+1 loading of associations.** `Customer.orders` (one-to-many) and `Order.customer` (many-to-one) are lazy and were loaded while mapping to DTOs, one query per entity. | Every extra query is a full round trip. The original `default_batch_fetch_size: 10` hid this as "1 + N/10"; without it the endpoints ran 101 statements each. |
| 3 | **No index on `order.customer_id`.** PostgreSQL does not index foreign key columns automatically. | Loading the orders of a set of customers is a sequential scan of the whole order table, and so is every FK check when a customer row is deleted or its key is updated. |
| 4 | **Pool and driver defaults.** No explicit Hikari sizing or timeouts, no `reWriteBatchedInserts`, no response compression. | Connection setup is several round trips (TCP, TLS, auth) and must never happen on the request path. Large uncompressed JSON wastes bandwidth to the client. |

### Baseline

| Request | Statements | Entities loaded | Lazy collection fetches | Body |
|---------|-----------:|----------------:|------------------------:|-----:|
| `GET /customer` (all) | **11** | 10,100 | 10 | 503,458 B |
| `GET /order` (all) | **11** | 10,100 | 0 | 950,440 B |
| `GET /customer` with `default_batch_fetch_size=1` | **101** | 10,100 | 100 | 503,458 B |
| `GET /order` with `default_batch_fetch_size=1` | **101** | 10,100 | 0 | 950,440 B |

At a 50 ms RTT, that is ~550 ms of pure network wait per request with batch fetching (~5 s without), before
transferring 10,100 rows and serialising up to ~1 MB of JSON.

## Changes made

### 1. Pagination (bounded result size)

- `page` (0-based, default 0) and `size` (default 20, max 100; larger values are **clamped**, a negative page or a size
  below 1 is a **400** ProblemDetail). Results are always ordered by `id` ascending so pages are stable.
- The body is still a plain JSON array (backward compatible). Metadata is in the headers: `X-Total-Count` and an RFC 8288
  `Link` header (`first`, `prev`, `next`, `last`).
- All of this lives in one place, `controller/Pagination`, so the coming endpoints (customer search, products) reuse it.
  Links keep any other query parameter (e.g. a future `name=`).

### 2. Constant number of statements per request

**`GET /order`**: `OrderRepository.findAll(Pageable)` is overridden with `@EntityGraph(attributePaths = "customer")`,
so the page comes back with its customers in one join. Combining a join fetch with LIMIT/OFFSET is safe here because
`customer` is a to-one association: the join does not multiply rows.

```sql
select o.id, o.customer_id, c.id, c.name, o.description
from "order" o join customer c on c.id = o.customer_id
order by o.id offset ? rows fetch first ? rows only;
select count(o.id) from "order" o;
```

**`GET /customer`**: join-fetching a **collection** with a `Pageable` makes Hibernate load every row and paginate in
memory (HHH90003004). There were two candidate approaches:

- (a) **Chosen:** page the customers, then load the orders of all those customers in a single
  `WHERE customer_id IN (:ids)` query and group them in `CustomerService`.
- (b) Batch fetching with a batch size ≥ 100.

Both give three statements. (a) was chosen because:

- the fetch plan is explicit and reviewable in the service and repository;
- it does not depend on a global Hibernate setting, on what is in the persistence context, or on the max page size staying
  ≤ the batch size;
- it transfers only the order columns. A join-fetch of customers with orders would repeat the customer columns on every
  order row.

The lazy `Customer.orders` collection is no longer touched on this path (`collectionFetches = 0` is asserted in the
tests).

```sql
select c.id, c.name from customer c order by c.id offset ? rows fetch first ? rows only;
select count(c.id) from customer c;
select o.id, o.customer_id, o.description from "order" o where o.customer_id in (?, ?, ...) order by o.id;
```

The orders query is written in JPQL on purpose. The equivalent derived query (`findByCustomerIdIn`) generates an
unnecessary inner join on `customer`.

Spring Data skips the count query when the page shows the total already (e.g. a first page that is not full), so these
are upper bounds.

**Safety nets** (`application.yaml`):

- `hibernate.default_batch_fetch_size: 100` (was 10). Any lazy association loaded outside these explicit fetch plans
  (e.g. by future code) costs one query per 100 entities, not one per entity.
- `hibernate.query.fail_on_pagination_over_collection_fetch: true`. If someone combines a collection fetch with
  pagination, it now fails fast instead of logging a warning and silently loading the whole table into memory.
- `hibernate.query.in_clause_parameter_padding: true`. IN lists are padded to a power of two (the 100-id list above uses
  128 parameters), so different page sizes share a handful of SQL strings and server-side prepared statements / plans.

### 3. Index on `order.customer_id`

`db.changelog-4.yaml`: `CREATE INDEX CONCURRENTLY idx_order_customer_id ON "order" (customer_id)`.

- `CONCURRENTLY` (with `runInTransaction: false`) so that building it on a large production table does not block writes.
- There is deliberately no `IF NOT EXISTS`. A failed concurrent build leaves an `INVALID` index behind. Liquibase does not
  mark the changeset as run, and the rerun must then fail loudly (drop the invalid index and retry) rather than silently
  skip a useless index. `SchemaIntegrationTest` asserts the index exists and is valid.

The planner uses it for the IN query:

```
Bitmap Heap Scan on "order"  (actual time=0.091..0.444 rows=1941 loops=1)
  Recheck Cond: (customer_id = ANY ('{1,...,20}'::bigint[]))
  Buffers: shared hit=125
  ->  Bitmap Index Scan on idx_order_customer_id  (actual time=0.075..0.076 rows=1941 loops=1)
```

### 4. Connection pool, driver and HTTP settings

| Setting | Value (env override) | Why |
|---------|---------------------|-----|
| `hikari.maximum-pool-size` / `minimum-idle` | 10 / 10 (`DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE`) | Fixed-size pool, as HikariCP recommends. New connections are expensive over a high-latency link, so they are opened up front, never on the request path. |
| `hikari.connection-timeout` | 5 s (`DB_CONNECTION_TIMEOUT_MS`) | Fail fast with an error when the pool is exhausted instead of queueing requests for the 30 s default. |
| `hikari.max-lifetime` / `keepalive-time` | 30 min / 2 min | Recycle and keep alive connections before firewalls or NAT between the app and the DB drop idle ones silently. |
| `reWriteBatchedInserts` (pgjdbc) | `true` | A JDBC batch of inserts becomes multi-row INSERTs: one round trip per batch. Set as a data-source property so it survives a `DB_URL` override. |
| `server.compression` | on, `application/json` and `application/problem+json`, `min-response-size: 1KB` | JSON compresses ~6-7x here (see below). Tomcat only applies the 1 KB threshold when it knows the `Content-Length`; Spring MVC streams JSON chunked, so in practice every JSON response is compressed for clients that send `Accept-Encoding: gzip`. That is cheap and harmless. |

**Note, not changed: `GenerationType.IDENTITY` disables JDBC insert batching.** Hibernate must execute each INSERT
immediately to read back the generated id, so `reWriteBatchedInserts` and `hibernate.jdbc.batch_size` have no effect on
our entities today. It does not matter for the current endpoints (one insert per POST). Bulk imports would need a pooled
sequence (`@SequenceGenerator(allocationSize = 50)` with a matching `INCREMENT BY 50` on the sequence). That gives one
`nextval` per 50 rows and then real batched inserts. It is a schema plus entity change with a migration to plan, see
"Considered but not done".

### 5. Read-only transactions

Both services are `@Transactional(readOnly = true)` at class level, with `@Transactional` only on the write methods
(confirmed, no change needed). For every GET this means:

- Hibernate sets the session to `FlushMode.MANUAL` and skips dirty checking, so no snapshot comparison of the up to
  10,100 loaded entities at commit.
- Spring marks the JDBC connection read-only. With pgjdbc's default `readOnlyMode=transaction`, that becomes
  `BEGIN READ ONLY`, sent together with the first query, so no extra round trip.
- It is the hook for routing reads to a replica later (e.g. a routing `DataSource` keyed on
  `TransactionSynchronizationManager.isCurrentTransactionReadOnly()`).

Mapping to DTOs happens inside that transaction (open-in-view is off), so there is no lazy loading after the
transaction ends.

## Results

| Request | Statements (before → after) | Entities loaded | Body | Body gzipped |
|---------|----------------------------|----------------:|-----:|-------------:|
| `GET /customer` (default page of 20) | 11 → **3** | 10,100 → 1,961 | 503,458 → 97,674 B | 15,390 B |
| `GET /customer?size=100` (max) | 11 → **3** | 10,100 → 10,100 | 503,458 → 503,458 B | 74,973 B |
| `GET /order` (default page of 20) | 11 → **2** | 10,100 → 38 | 950,440 → 1,861 B | 639 B |
| `GET /order?size=100` (max) | 11 → **2** | 10,100 → 163 | 950,440 → 9,398 B | 2,122 B |

At a 50 ms RTT, network wait drops from ~550 ms (~5 s without batch fetching) to ~150 ms for `/customer` and ~100 ms for
`/order`, and it **stays there for any page size and any table size**. (Each transaction also has one commit round
trip, before and after alike.) The `GET /customer?size=100` row is the same data as the old "all customers" response,
because the seed has exactly 100 customers. For that request the gain is only in round trips and compression; see the
residual risk below.

These gains are locked in by integration tests:

- `CustomerIntegrationTest`: ≤ 3 statements and no lazy collection fetches for size 1, 20 and 100.
- `OrderIntegrationTest`: ≤ 2 statements for size 1, 20 and 100. Also covers the `X-Total-Count` / `Link` headers on the
  first, middle, last and past-the-end pages, clamping to 100, and 400 on invalid paging.
- Both classes keep the existing JSON shape checks.
- `CompressionIntegrationTest`: gzip on a real port.
- `SchemaIntegrationTest`: the index exists and is valid.

## Considered but deliberately not done

| Option | Why not (now) |
|--------|---------------|
| **Second-level cache / Redis** | Removes round trips, but adds invalidation complexity, staleness, and another piece of infrastructure (Redis is itself remote). The read paths are already a constant 2-3 statements. Revisit with production metrics showing hot, rarely-changing data. |
| **Keyset (seek) pagination** | `OFFSET n` still makes PostgreSQL walk and discard *n* index entries: page 500 of orders reads ~10,000 rows to return 20 (6.6 ms here, growing with depth). Keyset (`WHERE id > :lastId ORDER BY id LIMIT :size`) is O(page) at any depth and immune to rows shifting between requests, but needs a cursor parameter and no random page access. That is a contract change, so it is not done now. It can be added later alongside `page` (e.g. `after=<id>`), with the `next` link carrying the cursor. |
| **Exact `COUNT(*)` on large tables** | Kept for `X-Total-Count`: it is a sequential or index-only scan (1.4 ms for 10,000 rows here) that grows with the table. If it becomes significant: an estimate from `pg_class.reltuples`, caching the count briefly, or dropping the total in favour of keyset `next` links. |
| **Read replicas** | Read-only transactions already make routing possible, but it is an infrastructure decision with replication-lag semantics (read-your-own-writes after a POST). Not justified before measuring primary load. |
| **DTO projections instead of entities** | Would skip entity hydration and persistence-context bookkeeping (the `readOnly` transaction already removes dirty checking). The entity path needs no extra code with MapStruct, and with bounded pages it is not the bottleneck. A good next step for `/customer` if profiling shows hydration cost. |
| **Split `/customer`'s embedded orders into `/customer/{id}/orders`** | The proper fix for the residual risk below, but it breaks the public `CustomerDTO` contract. It needs a versioning/deprecation decision, so it is a recommended follow-up, not done here. |
| **IDENTITY → pooled sequence** | Only helps bulk inserts (see above), which no endpoint does. It changes the schema and entities and needs a migration that aligns `INCREMENT BY` with the existing sequences, so it is not worth the risk in this phase. |
| **`hikari.auto-commit=false` + `hibernate.connection.provider_disables_autocommit`** | Lets Hibernate delay taking a connection and skip the auto-commit toggle. pgjdbc does that toggle client-side (no round trip), so the gain is small. Left as a tuning option. |

## Known residual risk

`GET /customer` still embeds **all** orders of each customer on the page. The number of statements is bounded (3), but
the **rows and bytes are not**: with the seed data a full page of 100 customers is still 10,100 entities and ~500 KB of
JSON (75 KB gzipped). A single customer with 100,000 orders would make even a page of one customer arbitrarily large.

Pagination limits the number of customers, not the size of each one. The recommended follow-up is a contract change:

- drop or cap the embedded `orders`;
- expose `GET /customer/{id}/orders` (paginated with the same `Pagination` contract, served by `idx_order_customer_id`);
- or return order ids / an order count instead of full orders.

## How to verify in production

1. **Count statements per request.** Temporarily set `spring.jpa.properties.hibernate.generate_statistics=true` (or
   `logging.level.org.hibernate.stat=DEBUG` for a per-session summary) on one instance. Expect 3 statements for
   `/customer` and 2 for `/order`. Turn it off afterwards; statistics have a cost.
2. **Watch the pool** through the Hikari metrics (`hikaricp.connections.pending`, `.acquire`, `.usage`, `.timeout`) once
   Actuator/Micrometer is wired in. Pending connections or timeouts mean the pool is too small or queries are slow.
3. **Check the plans** in `psql`, with real ids:

   ```sql
   -- Orders of one page of customers: expect a Bitmap/Index Scan on idx_order_customer_id, not a Seq Scan.
   EXPLAIN (ANALYZE, BUFFERS)
   SELECT id, description, customer_id FROM "order" WHERE customer_id IN (1, 2, 3, 4, 5) ORDER BY id;

   -- A deep page of orders: watch the rows read vs. returned to judge whether keyset pagination is needed.
   EXPLAIN (ANALYZE, BUFFERS)
   SELECT o.id, o.description, c.id, c.name
   FROM "order" o JOIN customer c ON c.id = o.customer_id
   ORDER BY o.id OFFSET 9980 LIMIT 20;

   -- The X-Total-Count query.
   EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM "order";

   -- The index is valid (false would mean a failed CREATE INDEX CONCURRENTLY).
   SELECT indexrelid::regclass, indisvalid FROM pg_index WHERE indexrelid = 'idx_order_customer_id'::regclass;
   ```

4. **Find slow or chatty statements** with `pg_stat_statements` (`calls`, `mean_exec_time`, `rows`, ordered by
   `total_exec_time`). A high `calls` count for a single-row SELECT on `order` or `customer` is the signature of a
   reintroduced N+1.
5. **Measure RTT** from the application host (e.g. `SELECT 1` timing). Multiply it by the statement counts above to see
   the share of latency due to round trips.
