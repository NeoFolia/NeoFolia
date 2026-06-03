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
| `-Dneoforge.regionThreads=4` | Args | `0` | Number of native region worker threads. |
| `-Dneoforge.regionizedChunkTicks=true` | Args | `false` | Enabling chunk ticks by region. |
| `-Dneoforge.regionizedScheduledTicks=true` | Args | `false` | Enabling scheduled ticks by region. |
| `-Dneoforge.regionizedBlockEntityTicks=true` | Args | `false` | Enable block ticks by region. |
| `-Dneoforge.regionizedEntityTicks=true` | Args | `false` | Enabling entity ticks by region. |
| `-Dneoforge.regionTaskTimeoutSeconds=60` | Args | `false` | Error message server shutdown timeout. |

## Diagnostics

```
/tps
```

Prints region count, running regions, sections, tracked entities, entity tasks, worker threads, global tick count, and last tick duration.
```
