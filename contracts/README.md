# Contracts

`runtime-v1.yaml` is the OpenAPI 3.1 contract of the Agent Runtime API (`/runtime/v1`), generated from the Spring
controllers by springdoc. It is what the SDKs (WS4) and the CLI build against. `RuntimeContractTest` fails when the
controllers and this file disagree.

After an intended API change, regenerate and commit:

    cd backend && TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -q test -Dtest=RuntimeContractTest -Dcontract.update=true

Breaking changes go to `/runtime/v2` with a deprecation window (spec §4.2).

The Python and TypeScript SDKs' generated clients are regenerated with `sdk/python/regenerate.sh` and
`sdk/typescript/regenerate.sh`; CI fails if they drift.
