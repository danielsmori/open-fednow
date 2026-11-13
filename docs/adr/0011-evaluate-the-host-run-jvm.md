# ADR-0011: The reliability evidence is recorded against the host-run JVM, not the container

## Status

Accepted. It records which process the synthetic reliability evidence describes. It makes no claim about production readiness, live rail integration or core correctness; see [known limitations](../known-limitations.md).

## Context

The gateway now ships as a container image, and `compose.app.yml` can run it inside the Compose network. That raises the question #116 asks: should `harness/evaluate_external.py` point at the containerised application instead of a JVM it starts itself?

Three properties of the harness stand in the way, and none of them is incidental:

- **The synthetic rail and the reference target bind loopback only.** `harness/rail_simulator.py` and `harness/reference_target.py` both listen on `127.0.0.1`. [The reproduction instructions](../reliability/reproduce.md) state that as a safety property: the fixtures are not reachable from the network and cannot be pointed at a live payment rail by accident. A container cannot reach a host's loopback listener on Linux, so targeting a container would mean either moving those fixtures into the Compose network or binding them to a routable address. The second option weakens the property deliberately. The first changes what the recorded evidence describes.
- **S05 kills the process.** `harness/restart_case.py` sends SIGKILL to the JVM at a specific point between a remote effect and local persistence, then starts it again. Against a container that becomes `docker kill` plus a restart, a different fault boundary that would have to be re-justified rather than assumed equivalent.
- **S11 runs two JVMs against one database.** `harness/two_worker_case.py` starts two processes to contend for a SQL inquiry lease. The container equivalent is two app containers, which is a reasonable thing to build but is not the same configuration the current evidence was produced on.

The evidence under `docs/reliability/evidence/` was recorded against the host-run JVM. Changing the process under test without rerunning everything would silently re-label it.

## Decision

The evaluated path stays the host-run JVM: `scripts/evaluate.py --external`, which starts the jar directly. `docs/reliability/reproduce.md` and the evaluator guide continue to describe that path, and it is the one the scenario results refer to.

The container is verified separately and more narrowly. CI builds the image, starts it against real PostgreSQL, Redis and RabbitMQ containers, and checks that it becomes healthy, serves `/fednow/health` and both actuator probes, and leaves the non-production fixture gates closed. `compose.app.yml` exists for running and demonstrating the stack, not for producing reliability evidence.

Nothing in `docs/reliability/evidence/` changes as a result of this decision.

## Consequences

The reliability claims keep describing exactly one process type, and the loopback-only property of the synthetic fixtures is preserved. The cost is that the container path carries less evidence than the host-run path: a healthy start and closed gates, not the scenario manifest.

Running the full scenario set against the containerised application remains possible later. It would need the rail simulator and reference target inside the Compose network, a container-aware fault injection step for S05, and a two-replica arrangement for S11. That is a separate piece of work with its own evidence, and until it exists, no scenario result should be described as having been produced against the container.
