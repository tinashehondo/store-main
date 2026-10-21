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
- All of this lives in one place, `controller/Pagination`, shared by `/customer`, the customer search and `/products`.
  Links keep any other query parameter, such as the search term in `name=`.
- `page` is capped at 21,474,836 (`Integer.MAX_VALUE / 100`): beyond that the row offset `page × size` overflows an
  `int` and Spring Data fails, so a larger page is a 400 rather than a 500.

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
our entities today. (`batch_size` does batch the `order_product` join rows, which have no generated id; see
"Products".) It does not matter for the current endpoints (one entity insert per POST). Bulk imports would need a pooled
sequence (`@SequenceGenerator(allocationSize = 50)` with a matching `INCREMENT BY 50` on the sequence). That gives one
`nextval` per 50 rows and then real batched inserts. It is a schema plus entity change with a migration to plan, see
"Considered but not done".

### 5. Read-only transactions

All three services (`CustomerService`, `OrderService`, `ProductService`) are `@Transactional(readOnly = true)` at class level, with `@Transactional` only on the write methods
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
| `GET /customer` (default page of 20) | 11 → **3** | 10,100 → 1,961 | 503,458 → 97,674 B | 15,396 B |
| `GET /customer?size=100` (max) | 11 → **3** | 10,100 → 10,100 | 503,458 → 503,458 B | 74,980 B |
| `GET /order` (default page of 20) | 11 → **3** | 10,100 → 38 | 950,440 → 2,141 B | 657 B |
| `GET /order?size=100` (max) | 11 → **3** | 10,100 → 163 | 950,440 → 10,798 B | 2,159 B |

`GET /order` was 2 statements until products were added; the third is the `IN` query that loads the products of the
page (see "Products"). Its body now includes `"products": []` for every seed order, which predates products. Bodies
were re-measured on the current code against the seed data; the gzip sizes are what `curl -H 'Accept-Encoding: gzip'`
receives.

At a 50 ms RTT, network wait drops from ~550 ms (~5 s without batch fetching) to ~150 ms for `/customer` and `/order`,
and it **stays there for any page size and any table size**. (Each transaction also has one commit round
trip, before and after alike.) The `GET /customer?size=100` row is the same data as the old "all customers" response,
because the seed has exactly 100 customers. For that request the gain is only in round trips and compression; see the
residual risk below.

These gains are locked in by integration tests:

- `CustomerIntegrationTest`: ≤ 3 statements and no lazy collection fetches for size 1, 20 and 100.
- `OrderIntegrationTest`: ≤ 3 statements for size 1, 20 and 100, and ≤ 2 for `GET /order/{id}`. Also covers the `X-Total-Count` / `Link` headers on the
  first, middle, last and past-the-end pages, clamping to 100, and 400 on invalid paging.
- Both classes keep the existing JSON shape checks.
- `CompressionIntegrationTest`: gzip on a real port.
- `SchemaIntegrationTest`: the index exists and is valid.

## Order lookup and customer search

### `GET /order/{id}`: two statements

`OrderRepository.findWithCustomerById` is a derived query with `@EntityGraph(attributePaths = "customer")`. It
replaces a `findById` followed by a lazy load of the customer (two round trips) with one statement:

```sql
select o.id, o.customer_id, c.id, c.name, o.description
from "order" o join customer c on c.id = o.customer_id
where o.id = ?;
```

The second statement is the products of the order (see "Products"). `OrderIntegrationTest` asserts at most 2
statements.

### `GET /customer?name=`: substring search backed by a trigram index

