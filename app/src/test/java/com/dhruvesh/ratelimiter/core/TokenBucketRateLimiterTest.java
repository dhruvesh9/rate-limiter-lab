package com.dhruvesh.ratelimiter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.dhruvesh.ratelimiter.model.RateLimitConfig;
import com.dhruvesh.ratelimiter.model.RateLimitDecision;
import com.dhruvesh.ratelimiter.model.RateLimitRequest;
import com.dhruvesh.ratelimiter.time.TimeSource;

/**
 * Test suite for the in-memory token bucket implementation.
 *
 * Intent of this file:
 * - Each test documents one important business rule of the limiter.
 * - The tests are not only for regression safety; they are also for learning.
 * - A new reader should be able to understand the algorithm by reading these tests
 *   before reading the production code.
 *
 * Reading strategy:
 * 1. Read the test name.
 * 2. Read the comment above the test.
 * 3. Understand the setup, action, and expected outcome.
 * 4. Then map that rule to TokenBucketRateLimiter.
 */
class TokenBucketRateLimiterTest {

	// Fixed starting point for time in tests.
	//
	// Why we use a fixed timestamp:
	// - deterministic tests are easier to reason about
	// - no dependency on real system time
	// - no flaky tests caused by waiting/sleeping
	private static final Instant BASE_TIME = Instant.parse("2026-06-13T00:00:00Z");

	// Default policy used by most tests:
	// - bucket capacity = 10
	// - refill = 1 token every 1 second
	//
	// This small configuration is intentional:
	// - small numbers make mental simulation easy
	// - easier for a junior engineer to validate expected token counts manually
	private static final RateLimitConfig DEFAULT_CONFIG = new RateLimitConfig(10, 1, Duration.ofSeconds(1));

	@Test
	void newKeyStartsWithFullBucket() {
		// Intent:
		// A brand-new key should not start empty in Milestone 1.
		// Our design decision says new buckets start full so the client gets
		// burst capacity immediately.
		//
		// Setup:
		// - create a limiter with a fake clock
		// - evaluate the first request for "user-1"
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		// Action:
		// First request costs 2 tokens.
		RateLimitDecision decision = limiter.evaluate(new RateLimitRequest("user-1", 2, DEFAULT_CONFIG));

		// Expectation:
		// - request is allowed because a new bucket starts with 10 tokens
		// - after consuming 2, 8 tokens remain
		assertTrue(decision.isAllowed());
		assertEquals(8, decision.getRemainingTokens());
	}

	@Test
	void successfulRequestConsumesTokens() {
		// Intent:
		// Prove that allowed requests actually reduce bucket state.
		// This is basic correctness: allow without consumption would break the limiter.
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		// First request: 10 -> 8
		limiter.evaluate(new RateLimitRequest("user-1", 2, DEFAULT_CONFIG));

		// Second request: 8 -> 6
		RateLimitDecision secondDecision = limiter.evaluate(new RateLimitRequest("user-1", 2, DEFAULT_CONFIG));

		// Expectation:
		// State must carry forward across requests for the same key.
		assertTrue(secondDecision.isAllowed());
		assertEquals(6, secondDecision.getRemainingTokens());
	}

	@Test
	void deniesWhenAvailableTokensAreLessThanRequestCost() {
		// Intent:
		// Prove the limiter denies requests when a bucket does not have enough tokens.
		//
		// This is the main guardrail of rate limiting:
		// if available tokens < request cost, the request must not be allowed.
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		// Consume all 10 tokens using five requests that each cost 2.
		for (int i = 0; i < 5; i++) {
			limiter.evaluate(new RateLimitRequest("user-1", 2, DEFAULT_CONFIG));
		}

		// Now the bucket is empty. Another request costing 2 must be denied.
		RateLimitDecision deniedDecision = limiter.evaluate(new RateLimitRequest("user-1", 2, DEFAULT_CONFIG));

		// Expectation:
		// - denied
		// - 0 tokens remain
		// - retryAfter should be 2 seconds because refill rate is 1 token/second
		//   and we need 2 more tokens for this request
		assertFalse(deniedDecision.isAllowed());
		assertEquals(0, deniedDecision.getRemainingTokens());
		assertEquals(Duration.ofSeconds(2), deniedDecision.getRetryAfter());
	}

	@Test
	void refillsAfterOneInterval() {
		// Intent:
		// Prove interval-based refill works for one full refill period.
		//
		// We are explicitly testing the chosen design:
		// 1 token is added after 1 full second has passed.
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		// Spend 2 tokens first: 10 -> 8
		limiter.evaluate(new RateLimitRequest("user-1", 2, DEFAULT_CONFIG));

		// Move time forward by exactly one refill interval.
		timeSource.advanceBy(Duration.ofSeconds(1));

		// Now request 1 token.
		// Before consuming, refill should happen: 8 -> 9
		// Then consume 1: 9 -> 8
		RateLimitDecision decision = limiter.evaluate(new RateLimitRequest("user-1", 1, DEFAULT_CONFIG));

		assertTrue(decision.isAllowed());
		assertEquals(8, decision.getRemainingTokens());
	}

	@Test
	void refillsAfterMultipleIntervalsWithoutExceedingCapacity() {
		// Intent:
		// Prove two rules at the same time:
		// 1. multiple elapsed refill intervals add multiple tokens
		// 2. a bucket must never exceed its configured capacity
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		// Spend 9 tokens: 10 -> 1
		limiter.evaluate(new RateLimitRequest("user-1", 9, DEFAULT_CONFIG));

		// Advance time by much more than enough to refill to capacity.
		// Without a capacity cap, this would overshoot badly.
		timeSource.advanceBy(Duration.ofSeconds(20));

		// Refill should cap at 10, not 21.
		// Then consuming 1 token should leave 9.
		RateLimitDecision decision = limiter.evaluate(new RateLimitRequest("user-1", 1, DEFAULT_CONFIG));

		assertTrue(decision.isAllowed());
		assertEquals(9, decision.getRemainingTokens());
	}

