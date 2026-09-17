# Milestone 01 implementation status

Status: implementation complete; automated server/physics verification passes.
The milestone acceptance record remains conditional on the manual two-client
dedicated-server and physical save/restart matrix in
[MILESTONE_01_VALIDATION.md](MILESTONE_01_VALIDATION.md).

## Completed implementation

The 2026-09-15 follow-up fixes active-orbit eligibility after leaving the initial
firing waypoint, preserves that orbit across player target changes, and stabilizes
turret aim against hull rotation. CBC's embedded barrel pose is transformed into
the parent world exactly once and synchronized through the mount, so authorization,
rendered aim, and projectile creation use the same direction. Improving heading
counts as navigation progress; oscillation does not reset the stall timer.

The 2026-09-17 follow-up removes the retired route-corridor target filter. Each
weapon now evaluates the HQ, survival/adventure players, defense markers, and
infrastructure markers against its own range, ballistic solution, traverse, and
line of fire. An engageable HQ retains first priority; otherwise the best feasible
tactical target can be attacked while the hull continues its cached HQ route.
Server-authoritative yaw/pitch poses are also sent to clients tracking the Sable
vehicle on change and by heartbeat, so the nested CBC turret renders the pose used
for authorization. CBC spent-cartridge results are replaced with an empty stack
inside only the authorized encounter fire call, before an item entity can exist.

A later manual damage test severed the turret before defeat. The log showed the
remaining hull repeatedly failing strict mount binding while only one fragment
received the final effect. Cleanup now treats a missing/separated weapon as a
diagnosed suspended attacker instead of throwing each tick. At defeat it emits
the effect for every loaded owned fragment, then repeatedly reconciles encounter
tags and Sable split lineage while removing fragments until no further owned
descendant appears. The physical split GameTest verifies multiple fragment
effects, an empty ownership set, and no tagged Sable residue; the exact manual
turret-severing sequence still needs one in-world confirmation.

- The exact user-authored `Test Ballon.excraft` v8 archive is bundled as a
  versioned fixture. It contains one Sable sublevel, ten honey-glue regions, one
  restored CBC pitch contraption and 61 finite AP autocannon cartridges.
- `/dtr prototype fixture <spawn>` starts the bundled vehicle. The ordinary
  `/dtr prototype start "<blueprint>" <spawn>` path still reads the invoking
  player's Toolgun repository. Both select the nearest loaded development HQ
  within 512 blocks and face the ship toward it before control begins.
- Placement validates the complete rotated volume, build height, loaded chunks,
  one supported CBC mount, finite ammunition, and a vehicle-sized clear route
  before changing the world. A temporary Sable observer owns every allocation
  made by the placement transaction so partial failure can use normal cleanup.
- The server-only hover-airship controller caches a vanilla A* route, follows it
  at a bounded 1.25 blocks/second, repairs only a blocked local segment, and
  never teleports the vehicle. The hull now faces its route waypoint and turns
  in place for large errors instead of being forced to face the HQ while trying
  to fly laterally. Physics writes occur in Sable's pre-physics event.
- The HQ always owns the cached strategic route. Each weapon independently
  selects the highest-priority feasible target in its configured envelope. The
  current order is engageable HQ, eligible survival/adventure player, defense
  marker, then infrastructure marker, with the HQ retained as the strategic
  fallback. Tactical fire does not replace or pause the hull's HQ route.
  A blocked HQ firing lane triggers a 16-position orbit search. AP-capable
  prototypes continuously fire at the obstruction at their normal cadence while
  cycling viable orbit positions. There is no lifetime breach-shot cap and a
  tactical search deadline starts another cycle instead of ending the assault.
- The restored CBC autocannon is aimed and fired through the pinned compiled
  API. Its independent turret uses a data-driven, gravity-compensated low-arc
  solution, checks the sampled trajectory, and fires only after the visible
  post-update barrel vector is aligned. The solver also exposes a high-arc mode
  for future artillery without enabling that vehicle behavior in this milestone.
  A narrow pinned-version hook attributes the actual projectile and returns
  exactly one consumed cartridge. Success requires that projectile to remove the
  HQ; the ship then remains for a configurable 60-tick impact linger. The
  controller records same-shot projectile spawn and direction errors against
  CBC's authoritative parent-world pose. Authorized encounter shots suppress
  CBC's reusable spent cartridge before entity construction; unrelated weapons
  retain their normal behavior.
- Attributed CBC projectiles retain CBC's own entity-damage profile. A narrow
  pinned-version collision-query hook adds the already-selected server player
  only when Sable's parent-world entity index omitted that player from CBC's
  existing bounded query. CBC still performs the exact swept-hitbox test and is
  the sole authority for damage, knockback, invulnerability, and penetration;
  the addon never calls player damage. Player aim uses center mass rather than
  the eye point.
- Structural damage is observed from Sable's authoritative old/new block-state
  callback. Blueprint positions remain stable through plot translation and
  splitting. Structural blocks weigh 1, functional blocks weigh 3, and 40%
  weighted loss enters defeat exactly once.
