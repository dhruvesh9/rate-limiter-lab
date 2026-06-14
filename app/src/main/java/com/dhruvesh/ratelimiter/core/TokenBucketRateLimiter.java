package com.dhruvesh.ratelimiter.core;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.dhruvesh.ratelimiter.model.BucketState;
import com.dhruvesh.ratelimiter.model.RateLimitConfig;
import com.dhruvesh.ratelimiter.model.RateLimitDecision;
import com.dhruvesh.ratelimiter.model.RateLimitRequest;
import com.dhruvesh.ratelimiter.time.SystemTimeSource;
import com.dhruvesh.ratelimiter.time.TimeSource;

/**
 * Token bucket implementation of the RateLimiter interface.
 *
 * Main responsibilities:
 * - hold per-key bucket state in memory
 * - refill tokens based on elapsed time
 * - decide allow or deny
 * - consume tokens on success
 * - protect same-key updates from race conditions
 *
 * Design note:
 * This class is intentionally framework-agnostic.
 * It knows nothing about Spring MVC, controllers, or HTTP headers.
 *
 * Simplicity note:
 * This class keeps the in-memory data structure inside itself instead of
 * splitting state storage and locking into separate classes. That is easier
 * to read when learning the algorithm.
 */
public class TokenBucketRateLimiter implements RateLimiter {

	// Single in-memory map holding everything for one key:
	// - the mutable bucket state
	// - the lock that protects that state
	private final ConcurrentHashMap<String, BucketEntry> buckets = new ConcurrentHashMap<String, BucketEntry>();

	// Abstract time provider so tests can control time deterministically.
	private final TimeSource timeSource;

	public TokenBucketRateLimiter() {
		this(new SystemTimeSource());
	}

	public TokenBucketRateLimiter(TimeSource timeSource) {
		if (timeSource == null) {
			throw new IllegalArgumentException("timeSource must not be null");
		}
		this.timeSource = timeSource;
	}

	@Override
	public RateLimitDecision evaluate(RateLimitRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("request must not be null");
		}

		// Extract the key once so the rest of the method is easier to read.
		String key = request.getKey();
		RateLimitConfig config = request.getConfig();
		Instant now = timeSource.now();

		// Create the key entry on first use, otherwise reuse the existing one.
		// computeIfAbsent is atomic, so all threads for the same key see the same entry.
		BucketEntry bucketEntry = buckets.computeIfAbsent(key, ignoredKey -> createBucketEntry(config, now));
		ReentrantLock lock = bucketEntry.getLock();

