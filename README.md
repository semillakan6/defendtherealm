# Create: Defend the Realm

A NeoForge 1.21.1 addon prototype for a server-authoritative PvE campaign built
around Create Aeronautics, Sable, and Create Big Cannons.

The current target is the Milestone 01 feasibility gate: one developer-authored
airship must spawn, fly autonomously, fire a real cannon, survive save/reload,
and clean itself up safely. The HQ, raids, progression, blueprints, structures,
and MineColonies integration are later milestones.

## Prerequisites

- A 64-bit JDK 21. Gradle toolchain auto-download is enabled as a fallback.
- Git and an internet connection for the first dependency resolution.
- At least 4 GiB available for the Gradle JVM; client and server runs need
  additional system memory.

Do not add mod JARs to `libs/`. Required mods are declared through Gradle and
pinned in `gradle.properties` by exact Modrinth version ID.

## Build and run

On Windows PowerShell:

```powershell
.\gradlew.bat compileJava
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
```

On Linux or macOS:

```bash
./gradlew compileJava
./gradlew build
./gradlew runClient
./gradlew runServer
```

The first run downloads Gradle, the Java 21 toolchain when necessary, Minecraft
development artifacts, and the pinned mod stack. Run directories and local
Gradle caches are intentionally ignored by Git.

`runServer` creates `run/eula.txt` on its first launch. Review Mojang's EULA and
set `eula=true` yourself before launching it again.

Run `./gradlew runData` whenever a change adds blocks, items, recipes, tags,
models, loot tables, or other generated data. Always run `compileJava` before
considering a development task complete.

## Pinned Milestone 01 stack

- Minecraft 1.21.1
- NeoForge 21.1.249
- Create 6.0.10
- Sable 2.0.5
- Create Aeronautics 1.3.2
- Create Big Cannons 5.11.7
- Ritchie's Projectile Library 2.1.2

This is the initial compatibility candidate, not a claim that Milestone 01 has
passed. Startup, physics, cannon firing, destruction, persistence, cleanup, and
two-client dedicated-server behavior still require direct validation.

## Project documents

- `MASTER_DESIGN.md` defines the full design and decision status.
- `MILESTONE_01_REQUIREMENTS.md` is the binding first implementation gate.
- `docs/MILESTONE_01_PLAN.md` records the development sequence and readiness
  status for the prototype.
