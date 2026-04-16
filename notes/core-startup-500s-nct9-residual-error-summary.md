# Core startup 500s: `nct.9` residual error summary

## Context

This note summarizes the remaining candidate-side errors observed during the `raywhite-testing` validation run for:

- revision: `core-e65d06a`
- Lettuce version: `6.6.1-nct.9-SNAPSHOT`
- key window: `2026-03-30T11:15:58Z` to `2026-03-30T11:16:01Z`

The goal of this note is to explain the **remaining small error case** after the original Lettuce `Recursive update` bug was fixed.

---

## Bottom line

The original Lettuce bug appears fixed on `nct.9`.

The remaining candidate-side issue is **not** the old recursive connection-acquisition failure. Instead, it is a **small burst of Redis command timeouts during session save/commit near the end of request processing**.

In simple terms:

1. the request handler succeeded
2. the app produced a normal response body
3. while the response was being committed, Spring Session tried to save/update session state in Redis
4. a few small Redis commands timed out at `200ms`
5. Cloud Run recorded the overall request as `500`

So the residual error is much smaller and qualitatively different from the original bug.

---

## Main findings

### 1. Only 5 candidate request failures were found in the relevant window

Candidate `500`s on `core-e65d06a`:

- `2026-03-30T11:15:58.816900Z` — `500` — `2.017218955s`
- `2026-03-30T11:15:58.887934Z` — `500` — `1.943705467s`
- `2026-03-30T11:15:59.084817Z` — `500` — `1.832553751s`
- `2026-03-30T11:15:59.651341Z` — `500` — `1.272395259s`
- `2026-03-30T11:15:59.653327Z` — `500` — `1.268453436s`

### 2. All 5 failures came from one single Cloud Run instance

Instance id:

- `008c15ff0866100e79692c0f64ad77e6186cafe104c0a2f28bbbf1a8ed951f4064894fe9e4816f5a356dcfe4f17f4f1bc62795da4756d2c88811ab52ed89fa07a17a54620c46c11985470dabff4926c608`

No other candidate instance showed request `5xx` in that immediate validation window.

### 3. The candidate-side timeout signals were all short `200ms` Redis command timeouts

Observed timeout commands:

- `EXISTS. Command timed out after 200 millisecond(s)`
- `PEXPIREAT. Command timed out after 200 millisecond(s)`
- one cache-side timeout during Redis transaction cleanup / `DISCARD`

Total candidate-side timeout signals found in the relevant window:

- `7`

### 4. The failing path was session save/commit, not the old connection-acquisition path

Representative stack traces showed:

- `RedisSessionRepository.save`
- `SessionRepositoryFilter$SessionRepositoryRequestWrapper.commitSession`
- Redis `EXISTS`
- Redis `PEXPIREAT`

This is different from the original bug path, which centered on:

- `AsyncConnectionProvider.getSynchronizer(...)`
- `PooledClusterConnectionProvider.getWriteConnection(...)`
- `AbstractRedisClient.initializeChannelAsync(...)`
- `IllegalStateException: Recursive update`

### 5. App access logs showed `200`, while Cloud Run request logs showed `500`

For the same failing traces:

- app access log recorded successful responses like `200 1006ms`, `200 907ms`, `200 800ms`, `200 394ms`, `200 398ms`
- Cloud Run request logs recorded the final outcome as `500`

Interpretation:

- controller/business logic completed successfully
- the response body was prepared successfully
- the request then failed while Spring Session was saving session state during response commit

In other words, the request failed **after** the application had effectively produced a good response.

---

## Representative residual failure paths

### Session commit timeout via `EXISTS`

Representative frames included:

- `RedisTemplate.hasKey`
- `RedisSessionRepository.save`
- `SessionRepositoryFilter$SessionRepositoryRequestWrapper.commitSession`

Representative cause:

```text
Caused by: io.lettuce.core.RedisCommandTimeoutException: EXISTS. Command timed out after 200 millisecond(s)
```

### Session commit timeout via `PEXPIREAT`

Representative frames included:

- `RedisTemplate.expireAt`
- `RedisSessionRepository$RedisSession.saveDelta`
- `RedisSessionRepository.save`
- `SessionRepositoryFilter$SessionRepositoryRequestWrapper.commitSession`

Representative cause:

```text
Caused by: io.lettuce.core.RedisCommandTimeoutException: PEXPIREAT. Command timed out after 200 millisecond(s)
```

### Cache-side timeout also present on the same instance

Observed separately on the same instance:

- `upside.configuration.RedisDatastoreCache`
- timeout while cleaning up / discarding Redis transaction state

Representative cause:

```text
Caused by: io.lettuce.core.RedisCommandTimeoutException: Command timed out after 200 millisecond(s)
```

This suggests the instance briefly experienced a small Redis command-timeout burst, not just one isolated session-save failure.

---

## What this means in simple terms

The residual `nct.9` error case is:

- not the old recursion bug
- not a broad connection-acquisition meltdown
- not a revision-wide collapse

It is instead:

- a **small burst**
- on **one instance**
- involving **short 200ms command timeouts**
- while **saving session state at response commit time**

Plain English summary:

> The request itself worked, but a bit of Redis bookkeeping right at the end took too long, so a few otherwise-successful requests became `500`s.

---

## Later topology warnings: real, but separate

Candidate logs also showed topology/connectivity warnings against:

- `10.152.0.73:6379`

Examples appeared later, around:

- `2026-03-30T11:24:44Z`
- `2026-03-30T11:28:36Z`
- `2026-03-30T11:29:40Z`

Representative warnings:

- `Unable to connect to [10.152.0.73/<unresolved>:6379]: connection timed out after 200 ms`
- `Cannot refresh Redis Cluster topology`
- `Cannot retrieve cluster partitions`

Important distinction:

- these warnings were on **other instances**
- they occurred **minutes later** than the 5 request failures
- they do not appear to be the direct cause of the small flip-boundary `500` burst

---

## Scope assessment

### What appears fixed

The targeted Lettuce bugfix succeeded in removing the original failure mode:

- `Recursive update = 0`
- no evidence in the residual candidate failures that the request was still failing in `AsyncConnectionProvider` / cluster connection acquisition

### What the remaining issue looks like

The remaining issue looks more like:

- app/framework response-commit sensitivity
- ordinary Redis command timeout pressure
- cold-instance / warmup / transient latency on one instance
- very aggressive `200ms` command timeout budget

### Current best assessment of scope

The residual `nct.9` issue appears to be **outside the scope of the original Lettuce recursion fix** unless further evidence shows that these command timeouts were still caused by client-side queueing, reconnect, topology churn, or connection-acquisition delay.

At present, the logs do **not** clearly show that.

---

## Practical conclusion

The correct short summary is:

> `nct.9` appears to have fixed the original Lettuce `Recursive update` bug. The remaining candidate-side errors were a tiny, instance-local burst of `200ms` Redis command timeouts during Spring Session save/commit, which turned a handful of otherwise-successful requests into `500`s.

That means:

- the original library bug appears fixed
- the catastrophic replacement meltdown seen on earlier patched versions appears gone
- the remaining work likely belongs in **app/timeout/warmup/infra investigation first**, not in another blind `AsyncConnectionProvider` change

---

## If further investigation is needed

The most useful next data would be:

1. target Redis node for each timed-out `EXISTS` / `PEXPIREAT`
2. command queue/dispatch timing for those timeouts
3. whether that instance was still warming connections or topology state
4. whether the `200ms` timeout budget is too aggressive for session-save commands during cutover
