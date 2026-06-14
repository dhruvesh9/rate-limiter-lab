# Architecture Decisions

## ADR-001: Milestone 1 token bucket semantics

Status:
- accepted

Date:
- 2026-06-13

## Context

Milestone 1 builds an in-memory token bucket rate limiter that is deterministic, testable, and small enough to implement cleanly in Java before adding HTTP integration or Redis-backed distribution.

The main design questions are:
- how tokens refill over time
- what the initial bucket state should be
- whether fractional tokens are supported
- how to handle requests whose cost is greater than bucket capacity
- what concurrency guarantee Milestone 1 should provide

These decisions must be explicit before defining HLD, LLD, tests, or implementation tasks.

## Decision

### 1. Refill model: interval-based refill

We will use interval-based refill for Milestone 1.

Chosen behavior:
- tokens are added based on whole elapsed refill intervals
- partial intervals do not add partial tokens
- example: if the refill rule is `1 token every 1 second`, then `3.8` elapsed seconds adds `3` tokens

Why:
- simpler mental model for a first implementation
- easier deterministic tests
- easier for a junior developer to reason about
- maps cleanly to the interview variant of `1 token added every 1 second`

Trade-off:
- less smooth than continuous refill
- lower fidelity than systems that calculate fractional accumulation

### 2. Initial bucket state: start full

We will initialize a newly created bucket with full capacity.

Chosen behavior:
- when a key is seen for the first time, its bucket starts with `capacity` tokens

Why:
- matches common token bucket expectations
- allows an initial burst up to the configured capacity
- easier to explain in interviews

Trade-off:
- a new client can burst immediately
- if a use case needs strict cold-start throttling, that can be introduced later as a configurable option

### 3. Token representation: integer tokens only

We will use integer token accounting in Milestone 1.

Chosen behavior:
- tokens are stored as whole integers
- request cost is an integer
- refill adds whole integers only

Why:
- avoids floating-point precision issues
- keeps state transitions easy to inspect in tests
- matches the interview variant where each request consumes `2` tokens and refill adds `1` token per second

Trade-off:
- cannot model fine-grained continuous refill in the first version
- future continuous refill may require a different internal representation

### 4. Request cost greater than capacity: reject as impossible

If a request asks for more tokens than the bucket can ever hold, the limiter will always deny it.

Chosen behavior:
- if `requestCost > capacity`, return `denied`
- do not attempt to wait, queue, or partially consume

Why:
- the request can never succeed under the current bucket definition
- rejecting impossible requests makes the contract explicit
- avoids hidden ambiguity in tests and later HTTP integration

Trade-off:
- the caller must handle this as a validation or policy problem

### 5. Refill strategy: lazy refill on access

We will calculate refill only when a request for a key is evaluated.

Chosen behavior:
- no background scheduler updates buckets
- when a request arrives, the limiter computes elapsed time since the bucket was last updated and applies refill before checking token availability

Why:
- simpler implementation
- no extra threads or timers
- easier to test deterministically
- scales naturally with sparse traffic because idle keys do not require work

Trade-off:
- bucket state is updated only when accessed
- cached metrics based on bucket state may be stale between requests

### 6. Concurrency target: correct same-key behavior is required

Milestone 1 must be correct under concurrent access for the same key.

Chosen behavior:
- the logical sequence “refill if needed, check availability, consume if allowed, store updated state” must be atomic per key
- requests for different keys should be able to proceed independently

Why:
- the user’s target system is a Spring Boot service, which will process concurrent requests
- the interview variant specifically tests shared-state correctness
- a rate limiter that overspends tokens under concurrency is functionally wrong, even if it works single-threaded

Trade-off:
- thread safety adds design complexity to the in-memory implementation
- we must think about lock granularity or atomic per-key updates in the LLD

### 7. Concurrency non-goal for Milestone 1: no cross-instance consistency

Milestone 1 does not provide coordination across multiple application instances.

Chosen behavior:
- correctness is only required within one JVM process
- distributed correctness is deferred to the Redis milestone

Why:
- keeps the first milestone narrowly scoped
- avoids pretending a single-process design is already distributed

## Consequences

These decisions imply:
- test cases must use elapsed whole intervals
- the first bucket for a new key should allow a burst immediately
- repeated calls at the same timestamp will not refill tokens
- a request with cost above capacity must always be denied
- LLD must include some form of per-key atomic state update strategy
- HLD should separate domain logic from storage and time source

## Rejected alternatives

### Alternative A: continuous refill with fractional tokens

Rejected for Milestone 1 because:
- more complex math
- more complex tests
- harder to explain and debug for a first implementation

This may be useful later if you want a more production-realistic algorithm variant.

### Alternative B: bucket starts empty

Rejected for Milestone 1 because:
- less intuitive for token bucket interviews
- makes first-call behavior surprising
- reduces learning value around burst capacity

### Alternative C: background refill scheduler

Rejected for Milestone 1 because:
- introduces scheduling and lifecycle complexity
- unnecessary for deterministic unit tests
- harder to reason about under concurrency

### Alternative D: single global lock for all keys

Rejected as the target design because:
- correct but overly coarse
- causes unrelated keys to block each other
- does not model scalable per-key isolation well

It may still appear temporarily in a learning prototype, but should not be the intended design direction.

## Interview variant mapping

The interview variant fits these decisions directly:
- bucket capacity: `N`, commonly `100`
- request cost: `2` tokens per API call
- refill rule: `1` token every `1` second
- per-key independent state
- same-key concurrent correctness required

This means the base milestone and the interview variant can share the same design, with only configuration differences for capacity and request cost.

## ADR-002: Prefer simple, teachable code structure over extra abstraction

Status:
- accepted

Date:
- 2026-06-14

## Context

This project is a learning-first system design and backend engineering lab.

The user goal is not only to produce working code, but to:
- understand the algorithm clearly
- explain the design in interviews
- connect low-level code to high-level system design
- stay close to production-style behavior without making the code unnecessarily hard to read

The earlier implementation used more separation and abstraction, including a separate in-memory store layer and separate lock/state structures. That style is defensible in production code, but it increased reading difficulty for a learner coming from a mostly Java 8 background.

## Decision

When multiple designs are technically valid, this project will prefer the simpler and more teachable structure as long as it does not reduce:
- correctness
- feature completeness
- interview readiness
- production-style behavior

Chosen coding-style direction:
- prefer fewer moving parts
- prefer one clear place where the core algorithm can be followed
- prefer explicit concurrency mechanisms over hidden or indirect ones
- prefer modern Java or Spring Boot features only when they improve readability for this project
- add intent-focused comments around algorithms, state transitions, concurrency, and important trade-offs

This means:
- simplicity is preferred over extra abstraction
- clarity is preferred over “premium-looking” architecture
- readability for a Java 8-oriented learner is an explicit design goal

## Consequences

This decision implies:
- a single-map per-key structure is preferred over multiple coordination structures when both are correct
- small domain models and explicit control flow are preferred over compact but harder-to-read patterns
- tests should read like executable documentation
- comments should explain intent, invariants, and edge cases, especially in concurrency-heavy code

## Rejected alternatives

### Alternative A: optimize primarily for abstraction purity

Rejected because:
- it makes learning slower
- it hides the algorithm behind extra layers
- it is harder to explain in an interview under time pressure

### Alternative B: simplify by removing production-relevant behavior

Rejected because:
- this project still needs concurrency correctness, realistic edge cases, and production-style evolution toward Redis, metrics, and HTTP integration
- the goal is simple structure, not toy behavior
