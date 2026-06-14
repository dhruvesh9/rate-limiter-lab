# Milestone 1 System Design

## Purpose

This document defines the High-Level Design (HLD) and Low-Level Design (LLD) for Milestone 1 of `rate-limiter-lab`.

Milestone 1 scope:
- in-memory token bucket only
- per-key rate limit decisions
- deterministic unit testing
- same-key concurrency correctness within one JVM

Out of scope:
- Spring MVC integration
- Redis distribution
- metrics and dashboards
- log pipelines
- load testing

## HLD

### Goal

Accept a request identified by a key, evaluate whether enough tokens are available in that key’s bucket, and return an allow or deny decision.

### Main components

#### 1. Caller

This is the code that asks whether a request is allowed.

For Milestone 1:
- this may be a unit test or a simple service-level caller

For later milestones:
- this may be a controller, filter, interceptor, or gateway-facing adapter

Responsibility:
- provide key and request cost
- consume the returned decision

#### 2. Rate limiter service

This is the core domain component.

Responsibility:
- load or initialize bucket state for a key
- compute refill based on elapsed time
- decide allow or deny
- consume tokens on allowed requests
- return decision metadata

This is the center of the Milestone 1 design.

#### 3. In-memory bucket store

This component holds bucket state per key.

Responsibility:
- map each key to its current bucket state
- support safe concurrent access
- keep keys isolated from each other

For Milestone 1, this is process-local memory only.

#### 4. Time source

This component provides the current time.

Responsibility:
- provide deterministic time to domain logic
- allow tests to simulate elapsed time without sleeping

This is a small but important design choice because time-based logic is hard to test if you call wall clock APIs directly everywhere.

#### 5. Decision result

This is the object returned by the limiter.

Responsibility:
- communicate allow or deny
- optionally expose useful metadata such as remaining tokens or retry timing

This becomes important later for HTTP headers, logs, and metrics.

### HLD data flow

1. Caller submits `key` and `requestCost`
2. Rate limiter fetches or creates bucket state for that key
3. Rate limiter reads current time from the time source
4. Rate limiter computes elapsed time and applies refill
5. Rate limiter checks whether enough tokens are available
6. If enough tokens exist, limiter consumes tokens and returns `allowed`
7. Otherwise, limiter returns `denied`
8. Updated bucket state is stored for that key

### HLD design choices

#### Lazy refill

Refill happens when a request arrives, not in a background scheduler.

Why:
- simpler lifecycle
- easier deterministic testing
- no unnecessary background work for idle keys

#### Per-key isolation

Each key has independent state.

Why:
- this matches real rate limiting behavior
- one client must not affect another

#### Same-key concurrency correctness

Same-key updates must be logically atomic.

Why:
- concurrent requests against the same key are the real correctness risk

## LLD

The LLD here describes responsibilities and method-level intent. It is not implementation code.

### Proposed package direction

Suggested package root:
- `com.dhruvesh.ratelimiter.ratelimiter`

Suggested sub-packages:
- `core`: domain interfaces and service
- `model`: bucket state, config, decision
- `time`: clock abstraction
- `store`: in-memory bucket state access

Why this split:
- keeps domain logic separate from framework code
- leaves room for later Redis and web adapters

### Core objects

#### 1. `RateLimiter`

Purpose:
- public domain interface for rate-limit evaluation

Suggested responsibility:
- expose a method that takes key, request cost, and relevant configuration inputs
- return a decision object

Design note:
- keep this interface small
- do not mix it with HTTP concepts like headers, request objects, or response entities

#### 2. `TokenBucketRateLimiter`

Purpose:
- token bucket implementation of the `RateLimiter` interface

Suggested responsibility:
- coordinate bucket lookup
- perform lazy refill calculation
- enforce allow/deny logic
- persist updated state

Design note:
- this class should contain the core algorithm
- it should depend on abstractions for time and state access where possible

#### 3. `BucketState`

Purpose:
- represent the mutable state of one key’s bucket

Suggested fields:
- available tokens
- last refill timestamp

Responsibility:
- carry only per-key runtime state
- not global configuration

