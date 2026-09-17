# User balloon inspection

Source: `run/saves/DTR Test World`, read directly from saved Anvil region and
entity files. Inspection does not load, rewrite, or acquire the world's save lock.

## Confirmed content

The stationary build occupies x=-6..-2, y=-33..-23, z=18..22 for placed blocks.
It contains 116 blocks: 69 white envelope, 22 oak planks, 20 oak fences,
one adjustable burner, one throttle lever, one analog lever, one ordinary lever,
and one CBC cannon mount. Front faces north.

- Burner at (-4,-31,20): ScrollValue=135, SignalStrength=2.
- Throttle at (-4,-31,21): State=2.
- Analog lever at (-4,-33,21): State=6.
- Cannon mount at (-4,-33,20): Running=true, pitch=0, yaw=180.
- A separate CBC pitch contraption at (-3.5,-35,20.5) contains a steel
  autocannon breech, recoil spring and two barrel blocks. Its initial orientation
  is north; its magazine contains 61 AP autocannon cartridges and bottomless
  ammunition is disabled.
- Multiple `simulated:honey_glue` entities define attachment regions.

The cannon remains assembled even though the balloon is disassembled. The user's
original bounding box excluded the gun below y=-34. A complete capture must
include the cannon entity or materialize its block layout deliberately, plus the
glue regions. Capturing only placed blocks would silently omit the loaded weapon.
The broad read-only inspection used (-9,-37,15) through (2,-19,26).

The selected build has no propulsion or steering blocks. Its burner provides
the lift-system starting point, but it is not evidence of horizontal flight
capability. Assisted horizontal control needs its own explicit validation.

The world also contains a Toolgun file:
`enxv_aeronautics_server_structures/380df991-f603-344c-a090-369bad2a924a/Test Ballon.excraft`.
It is a complete v8 archive containing one sublevel, the runtime CBC pitch
contraption, its 61 finite AP cartridges, and 10 honey-glue regions. The assembled
Sable copy near (-1.732,-31.326,3.948) remains to be matched to its UUID using the
loaded-world diagnostic.

## Implemented from these findings

`MachinerySnapshot` preserves only explicit burner and lever settings. It strips
coordinates, identity, network state and timers by construction. Inactive cannon
mounts export their type only; active mounts are rejected rather than producing
an incomplete vehicle. Unknown block-entity types still fail closed.

`/dtr prototype vehicles` reports up to 32 loaded Sable vehicle descriptions in
the current dimension, including identity, position and available split parent.
It does not claim ownership or mutate any vehicle.

## Repeat inspection

PowerShell:

```powershell
.\gradlew.bat --gradle-user-home .gradle-user-home inspectVehicle --args="'run/saves/DTR Test World' -9 -37 15 2 -19 26"
```

Next: execute the guarded Toolgun-backed clone, visually verify the restored
cannon and glue, then test rollback, save/reload, and physical cleanup. No changes
to the user's original build are needed for the investigation.

## The remaining step needs an in-game observation:

1. Run `.\gradlew.bat runClient`.
2. Open `DTR Test World`.
3. Enter:

`/dtr prototype start "Test Ballon" 0 20 0 0 20 -100`
