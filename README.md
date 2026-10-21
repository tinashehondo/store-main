# Store Application
The Store application keeps track of customers, orders and products in a PostgreSQL database, behind a small REST API.

- [Overview and architecture](#overview-and-architecture)
- [Running locally](#running-locally)
- [API summary](#api-summary)
- [Assumptions and decisions](#assumptions-and-decisions)
- [Performance](#performance)
- [CI/CD](#cicd)
- [Testing](#testing)
- [What I'd do next](#what-id-do-next)
- [Brief](#brief) (the original task text, with where each item is handled)

## Overview and architecture

Spring Boot 3.4 on Java 17, Spring Data JPA (Hibernate 6) on PostgreSQL 16, Liquibase migrations, MapStruct mappers.

```
controller/   REST endpoints, request validation, the shared Pagination contract (array body + X-Total-Count/Link)
service/      read-only transactions by default; explicit fetch plans ("page, then one IN query")
repository/   Spring Data repositories, entity graphs, the native join-table queries
entity/       Customer, Order, Product (Order ↔ Product many-to-many through order_product)
dto/          the JSON contract: response DTOs and request records with bean validation
mapper/       MapStruct entity → DTO mappers
exception/    GlobalExceptionHandler: every error is an RFC 7807 problem+json without internals
db/changelog  Liquibase changesets 1..6 (schema, demo seed data, sequence reset, indexes, products)
```

The data model is circular (a customer owns orders, an order refers back to its customer), so the API returns DTOs with
truncated versions of the related object (`CustomerOrderDTO`, `OrderCustomerDTO`, `OrderProductDTO`) instead of
entities. The API is specified in [OpenAPI.yaml](OpenAPI.yaml).

## Running locally

This assumes a POSIX environment. On Windows use `gradlew.bat` instead of `./gradlew`; the Docker command syntax differs
too (or install PostgreSQL on bare metal).

**1. Start PostgreSQL 16.2** on port 5433 (note the non-standard port) with a `store` database and `admin:admin`:

```shell
docker run -d \
  --name postgres \
  --restart always \
  -e POSTGRES_USER=admin \
  -e POSTGRES_PASSWORD=admin \
  -e POSTGRES_DB=store \
  -v postgres:/var/lib/postgresql/data \
  -p 5433:5432 \
  postgres:16.2 \
  postgres -c wal_level=logical
```

**2. Run the application** on port 8080:

```shell
./gradlew bootRun
```

`bootRun` activates the **`dev` profile** ([application-dev.yaml](src/main/resources/application-dev.yaml)): the
`admin/admin` credentials of the container above, the `demo` Liquibase context (100 customers and 10,000 orders of seed
data) and formatted SQL logging. Set `SPRING_PROFILES_ACTIVE` to override it. Liquibase migrates the schema on startup.
To generate more sample data, see [utils/README.md](utils/README.md).

The jar and the Docker image run **without a profile**, i.e. with production defaults: no credential defaults (startup
fails with `password authentication failed for user "${DB_USERNAME}"` if they are missing) and no seed data.

| Variable | Default | Purpose |
|----------|---------|---------|
| `DB_URL` | `jdbc:postgresql://localhost:5433/store` | JDBC URL of the database |
| `DB_USERNAME` | none (`admin` in the dev profile) | Database user |
| `DB_PASSWORD` | none (`admin` in the dev profile) | Database password |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `10` | Hikari pool size (fixed-size pool) |
| `DB_CONNECTION_TIMEOUT_MS` | `5000` | Fail fast when the pool is exhausted |
| `DB_MAX_LIFETIME_MS` / `DB_KEEPALIVE_TIME_MS` | `1800000` / `120000` | Recycle and keep alive connections across firewalls/NAT |
| `LIQUIBASE_CONTEXTS` | `prod` (`demo` in the dev profile) | `demo` also loads the seed data; never set it in production |
| `SPRING_PROFILES_ACTIVE` | none (`dev` for `bootRun`) | Spring profiles |

**3. Or run the Docker image** (the same image CI builds):

```shell
docker build -t store .
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5433/store \
  -e DB_USERNAME=admin -e DB_PASSWORD=admin \
  -e LIQUIBASE_CONTEXTS=demo \
  store
```

It runs as a non-root user (uid 10001) and has a Docker `HEALTHCHECK`. On Kubernetes, use
`/actuator/health/liveness` and `/actuator/health/readiness` as probes; `health` is the only Actuator endpoint exposed.

## API summary

All list endpoints share one pagination contract: `page` (0-based, default 0, at most 21,474,836) and `size` (default 20;
above 100 it is clamped to 100). The body is a **plain JSON array** and the metadata is in headers:

```shell
curl -i 'http://localhost:8080/order?page=1&size=2'
# HTTP/1.1 200
# X-Total-Count: 10000
# Link: <http://localhost:8080/order?page=0&size=2>; rel="first", <http://localhost:8080/order?page=0&size=2>; rel="prev",
#       <http://localhost:8080/order?page=2&size=2>; rel="next", <http://localhost:8080/order?page=4999&size=2>; rel="last"
# [{"id":3,"description":"...","customer":{"id":..,"name":".."},"products":[]}, {...}]
```

| Endpoint | Example |
|----------|---------|
| `GET /customer` | `curl -i 'http://localhost:8080/customer?size=5'` |
| `GET /customer?name=` (case-insensitive substring) | `curl -i 'http://localhost:8080/customer?name=onnel'` |
| `POST /customer` | `curl -i -X POST -H 'Content-Type: application/json' -d '{"name":"Jane Doe"}' http://localhost:8080/customer` |
| `POST /products` | `curl -i -X POST -H 'Content-Type: application/json' -d '{"description":"Widget"}' http://localhost:8080/products` |
| `GET /products` (each with its order ids) | `curl -i 'http://localhost:8080/products'` |
| `GET /products/{id}` | `curl -i http://localhost:8080/products/1` |
| `POST /order` | `curl -i -X POST -H 'Content-Type: application/json' -d '{"description":"First order","customerId":1,"productIds":[1]}' http://localhost:8080/order` |
| `GET /order` (each with customer and products) | `curl -i 'http://localhost:8080/order'` |
| `GET /order/{id}` | `curl -i http://localhost:8080/order/1` |

Errors are `application/problem+json` (RFC 7807), with an `errors` array of `{field, message}` on validation failures:
400 for invalid input, 404 for an unknown id in the path, 422 for an unknown id referenced in a body (the `detail`
lists every missing product id), 409 for a data integrity conflict, 405/406/415 for the wrong method, `Accept` or
`Content-Type`, and a generic 500 that never exposes internals.

## Assumptions and decisions

| Decision | Rationale | Rejected alternative |
|----------|-----------|----------------------|
| **Pagination keeps a plain array body**, with `X-Total-Count` and an RFC 8288 `Link` header; sorted by id; `size` clamped at 100. | Existing clients that read an array keep working; every response is bounded. | A `{content, page}` envelope (breaks every client), or no pagination (unbounded responses, see [PERFORMANCE.md](PERFORMANCE.md)). |
| **`order_product` join table without a quantity, no backfill.** "1 or more products" is enforced by the API (`productIds` is required, 1..100), not by the schema. | The brief only asks which products an order contains. The 10,000 existing orders have no products and there is nothing truthful to backfill them with, so they return `products: []`. | A quantity column (not asked for), or a DB constraint (cannot be expressed on a join table, and the legacy orders would violate it). |
| **`GET /customer?name=`** is a case-insensitive substring match; `%`, `_` and `\` are escaped; a blank term is 400; backed by a `pg_trgm` GIN index on `lower(name)`. | Matches "a substring of one of the words" without a tokenizer. A leading-wildcard `LIKE` cannot use a B-tree index; the trigram index can. | Word-by-word matching (more complex, same results except across a space), full-text search (stems words, so no substrings), or `ILIKE` without an index (sequential scans). |
| **Search on the whole name**, so `"el Do"` can match across a word boundary. | The simplest correct superset of "substring of one of the words". | Splitting names into words. |
| **422 for unknown ids in a body** (`customerId`, `productIds`); the customer is checked first. 404 stays for an unknown id in the path. | The request is well-formed but refers to something that does not exist: the target resource (`/order`) was found. | 404 (suggests `/order` itself is missing) or 400 (it is not a syntax error). |
| **`POST /order` body is `{"description", "customerId", "productIds"}`.** | Clients reference existing entities by id; they cannot create or change a customer through an order. | The old nested `{"customer": {...}}` body, which let clients send a whole customer. |
| **`productIds` capped at 100**, duplicates collapsed. | Each id is a bind parameter of the product lookup and a row of the order. | Unbounded lists. |
| **Plural `/products`**, unlike `/customer` and `/order`. | It is what the provided OpenAPI spec defines; the spec is the contract. | Renaming to `/product` for consistency. |
| **No `orders` collection on the `Product` entity.** | A popular product can be in thousands of orders and the API only needs their ids, which an index-only query returns. | A mapped `@ManyToMany(mappedBy)` collection, which invites loading whole orders. |
| **Seed data only in the `demo` Liquibase context**; the default context is `prod`. | A production database must never receive demo customers. The default must name a context: with none, Liquibase runs every changeset. | Seeding everywhere (the original setup). |
| **A sequence-reset changeset (3)** moves the id sequences past the seed data's explicit ids. | The original `data.sql` did not, so the first `POST` collided with id 1. | Editing `data.sql` (changes an applied changeset's checksum). **Limitation:** it runs once. A database first started without `demo` and later started with it loads the seed data after the reset, and the next `POST` collides; reset the sequences or recreate the database. |
| **Applied changesets are never modified**; every change is a new changeset. | Liquibase checksums would stop the app on every existing database. | Editing changesets 1 and 2 (only a `context: demo` was added to 2, which does not change its checksum). |
| **Indexes on existing tables are built `CONCURRENTLY`, without `IF NOT EXISTS`.** | No write lock on a large production table. A failed concurrent build leaves an `INVALID` index; without `IF NOT EXISTS` the rerun fails loudly instead of skipping it. | A plain `CREATE INDEX` (blocks writes), or `IF NOT EXISTS` (hides a broken index). |
| **`CREATE EXTENSION pg_trgm` needs elevated rights.** On managed PostgreSQL (RDS, Cloud SQL, Azure) it is a DBA step before deployment; the changeset then is a no-op. | The application user should not be a superuser. | Running the app as a superuser. |
| **No cascade from `Customer.orders`, no Lombok `@Data` on entities.** Entities use id-based `equals`, a constant `hashCode` and a `toString` without associations. | `@Data` generates `equals`/`hashCode` over lazy collections (N+1 queries, stack overflows on the cycle); a cascade from customer would let a customer write orders. | Keeping `@Data` and cascades. |
| **Credentials have no defaults outside the `dev` profile.** | A deployment missing its secrets fails at startup instead of trying `admin/admin`. | Defaults in `application.yaml`. |
| **CI on GitHub Actions, image on GHCR, pushed from `main` only.** | Repository-native, no extra accounts; PR builds are verified but never published. | An external CI or registry. |

## Performance

The GET endpoints are slow in production because of database round trips over a high-latency link. They now run a
**constant number of SQL statements** whatever the page size (3 for each list, 2 for each lookup by id, down from 11, or
101 without batch fetching), return **bounded pages**, use the right **indexes**, a **fixed-size connection pool** and
**gzip**. The statement counts are asserted by the integration tests. The analysis, measurements, alternatives that were
considered and not done, and the remaining risks (the orders embedded in each customer, the order ids embedded in each
product) are in [PERFORMANCE.md](PERFORMANCE.md).

## CI/CD

[.github/workflows/ci.yml](.github/workflows/ci.yml) runs on every pull request and every push to `main`:

| Stage | Verifies | Why it gates promotion |
|-------|----------|------------------------|
| `spotlessCheck` | Formatting (Palantir Java Format) | Diffs stay reviewable. |
| `build` | Compilation, unit tests, `@WebMvcTest` slices, Testcontainers integration tests against PostgreSQL 16.2 with the real Liquibase migrations | Behaviour, the JSON contract, the schema and the statement counts are checked against a real database. |
| JaCoCo gate (part of `check`) | At least 80% line coverage | Untested code does not get in silently. |
| Docker build | The multi-stage image builds | What is deployed is what was tested. |
| Trivy scan | No fixable CRITICAL or HIGH vulnerability in the OS packages or the libraries of the image | Known-vulnerable images are not published. |
| Push to GHCR | Only on `main`, only if all of the above passed; pushes the exact image that was scanned, tagged with the short SHA and `latest` | PRs never publish; `main` is always deployable. |

Test and coverage reports are uploaded as artifacts. Permissions are read-only except `packages: write` for the Docker
job.

**Known issue:** the Trivy stage currently fails on vulnerabilities in libraries managed by Spring Boot 3.4.2 (Tomcat,
Jackson, pgjdbc, Spring Framework), so nothing is pushed until the dependencies are upgraded (see below).

## Testing

| Kind | What | Run |
|------|------|-----|
| Unit | Services with mocked repositories (`*ServiceTest`), `PaginationTest`, `EntityIdentityTest`, `ConfigurationDefaultsTest` | `./gradlew test --tests '*ServiceTest'` |
| Web slice | `@WebMvcTest` per controller: validation, status codes, problem+json without internals, paging headers | `./gradlew test --tests '*ControllerTests'` |
| Integration | Testcontainers PostgreSQL 16.2 (Docker required) with the real migrations and seed data: JSON shapes, search semantics, schema and index validity, query plans, gzip, actuator; `ProductionDefaultsIntegrationTest` starts the app like a deployment, on an empty database | `./gradlew test --tests '*IntegrationTest'` |
| Statement counts | `SqlStatementCounter` (Hibernate statistics, `test` profile only) asserts the number of statements at size 1, 20 and 100 for every GET | part of the integration tests |
| Everything | Formatting, all tests and the coverage gate | `./gradlew spotlessCheck build` (report: `build/reports/jacoco/test/html/index.html`) |
| API | [postman/store.postman_collection.json](postman/store.postman_collection.json): every endpoint, the error cases and an end-to-end product → order → product flow, against a running app with the demo data | `npx newman run postman/store.postman_collection.json` (add `--env-var baseUrl=http://host:port` for another host), or import it into Postman |

## What I'd do next

- Upgrade Spring Boot (3.5.x with version overrides, or 4.x) so that the Trivy gate passes again.
- Paginate the embedded collections: `GET /customer/{id}/orders` and `GET /products/{id}/orders` (contract changes).
- Keyset pagination for deep pages, and an estimated `X-Total-Count` for very large tables.
- A CI job that migrates a database from the original schema to the current one, so that upgrade paths are tested
  automatically; Dependabot, an SBOM and image signing; SHA-pinned actions.
- Authentication, and Micrometer metrics (Hikari pool, request latency).

## Brief

The original text of the task, unchanged. ✅ marks what is done, with where it is handled.

# Tasks

1. Extend the order endpoint to find a specific order, by ID
   ✅ `GET /order/{id}`: [OrderController](src/main/java/com/example/store/controller/OrderController.java), [API summary](#api-summary)
2. Extend the customer endpoint to find customers based on a query string to match a substring of one of the words in their name
   ✅ `GET /customer?name=`: [CustomerController](src/main/java/com/example/store/controller/CustomerController.java), [decisions](#assumptions-and-decisions), [PERFORMANCE.md](PERFORMANCE.md#get-customername-substring-search-backed-by-a-trigram-index)
3. Users have complained that in production the GET endpoints can get very slow. The database is unfortunately not co-located with the application server, and there's high latency between the two. Identify if there are any optimisations that can improve performance
   ✅ [PERFORMANCE.md](PERFORMANCE.md), [Performance](#performance)
4. Add a new endpoint /products to model products which appear in an order:
      * A single order contains 1 or more products. 
      * A product has an ID and a description. 
      * Add a POST endpoint to create a product
      * Add a GET endpoint to return all products, and a specific product by ID
        * In both cases, also return a list of the order IDs which contain those products
      * Change the orders endpoint to return a list of products contained in the order

   ✅ [ProductController](src/main/java/com/example/store/controller/ProductController.java), [db.changelog-6.yaml](src/main/resources/db/changelog/db.changelog-6.yaml), [decisions](#assumptions-and-decisions), [PERFORMANCE.md](PERFORMANCE.md#products)

# Bonus points
1. Implement a CI pipeline on the platform of your choice to build the project and deliver it as a Dockerized image
   ✅ [ci.yml](.github/workflows/ci.yml), [Dockerfile](Dockerfile), [CI/CD](#cicd) (see the known issue there)

# Notes on the tasks
Assume that the project represents a production application.
Think carefully about the impact on performance when implementing your changes
The specifications of the tasks have been left deliberately vague. You will be required to exercise judgement about what to deliver - in a real world environment, you would clarify these points in refinement, but since this is a project to be completed without interaction, feel free to make assumptions - but be prepared to defend them when asked.
There's no CI pipeline associated with this project, but in reality there would be. Consider the things that you would expect that pipeline to verify before allowing your code to be promoted
Feel free to refactor the codebase if necessary. Bad choices were deliberately made when creating this project.

✅ Production concerns: [decisions](#assumptions-and-decisions) and [Running locally](#running-locally); performance:
[PERFORMANCE.md](PERFORMANCE.md); pipeline: [CI/CD](#cicd); refactoring: entities without `@Data` or cascades,
DTO request records, centralised error handling.
