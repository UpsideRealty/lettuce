# Core startup 500s: minimal async-provider re-entry fix summary

## Context

This change was implemented as a **fresh JJ change from `6.6.0.RELEASE`** using:

```bash
jj new 6.6.0.RELEASE
```

The goal was to apply the smallest practical fix for the original Lettuce failure mode:

- same request thread
- same connection key
- re-entry into `AsyncConnectionProvider.getSynchronizer(...)`
- outer creation still active
- `ConcurrentHashMap.computeIfAbsent(...)`
- `IllegalStateException: Recursive update`

## What we changed

### 1. `AsyncConnectionProvider`

File:
- `src/main/java/io/lettuce/core/internal/AsyncConnectionProvider.java`

Implemented changes:

- removed `computeIfAbsent(...)`
- switched to placeholder installation via `putIfAbsent(...)`
- added an injected `Executor` used to start connection creation asynchronously
- changed the provider contract to:
  - one internal shared acquisition per key
  - one returned waiter future per caller
- caller cancellation no longer cancels the shared acquisition
- provider/key close owns cancellation and cleanup
- late connection success after close now closes the connection instead of leaking it

Behaviorally, the key fix is:

1. install placeholder in the map
2. return waiter future
3. start `connectionFactory.apply(key)` on a later executor tick

That breaks the original same-thread inline re-entry path and avoids `ConcurrentHashMap` recursive update.

### 2. `PooledClusterConnectionProvider`

File:
- `src/main/java/io/lettuce/core/cluster/PooledClusterConnectionProvider.java`

Implemented changes:

- switched to the new `AsyncConnectionProvider<..., ...>` type
- wired connection startup through:

```java
redisClusterClient.getResources().eventExecutorGroup()
```

- kept wrapper behavior thin
- preserved remote-address-aware exception decoration by computing the socket address from the key in the wrapper
- preserved synchronous unknown-node-id behavior via explicit prevalidation before async startup

Explicitly not added:

- retry-on-closed logic
- cache invalidation helpers
- extra cancellation forwarding/suppression
- additional cluster hardening

### 3. `MasterReplicaConnectionProvider`

File:
- `src/main/java/io/lettuce/core/masterreplica/MasterReplicaConnectionProvider.java`

Implemented changes:

- switched to the new `AsyncConnectionProvider<..., ...>` type
- wired connection startup through:

```java
redisClient.getResources().eventExecutorGroup()
```

- otherwise kept wrapper behavior close to baseline

Explicitly not added:

- retry-on-closed logic
- extra cancellation handling
- additional master/replica hardening

## Tests added or updated

### Added

- `src/test/java/io/lettuce/core/internal/AsyncConnectionProviderUnitTests.java`

Covers:

- async boundary before factory start
- same-thread same-key recursive re-entry converging on one acquisition
- concurrent same-key callers sharing acquisition
- synchronous failure cleanup
- asynchronous failure cleanup
- caller cancellation not canceling shared acquisition
- close canceling cancellable pending acquisition
- close waiting for non-cancellable pending acquisition and closing late connection

### Updated

- `src/test/java/io/lettuce/core/cluster/PooledClusterConnectionProviderUnitTests.java`
- `src/test/java/io/lettuce/core/masterreplica/MasterReplicaConnectionProviderUnitTests.java`
- `src/test/java/io/lettuce/core/cluster/AsyncConnectionProviderIntegrationTests.java`

Added/updated wrapper-level coverage for:

- caller cancellation isolation
- other waiters succeeding after one waiter cancels
- updated constructor wiring

## Validation performed

## Java runtime note

Validation was run with **Java 21**.

Reason:
- the repo's Kotlin plugin fails under local Java 26 with `IllegalArgumentException: 26`

Typical setup used:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export PATH="$JAVA_HOME/bin:$PATH"
```

### Passed unit tests

- `AsyncConnectionProviderUnitTests`
- `PooledClusterConnectionProviderUnitTests`
- `MasterReplicaConnectionProviderUnitTests`
- `mvn test-compile`

### Passed integration tests

After bringing up the repo test environment, these passed:

- `RedisClusterReadFromIntegrationTests`
- `ClusterPartiallyDownIntegrationTests`
- `MasterReplicaIntegrationTests`
- `StaticMasterReplicaIntegrationTests`
- `RedisClusterStressScenariosIntegrationTests`

`AsyncConnectionProviderIntegrationTests` also passed when run method-by-method:

- `shouldCloseConnectionByKey`
- `shouldCloseConnections`
- `connectShouldFail`
- `connectShouldFailConcurrently`

### Test environment note

The repo integration environment requires Docker and the Redis test topology.

Because the test image does not publish an arm64 manifest, startup required amd64 emulation:

```bash
DOCKER_DEFAULT_PLATFORM=linux/amd64 make start version=8.0
```

Ports exercised by the relevant tests included:

- standalone/master-replica: `6479`
- cluster: `7379`, `7383`

### Known unrelated baseline issue

The full unit suite still hits an unrelated failure outside this patch area:

- `io.lettuce.core.output.NumberListOutputUnitTests.set`

That failure was reproduced under both Java 17 and Java 21 and was not addressed by this change.

## Published internal snapshot

A new internal snapshot was published to Google Artifact Registry:

- repo: `australia-southeast1-maven.pkg.dev/upside-ci/nct-maven-snapshots`
- artifact: `io.lettuce:lettuce-core`
- version: `6.6.1-nct.9-SNAPSHOT`

Published package listing confirmed that `6.6.1-nct.9-SNAPSHOT` is now present.

## Publish notes

The project `pom.xml` is wired for Sonatype/OSSRH deploys by default, so publishing to the internal repo was done with a direct Artifact Registry deployment instead of a normal `mvn deploy`.

A temporary Maven wagon extension for Artifact Registry was used, and then removed after publishing.

## Files changed

Main sources:
- `src/main/java/io/lettuce/core/internal/AsyncConnectionProvider.java`
- `src/main/java/io/lettuce/core/cluster/PooledClusterConnectionProvider.java`
- `src/main/java/io/lettuce/core/masterreplica/MasterReplicaConnectionProvider.java`

Tests:
- `src/test/java/io/lettuce/core/internal/AsyncConnectionProviderUnitTests.java`
- `src/test/java/io/lettuce/core/cluster/PooledClusterConnectionProviderUnitTests.java`
- `src/test/java/io/lettuce/core/masterreplica/MasterReplicaConnectionProviderUnitTests.java`
- `src/test/java/io/lettuce/core/cluster/AsyncConnectionProviderIntegrationTests.java`

## Outcome

This patch line stays close to the original scope:

- fix the original recursive-update issue directly
- avoid `computeIfAbsent(...)` for same-key acquisition
- insert an explicit async boundary before real connection creation
- keep cluster/master-replica wrapper changes minimal
- avoid bundling broader retry/invalidation hardening into the first patch
