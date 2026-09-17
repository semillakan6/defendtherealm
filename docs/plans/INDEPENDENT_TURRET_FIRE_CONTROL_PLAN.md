# Independent turret and ballistic fire-control plan

Status: implemented on 2026-09-14; automated physical GameTests pass and the
real-blueprint/two-client visual retest remains pending.

## Objective

Separate vehicle navigation from weapon fire control. The test balloon follows
and faces its route while its CBC turret independently slews toward the selected
target. A shot is authorized only when the rendered barrel direction agrees
with a reachable, unobstructed physical trajectory.

## Implementation

- The hover-airship hull faces its current navigation waypoint, turns in place
  above a 20-degree error, and ramps back to its bounded 1.25-block/second speed.
- Independent-turret fire never replaces the strategic or orbit destination.
  Existing fixed-forward, broadside and indirect-artillery behaviors remain
  explicit future control contracts rather than airship navigation rules.
- Every hardpoint declares a trajectory type, projectile speed, gravity, drag,
  and maximum flight time. The test autocannon uses a gravity-compensated low
  arc; the same solver exposes high-arc solutions for future artillery.
- Fire control leads moving targets, commands the
  CBC mount and nested contraption together, and measures alignment from the
  post-command barrel vector visible to clients.
- Obstruction checks sample the solved arc in both the Sable vehicle and parent
  world. CBC remains solely responsible for ammunition, projectile simulation,
  collision, damage and penetration.

## Acceptance

- Pure checks cover low/high arcs, moving-target lead and unreachable targets.
- The physical fixture must show a moving/orbiting hull with an independently
  aligned barrel, a real CBC shot and attributed impact.
- A blocked firing lane must not cancel an accepted orbit route or cause the
  vehicle to hold solely because its weapon is aiming or firing.
- Run `compileJava`, `verifyEncounter`, `runData`, `runGameTestServer`, and
  `build`; then repeat the real-blueprint and two-client visual checks.

No synthetic damage or hit confirmation is permitted.

## Follow-up correction (2026-09-15)

The real-world retest exposed remaining coupling. Active orbits were disabled
as soon as the hull left the original firing waypoint, and player selection
returned that original waypoint instead of advancing the orbit. Both paths now
advance the same maneuver independently of the weapon target. Hull heading
improvement counts toward progress; oscillation does not extend the timeout.
The turret compensates changes in hull orientation using its previous world
barrel direction, then applies bounded target tracking (with a total mount step
limit of three degrees per tick). This permits stabilization during the hull's
maximum commanded turn rate. Visual confirmation on both clients remains open.

## Lifecycle and pose correction (2026-09-15)

The later blocked-HQ retest exposed two separate faults. A completed orbit-search
deadline rejected the HQ and drove normal cleanup after only three breach shots,
and fire control compared CBC's embedded plot coordinates directly with a
parent-world target. The implementation now:

- treats orbit deadlines and failed candidates as retry/replan events;
- continuously reloads and breaches while the HQ and attacker survive;
- permits cleanup only for HQ destruction, attacker defeat/removal, or an
  administrative cancellation;
- reconstructs CBC's barrel-end/projectile origin in the embedded level and
  transforms that pose exactly once through Sable into the parent world;
- synchronizes changed mount controls to clients and requires both firing and
  rendered barrel poses to align before authorizing a real shot; and
- records the observed CBC projectile origin and direction against the
  authorization pose.

The disposable physical fixture passes with real player damage, exact projectile
pose checks, continuous breaching beyond three shots, HQ destruction, and normal
cleanup. A two-client visual retest of the user-authored balloon remains required.

## Target-envelope and presentation follow-up (2026-09-17)

The corridor heuristic is removed. Each hardpoint now ranks only targets that
its own range, trajectory and line-of-fire checks deem feasible, using the order
HQ, survival/adventure player, defense marker, then infrastructure marker. If the
HQ is not yet engageable, an off-route player or marker can be attacked without
changing the vehicle's strategic route.

The server broadcasts bounded turret-pose updates to players tracking the parent
Sable chunk. Clients resolve the exact nested contraption and apply the
authoritative yaw/pitch on change plus a 20-tick heartbeat. This keeps visual
mount motion separate from hull navigation and from projectile authority.

Encounter-controlled CBC autocannon calls suppress a non-empty spent cartridge
at CBC's `getSpentItem` boundary, before an item entity is constructed. The
physical fixture verifies the hook runs for every real shot and that no reusable
empty cartridge remains. All four GameTests pass; two-client rendered alignment
remains the manual visual gate.
