# Contributing to OpenFedNow

OpenFedNow is an open-source synthetic reference project for studying legacy-core and instant-payment integration. It has not measured the number of institutions blocked by core systems or validated a live deployment. Contributions should preserve the distinction between local fixture behavior and verified rail or vendor behavior.

---

## Where Contributions Are Most Needed

### Core Banking Adapters

The three vendor-shaped adapters implement the `CoreBankingAdapter` interface and have local fixture tests. Product compatibility and market coverage are unverified:

| Adapter | Platforms | Status | Reference |
|---------|-----------|--------|-----------|
| `FiservAdapter` | REST/JSON-shaped fixture | Local WireMock tests | [Capability matrix](docs/reliability/adapter-capabilities-v1.md) |
| `FisAdapter` | REST/JSON-shaped fixture | Local WireMock tests | [Capability matrix](docs/reliability/adapter-capabilities-v1.md) |
| `JackHenryAdapter` | jXchange SOAP-shaped fixture | Local WireMock tests | [Capability matrix](docs/reliability/adapter-capabilities-v1.md) |

If you have access to a Fiserv, FIS, or Jack Henry sandbox environment, testing against a real vendor endpoint — rather than the WireMock suite — remains one of the most valuable contributions.

### Other High-Value Areas

- **Shadow Ledger** — Redis-backed balance tracking with optimistic locking (`ShadowLedger.java`)
- **Saga Orchestration** — durable state persistence for `SagaOrchestrator.java`
- **ISO 20022 Validation** — schema validation for pacs.008 and pacs.002 messages
- **Test Coverage** — integration tests for the pacs.008/pacs.002 round-trip
- **Documentation** — integration guides for specific core banking platforms

---

## Getting Started

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker with Compose (for local PostgreSQL, Redis, and RabbitMQ)

### Local Setup

```bash
# Clone the repo
git clone https://github.com/danielsmori/open-fednow.git
cd open-fednow

# Start local dependencies (PostgreSQL + Redis + RabbitMQ)
docker compose up -d

# Build
mvn -B --no-transfer-progress package -DskipTests

# Default tests use H2; run the tagged integration suite with Docker separately
mvn test
mvn test -Dgroups=integration -DexcludedGroups=
```

### Running the Application Locally

```bash
mvn spring-boot:run
```

The gateway starts on `http://localhost:8080`. Open the browser demo at <http://localhost:8080/demo/>, or verify via `curl http://localhost:8080/fednow/health`.

The application requires PostgreSQL at runtime (the idempotency INSERT uses `ON CONFLICT DO NOTHING`, which H2 does not implement). Defaults in `application.yml` point at the docker-compose Postgres — override with `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` for other environments.

---

## How to Contribute

1. **Check open issues** — look for issues labeled `help wanted` or `good first issue`
2. **Open an issue first** for significant changes — describe what you want to build before writing code
3. **Fork the repo** and create a branch: `git checkout -b feature/your-feature-name`
4. **Write tests** for any new functionality
5. **Submit a pull request** with a clear description of what the change does and why

---

## Code Style

- Standard Java conventions (no custom formatter required)
- Javadoc on all public methods — especially important for the `CoreBankingAdapter` interface and its implementations
- `TODO` comments are acceptable in stub implementations; use the format `// TODO: description (#issue-number)`

---

## A Note on Core Banking Vendor Access

Implementing a vendor adapter requires access to that vendor's API documentation and ideally a sandbox environment. If you have authorized access to a vendor sandbox, please propose a scoped test plan that identifies the exact product and API version. Do not include credentials, customer data, or proprietary specifications in an issue or pull request. Local WireMock behavior must remain labeled as fixture evidence.

---

## License

By contributing to OpenFedNow, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
