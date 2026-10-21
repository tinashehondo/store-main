# Data generation
The script in here can be used to generate a lot of sample data. It requires node and npm to run.

It generates `INSERT` statements for **customers and orders only**. It knows nothing about products: it creates no
`product` or `order_product` rows, so every generated order has `"products": []`, like the orders of the existing seed
data. Create products through `POST /products` and orders that contain them through `POST /order`.

# Installation
```shell
npm install
```

# Execution
Write the output to a **new** file, never over the existing `data.sql`:

```shell
node ./generateData.js > ../src/main/resources/db/changelog/data-large.sql
```

# Loading the data
> **Warning:** do not edit or overwrite `data.sql` or any changeset that has already been applied (`db.changelog-1.yaml`
> to the highest number in `db.changelog-master.yaml`). Liquibase stores a checksum of every applied changeset
> (`data.sql` is part of `2-data`'s), and the application then refuses to start on any database that has run it.

Load generated data through a new changeset instead:

1. Create `db.changelog-<next number>.yaml` (see `db.changelog-master.yaml` for the highest one) and include it at the
   end of `db.changelog-master.yaml`:

   ```yaml
   databaseChangeLog:
     - changeSet:
         id: <next number>-large-demo-data
         author: <you>
         context: demo
         changes:
           - sqlFile:
               path: db/changelog/data-large.sql
           # The generated INSERTs use explicit ids and do not advance the sequences (changeset 3 explains why).
           - sql:
               sql: >-
                 SELECT setval(pg_get_serial_sequence('customer', 'id'), (SELECT MAX(id) FROM customer));
                 SELECT setval(pg_get_serial_sequence('"order"', 'id'), (SELECT MAX(id) FROM "order"));
   ```

2. Keep `context: demo`, so that it never runs on a production database (the default context is `prod`).
3. The script numbers ids from 1, which collides with the existing seed data. Either run it against an empty database
   without the `2-data` changeset, or offset the ids in the script first.

To start over on a local database instead, drop and recreate it (`docker rm -f postgres` and run the `docker run`
command from the main README again, which also needs `docker volume rm postgres`).

# Known limitation
The script does not escape quotes: a generated name such as `O'Brien` produces invalid SQL. Check the output, or
escape `'` as `''` in the script, before loading it.