- A pinned Sable split hook records and tags child ownership before lineage is
  cleared. Removal affects only encounter UUIDs, ejects passengers, removes
  owned projectiles, and is idempotent. Unloaded UUIDs are never assumed removed.
- Defeated ships stop attacking and descend at a bounded speed until grounded or
  a configurable 400-tick timeout. The final effect has no terrain damage,
  deletes only tagged encounter entities, preserves players, and applies at most
  nonlethal damage to unrelated living entities before cleanup.
- At most three deduplicated 3x3 parent-world chunk windows plus an encounter
  Sable ticket remain loaded. Tickets are persisted, reconciled, and released on
  success, defeat, cancellation, or failure.
- Vehicle combat is data-driven by template. A vehicle profile declares a
  distinct family, navigation profile, weapon hardpoints, behavior, range band,
  preferred orbit band, safe firing speed, and breaching capability. The current
  release intentionally implements only the `HOVER_AIRSHIP` planner and
  `INDEPENDENT_TURRET` behavior; surface ships, land vehicles, fixed-wing craft,
  forward guns, broadsides, and artillery fail closed until their own planners
  and attack logic exist. This prevents airship assumptions from silently
  controlling future vehicle types.
- Schema 5 SavedData persists encounter identity, ownership, strategic/combat
  targets, tactical phase, orbit/breach state, route,
  weapon accounting, damage, split offsets, projectile identity, destruction
  timer, recovery grace, and ticket ledger. Schemas 1 through 4 remain readable. Load reconciliation
  reconnects tagged Sable objects instead of spawning replacements.
- Load recovery distinguishes a temporarily restoring nested CBC runtime from a
  destroyed weapon. After Sable reconnects the root UUID, the controller holds
  the ship and allows Toolgun machinery restoration. An expired recovery window
  or missing weapon suspends combat with diagnostics; it does not remove a live
  attacker.
- Operators with permission level 2 receive a bounded route-debug payload. With
  F3+H enabled, the client renders the route, active node, target, orbit
  candidate, and blocker; non-operators receive no payload.
- `/dtr prototype inspect`, `vehicles`, and `cancel` provide operator diagnostics
  and shared cleanup. Invalid placement, unavailable target data, obstruction,
  timeout, weapon failure, recovery failure, defeat, success, and cancellation
  remain distinguishable diagnostics. Active cleanup begins only for HQ
  destruction, attacker defeat/removal, or administrative cancellation.

## Verification evidence — 2026-09-17

- `compileJava` passed on Eclipse Temurin 21.0.12.1.
- `verifyEncounter` passed 113 JVM checks covering lifecycle invariants, exact
  40% damage behavior, schema 1–4 reads, context round trips, combat-profile
  validation, independent hardpoint state, range/orbit policy, route/cooldown,
  defeat timer, split-coordinate translation, ticket ledger, and terminal-state
  retention.
- `runData` passed after the development HQ block/item data changes.
- `runGameTestServer` initialized Sable Rapier in all dimensions and passed all
  four required GameTests. The physical fixture flew about 100 blocks, fired
  real AP rounds, selected a Survival player more than 48 blocks off the HQ
  route, observed CBC reduce
  that player's health from 20 to 6, resumed the unchanged strategic route,
  destroyed the HQ, lingered, and cleaned up. A second fixture received physical
  block removals, produced an
  inherited split, crossed 40% loss, ran defeat/final-blast cleanup, and released
  its tickets. A blocked-lane fixture fired more than the former three-shot cap,
  continued its assault, pierced the opened lane, destroyed the HQ, and cleaned
  up. A third fixture was cancelled and cleaned up; an attempted concurrent
  fixture was rejected. Projectile origin matched the recorded CBC muzzle within
  0.25 blocks and direction within three degrees. Every authorized shot passed
  through the spent-item policy and the finished world contained zero reusable
  empty autocannon-cartridge entities.
- Client, server, GameTest, and data runs now use separate working directories.
  GameTest uses a deterministic near-origin grid because Sable's observed pose
  loses sub-block precision at vanilla GameTest's multi-million-block origins.
- The user separately confirmed the visible single-player happy path: spawn
  facing the HQ, approach, aim, real impact, 60-tick wait, and despawn.

The recovery grace, target priority, continuous orbit/breach loop,
and operator overlay compile and pass the standalone suite. Their visible
restart/obstruction behavior still requires the manual retest recorded in the
validation checklist.

Network-denied Mojang authentication/version-check warnings during the disposable
GameTest do not affect its offline server result. No required GameTest failed.

## Remaining acceptance work

The code paths are present, but these requirements cannot be honestly marked as
observed by an automated single-process GameTest:

- restart the server during approach, engagement, and defeat, then verify the
  same physical UUIDs resume without duplication;
- connect two real clients to `runServer` and confirm synchronized movement,
  firing, terminal outcome, player ejection, and nonlethal final-blast handling;
- exercise logout/dimension departure and a real player passenger;
- record tick time and process/resource observations on the test hardware.

Until that matrix is recorded, this repository is implementation-complete but
Milestone 01 is not an unconditional acceptance pass.
