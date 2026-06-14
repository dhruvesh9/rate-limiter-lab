package com.dhruvesh.ratelimiter.model;

import java.time.Instant;

/**
 * Mutable runtime state for one key's bucket.
 *
 * This is not policy/configuration.
 * This is the live state that changes on every request.
 */
public class BucketState {

	// Current number of tokens available to spend.
	private int availableTokens;

	// Last time we applied refill logic to this bucket.
	private Instant lastRefillAt;

	public BucketState(int availableTokens, Instant lastRefillAt) {
		if (availableTokens < 0) {
			throw new IllegalArgumentException("availableTokens must be non-negative");
		}
		if (lastRefillAt == null) {
			throw new IllegalArgumentException("lastRefillAt must not be null");
		}
		this.availableTokens = availableTokens;
		this.lastRefillAt = lastRefillAt;
	}

	public static BucketState createFullBucket(int capacity, Instant now) {
		// New keys start with a full bucket in Milestone 1.
		return new BucketState(capacity, now);
	}

	public int getAvailableTokens() {
		return availableTokens;
	}

	public void setAvailableTokens(int availableTokens) {
		if (availableTokens < 0) {
			throw new IllegalArgumentException("availableTokens must be non-negative");
		}
		this.availableTokens = availableTokens;
	}

	public Instant getLastRefillAt() {
		return lastRefillAt;
	}

	public void setLastRefillAt(Instant lastRefillAt) {
		if (lastRefillAt == null) {
			throw new IllegalArgumentException("lastRefillAt must not be null");
		}
		this.lastRefillAt = lastRefillAt;
	}
}
