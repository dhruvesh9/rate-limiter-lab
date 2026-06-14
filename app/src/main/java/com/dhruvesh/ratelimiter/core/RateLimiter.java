package com.dhruvesh.ratelimiter.core;

import com.dhruvesh.ratelimiter.model.RateLimitDecision;
import com.dhruvesh.ratelimiter.model.RateLimitRequest;

/**
 * Small domain-facing interface for rate-limit evaluation.
 */
public interface RateLimiter {

	RateLimitDecision evaluate(RateLimitRequest request);
}
