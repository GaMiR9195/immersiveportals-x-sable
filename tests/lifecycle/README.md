# Lifecycle regressions

Run `bash tests/lifecycle/run.sh` with JDK 21 and fastutil/JOML in the Gradle cache
(or set `FASTUTIL_JAR` and `JOML_JAR`). Python 3 is used only to build the temporary harness.
No generated file is kept in the source tree.

`run.sh` runs the focused `LifecycleTest`. The lifecycle, shared context, path, material
snapshot, map, geometry and key classes are compiled from production.
`generate-harness.py` additionally extracts the actual tracker members, Atlas resource
lifecycle, mapping, Sable dependency-chain orchestration, and session batching/deferred-broadcast
methods from the current production files. Changing those methods changes the tested code; their
business logic is not copied into the fixture.

Minecraft/Sable platform types, nearby-portal discovery, raw physics readback, native handles,
session encoding/delivery and unrelated cache plumbing are controlled seams. The test injects a
known crossing at the observation seam; it does not simulate the full Minecraft sweep or load JNI.
Native fixtures can return an allocation failure or throw
during create/node initialization; the publication seam can fail after partial advertisement.
The real production rollback must release all newly opened resources and preserve old paths.
The full mod is also compiled separately against real Minecraft/NeoForge/Sable dependencies.

Assertions cover solo completion/withdrawal, material slivers, U and joint gaps, component-wide
frame consensus, empty connectors, sibling offsets, equivalent mappings, identity loops,
nested returns, native image-open failure rollback, frame-ID reuse, replaced bodies,
portal retarget/resize/disable, immediate promoted-frame geometry before the next sample,
cleared stale flip decisions, observed out-and-back angular arcs, and finite SAT compared
with independent polygon clipping.
This is not an in-game/render/network integration test or a substitute for native solver
regressions in `natives/rapier/src/joint_transit.rs` and the source/image contact tests.
