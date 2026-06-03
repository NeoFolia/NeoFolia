# NeoFolia — Minecraft 26.1.2

A NeoForge fork porting [Folia](https://github.com/PaperMC/Folia)'s regionized multithreaded server model natively into the NeoForge modding ecosystem.

## What It Does

Enables Minecraft servers running NeoForge mods to distribute world tick processing across multiple threads organized by region, instead of running all world logic on a single server thread. This can significantly improve server performance when many players are spread across a world.

**This is not a Paper/Bukkit plugin.** The runtime stays NeoForge/FML — scheduling and ownership are exposed through native NeoForge API interfaces, not Bukkit or Paper APIs.

## Requirements

- **Java 25**
- **Minecraft 26.1.2**

## Configuration

Region threading is gated behind experimental server config options, **both defaulting to off**:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `-Dneoforge.regionThreads=4` | JVM Flag | `4` | Number of native region worker threads; `4` means `max(1, availableProcessors - 1)` |
| `-Dneoforge.regionizedChunkTicks=true` | JVM Flag | `false` | Enabling chunk ticks by region. |
| `-Dneoforge.regionizedScheduledTicks=true` | JVM Flag | `false` | Number of native region worker threads; `0` means `max(1, availableProcessors - 1)` |
| `-Dneoforge.regionizedBlockEntityTicks=true` | JVM Flag | `false` | Enable block ticks by region. |
| `-Dneoforge.regionizedEntityTicks=true` | JVM Flag | `false` | Enabling entity ticks by region. |
| `-Dneoforge.regionTaskTimeoutSeconds=60` | JVM Flag | `false` | Error message server shutdown timeout. |

## Diagnostics

```
/tps
```

Prints region count, running regions, sections, tracked entities, entity tasks, worker threads, global tick count, and last tick duration.
```

## License

LGPL v2.1 — see [LICENSE.txt](LICENSE.txt).
