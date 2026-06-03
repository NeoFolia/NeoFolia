# Forgia — Minecraft 1.21.1

A NeoForge fork porting [Folia](https://github.com/PaperMC/Folia)'s regionized multithreaded server model natively into the NeoForge modding ecosystem.

## What It Does

Enables Minecraft servers running NeoForge mods to distribute world tick processing across multiple threads organized by region, instead of running all world logic on a single server thread. This can significantly improve server performance when many players are spread across a world.

**This is not a Paper/Bukkit plugin.** The runtime stays NeoForge/FML — scheduling and ownership are exposed through native NeoForge API interfaces, not Bukkit or Paper APIs.

## Status

Currently at **stage 1** of the port (region data primitives and tick scheduler internals). The `ThreadedRegionizer` is a minimal facade; Folia's adjacency, merge radius, section split/merge, and empty-section retirement logic still need to be ported. The active bridge drains scheduled tasks on the main server thread rather than delegating to independent region workers.

## Requirements

- **Java 21**
- **Minecraft 1.21.1**
- **NeoForm** 20240808.144430

## Configuration

Region threading is gated behind experimental server config options, **both defaulting to off**:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `enableRegionThreadingBridge` | boolean | `false` | Enables the single-thread bridge that exposes the scheduling API and drains global/region queues on the existing server tick loop |
| `regionThreadingWorkerThreads` | int (0–512) | `0` | Number of native region worker threads; `0` means `max(1, availableProcessors - 1)` |

## API Surface

The public API lives in `net.neoforged.neoforge.server.threading`:

| Interface | Purpose |
|-----------|---------|
| `RegionThreading` | Entry point: global/region/entity schedulers, ownership assertions, `executeOrSchedule` helpers |
| `GlobalRegionScheduler` | Schedule work on the global region |
| `RegionScheduler` | Schedule work on the region owning a block position |
| `EntityRegionScheduler` | Schedule work on the region owning an entity |
| `RegionThreadingContext` | Describes current execution context (`NONE`, `GLOBAL_REGION`, `REGION`, `ASYNC`) |
| `ScheduledRegionTask` | Cancellable/completable handle for scheduled work |

Internal implementation is in `net.neoforged.neoforge.server.threading.region`.

## Diagnostics

```
/neoforge regionthreading status
```

Prints region count, running regions, sections, tracked entities, entity tasks, worker threads, global tick count, and last tick duration. Requires permission level 2.

## Port Roadmap

1. ~~Port Folia's region data primitives and tick scheduler internals without Bukkit/Paper API exposure~~ *(in progress)*
2. Wire regionized server/global tick loops into `MinecraftServer` and `ServerLevel`
3. Move entity and position scheduling behind the native NeoForge API skeleton
4. Convert NeoForge tick and gameplay event dispatch to run on the owning context
5. Add hard ownership checks for unsafe off-region access
6. Add optional mod metadata declaring support for regionized execution

## Building

```bash
./gradlew setup
./gradlew build
```

## License

LGPL v2.1 — see [LICENSE.txt](LICENSE.txt).