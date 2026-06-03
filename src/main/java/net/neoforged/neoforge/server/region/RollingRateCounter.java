/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public final class RollingRateCounter {
    private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private final ConcurrentLinkedQueue<Long> events = new ConcurrentLinkedQueue<>();

    public void record() {
        long now = System.nanoTime();
        this.events.add(now);
        this.prune(now);
    }

    public double ratePerSecond() {
        long now = System.nanoTime();
        this.prune(now);
        return (double)this.events.size() / ((double)WINDOW_NANOS / (double)TimeUnit.SECONDS.toNanos(1L));
    }

    private void prune(long now) {
        long oldestIncluded = now - WINDOW_NANOS;
        Long timestamp;
        while ((timestamp = this.events.peek()) != null && timestamp < oldestIncluded) {
            this.events.poll();
        }
    }
}
