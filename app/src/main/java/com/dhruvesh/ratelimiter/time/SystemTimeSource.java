package com.dhruvesh.ratelimiter.time;

import java.time.Instant;

/**
 * Production time source that simply returns the real current time.
 */
public class SystemTimeSource implements TimeSource {

	@Override
	public Instant now() {
		return Instant.now();
	}
}
