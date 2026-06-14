# Rate Limiter Lab Requirements

## Project goal

The long-term goal of this project is to build a production-style API rate limiting system and use it to learn system design, low-level design, testing, observability, and distributed systems concepts relevant to backend interviews.

The final system may later include:
- a Spring Boot API
- in-memory rate limiting
- Redis-backed distributed rate limiting
- metrics and dashboards
- structured logging
- load testing
- infrastructure components such as reverse proxy or API gateway integration

This document defines requirements for the current milestone only.

## Milestone 1 scope

Milestone 1 focuses on the core in-memory token bucket limiter.

Included:
- token bucket domain behavior
- per-key rate limit evaluation
- deterministic unit-testable behavior

Excluded:
- HTTP endpoint protection
- Redis or any distributed coordination
- API gateway integration
- ELK/log pipeline setup
- Prometheus/Grafana dashboards
- load testing

## Milestone 1 functional requirements

### FR-1: The limiter must evaluate requests per key

The limiter must support rate limiting for an arbitrary key representing a client identity.

Examples of keys for later milestones:
- user id
- API key
- IP address
- tenant id

For Milestone 1, the key is treated as an opaque string-like identifier. The limiter does not need to understand its meaning.

Why this matters:
- In system design, rate limiting is almost always scoped per identity, not globally.

### FR-2: Each key must have an independent token bucket

The limiter must maintain separate state for each key.

Behavior:
- requests for one key must not consume tokens from another key
- each key must track its own available tokens and refill timing

Why this matters:
- This is the minimum correctness requirement for multi-client behavior.

### FR-3: Each bucket must have a maximum capacity

Each key’s bucket must have a configured maximum number of tokens.

Behavior:
- the number of available tokens must never exceed the configured capacity
- the bucket may start with an initial number of tokens, to be decided in `docs/decisions.md`

Why this matters:
- Capacity defines burst tolerance, which is a core token bucket property.

### FR-4: The limiter must support token refill over time

Each bucket must regain tokens as time passes according to refill configuration.

Inputs required:
- refill rate
- refill period or equivalent time unit
- a time source

Behavior:
- token availability must change based on elapsed time
- refill behavior must be deterministic for a given input time sequence
- exact refill semantics will be recorded separately in `docs/decisions.md`

Why this matters:
- Time-based refill is the central behavior that distinguishes token bucket from simpler counters.

### FR-5: A request must declare token cost

Each rate limit check must support a token cost representing how many tokens the request consumes if allowed.

Behavior:
- a normal request may consume one token
- future use cases may consume more than one token
- the request cost must be validated against bucket state

Why this matters:
- Cost-based requests make the model more realistic and expose edge cases interviewers often ask about.

### FR-6: The limiter must return an allow or deny decision

For each request, the limiter must return a decision indicating whether the request is allowed.

Behavior:
- if enough tokens are available, the request is allowed and tokens are consumed
- if enough tokens are not available, the request is denied and token consumption rules must be consistent with the chosen design

Minimum output:
- allowed or denied

Future-friendly output that may be useful later:
- remaining tokens
- configured capacity
- retry-after estimate
- timestamp or duration until next token becomes available

Why this matters:
- The decision contract becomes the basis for HTTP headers, metrics, logs, and debugging later.

### FR-7: The limiter must be testable with a controllable time source

The design must not depend directly on wall-clock time in a way that makes unit tests brittle.

Behavior:
- the limiter must be able to use a controllable or injectable time source
- tests must be able to simulate elapsed time deterministically

Why this matters:
- If time cannot be controlled, your tests will be flaky and your design will be hard to defend in interviews.

### FR-8: The limiter must behave deterministically for repeated evaluations

Given the same initial bucket state, same request sequence, and same time progression, the limiter must produce the same results every time.

Why this matters:
- Determinism is required for clean testing and for reasoning about correctness.

### FR-9: The limiter must support in-memory state for Milestone 1

Bucket state will be stored in-process in application memory for the first milestone.

Behavior:
- state persists only for the lifetime of the process
- no cross-instance coordination is required
- crash recovery is out of scope

Why this matters:
- This keeps the first implementation small while preserving a clean path to Redis later.

## Milestone 1 non-functional requirements

### NFR-1: Keep the first design small

The first implementation should use the minimum number of domain abstractions needed for clarity and testability.

Why this matters:
- Over-design is a common failure mode in learning projects and interviews.

### NFR-2: Separate domain logic from framework concerns

The token bucket algorithm must be designed independently from Spring MVC, controllers, filters, and HTTP response formatting.

Why this matters:
- This separation makes the limiter reusable and easier to test.

### NFR-3: Concurrency expectations must be stated explicitly

Milestone 1 must document whether same-key concurrent access is required to be correct.

Note:
- The exact concurrency guarantee is a design decision and should be written in `docs/decisions.md`.

Why this matters:
- A Spring application handles concurrent requests by default, so concurrency cannot remain implicit.

## Inputs and outputs summary

### Required inputs
- key
- bucket capacity
- refill rate
- refill period or equivalent refill configuration
- token cost
- time source

### Required output
- allow or deny decision

### Optional output for future milestones
- remaining tokens
- retry-after estimate
- bucket capacity
- diagnostic metadata for logs or metrics

## Milestone 1 acceptance statement

Milestone 1 requirements are satisfied when:
- the in-memory token bucket behavior is clearly documented
- per-key isolation is explicitly required
- refill and token consumption are defined at the contract level
- the decision output is defined
- testability through a controllable time source is required
- out-of-scope production components are intentionally deferred
