# Development HQ assault

The supplied balloon and its destruction/cancellation lifecycle pass the
disposable server physics suite. Manual restart and two-client acceptance remain.

## Running the demo
0. Run enviroment with `.\gradlew.bat --gradle-user-home .gradle-user-home runClient`.
1. In a disposable copy of DTR Test World, give yourself the marker:
   `/give @s createdefendtherealm:dev_hq`.
2. Place it outdoors with clear air around it. Its appearance uses red concrete.
   Optional priority targets are
   `createdefendtherealm:dev_defense_target` (orange) and
   `createdefendtherealm:dev_infrastructure_target` (yellow).
3. Start the bundled, versioned fixture with `/dtr prototype fixture <x> <y> <z>`,
   or the player's Toolgun copy with `/dtr prototype start "Test Ballon" <x> <y> <z>`.
   The three numbers are the spawn location; the target is detected automatically.
4. Use `/dtr prototype inspect` for route cursor, speed, aim error, shot requests,
   actual shots, replenishments, repair counters, and errors.
5. `/dtr prototype cancel` stops the encounter and invokes physical cleanup.
6. Operators can press F3+H to display the current route, active node, combat
   target, orbit candidate, and firing blocker. The server sends this data only
   to permission-level-2 players.

Use a spawn high enough that the entire balloon clears terrain. The prototype
requires one finite cartridge to seed replenishment; bottomless CBC mode is
still rejected. `prototypeShotInterval` in the common config defaults to 40 ticks.
CBC's own cooldown may make the actual interval longer.

## Implementation boundaries

- `VehicleRoutePlanner` and `RoutePlanners` separate navigation profile selection
  from physics. `hover_airship` uses vanilla PathFinder with a custom NodeEvaluator
  and an unspawned adapter mob. Clearance includes full height and horizontal
  rotation allowance. Air routes are bounded to loaded chunks and build bounds.
- Route points, integrity, destruction timing, target priority, tactical state,
  recovery grace, and tickets persist in encounter schema 5. Schemas 1 through
  4 still load;
  legacy coordinate-only encounters remain operator controlled. Cache entries
  are scoped to the assault and cleared at completion/server shutdown.
- Sable impulses occur in ForgeSablePrePhysicsTickEvent. No transform writes or
  teleport recovery are used. Progress stalls trigger local connectors to later
  safe route anchors, with three failed repairs before termination.
- The cached navigation route always terminates at the HQ. A survival/adventure
  player within 48 blocks of the vehicle and along that corridor can temporarily
  interrupt it for opportunity fire; the ship holds, fires up to three attempts,
  and resumes the same HQ route after a hit, departure, obstruction, or limit.
- A direct-shot obstruction starts a bounded search of 16 angles across three
  weapon-profile ranges. Tactical paths use clear-segment lookahead and reject
  an obstructed or stalled candidate instead of pursuing individual A* nodes
  indefinitely. If no safe line of sight exists, a template explicitly marked
  as breach-capable fires up to three real CBC rounds total for that target
  episode. Continued obstruction rejects the tactical target while the HQ
  remains the strategic objective.
- Server restart reconciliation holds the same root vehicle for up to 200 ticks
  while its nested Toolgun/CBC runtime restores. A missing mount during this
  window is not reported as `WEAPON_FAILURE`; expiry uses `RECOVERY_FAILED`.
- CBC setters aim the nested contraption. Sable does not reliably tick the restored
  mount bridge, so DTR advances the nested CBC runtime and invokes CBC's public
  `fireShot` operation on its own 40-tick gate. CBC still performs real breech,
  cartridge, projectile, failure, and recoil behavior.
  Three narrow mixins observe the firing call, actual consumed cartridge, and
  projectile tick. These are pinned-release internal hooks, not stable APIs.
  Projectile attribution is attached during the exact controlled firing call.
- Hostile-player aim uses center mass. If Sable's moving-sublevel tracking leaves
  that selected player out of CBC's parent-level spatial result, a pinned hook
  adds that one player to CBC's existing candidate list. CBC itself still decides
  the swept collision and exclusively applies its normal damage, knockback, and
  penetration behavior; DTR never simulates or directly applies the hit.
- If a finite magazine immediately refills CBC's breech, the exact consumed stack
  is stored in persisted controller reserve until CBC exposes an input slot. This
  preserves one-for-one effective ammunition across save/reload.
- Success observes real HQ removal during an attributed projectile tick; the
  controller never deletes the HQ to simulate damage. Cleanup kicks passengers
  out of the plot before removal. Split ownership is captured before Sable clears
  parent lineage, and unloaded UUIDs are not mistaken for removed objects.
- Three deduplicated 3x3 parent chunk windows and owned Sable tickets bound
  loading. Defeat stops combat, descends until ground or 400 ticks, runs a
  terrain-safe final effect, then uses the same cleanup path as cancellation.

## Remaining physical acceptance

The supplied balloon's 100-block flight, aiming, real shot, cartridge replacement,
HQ destruction, physical damage/fragmentation, defeat, cancellation, duplicate
rejection, and three consecutive cleanups pass in the disposable GameTest. Still
validate real save/restart during each phase, obstacle repair, a two-client
dedicated session, connected-player passenger handling, and tick/resource
measurements. No unconditional Milestone 01 acceptance pass is claimed yet.
