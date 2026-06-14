package com.dhruvesh.ratelimiter.time;

import java.time.Instant;

/**
 * Clock abstraction.
 *
 * We use this instead of calling Instant.now() directly in the algorithm
 * so tests can fully control time.
 */
public interface TimeSource {

	Instant now();
}
