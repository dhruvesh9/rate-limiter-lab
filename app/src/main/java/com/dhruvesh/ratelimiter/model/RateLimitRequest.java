package com.dhruvesh.ratelimiter.model;

/**
 * Input to one rate-limit evaluation.
 */
public class RateLimitRequest {

	// Identity being rate limited, such as API key or user id.
	private final String key;

	// Number of tokens this request wants to consume.
	private final int cost;

	// Policy that should be applied to this request.
	private final RateLimitConfig config;

	public RateLimitRequest(String key, int cost, RateLimitConfig config) {
		if (key == null || key.trim().isEmpty()) {
			throw new IllegalArgumentException("key must not be blank");
		}
		if (cost <= 0) {
			throw new IllegalArgumentException("cost must be positive");
		}
		if (config == null) {
			throw new IllegalArgumentException("config must not be null");
		}
		this.key = key;
		this.cost = cost;
		this.config = config;
	}

	public String getKey() {
		return key;
	}

	public int getCost() {
		return cost;
	}

	public RateLimitConfig getConfig() {
		return config;
	}
}