	@Test
	void repeatedCallsAtSameTimestampDoNotRefill() {
		// Intent:
		// Prove that refill does not happen unless enough time has passed.
		//
		// This is important because repeated calls at the exact same timestamp
		// must keep consuming the same bucket without magically regenerating tokens.
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		// All three requests happen at the exact same fake time.
		RateLimitDecision first = limiter.evaluate(new RateLimitRequest("user-1", 4, DEFAULT_CONFIG));
		RateLimitDecision second = limiter.evaluate(new RateLimitRequest("user-1", 4, DEFAULT_CONFIG));
		RateLimitDecision third = limiter.evaluate(new RateLimitRequest("user-1", 4, DEFAULT_CONFIG));

		// Expected sequence:
		// - first: 10 -> 6
		// - second: 6 -> 2
		// - third: denied because only 2 tokens remain and no time has advanced
		assertTrue(first.isAllowed());
		assertTrue(second.isAllowed());
		assertFalse(third.isAllowed());
		assertEquals(2, third.getRemainingTokens());
	}

	@Test
	void requestCostGreaterThanCapacityIsDenied() {
		// Intent:
		// Prove that an impossible request is always denied.
		//
		// If bucket capacity is 10, a request costing 11 can never succeed,
		// even if the caller waits forever.
		// This is a policy/configuration problem, not a temporary shortage problem.
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		RateLimitDecision decision = limiter.evaluate(new RateLimitRequest("user-1", 11, DEFAULT_CONFIG));

		// Expectation:
		// - denied
		// - no state corruption; bucket still remains full at 10
		// - retryAfter is null because waiting cannot ever make this request valid
		assertFalse(decision.isAllowed());
		assertEquals(10, decision.getRemainingTokens());
		assertNull(decision.getRetryAfter());
	}

	@Test
	void differentKeysRemainIsolated() {
		// Intent:
		// Prove that one key's traffic does not affect another key's bucket.
		//
		// This is a core rate-limiter requirement:
		// each client identity must have its own independent state.
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);

		// Spend most of user-1's tokens.
		limiter.evaluate(new RateLimitRequest("user-1", 8, DEFAULT_CONFIG));

		// user-2 should still get a fresh full bucket.
		RateLimitDecision otherKeyDecision = limiter.evaluate(new RateLimitRequest("user-2", 2, DEFAULT_CONFIG));

		// user-2 starts from 10, consumes 2, leaves 8.
		assertTrue(otherKeyDecision.isAllowed());
		assertEquals(8, otherKeyDecision.getRemainingTokens());
	}

	@Test
	void sameKeyConcurrentRequestsCannotOverspend() throws InterruptedException, ExecutionException {
		// Intent:
		// This is the most important concurrency test in the suite.
		//
		// We want to prove that many threads hitting the SAME key at the SAME time
		// do not overspend the shared bucket.
		//
		// Interview-style configuration:
		// - capacity = 100
		// - refill = 1 token/second
		// - each request costs 2 tokens
		//
		// Math:
		// - a full bucket contains 100 tokens
		// - each allowed request costs 2
		// - therefore only 50 requests can succeed before the bucket is empty
		// - if we send 80 concurrent requests, exactly 50 should succeed and 30 should fail
		//
		// If this test fails and more than 50 are allowed, it means our locking is broken
		// and multiple threads are consuming the same tokens incorrectly.
		FakeTimeSource timeSource = new FakeTimeSource(BASE_TIME);
		TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(timeSource);
		RateLimitConfig interviewConfig = new RateLimitConfig(100, 1, Duration.ofSeconds(1));
		ExecutorService executor = Executors.newFixedThreadPool(16);

		try {
			// Build 80 concurrent tasks that all target the same key.
			List<Callable<RateLimitDecision>> tasks = new ArrayList<Callable<RateLimitDecision>>();
			for (int i = 0; i < 80; i++) {
				tasks.add(new Callable<RateLimitDecision>() {
					@Override
					public RateLimitDecision call() {
						return limiter.evaluate(new RateLimitRequest("shared-key", 2, interviewConfig));
					}
				});
			}

			List<Future<RateLimitDecision>> futures = executor.invokeAll(tasks);
			int allowedCount = 0;
			int deniedCount = 0;

			// Count how many threads were allowed and how many were denied.
			for (Future<RateLimitDecision> future : futures) {
				RateLimitDecision decision = future.get();
				if (decision.isAllowed()) {
					allowedCount++;
				} else {
					deniedCount++;
				}
			}

			assertEquals(50, allowedCount);
			assertEquals(30, deniedCount);
		} finally {
			// Always shut down the thread pool so the test does not leak threads.
			executor.shutdown();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	/**
	 * Fake clock used only by tests.
	 *
	 * Why this helper exists:
	 * - We do not want tests to call the real system clock.
	 * - We want full control over time progression.
	 * - We want to test refill logic instantly, without Thread.sleep().
	 */
	private static class FakeTimeSource implements TimeSource {

		// Mutable current time controlled by the test.
		private Instant current;

		private FakeTimeSource(Instant initialTime) {
			this.current = initialTime;
		}

		@Override
		public synchronized Instant now() {
			// Return the current fake time instead of real wall-clock time.
			return current;
		}

		private synchronized void advanceBy(Duration duration) {
			// Move time forward deterministically.
			// This is how tests simulate refill intervals.
			current = current.plus(duration);
		}
	}
}
