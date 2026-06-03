# NeoFolia — Minecraft 26.1.2

An unofficial NeoForge fork that brings Folia's regionalized, multi-threaded server model to the NeoForge modding ecosystem. fork porting [Folia](https://github.com/PaperMC/Folia)'s regionized multithreaded server model natively into the NeoForge modding ecosystem.

## What It Does

Enables Minecraft servers running NeoForge mods to distribute world tick processing across multiple threads organized by region, instead of running all world logic on a single server thread. This can significantly improve server performance when many players are spread across a world.

**This is not a Paper/Bukkit plugin.** The runtime stays NeoForge/FML — scheduling and ownership are exposed through native NeoForge API interfaces, not Bukkit or Paper APIs.

## Requirements

- **Java 25**
- **Minecraft 26.1.2**

## Configuration

Java startup arguments are no longer required, configuration and settings are now located in **config/neofolia.toml**


| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `region_threads=0` | Args | `0` | Number of native region worker threads. |
| `regionized_chunk_ticks` | Args | `true` | Enabling chunk ticks by region. |
| `regionized_scheduled_ticks` | Args | `true` | Enabling scheduled ticks by region. |
| `regionized_block_entity_ticks` | Args | `true` | Enable block ticks by region. |
| `regionized_entity_ticks` | Args | `true` | Enabling entity ticks by region. |
| `region_task_timeout_seconds` | Args | `60` | Error message server shutdown timeout. |

## Diagnostics

```
/tps
```

Prints region count, running regions, sections, tracked entities, entity tasks, worker threads, global tick count, and last tick duration.
