# Milestone 1: In-Memory Token Bucket Core

Goal: build and validate a single-node, in-memory token bucket limiter before adding HTTP integration or distributed coordination.

Status: proposed

## Scope boundaries

- Include only core rate-limiter domain logic and unit tests.
- Do not add Spring MVC endpoint protection in this milestone.
- Do not add Redis, metrics, Grafana, structured logging, or load testing in this milestone.

## Tasks

### M1-T1: Define the problem contract

What to do:
- Write down the exact behavior the limiter must support.
- Clarify inputs: key, capacity, refill rate, refill period, token cost per request, and time source.
- Clarify outputs: allowed/denied decision and any metadata needed for future HTTP headers or metrics.

Why it matters:
- In interviews, unclear contracts create weak implementations and vague trade-offs.
- A strong rate limiter starts with explicit semantics, especially around refill behavior and request cost.

Suggested deliverable:
- A short checklist in this file or `docs/requirements.md` describing the first version contract.

### M1-T2: Choose the token bucket semantics

What to do:
- Decide whether refill is continuous or interval-based.
- Decide whether the bucket starts full.
- Decide how to handle fractional token accumulation.
- Decide what happens when a request asks for more tokens than bucket capacity.

Why it matters:
- These choices change correctness, test strategy, and future Redis compatibility.
- Interviewers often probe these exact edge cases to see whether the design is precise or hand-wavy.

Suggested deliverable:
- A decision entry in `docs/decisions.md` once you commit to one behavior.

### M1-T3: Design the core interfaces

What to do:
- Identify the minimum abstractions before coding:
  - limiter interface
  - decision/result model
  - bucket state model
  - pluggable clock/time source
- Keep the design small enough that the in-memory version is easy to reason about.

Why it matters:
- This is the seam between algorithm design and framework integration.
- A clock abstraction is especially important because time-dependent logic is otherwise hard to test cleanly.

Suggested deliverable:
- A short interface sketch in notes before implementation.

### M1-T4: Define concurrency expectations for the in-memory version

What to do:
- State whether Milestone 1 must be thread-safe.
- If yes, define the expected correctness under concurrent requests for the same key.
- State whether per-key isolation is required now or deferred to Milestone 2.

Why it matters:
- “In-memory” is not the same as “single-threaded.”
- For a Spring Boot service, concurrent access is the default runtime condition, and interviewers will challenge this immediately.

Suggested deliverable:
- A short note describing the concurrency target and non-goals for this milestone.

### M1-T5: Break testing into deterministic scenarios

What to do:
- List unit test cases before implementation.
- Cover normal flow, boundary behavior, and time progression.
- Use a fake or controllable clock in test design.

Why it matters:
- Time-based algorithms are easy to get mostly right and still wrong at the edges.
- Good tests prove you understand refill math, denial behavior, and state transitions.

Suggested initial test matrix:
- allows requests while tokens remain
- denies request when bucket is empty
- refills tokens after time advances
- never exceeds bucket capacity after refill
- handles exact-boundary refill correctly
- handles multiple elapsed refill periods correctly
- behaves correctly when request cost is greater than one token
- rejects impossible requests when cost exceeds bucket capacity
- preserves independent state for different keys
- behaves correctly under repeated calls at the same timestamp

### M1-T6: Decide package/module placement

What to do:
- Choose where the rate limiter domain classes should live under `app/src/main/java`.
- Keep algorithm code separate from HTTP/controller concerns from day one.

Why it matters:
- Package structure signals design maturity.
- Separating domain logic now prevents the Spring layer from becoming the algorithm layer later.

Suggested deliverable:
- A proposed package layout with one sentence per package.

### M1-T7: Define Milestone 1 acceptance criteria

What to do:
- Write a crisp “done” definition for the milestone.

Why it matters:
- Without acceptance criteria, it is easy to drift into endpoint work, observability work, or premature Redis design.
- Interview prep improves when each milestone has a clear behavioral claim you can defend.

Done when:
- token bucket behavior is explicitly documented
- core abstractions are chosen
- concurrency expectations are stated
- unit test cases are listed
- implementation can be developed next as a small, isolated change

## Recommended commit sequence

1. `docs: define milestone 1 contract and acceptance criteria`
2. `docs: record token bucket semantics and concurrency assumptions`
3. `test-plan: add milestone 1 unit test matrix`
