package com.zaza.housing.util;

import java.time.Clock;
import java.util.LinkedHashMap;

/** Per-client fixed windows with bounded, access-ordered client state. */
public final class BoundedRateLimiter {
  private final Clock clock;
  private final int limit, maximumClients;
  private final LinkedHashMap<String, Integer> clients = new LinkedHashMap<>(16, .75f, true);
  private long minute = Long.MIN_VALUE;

  public BoundedRateLimiter(Clock clock, int limit, int maximumClients) {
    if (limit < 1 || maximumClients < 1)
      throw new IllegalArgumentException("Rate limits and client capacity must be positive.");
    this.clock = clock;
    this.limit = limit;
    this.maximumClients = maximumClients;
  }

  public synchronized boolean allow(String address) {
    long current = clock.instant().getEpochSecond() / 60;
    if (minute != current) { clients.clear(); minute = current; }
    Integer count = clients.get(address);
    if (count != null && count >= limit) return false;
    if (count == null && clients.size() >= maximumClients)
      clients.remove(clients.keySet().iterator().next());
    clients.put(address, count == null ? 1 : count + 1);
    return true;
  }
}