		// Lock only this key.
		// This is the main concurrency guarantee of Milestone 1:
		// requests for the same key must be serialized.
		lock.lock();
		try {
			return evaluateInsideLock(request, bucketEntry, now);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Runs the full rate-limit evaluation while already holding the per-key lock.
	 *
	 * Because we are inside the key-specific lock, the sequence below is atomic
	 * for one key:
	 * - fetch bucket
	 * - refill bucket
	 * - check tokens
	 * - consume tokens if allowed
	 * - state stays safely updated for the next request
	 */
	private RateLimitDecision evaluateInsideLock(RateLimitRequest request, BucketEntry bucketEntry, Instant now) {
		// Policy settings such as capacity and refill rule.
		RateLimitConfig config = request.getConfig();

		// Read the mutable bucket state for this key.
		BucketState bucketState = bucketEntry.getBucketState();

		// Before deciding allow/deny, bring the bucket up to date with elapsed time.
		refillBucket(bucketState, config, now);

		// If the request cost is larger than total capacity,
		// the request can never succeed under this policy.
		if (request.getCost() > config.getCapacity()) {
			return new RateLimitDecision(false, bucketState.getAvailableTokens(), config.getCapacity(), null);
		}

		// Happy path: enough tokens exist, so consume them and allow the request.
		if (bucketState.getAvailableTokens() >= request.getCost()) {
			int remainingTokens = bucketState.getAvailableTokens() - request.getCost();
			bucketState.setAvailableTokens(remainingTokens);
			return new RateLimitDecision(true, remainingTokens, config.getCapacity(), null);
		}

		// Not enough tokens. Calculate how long the caller would need to wait.
		Duration retryAfter = calculateRetryAfter(bucketState, config, request.getCost(), now);
		return new RateLimitDecision(false, bucketState.getAvailableTokens(), config.getCapacity(), retryAfter);
	}

	private BucketEntry createBucketEntry(RateLimitConfig config, Instant now) {
		BucketState initialState = BucketState.createFullBucket(config.getCapacity(), now);
		return new BucketEntry(initialState);
	}

	/**
	 * Apply interval-based refill.
	 *
	 * Example:
	 * - refill rule = 1 token every 1 second
	 * - last refill = 10:00:00
	 * - now = 10:00:03.800
	 * - completed intervals = 3
	 * - tokens added = 3
	 */
	private void refillBucket(BucketState bucketState, RateLimitConfig config, Instant now) {
		if (now.isBefore(bucketState.getLastRefillAt())) {
			throw new IllegalArgumentException("current time must not be earlier than last refill time");
		}

		// How much real time passed since this bucket was last brought up to date?
		long elapsedMillis = Duration.between(bucketState.getLastRefillAt(), now).toMillis();

		// Refill period converted to milliseconds so we can work with whole intervals.
		long refillPeriodMillis = config.getRefillPeriod().toMillis();

		// Interval-based refill means we count only complete refill periods.
		long completedIntervals = elapsedMillis / refillPeriodMillis;

		// If no full interval passed, no refill happens.
		if (completedIntervals <= 0) {
			return;
		}

		// Total new tokens earned since the last refill timestamp.
		long tokensToAdd = completedIntervals * config.getRefillTokens();

		// Add earned tokens to current tokens.
		long totalTokens = bucketState.getAvailableTokens() + tokensToAdd;

		// A bucket is never allowed to exceed its configured capacity.
		if (totalTokens > config.getCapacity()) {
			totalTokens = config.getCapacity();
		}

		// Persist the new token count.
		bucketState.setAvailableTokens((int) totalTokens);

		// Move the last refill timestamp forward by the exact number of completed intervals.
		// This preserves leftover partial time for the next request.
		bucketState.setLastRefillAt(bucketState.getLastRefillAt().plusMillis(completedIntervals * refillPeriodMillis));
	}

	/**
	 * Calculate how long the caller must wait until enough tokens become available.
	 *
	 * For example:
	 * - available = 0
	 * - request cost = 2
	 * - refill = 1 token / second
	 * Then the caller must wait 2 seconds.
	 */
	private Duration calculateRetryAfter(BucketState bucketState, RateLimitConfig config, int requestCost, Instant now) {
		// How many more tokens are needed before this request can succeed?
		int missingTokens = requestCost - bucketState.getAvailableTokens();

		// Count how many refill intervals are needed to produce the missing tokens.
		long intervalsNeeded = missingTokens / config.getRefillTokens();
		if (missingTokens % config.getRefillTokens() != 0) {
			intervalsNeeded++;
		}

		// Compute the future instant when the required tokens should exist.
		Instant nextAvailableAt = bucketState.getLastRefillAt()
				.plus(config.getRefillPeriod().multipliedBy(intervalsNeeded));

		// Convert that future instant into a wait duration for the caller.
		return Duration.between(now, nextAvailableAt);
	}

	/**
	 * Small holder object for everything related to one key.
	 *
	 * Why this exists:
	 * - one map entry per key is easier to understand than separate state and lock maps
	 * - the bucket state and the lock naturally belong together
	 */
	private static class BucketEntry {

		private final ReentrantLock lock;
		private final BucketState bucketState;

		private BucketEntry(BucketState bucketState) {
			this.lock = new ReentrantLock();
			this.bucketState = bucketState;
		}

		private ReentrantLock getLock() {
			return lock;
		}

		private BucketState getBucketState() {
			return bucketState;
		}
	}
}
