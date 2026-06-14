package com.dhruvesh.ratelimiter.model;

import java.time.Duration;

/**
 * Immutable configuration for one rate-limit policy.
 *
 * Example:
 * - capacity = 100
 * - refillTokens = 1
 * - refillPeriod = 1 second
 */
public class RateLimitConfig {

	// Maximum tokens a bucket can hold.
	private final int capacity;

	// Number of tokens added every refill period.
	private final int refillTokens;

	// Time interval used for refill.
	private final Duration refillPeriod;

	public RateLimitConfig(int capacity, int refillTokens, Duration refillPeriod) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		if (refillTokens <= 0) {
			throw new IllegalArgumentException("refillTokens must be positive");
		}
		if (refillPeriod == null) {
			throw new IllegalArgumentException("refillPeriod must not be null");
		}
		if (refillPeriod.isZero() || refillPeriod.isNegative()) {
			throw new IllegalArgumentException("refillPeriod must be positive");
		}
		this.capacity = capacity;
		this.refillTokens = refillTokens;
		this.refillPeriod = refillPeriod;
	}

	public int getCapacity() {
		return capacity;
	}

	public int getRefillTokens() {
		return refillTokens;
	}

	public Duration getRefillPeriod() {
		return refillPeriod;
	}
}