Design note:
- keep config separate from state so tests can vary state and policy independently

#### 4. `RateLimitConfig`

Purpose:
- hold rate limit policy inputs

Suggested fields:
- capacity
- refill tokens per period
- refill period

For the interview variant:
- capacity `N`
- refill tokens `1`
- refill period `1 second`

Responsibility:
- define the rule set applied to requests

Design note:
- this keeps policy separate from bucket runtime state

#### 5. `RateLimitRequest`

Purpose:
- represent one evaluation request

Suggested fields:
- key
- request cost
- config reference or policy inputs

Responsibility:
- package the input needed for one decision

Design note:
- this object is optional for the first implementation
- it is useful if you want a clean method signature and later extensibility

#### 6. `RateLimitDecision`

Purpose:
- represent the result of one evaluation

Suggested fields:
- allowed
- remaining tokens
- capacity
- retry-after estimate or next available time

Responsibility:
- provide enough domain information for later adapters

Design note:
- do not overstuff this with logging-only or transport-only fields yet

#### 7. `TimeSource`

Purpose:
- abstract current time access

Suggested responsibility:
- return the current time in a consistent unit

Design note:
- tests should be able to provide a fake implementation
- avoid burying direct wall-clock reads inside the algorithm

#### 8. `BucketStateStore`

Purpose:
- provide access to per-key bucket state

Suggested responsibility:
- fetch existing state for a key
- create initial state for a new key
- support safe same-key updates

Design note:
- for Milestone 1, this can still be very small
- later, a Redis-backed implementation can follow the same role conceptually

### Concurrency design direction

This is the most important LLD topic for your interview variant.

#### Required guarantee

For the same key, this logical sequence must be atomic:
- read current state
- apply refill
- check tokens
- consume if allowed
- store updated state

If it is not atomic, two concurrent threads can both succeed using the same tokens.

#### Desired isolation

Different keys should proceed independently where practical.

That means:
- avoid a single coarse lock for all keys as the intended design
- prefer a design direction where one hot key does not block unrelated keys

#### Important note for implementation

You do not need a perfect distributed lock strategy now.
You do need a clear single-process consistency strategy.

## Coding task breakdown

These are the first coding tasks that should follow this design.

### Task C1: Create the domain model skeleton

Goal:
- create the minimum model types without algorithm logic

Expected files:
- config model
- decision model
- bucket state model
- clock abstraction
- rate limiter interface

Why this task exists:
- it creates the vocabulary of the design before mutable logic appears

### Task C2: Add a fake time source for tests

Goal:
- make time-dependent tests deterministic from the start

Expected outcome:
- tests can advance time manually without sleeping

Why this task exists:
- otherwise you will write weak tests and patch testability later

### Task C3: Implement single-threaded token refill and consume flow

Goal:
- prove the core math and state transitions first

Scope:
- allow request when enough tokens exist
- deny request when not enough tokens exist
- refill based on elapsed whole intervals
- enforce capacity ceiling

Why this task exists:
- separates algorithm correctness from concurrency complexity

### Task C4: Add deterministic unit tests for core scenarios

Goal:
- lock in behavior before concurrency hardening

Minimum scenarios:
- initial bucket full
- repeated successful consumption
- denial when tokens are insufficient
- refill after elapsed time
- refill capped at capacity
- request cost greater than capacity denied

Why this task exists:
- you need proof that the algorithm is right before adding thread-safety details

### Task C5: Add same-key concurrency protection

Goal:
- make the state transition safe under concurrent access

Scope:
- same-key requests must not overspend tokens
- different keys should remain logically independent

Why this task exists:
- this is the real engineering difficulty in your interview variant

### Task C6: Add concurrency-focused tests

Goal:
- verify correctness under contention

Minimum scenarios:
- many threads on one key do not allow more requests than tokens support
- requests on different keys do not corrupt each other

Why this task exists:
- concurrency claims without tests are weak claims

## Definition of coding-ready

The project is ready for implementation when:
- requirements are documented
- decisions are recorded
- HLD identifies the main components
- LLD defines the object responsibilities
- coding tasks are small and ordered
- each coding task has a clear reason and scope