The search is a case-insensitive substring match. User input is escaped (`\`, `%`, `_`), so wildcards in it are matched
literally. It has the same fetch plan as the list (page, count, one `IN` query for the orders), so it is at most **3
statements** for any page size. Hibernate generates:

```sql
select c.id, c.name from customer c
where lower(c.name) like lower(?) escape '\'
order by c.id offset ? rows fetch first ? rows only;
select count(c.id) from customer c where lower(c.name) like lower(?) escape '\';
```

A pattern with a leading wildcard cannot use a B-tree index, so without help every search is a sequential scan of
`customer`. `db.changelog-5.yaml` adds:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX CONCURRENTLY idx_customer_name_trgm ON customer USING gin (lower(name) gin_trgm_ops);
```

- **The expressions must match.** The index is on `lower(name)` and the query filters on `lower(c.name)`. With
  `upper(...)`, a different collation or no function at all, the planner cannot use the index.
  `SchemaIntegrationTest` asserts that the index definition is exactly this, and that the planner can use it for the
  search predicate (`EXPLAIN` with `SET LOCAL enable_seqscan = off`, because on 100 seed rows a seq scan is rightly
  cheaper).
- **Both sides are lowered by PostgreSQL** (`lower(:pattern)`), not one in Java and one in SQL. Case folding then
  follows a single set of rules, the database's. Java's `toLowerCase` differs from PostgreSQL's `lower()` for some
  characters, such as `İ`.
- **`CREATE EXTENSION` needs elevated privileges.** On managed PostgreSQL (RDS, Cloud SQL, Azure) the application user
  usually cannot run it, so it is a **DBA step before deployment**. `pg_trgm` is on the allow-list of all of them.
  `IF NOT EXISTS` then makes the changeset a no-op. Its rollback deliberately does not drop the extension.
- **Trigrams only help for terms of at least 3 characters.** A 1-2 character term yields no complete trigram, so the
  index cannot narrow the search and PostgreSQL scans the table.
- `CONCURRENTLY` with `runInTransaction: false`, and no `IF NOT EXISTS`, for the same reasons as
  `idx_order_customer_id`.

Expected plan (seed data, `enable_seqscan = off`, or any table large enough that a seq scan is not cheaper):

```
Aggregate
  ->  Bitmap Heap Scan on customer c
        Recheck Cond: (lower((name)::text) ~~ '%onnel%'::text)
        ->  Bitmap Index Scan on idx_customer_name_trgm
              Index Cond: (lower((name)::text) ~~ '%onnel%'::text)
```

Measured on a scratch PostgreSQL 16.2 with **1,000,000 generated customers** (default planner settings,
`EXPLAIN ANALYZE`):

| Term | Page query (`ORDER BY id LIMIT 20`) | Count query (`X-Total-Count`) |
|------|-------------------------------------|-------------------------------|
| `onnel` (1 match) | Bitmap Index Scan on `idx_customer_name_trgm` + sort, **2.2 ms** | Bitmap Index Scan, **1.2 ms** |
| `abc` (2,867 matches) | Index Scan on `customer_pkey` + filter, 6.1 ms | Bitmap Index Scan, **8.6 ms** |
| `ab` (53,196 matches, 2 chars) | Index Scan on `customer_pkey` + filter, 0.2 ms | Parallel **Seq Scan**, **235 ms** |

How to read this:

- **The page query plan depends on the term, and that is correct.** For a frequent term, walking the primary key in id
  order and stopping after 20 matches is cheaper than collecting every match and sorting. The planner chooses from its
  selectivity estimates, so no single plan is asserted for the page query.
- **The count query is the expensive one for short terms.** It must visit every match. For terms under 3 characters it
  is a full scan, which grows with the table. If this becomes a problem, the options are a minimum term length of 3
  (a contract change: today only blank is rejected), an estimated or capped count, or dropping `X-Total-Count` for
  searches. See "Exact `COUNT(*)` on large tables" below.
- GIN indexes slow down writes a little (`fastupdate` defers part of the cost) and are larger than B-trees. For a name
  column that is rarely updated, that is a good trade.

**Accepted semantics:** matching is a substring of the whole name, not of each word. That satisfies "a substring of
one of the words in their name". The only difference is that a term containing a space (`"el Do"`) can match across a
word boundary ("Muri**el Do**nnelly").

## Products

Products are linked to orders many-to-many through `order_product(order_id, product_id)` (`db.changelog-6.yaml`).
`Order` owns a lazy `@ManyToMany Set<Product> products`, which is only used to write the join rows. `Product` does not
map its orders at all: a popular product can be in thousands of orders, and the API only needs their ids.

### Indexes

| Index | Serves |
|-------|--------|
| `PRIMARY KEY (order_id, product_id)` | "products of these orders" (`GET /order`, `GET /order/{id}`): `order_id` is the leading column. |
| `idx_order_product_product_id (product_id, order_id)` | "orders containing these products" (`GET /products`, `GET /products/{id}`), and the FK check when a product is deleted. |

The second index includes `order_id` (the spec asked for `product_id` alone). The order-ids query filters on
`product_id` and returns `order_id` sorted. With both columns in the index, it is an **index-only scan that is already
in the right order**: no sort and no heap access once the table is vacuumed. On a two-column table that costs nothing
extra.

Measured on a scratch PostgreSQL 16.2 with 200,000 orders, 1,000 products and 600,000 join rows (`VACUUM ANALYZE`d),
for one page of 20 products and one page of 20 orders:

```
-- Order ids of 20 products (12,000 rows): no Sort node, no heap access.
Index Only Scan using idx_order_product_product_id on order_product  (actual time=0.041..1.676 rows=12000 loops=1)
  Index Cond: (product_id = ANY ('{1,...,20}'::bigint[]))
  Heap Fetches: 0
Execution Time: 2.178 ms

-- Products of 20 orders (60 rows): the primary key finds the join rows; product is small enough to hash.
Sort  ->  Hash Join  ->  Index Only Scan using order_product_pkey on order_product op
                             Index Cond: (order_id = ANY ('{101,...,120}'::bigint[]))
Execution Time: 0.309 ms
```

Both indexes are built with a plain `CREATE INDEX` in the same transaction as `CREATE TABLE`. The table is new and
empty, so there is nothing for `CONCURRENTLY` to protect.

### Fetch plans

Every read is the same "page, then one `IN` query, grouped in Java" pattern as `/customer`. The lazy `Order.products`
collection is never initialised on a read path; the mapper has no single-argument `orderToOrderDTO` that could touch
it. The join-table queries are native SQL because JPQL could only reach `order_product` through `Order.products`, which
adds a join on `"order"` that nothing needs.

| Request | Statements | SQL |
|---------|-----------:|-----|
| `GET /order` (any page size) | **3** (was 2) | the page with customer join-fetched, the count, then `SELECT op.order_id, p.id, p.description FROM order_product op JOIN product p ON p.id = op.product_id WHERE op.order_id IN (…) ORDER BY op.order_id, op.product_id` |
| `GET /order/{id}` | **2** (was 1) | the order with its customer, then the same products query for one id |
| `GET /products` (any page size) | **3** | `SELECT p.id, p.description FROM product p ORDER BY p.id OFFSET … LIMIT …`, the count, then `SELECT product_id, order_id FROM order_product WHERE product_id IN (…) ORDER BY product_id, order_id` |
| `GET /products/{id}` | **2** | `findById`, then the order-ids query for one id |
| `POST /order` with N products | **4** | the customer, all N products in one `findAllById` (`IN`), the order `INSERT`, and one JDBC batch for the N `order_product` rows |

Join-fetching `products` together with a `Pageable` was ruled out for the same reason as for customers (HHH90003004, now
an error through `fail_on_pagination_over_collection_fetch`). Empty pages skip the `IN` query.

**Batched join-table inserts.** `hibernate.jdbc.batch_size: 50` is now set. `IDENTITY` entities still insert one at a
time (see above), but join rows have no generated id. Hibernate sends them as one JDBC batch, which
`reWriteBatchedInserts` turns into a single multi-row `INSERT`: one round trip for up to 50 products rather than one per
product. `productIds` is capped at 100 per order, which also bounds the `IN` list of the product lookup.

The integration tests assert all of these counts, with `collectionFetches == 0` on every read
(`ProductIntegrationTest`, `OrderIntegrationTest`).

### Residual risk: unbounded order ids per product

`GET /products` and `GET /products/{id}` return the ids of **every** order that contains each product, because that is
what the task asks for. The statement count stays constant, but the payload does not: a product in 1,000,000 orders
returns 1,000,000 ids (~8 MB of JSON), and a page of 100 popular products multiplies that. This is the same shape of
problem as the customers' embedded orders below.

The recommended follow-up is a paginated `GET /products/{id}/orders` (the same `Pagination` contract, served by
`idx_order_product_product_id` as a keyset- or offset-paged index-only scan). The embedded list could then be capped,
or replaced by an order count. Both are contract changes, so neither is done here.

## Considered but deliberately not done

| Option | Why not (now) |
|--------|---------------|
| **Second-level cache / Redis** | Removes round trips, but adds invalidation complexity, staleness, and another piece of infrastructure (Redis is itself remote). The read paths are already a constant 2-3 statements. Revisit with production metrics showing hot, rarely-changing data. |
| **Keyset (seek) pagination** | `OFFSET n` still makes PostgreSQL walk and discard *n* index entries: page 500 of orders reads ~10,000 rows to return 20 (6.6 ms here, growing with depth). Keyset (`WHERE id > :lastId ORDER BY id LIMIT :size`) is O(page) at any depth and immune to rows shifting between requests, but needs a cursor parameter and no random page access. That is a contract change, so it is not done now. It can be added later alongside `page` (e.g. `after=<id>`), with the `next` link carrying the cursor. |
| **Exact `COUNT(*)` on large tables** | Kept for `X-Total-Count`: it is a sequential or index-only scan (1.4 ms for 10,000 rows here) that grows with the table. If it becomes significant: an estimate from `pg_class.reltuples`, caching the count briefly, or dropping the total in favour of keyset `next` links. |
| **Read replicas** | Read-only transactions already make routing possible, but it is an infrastructure decision with replication-lag semantics (read-your-own-writes after a POST). Not justified before measuring primary load. |
| **DTO projections instead of entities** | Would skip entity hydration and persistence-context bookkeeping (the `readOnly` transaction already removes dirty checking). The entity path needs no extra code with MapStruct, and with bounded pages it is not the bottleneck. A good next step for `/customer` if profiling shows hydration cost. |
| **Split `/customer`'s embedded orders into `/customer/{id}/orders`** | The proper fix for the residual risk below, but it breaks the public `CustomerDTO` contract. It needs a versioning/deprecation decision, so it is a recommended follow-up, not done here. |
| **IDENTITY → pooled sequence** | Only helps bulk inserts (see above), which no endpoint does. It changes the schema and entities and needs a migration that aligns `INCREMENT BY` with the existing sequences, so it is not worth the risk without a bulk-insert use case. |
| **`hikari.auto-commit=false` + `hibernate.connection.provider_disables_autocommit`** | Lets Hibernate delay taking a connection and skip the auto-commit toggle. pgjdbc does that toggle client-side (no round trip), so the gain is small. Left as a tuning option. |

## Known residual risk

`GET /customer` still embeds **all** orders of each customer on the page. The number of statements is bounded (3), but
the **rows and bytes are not**: with the seed data a full page of 100 customers is still 10,100 entities and ~500 KB of
JSON (75 KB gzipped). A single customer with 100,000 orders would make even a page of one customer arbitrarily large.

Pagination limits the number of customers, not the size of each one. The recommended follow-up is a contract change:

- drop or cap the embedded `orders`;
- expose `GET /customer/{id}/orders` (paginated with the same `Pagination` contract, served by `idx_order_customer_id`);
- or return order ids / an order count instead of full orders.

The same applies to the order ids embedded in `/products` (see "Residual risk: unbounded order ids per product").

## How to verify in production

1. **Count statements per request.** Temporarily set `spring.jpa.properties.hibernate.generate_statistics=true` (or
   `logging.level.org.hibernate.stat=DEBUG` for a per-session summary) on one instance. Expect 3 statements for
   `/customer` (with or without `name`), `/order` and `/products`, and 2 for `/order/{id}` and `/products/{id}`. Turn it
   off afterwards; statistics have a cost. Hibernate only counts its own statements: the first time a pooled connection
   runs the native join-table queries, pgjdbc also sends one `pg_catalog` metadata query, which shows up in
   `pg_stat_statements` but not in these statistics.
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

   -- Order ids of a page of products: expect an Index Only Scan on idx_order_product_product_id, with no Sort.
   EXPLAIN (ANALYZE, BUFFERS)
   SELECT product_id, order_id FROM order_product WHERE product_id IN (1, 2, 3) ORDER BY product_id, order_id;

   -- Customer search: expect a Bitmap Index Scan on idx_customer_name_trgm for selective terms of 3+ characters.
   EXPLAIN (ANALYZE, BUFFERS)
   SELECT count(id) FROM customer WHERE lower(name) LIKE lower('%onnel%') ESCAPE '\';

   -- The index is valid (false would mean a failed CREATE INDEX CONCURRENTLY).
   SELECT indexrelid::regclass, indisvalid FROM pg_index
   WHERE indexrelid IN ('idx_order_customer_id'::regclass, 'idx_customer_name_trgm'::regclass,
                        'idx_order_product_product_id'::regclass);
   ```

4. **Find slow or chatty statements** with `pg_stat_statements` (`calls`, `mean_exec_time`, `rows`, ordered by
   `total_exec_time`). A high `calls` count for a single-row SELECT on `order` or `customer` is the signature of a
   reintroduced N+1.
5. **Measure RTT** from the application host (e.g. `SELECT 1` timing). Multiply it by the statement counts above to see
   the share of latency due to round trips.
