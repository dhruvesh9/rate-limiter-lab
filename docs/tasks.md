# Milestone 1: In-Memory Token Bucket Core

Goal: build and validate a single-node, in-memory token bucket limiter before adding HTTP integration or distributed coordination.

Status: in progress

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

## Immediate next stories

### Story 1: Write the first functional contract

Goal:
- Document what a single `allow` decision means for one key in the in-memory limiter.

Your task:
- Update `docs/requirements.md` with a first-pass contract for:
  - request key
  - bucket capacity
  - refill configuration
  - token cost per request
  - allow/deny result

Expected output:
- A short requirements section, not implementation details.

Why this is first:
- You should not design classes until the behavior is explicit.

Suggested commit:
- `docs: define milestone 1 limiter contract`

Status:
- completed

### Story 2: Record semantic decisions

Goal:
- Freeze the algorithm rules so later tests and code have one source of truth.

Your task:
- Update `docs/decisions.md` with decisions for:
  - refill model
  - initial bucket state
  - fractional token handling
  - request cost greater than bucket capacity
  - thread-safety expectation for milestone 1

Expected output:
- One concise decision record with rationale and rejected alternatives.

Why this matters:
- This is where system design becomes engineering instead of general theory.

Suggested commit:
- `docs: record token bucket semantics`

Status:
- completed

### Story 3: Propose the HLD

Goal:
- Describe the moving parts before you design method signatures.

Your task:
- Add a short high-level design note, either in `docs/system-design.md` or a section in `docs/requirements.md`, covering:
  - client request identified by key
  - limiter service
  - in-memory bucket store
  - clock/time source
  - decision result returned to caller

Expected output:
- A simple component view in words or bullets.

Why this matters:
- Interviewers expect you to separate algorithm, storage, and integration concerns.

Suggested commit:
- `docs: add milestone 1 high level design`

Status:
- completed

### Story 4: Propose the LLD

Goal:
- Define the smallest object model needed for implementation.

Your task:
- Write down the candidate interfaces/models only at the design level:
  - rate limiter interface
  - bucket state object
  - rate limit decision/result object
  - clock abstraction

Expected output:
- Interface notes and responsibilities, not full code.

Why this matters:
- Good low-level design keeps the first implementation small and testable.

Suggested commit:
- `design: sketch milestone 1 low level interfaces`

Status:
- completed

### Story 5: Write the unit test plan

Goal:
- Decide how you will prove correctness before you start writing production code.

Your task:
- Expand the test matrix into named test cases with setup, action, and expected result.
- Include fake clock usage in the plan.

Expected output:
- A clear unit-test checklist in `docs/tasks.md` or a dedicated test-plan section.

Why this matters:
- Time-based code fails at boundaries, not in happy-path demos.

Suggested commit:
- `test-plan: define milestone 1 token bucket scenarios`

Status:
- next

### Story 6: Prepare implementation slice

Goal:
- End Milestone 1 planning with one tiny, safe coding slice.

Your task:
- Identify the first implementation PR scope:
  - package creation
  - interfaces and models
  - one happy-path unit test
- Explicitly exclude concurrency hardening and Spring MVC integration from that first slice unless already decided.

Expected output:
- A narrow implementation plan for the first coding change.

Why this matters:
- Small slices reduce design mistakes and make review easier.

Suggested commit:
- `plan: define first implementation slice for limiter core`

Status:
- next

## Ready-to-code sequence

Follow this order when you start implementation:

1. finalize test scenarios
2. define the first implementation slice
3. create domain models and interfaces only
4. add fake clock support for tests
5. implement single-threaded algorithm flow
6. add concurrency protection for same-key access
7. add concurrency tests

Rule for yourself:
- do not combine all of these in one commit
- keep each change reviewable and easy to reason about

## Interview variant backlog

### Variant A: Fixed-cost token bucket under concurrency

Problem statement:
- each key has an independent bucket
- each bucket has capacity `N` tokens, with `100` as the default interview example
- each API call consumes `2` tokens
- `1` token is added every `1` second

Why this variant matters:
- it looks simple, but it forces you to reason about shared mutable state
- the real interview difficulty is usually concurrency correctness, not token math

Key design questions to answer before coding:
- what happens when available tokens are `1` and a request needs `2`
- whether refill is calculated lazily on access or by a background scheduler
- whether same-key concurrent requests must serialize correctly
- whether different keys should avoid blocking each other
- what time source and clock precision are assumed

Suggested learning order:
1. solve it single-threaded with deterministic tests
2. define the concurrency guarantees
3. add thread-safety for same-key access
4. test contention on one key and independence across many keys
