/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.handling;

import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
public interface QueuedPacket {
    void handle();

    default String description() {
        return this.getClass().getSimpleName();
    }

    record CustomPayload(Runnable task) implements QueuedPacket {
        @Override
        public String description() {
            return "CustomPayload";
        }

        @Override
        public void handle() {
            task.run();
        }
    }
}
