# Milestone 01 validation record

Last updated: 2026-09-17

## Target-envelope, casing, and client-pose correction — 2026-09-17

Weapon acquisition is no longer limited to a corridor around the HQ route. The
physical fixture places its Survival target more than 48 blocks off that route;
the turret selects it using the weapon's range and ballistic feasibility, CBC
reduces its health from 20 to 6, and the hull continues toward the HQ. An
engageable HQ remains the first weapon priority, followed by players, defense
markers, and infrastructure markers.

Encounter-authorized CBC shots now intercept the pinned `getSpentItem` call and
return an empty stack before CBC constructs an item entity. The fixture asserts
that every real shot applies this policy and that the completed world contains
zero `createbigcannons:empty_autocannon_cartridge` entities. Unrelated CBC fire
is outside the scoped hook. Server turret poses are sent to tracking clients on
change and every 20 ticks; the client applies the pose to the matching nested
contraption identified by vehicle, mount position, entity ID, and UUID.

Validation: `compileJava` and `verifyEncounter` pass with 113 checks. At 14:25:52,
`runGameTestServer` completed all four required physical tests in 37.72 seconds,
including the off-route target, real CBC player damage, HQ destruction, casing
absence, weighted defeat/splitting, cancellation, and cleanup. Because a headless
GameTest has no rendered clients, visible turret agreement on both dedicated
clients remains a manual acceptance item.

## Severed-turret cleanup correction — 2026-09-17

Manual encounter `a5655812-ce0e-4db1-90b7-d6c90580fed8` exposed a cleanup gap:
after the turret separated, the root fragment reported zero mounts every tick,
and the final effect was visible on only one fragment. Weapon separation now
suspends combat with a stable diagnostic instead of producing an exception loop.
Defeat applies its effect to every loaded owned fragment and cleanup performs
fixed-point passes that reclaim encounter-tagged or lineage-owned descendants
created during removal.

The 16:32:20 physical rerun passed all four required GameTests. Its damage case
created multiple owned fragments, asserted that more than one fragment received
the final effect, completed with `owned=[]`, and found no encounter-tagged Sable
sublevel afterward. The exact hand-severed turret/hull sequence remains open for
one visual in-world retest before closing this regression.

## Navigation and turret correction — 2026-09-15

The real-world retest exposed remaining coupling after the previous automated
pass. Active orbits now remain eligible after leaving the original firing
waypoint and advance through the same movement handler during player targeting.
Turret stabilization compensates hull rotation; heading improvement counts as
progress without granting oscillating turns an unlimited timeout.

Validation: `compileJava build runGameTestServer` passed. The standalone suite
contains 113 checks, including maximum-rate hull-turn stabilization, bounded
mount motion and turning-progress timeout regressions. All four physical
GameTests passed at 13:30:43 (see `run-gametest/logs/latest.log`). The orbit
displacement assertion now measures from orbit entry, excluding approach travel.
These results do not establish sustained rendered alignment on two clients;
the real-balloon visual retest remains open.

The 14:07:50 physical rerun also verified the lifecycle/pose correction. CBC
reduced an eligible Survival player from 20 health to 6 through its own damage
path, the observed projectile origin matched the authorized parent-world muzzle
within 0.25 blocks and its direction within three degrees, and a blocked-lane
assault fired beyond the former three-shot limit before proceeding to real HQ
destruction and cleanup. Tactical stalls no longer authorize despawning a live
attacker.

## Current validation status

The implementation, standalone lifecycle suite, data generation, physical
GameTests, build, and interactive development-client startup have passed. The
remaining acceptance work is limited to behavior that the automated fixture
cannot establish: process-restart recovery, observation by two network clients,
player/passenger handling, dynamic obstruction repair, isolation from unrelated
live objects, the operator-only route
overlay, and repeated-run performance/resource measurements.

## Automated commands

