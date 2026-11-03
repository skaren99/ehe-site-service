# ehe-site-service

Site content, pricing, products, rental reservations and closures. Independently deployed Spring Boot 3.5.16 service on Java 21, with its own PostgreSQL database. This is a migration baseline; see the sibling platform repository's architecture and migration matrix for production limitations and pending legacy feature parity.

## Build and test

```sh
./mvnw verify
```

On Windows use `.\mvnw.cmd verify`. The wrapper downloads Maven 3.9.11. Tests use disposable H2 databases in PostgreSQL compatibility mode. They do not prove real PostgreSQL deployment or external OIDC connectivity. Integration tests cover authorization and the domain invariants relevant to this service.

## Run

Use the sibling `ehe-platform/compose.yaml` for a complete local stack. To run independently, provide `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `OIDC_ISSUER`, `OIDC_JWK_URI`, and `CORS_ORIGINS` as environment variables, then:

```sh
java -jar target/ehe-site-service-0.1.0.jar
```

Default port: 8081. Copy values from `.env.example` into your process environment; Spring Boot does not automatically load a `.env` file. The Dockerfile builds/tests the service, then runs the JAR as a non-root user. GitHub Actions runs the wrapper verify goal for pushes and pull requests.

Database schema is managed with Flyway. The initial implementation stores a JSON state document with transactional row locking. This keeps prototype data shapes reviewable but serializes service writes. Normalize entities before high-throughput use; this is documented rather than hidden behind an ORM.

Startup seeds the source catalogue and default settings only for a new database. Existing state is never overwritten. Runtime settings/bookings from the original Node/SQLite app have not been migrated.

Role and audience requirements, requests and error codes are in `ehe-platform/API.md`. No secrets, customer database or generated build artifacts are tracked. Original Node/prototype source remains in `ehe-design-archive`.
