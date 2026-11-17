package com.zaza.housing.util;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BoundedRateLimiterTests {
  @Test
  void newClientsAreAdmittedWhenTrackedClientCapacityIsFull() {
    var limiter = new BoundedRateLimiter(Clock.systemUTC(), 1, 32);
    for (int i = 0; i < 10001; i++) assertThat(limiter.allow("client-" + i)).isTrue();
    assertThat(limiter.allow("new-legitimate-client")).isTrue();
    assertThat(limiter.allow("new-legitimate-client")).isFalse();
  }

  @Test
  void clientChurnDoesNotResetARecentlyUsedExhaustedBucket() {
    var limiter = new BoundedRateLimiter(Clock.systemUTC(), 1, 32);
    assertThat(limiter.allow("hot-client")).isTrue();
    for (int i = 0; i < 1000; i++) {
      assertThat(limiter.allow("hot-client")).isFalse();
      assertThat(limiter.allow("cold-" + i)).isTrue();
    }
  }

  @Test
  void newMinuteResetsClientQuota() {
    var now = new AtomicReference<>(Instant.parse("2026-11-17T00:00:00Z"));
    Clock clock = new Clock() {
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId zone) { return this; }
      public Instant instant() { return now.get(); }
    };
    var limiter = new BoundedRateLimiter(clock, 1, 32);
    assertThat(limiter.allow("client")).isTrue();
    assertThat(limiter.allow("client")).isFalse();
    now.set(now.get().plusSeconds(60));
    assertThat(limiter.allow("client")).isTrue();
  }
}
