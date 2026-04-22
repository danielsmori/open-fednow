# Contributing to OpenFedNow

Thank you for your interest in contributing. OpenFedNow is an open-source project with a specific goal: making FedNow participation accessible to the thousands of U.S. community banks and credit unions currently blocked by legacy core banking systems. Every contribution moves that goal forward.

---

## Where Contributions Are Most Needed

### Core Banking Adapters (Highest Priority)

The vendor-specific adapters are the most impactful area for contribution. Each adapter implements the `CoreBankingAdapter` interface for a specific platform:

| Adapter | Platform | Status | Issue |
|---------|----------|--------|-------|
| `FiservAdapter` | DNA (REST) | In Development | #1 |
| `FiservAdapter` | Precision / Premier (SOAP) | Planned | #2 |
| `FisAdapter` | Horizon / IBS | Planned | Phase 2 |
| `JackHenryAdapter` | SilverLake / Symitar | Planned | Phase 3 |

If you have access to a Fiserv, FIS, or Jack Henry sandbox environment, your contribution here has direct real-world impact.

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
- Docker (for local Redis and RabbitMQ via `docker-compose`)

### Local Setup

```bash
# Clone the repo
git clone https://github.com/danielsmori/open-fednow.git
cd open-fednow

# Start local dependencies (Redis + RabbitMQ)
docker-compose up -d

# Build
mvn clean install

# Run tests
mvn test
```

### Running the Application Locally

```bash
mvn spring-boot:run
```

The gateway will start on `http://localhost:8080`. Use the `/fednow/health` endpoint to verify.

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

Implementing a vendor adapter requires access to that vendor's API documentation and ideally a sandbox environment. If you work at a financial institution or fintech that has this access and are willing to contribute an adapter implementation, please open an issue or reach out directly — this is exactly the kind of contribution that makes the project viable for real-world deployment.

---

## License

By contributing to OpenFedNow, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
