package com.dhruvesh.ratelimiter.model;

import java.time.Duration;

/**
 * Result returned by the rate limiter for one request.
 */
public class RateLimitDecision {

	// True means the request may proceed.
	private final boolean allowed;

	// Tokens left after evaluation.
	private final int remainingTokens;

	// Echoes the configured capacity for easier debugging and future HTTP mapping.
	private final int capacity;

	// If denied, this may tell the caller how long to wait.
	// Null means either:
	// - request was allowed
	// - or retry-after is not meaningful for this denial
	private final Duration retryAfter;

	public RateLimitDecision(boolean allowed, int remainingTokens, int capacity, Duration retryAfter) {
		if (remainingTokens < 0) {
			throw new IllegalArgumentException("remainingTokens must be non-negative");
		}
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive");
		}
		this.allowed = allowed;
		this.remainingTokens = remainingTokens;
		this.capacity = capacity;
		this.retryAfter = retryAfter;
	}

	public boolean isAllowed() {
		return allowed;
	}

	public int getRemainingTokens() {
		return remainingTokens;
	}

	public int getCapacity() {
		return capacity;
	}

	public Duration getRetryAfter() {
		return retryAfter;
	}
}
