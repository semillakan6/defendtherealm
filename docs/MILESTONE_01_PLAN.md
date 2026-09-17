# Milestone 01 development plan

## Implementation result (2026-09-14)

The approved implementation sequence is complete in code. The exact balloon is
bundled, and the isolated disposable GameTest now proves autonomous flight, real
CBC impact/replenishment, physical weighted damage, split ownership, bounded
defeat, three consecutive cleanup outcomes, duplicate rejection, and ticket
release. `verifyEncounter` passes 113 checks and all four required GameTests pass.

The approved recovery-and-obstruction follow-up is also implemented. Active
encounters now wait through a bounded nested-machinery restoration phase after
load, circle through alternate firing positions, continuously breach an obstruction
at the configured firing cadence, and acquire range-profiled player opportunities
without replacing the HQ route. Tactical stalls and weapon faults no longer end
an active raid while its attacker and HQ survive. The physical fixture proves
that CBC applies real Survival-player damage before the balloon resumes and
destroys the HQ.
Permission-level-2 players can inspect this state in-world with
F3+H. Manual restart, blocked-lane, moving-player presentation, and overlay
observations remain in the validation matrix.

The navigation/fire-control separation and trajectory-aware turret follow-up is
specified in [the independent turret plan](plans/INDEPENDENT_TURRET_FIRE_CONTROL_PLAN.md).
The hull now follows its route heading while an independent turret visibly
tracks a gravity-compensated firing solution; high-arc artillery is represented
by a separate trajectory profile rather than generalized airship behavior.

The milestone's remaining gate is observational rather than an unimplemented
feature: real process restarts in each active phase, a two-client dedicated
server session, passenger/departure cases, and tick/resource measurements. Use
[the validation matrix](MILESTONE_01_VALIDATION.md); do not promote Milestone 01
to an unconditional pass until those results are recorded.

## Approved next slice: HQ assault

Implement a development HQ target, nearest-loaded-HQ selection, vehicle-sized
Minecraft A* routing, assault route caching, local reconnection/repair, bounded
Sable impulses, CBC mount aiming, and controlled real shots with one-for-one
cartridge replenishment. Hover-airship routing is the initial planner profile;
ground and fixed-wing planners remain future implementations. The demo succeeds
when the encounter's projectile destroys its HQ marker. See
[HQ assault handoff](HQ_ASSAULT_PROTOTYPE.md) for operation and validation limits.

Historical status: build environment, encounter foundation, and guarded Toolgun-backed
prototype spawn implemented; this statement predates the 2026-09-10 physical suite. See
[implementation status](MILESTONE_01_IMPLEMENTATION_STATUS.md)
for the current command behavior, validation evidence and remaining work.

## Baseline validation (2026-09-07)

- `compileJava` passed with Gradle 9.2.1 and an auto-provisioned Eclipse Temurin
  21.0.12.1 toolchain after resolving the complete pinned stack.
- `build` passed and produced the addon JAR.
- `runGameTestServer` discovered and loaded the addon plus Create, Sable,
  Aeronautics (including its bundled Simulated and Offroad mods), CBC, RPL, and
  embedded libraries on the server distribution. It then reached the expected
  `No test functions were given` exit because this empty starter project has no
  GameTests yet.
- A normal dedicated server ready state is not yet proven; `runServer` still
  requires the developer to review and accept Mojang's EULA.
- Client startup is not yet proven in this record.

Subsequent evidence: the development client loaded the stack (including Toolgun)
and the user created and saved `DTR Test World` with assembled/disassembled vehicle
copies. Offline block/entity inspection is recorded in [balloon findings](BALLOON_01_INSPECTION.md).
Dedicated-server multiplayer and automated physics lifecycle checks remain open.

On the validation machine, an inherited `GRADLE_USER_HOME=C:\.gradle` was not
writable. Validation therefore used `--gradle-user-home .gradle-user-home`.
Developers with the same machine-level setting can use that option or correct
their own user-level Gradle configuration; the local cache path is ignored.

## Scope interpretation

Milestone 01 is an integration feasibility gate, not the first slice of the full
campaign. Work on the HQ, scheduling, progression, crew AI, radar, structures,
rewards, MineColonies, and multiple ships remains deferred until the single-ship
lifecycle is proven.

The hard part is lifecycle correctness across three systems: Minecraft/NeoForge
server state, Sable sub-level physics, and Create/CBC machinery. Visual polish
should not precede proof that spawn, control, firing, save/reload, fragmentation,
and repeated cleanup are safe.

## Prepared baseline

- Java compilation targets JDK 21 through Gradle toolchains.
- The Gradle wrapper and all major mods are pinned to exact versions.
- Client, dedicated server, GameTest server, and data-generation run
  configurations already exist.
- Generated data, run directories, build output, IDE state, and the local Gradle
  cache are excluded from version control.
- CI builds on Linux with Temurin 21 using the checked-in wrapper.

## Implementation sequence

1. Prove dependency startup in a development client and dedicated server. Save
   the exact logs and reject this stack if either side fails.
2. Build a narrow integration layer for Sable assembly, transform reads,
   force/control writes, splitting lineage, and removal. Record whether each
   call is public API, accessor, mixin, or another internal hook.
3. Add a server-only encounter state machine and developer start, inspect, and
   cancel commands. Keep the one-active-encounter invariant in one authority.
4. Implement transactional template placement and assembly. Validate the full
   bounding volume first and make rollback safe to repeat.
5. Implement slow assisted flight against a fixed target. Keep physics writes
   on the thread and phase required by Sable; do not teleport the ship.
6. Integrate one preloaded CBC weapon. Validate mount limits, self-obstruction,
   firing cadence, real ammunition, and a safe exhausted/destroyed state.
7. Add deterministic weighted integrity and a one-way defeat transition.
8. Add lineage-aware cleanup, passenger preservation, and release of any chunk
   tickets or transient encounter resources. Make cancellation use the same path.
9. Persist encounter identity and recovery state with server-side SavedData.
   Reconcile references after load before resuming control.
10. Execute every scenario in `MILESTONE_01_REQUIREMENTS.md`, including three
    consecutive encounters and a two-client dedicated-server run, then record
    tick-time and leak observations.

## Initial package boundaries

- `encounter`: aggregate, state machine, commands, diagnostics, and termination
  reasons.
- `integration.sable`: assembly, transforms, forces, lineage, and removal only.
- `integration.cbc`: weapon discovery, aim constraints, firing, and ammunition
  state only.
- `template`: versioned ship definition, validation, placement, and rollback.
- `persistence`: SavedData serialization, migrations, and load reconciliation.
- `navigation`: target geometry, obstruction checks, and bounded flight control.

Common code must not reference client classes. Client rendering or presentation
belongs under a client-only package and registration path.

## Known risks to test first

- Sable is native-code-backed and its failure modes can be process-level rather
  than ordinary Java exceptions.
- Splitting and removal are central to cleanup, so a happy-path flight demo does
  not establish viability.
- CBC compatibility notes have included Sable impact and attachment/splitting
  fixes; real firing from the selected moving structure is mandatory evidence.
- Modrinth Maven does not supply transitive dependency metadata. The five
  required top-level artifacts are therefore declared explicitly; embedded
  libraries remain supplied by their owning mod JARs.
- Development runs must use Java 21 even if another JDK launches Gradle.

## Definition of environment-ready

The repository is ready for implementation when `compileJava` and `build` pass
from a clean checkout and the pinned stack reaches both a client title screen and
a dedicated-server ready state. At this point only compilation has been proven;
runtime startup remains an explicit next validation step.
