/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.region;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Backpressure for the expensive configuration stage that prepares player spawn
 * chunks. Without this, a login wave can start many spawn chunk loads at once.
 */
public final class RegionizedLoginLimiter {
    private static final Object LOCK = new Object();
    private static final Map<Object, Long> WAITING_LOGINS = new IdentityHashMap<>();
    private static int activeLogins;
    private static long deferredAttempts;

    private RegionizedLoginLimiter() {
    }

    public static void registerWaiting(Object login) {
        synchronized (LOCK) {
            WAITING_LOGINS.putIfAbsent(login, System.nanoTime());
        }
    }

    public static void unregisterWaiting(Object login) {
        synchronized (LOCK) {
            WAITING_LOGINS.remove(login);
        }
    }

    public static boolean tryAcquire(Object login) {
        int maxPendingLogins = RegionizedServerSettings.maxPendingLogins();
        synchronized (LOCK) {
            if (activeLogins >= maxPendingLogins) {
                WAITING_LOGINS.putIfAbsent(login, System.nanoTime());
                deferredAttempts++;
                return false;
            }

            WAITING_LOGINS.remove(login);
            activeLogins++;
            return true;
        }
    }

    public static void release() {
        synchronized (LOCK) {
            if (activeLogins > 0) {
                activeLogins--;
            }
        }
    }

    public static Snapshot snapshot() {
        long now = System.nanoTime();
        long oldestWaitingAgeNanos = 0L;
        synchronized (LOCK) {
            for (long waitingSince : WAITING_LOGINS.values()) {
                oldestWaitingAgeNanos = Math.max(oldestWaitingAgeNanos, now - waitingSince);
            }

            return new Snapshot(
                activeLogins,
                WAITING_LOGINS.size(),
                RegionizedServerSettings.maxPendingLogins(),
                Math.max(0L, oldestWaitingAgeNanos),
                deferredAttempts
            );
        }
    }

    public record Snapshot(
        int activeLogins,
        int waitingLogins,
        int maxPendingLogins,
        long oldestWaitingAgeNanos,
        long deferredAttempts
    ) {
    }
}
