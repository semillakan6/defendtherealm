# Create: Defend the Realm

A NeoForge 1.21.1 addon prototype for a server-authoritative PvE campaign built
around Create Aeronautics, Sable, and Create Big Cannons.

The current target is the Milestone 01 feasibility gate: one developer-authored
airship must spawn, fly autonomously, fire a real cannon, survive save/reload,
and clean itself up safely. The HQ, raids, progression, blueprints, structures,
and MineColonies integration are later milestones.

The complete prototype implementation is present: bundled vehicle placement,
autonomous flight, real CBC firing, damage/fragment tracking, persistence,
bounded loading, defeat and cleanup. Automated physics tests pass; the manual
two-client and real restart acceptance matrix remains. See [implementation
status](docs/MILESTONE_01_IMPLEMENTATION_STATUS.md).

Run `./gradlew verifyEncounter` for the standalone lifecycle and persistence
regression suite; `build` also runs it automatically.

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
.\gradlew.bat runClientTwo
.\gradlew.bat runServer
```

On Linux or macOS:

```bash
./gradlew compileJava
./gradlew build
./gradlew runClient
./gradlew runClientTwo
./gradlew runServer
```

The first run downloads Gradle, the Java 21 toolchain when necessary, Minecraft
development artifacts, and the pinned mod stack. Run directories and local
Gradle caches are intentionally ignored by Git.

`runServer` creates `run-server/eula.txt` on its first launch. Review Mojang's EULA and
set `eula=true` yourself before launching it again.

Client, dedicated-server, GameTest, and data-generation state are isolated in
`run`, `run-server`, `run-gametest`, and `run-data` respectively. Run the physical
regression suite with `./gradlew runGameTestServer`.

Run `./gradlew runData` whenever a change adds blocks, items, recipes, tags,
models, loot tables, or other generated data. Always run `compileJava` before
considering a development task complete.

In `DTR Test World`, place a development HQ and run the bundled fixture with:

`/dtr prototype fixture 0 20 0`

## Pinned Milestone 01 stack

Development runs also include [Create Aeronautics: Toolgun](https://modrinth.com/mod/create-aeronautics-toolgun)
for saving and duplicating prototype vehicles. Its release is pinned in
`gradle.properties` and it is not required by the published addon. Restart the
development client after changing runtime dependencies.

- Minecraft 1.21.1
- NeoForge 21.1.249
- Create 6.0.10
- Sable 2.0.5
- Create Aeronautics 1.3.2
- Create Big Cannons 5.11.7
- Ritchie's Projectile Library 2.1.2

The pinned stack passes compilation, build, data generation, and the disposable
server physics suite. Physical restart recovery and two-client dedicated-server
behavior still require the recorded manual acceptance run.

## Project documents

- `MASTER_DESIGN.md` defines the full design and decision status.
- `MILESTONE_01_REQUIREMENTS.md` is the binding first implementation gate.
- `docs/MILESTONE_01_PLAN.md` records the development sequence and readiness
  status for the prototype.