Run from the repository root with JDK 21:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home compileJava verifyEncounter
.\gradlew.bat --gradle-user-home .gradle-user-home runData
.\gradlew.bat --gradle-user-home .gradle-user-home runGameTestServer
.\gradlew.bat --gradle-user-home .gradle-user-home build
```

Observed result on Windows 11 with Eclipse Temurin 21.0.12.1:

- `verifyEncounter`: 113 checks passed, including low/high ballistic arcs,
  moving-target lead, unreachable-target rejection, and trajectory profile parsing.
- `runData`: passed.
- `runGameTestServer`: four required tests passed; Rapier initialized for all
  dimensions. The balloon sequence covered real flight/fire/HQ impact, physical
  weighted damage and split inheritance, final blast, three consecutive cleanup
  outcomes, duplicate rejection, ticket release, and real CBC damage against a
  Survival player more than 48 blocks off the unchanged HQ route. Every shot
  passed the spent-item policy and no reusable empty autocannon cartridge entity
  remained. The extended
  sequence also placed a bedrock wall across the initial HQ firing lane,
  fired continuously beyond three rounds while cycling orbit candidates, and
  completed only after the lane opened and a real projectile destroyed the HQ.
  The fixture verifies that a real shot is authorized only after both firing and
  rendered barrel vectors are within one degree of the low-arc CBC solution.
- `build`: passed and produced the addon JAR; its `check` phase reran all 113
  standalone encounter checks.
- `runClient`: reached a visible, responsive NeoForge 1.21.1 window with the
  complete dependency stack. A repeated config-correction loop was traced to two
  stale development clients watching the same `run/config` file with different
  config schemas. After terminating those stale clients and regenerating
  `createdefendtherealm-common.toml`, a single fresh client started with zero
  config-correction warnings. Gradle remaining at `90% EXECUTING :runClient`
  while the window is open is expected; the task ends when Minecraft exits.

The fixture starts at `(12, -28, 160)` and attacks the HQ at `(12, -48, 40)` in
the disposable near-origin GameTest world. The initial route is approximately
120 blocks; the cannon begins with 61 cartridges
before and 60 after CBC consumption, followed by one replenishment. The marker
was destroyed by the attributed projectile and the full 60-tick linger elapsed.

## Acceptance checklist

Update this checklist immediately after each test. Check an item only when its
expected result and supporting log or observation have both been recorded.

### Completed automated validation

- [x] Exact dependency versions and JDK are pinned and documented.
- [x] `compileJava` succeeds with Java 21.
- [x] `verifyEncounter` passes all 100 lifecycle and persistence checks.
- [x] `runData` completes successfully.
- [x] `build` completes and produces the addon JAR.
- [x] Development client reaches a visible, responsive game window.
- [x] Development-client config loads without a correction loop.
- [x] Physical GameTest performs autonomous flight and reaches firing range.
- [x] CBC fires a real projectile and consumes one cartridge.
- [x] Controller replenishes the consumed cartridge exactly once.
- [x] Attributed projectile destroys the development HQ.
- [x] Post-impact linger completes before vehicle cleanup.
- [x] Weighted damage remains active below 40% and triggers defeat at 40%.
- [x] Physical Sable splits inherit encounter ownership.
- [x] Cancellation and repeated cleanup are idempotent.
- [x] Duplicate active-encounter request is rejected.
- [x] Owned sublevels, projectiles, and chunk tickets are released.
- [x] Automated suite completes three consecutive cleanup outcomes.
- [x] The independent turret selects an eligible Survival player more than 48
  blocks off the HQ route using its own engagement envelope, applies
  14 points of real CBC damage, and resumes the cached HQ route. The test records
  `playerCbcDamageAccepted=true`, `playerHitDamaged=true`, and
  `playerHitDamageObserved=14.0`; no addon-side damage call exists.
- [x] Every encounter-authorized autocannon shot applies the scoped spent-item
  policy, and the physical fixture finishes with no reusable empty autocannon
  cartridge entity in the world.

### Completed manual integrated-client validation

- [x] The first hostile-player prototype test exposed two defects rather than a
  pass: selecting `DevTwo` replaced the HQ route, and visually direct AP rounds
  did not reduce the survival player's health. The controller now retains the
  HQ route and treats a player within 48 blocks along it as opportunity fire.
  The rejected temporary damage fallback has been removed. The pinned
  integration now repairs only CBC's existing collision-candidate list when
  Sable's parent-world entity index omits the selected player. CBC still performs
  the exact swept-hitbox test and remains the sole damage, knockback, and
  penetration authority. Player aim now uses center mass. The corrected visible
  behavior remains unchecked in the acceptance item below.
- [x] The pre-fix restart defect was reproduced and diagnosed. Encounter
  `9623615a-fd31-4fd5-9070-b90680b86192` reconnected the same Sable root after
  the integrated server restarted, but checked the nested CBC mount before the
  Toolgun runtime restored and incorrectly completed as `WEAPON_FAILURE`.
  Schema 4 now persists an explicit recovery phase and grants a configurable
  200-tick restoration window. The corrected behavior remains unchecked below
  until it is repeated in-world.

- [x] Happy-path balloon assault completed with `TARGET_DESTROYED`.
  On 2026-09-10 encounter `a6b560fd-36b1-44cb-b277-0f193402db7d` fired one
  shot, recorded one replenishment, and ended with `owned=[]`, `tickets=0`, and
  `projectiles=0` in `run/logs/latest.log`.
- [x] Removing the HQ without an attributed hit completed with `LOST_TARGET`.
  Encounter `ca8b33fc-6b3e-414f-a1ee-2bac3d711d81` fired no shots and ended
  with no owned vehicle, projectile, or ticket residue.
- [x] A second encounter could start after successful cleanup of the first.
- [x] Destroying the autocannon mount produced the safe `WEAPON_FAILURE` path.
  On 2026-09-10 encounter `8f4f2755-0f4d-452e-a8c1-7f9d940d3fa0` recorded the
  lost mount, attributed 10 of 121 integrity weight, inherited ownership of the
  physical split, and ended with `owned=[]`, `tickets=0`, and `projectiles=0`.
- [x] Crossing the physical damage threshold entered `DESTROYING` exactly once.
  Encounter `26746e8e-c83f-4b5b-9996-fbedd6a3d4ee` reached 49 of 121 lost
  integrity weight, tracked the root and three inherited splits, and reached the
  final three destruction ticks. A subsequent encounter started successfully,
  confirming that the active slot and owned sublevels had been released.
- [x] A player standing on the ship survived the final blast with only minimal
  observed damage. There was no player death, disconnect, or controller crash at
  the destruction time. The later `Dev fell from a high place` message occurred
  during a different encounter before its destruction and was not blast damage.
- [x] Passive mobs roaming beside the defeated ship remained alive and visibly
  unharmed after the final blast. Minecraft did not emit per-mob health values,
  so this result is supported by the developer's direct in-world observation,
  not a numerical log measurement.
- [x] Three consecutive integrated-client encounters reached clean terminal
  outcomes: `TARGET_DESTROYED`, `LOST_TARGET`, and `WEAPON_FAILURE`. This does
  not replace the pending dedicated-server resource-baseline test.
- [x] A later three-run integrated-client sequence also completed without
  accumulating encounter vehicles: `ca1d77c4-69cf-4722-9f31-a729384700e8`,
  `88aa01c9-8d9c-491e-8852-0470887039b3`, and
  `15e45020-8e38-4132-bc55-d36f70cd60cf`. Loaded vehicles returned from two to
  the same unrelated baseline vehicle `809f811e-75d4-47a0-be5d-ffde79e4e84a`
  after every run, while encounter tickets and projectiles returned to zero.
- [x] The unrelated assembled Sable balloon survived the manual defeat sequence
  and the later three-run sequence. Manual cancellation isolation remains to be
  exercised on the dedicated server.
- [x] Two clients observed the same integrated-server assault. On 2026-09-10,
  `Dev` and `DevTwo` received the spawn of encounter
  `467b470e-00be-4266-b481-7fbaee11c6c5` and vehicle
  `ca331e02-b81b-4629-9e63-ffa2961a39e0`. The server activated assisted physics
  at a 0.025-second timestep, and the developer directly observed both clients
  synchronize the successful flight, attack, HQ destruction, linger, and ship
  removal. `DevTwo` briefly disconnected and rejoined during approach, then
  remained connected through cleanup. The logs contain no controller crash or
  client-only class failure. This was multiplayer hosted by the primary
  client's integrated server, not the separately running dedicated server, so
  it does not close the dedicated-server acceptance item below.
- [x] Player dimension change did not detach or stall the encounter. Encounter
  `a44c6d57-d78b-4836-9364-95ecfdbf4b72`, rooted at vehicle
  `986dd580-e7e6-4f7a-9161-4e6ea0fa64f8`, started in the Overworld at
  15:04:46. At 15:04:53, `Dev` entered the Nether and received the
  `We Need to Go Deeper` advancement. Inspections issued from the other
  dimension showed the same Overworld encounter continuing through APPROACHING
  with its distance decreasing and chunk-ticket count dropping from 21 to 12.
  It completed at 15:06:27 with `TARGET_DESTROYED`, one consumed and replenished
  cartridge, `owned=[]`, `tickets=0`, and `projectiles=0`. This establishes the
  integrated-server dimension-change behavior; the corresponding
  dedicated-server observation remains part of that server's acceptance run.
- [x] Accepted the Sable client warning `Received a sub-level movement packet
  for a non-existent sub-level` as harmless late client packet delivery on the
  pinned Sable 2.0.5 stack. Inspection of the resolved compiled dependency shows
  that `ClientboundSableSnapshotDualPacket.handleClient` looks up each snapshot's
  sublevel, logs this message when the client has already removed it, skips that
  entry, and continues without mutating or recreating anything. During the
  two-client test both clients logged the warning at 14:59:38 immediately after
  the same encounter ship was removed, with no exception, disconnect, retained
  object, or subsequent controller failure. The related portal-time warning
  `tracking removal packet for unknown sub-level: 0, 0` is likewise a guarded
  no-op: Sable's handler logs and returns when the client dimension transition
  has already discarded that tracked sublevel. Because movement snapshots use
  Sable's UDP path while tracking removal uses its TCP path, cross-channel
  arrival after local removal is the consistent explanation. Recheck this
  diagnostic during dedicated-server testing, but it no longer blocks milestone
  acceptance unless it coincides with a visible stale object, crash, or leak.

## Validation still required for Milestone 01 acceptance

Use `run-server`, not the client `run` directory. Review and accept Mojang's EULA
in `run-server/eula.txt`, then start `runServer` and connect two matching clients.
Perform each case in a disposable world copy and save its `latest.log`.

- [ ] **Dedicated-server authority and two-client synchronization.** Start the
   bundled fixture with `/dtr prototype fixture <x> <y> <z>` in clear air about
   100 blocks from a development HQ. Both clients must observe the same UUID,
   movement, real shot, impact, linger, and cleanup. Confirm there is only one
   server-side controller and no client-only class failure.
- [ ] **Real process restart recovery during APPROACHING.** Restart the server,
   verify the same encounter/root UUID resumes, and confirm no duplicate ship or
   retained tickets after completion.
  The former immediate false `WEAPON_FAILURE` has been fixed; specifically
  verify that inspect shows `recoveryPending` followed by
  `recoveryCompletedTick` and that the same root resumes.
- [ ] **Real process restart recovery during ENGAGING.** Restart the server,
   verify monotonic shot/ammunition state, and confirm no duplicate firing or
   ship resurrection.
- [ ] **Real process restart recovery during DESTROYING.** Restart the server,
   verify the destruction timer resumes, and confirm cleanup completes exactly
   once. For every restart test, `/dtr prototype inspect` must show the same
   encounter/root UUID, monotonic damage/timer state, no second ship, and
   eventual ticket release.
- [ ] **HQ removal handling.** Remove the HQ without an attributed projectile.
   Under the revised assault contract, confirmed removal is HQ destruction and
   must enter the normal linger/cleanup path. An unloaded HQ chunk must instead
   retain the encounter with `HQ chunk temporarily unavailable`. The earlier
   `LOST_TARGET` result is historical and has been superseded; manually retest
   both cases on the current build.
- [ ] **Dynamic obstruction and route repair.** Obstruct the route after launch.
   The route repair counter must advance and the ship must reconnect to a safe
   cached anchor without teleporting. At firing range, block direct sight and
   verify the 16-position orbit search. If every lane stays blocked, the AP test
   balloon must keep firing at the blocker at its normal cadence while cycling
   safe orbit candidates; it must not despawn while both it and the HQ survive.
   Automated blocked-lane regression passed on 2026-09-15: tactical paths use
   clear-segment lookahead, retry a stalled candidate after 100 ticks, stop the
   complete orbit search after 600 ticks, and begin another search cycle instead
   of terminating. The physical fixture fired beyond the former three-shot cap,
   retained the assault, and completed by real HQ destruction after the test
   opened the breached lane. Keep this item open until a two-client manual run confirms the
   visible orbit and a dynamically added route obstruction.
- [x] **HQ priority and hostile opportunity fire.** The automated physical
   fixture places a Survival `ServerPlayer` more than 48 blocks off the HQ route. The turret
   selected that player independently, CBC reduced health from 20 to 6, and the
   ship resumed its original HQ route before destroying the marker. In manual
   play, continue to verify the same presentation for moving players. Creative
   and spectator filtering remains covered by the server selector.
   Inspect must record `lastPlayerHitTarget`, `lastPlayerHitProjectile`,
   `playerHitProcessedByCbc=true`, `playerHitDamaged=true`, and positive
   `playerHitDamageObserved` for a direct hit. If Sable's parent-world entity
   index omitted the target from CBC's normal candidate query, inspect must also
   record `playerCollisionCandidateRepaired=true`. There must be no addon-side
   damage call.
- [ ] **Operator-only path overlay.** With F3+H enabled, an operator sees the
   route, current node, selected target, orbit candidate, and blocker. A
   non-operator client receives no overlay; disabling F3+H hides it immediately.
- [x] **Weapon failure.** Destroying the autocannon mount stopped the controller
   with `WEAPON_FAILURE`; split ownership was inherited and cleanup released the
   vehicle, fragment, projectiles, and tickets.
- [ ] **Severed-turret defeat cleanup.** On the corrected build, detach the
   turret, then cross the 40% damage threshold. Every owned hull/turret fragment
   must show the defeat effect and disappear; `/dtr prototype vehicles` must
   show no encounter corpse and logs must not repeat the former “expects 1
   cannon mount, found 0” exception. Automated multi-fragment cleanup passes,
   but this exact manual topology must be observed once.
- [ ] **Passenger safety during cancellation.** Ride the vehicle and cancel it.
   The player must be
   ejected/preserved. Repeat
- [x] **Passenger safety during final blast.** A player standing on the vehicle
   was preserved and received only minimal, nonlethal damage.
- [ ] **Player logout.** Log out during an active encounter and verify a bounded
   terminal or recovery outcome without indefinitely retained tickets.
- [x] **Player dimension change.** `Dev` entered the Nether during encounter
   `a44c6d57-d78b-4836-9364-95ecfdbf4b72`. The controller remained tied to its
   explicit Overworld HQ, completed with `TARGET_DESTROYED`, and released all
   loading tickets, owned objects, and projectiles. This was verified on the
   integrated server; it should also be observed during the dedicated-server
   acceptance run.
- [x] **Passive-mob cleanup isolation.** Passive mobs beside the vehicle remained
   alive and visibly unharmed through defeat and cleanup.
- [ ] **Unrelated Sable-structure cancellation isolation.** The existing balloon
   survived defeat and repeated terminal cleanup. Keep it nearby during a manual
   cancellation and verify the same UUID remains afterward.
- [ ] **Three-run manual repeatability.** Run three consecutive complete
   encounters on the dedicated server. Record `/dtr prototype inspect`,
   `/dtr prototype vehicles`, server tick time, process memory, and
   sublevel/ticket counts before, during, and after the runs. Counts must return
   to baseline without growth.
- [ ] **Performance record.** Record hardware, idle/engagement/destruction server
   tick time, process memory, and any growing resource use.

The rejection, duplicate-request, real-projectile happy path, replenishment,
weighted damage threshold, physical splitting/lineage, cancellation, repeated
cleanup, final blast, and ticket-release paths already have automated coverage.
They should be observed during the dedicated-server runs but do not need separate
new test cases unless the manual behavior contradicts the automated evidence.

The milestone acceptance gate is closed only when these observations are added
to this file with hardware, expected/actual result, and log references. Automated
serialization checks are not a substitute for a real process restart or two
network clients.
