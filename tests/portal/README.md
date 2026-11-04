# Portal regression tests

`run.sh`: dependency-free JDK 21 tests of the production continuous SAT core
(`PortalSweepMath`), the session through-prism (`PortalPrismTest`: containment, slanted
passage, ray intervals, native region layout and render faces agree) and the entity
collision region (`CollisionRegionTest`: keep/exclude pieces of the native and node frames).

`run-detector-fixtures.sh`: production PortalCrossingDetector and PortalOrder compiled
against small, explicit Minecraft/Sable/portal API fixtures plus the real JOML 1.10.5
library. The pose fixture reproduces companion's transform order; the mapping fixture only
implements translation; the projection-tree fixture models ROOT sessions only (production
prisms, first root wins contested space), enough for the flip rule (no native material,
the branch holding most blocks takes the body) and piece retention. Rotated body tests do
use real JOML quaternions. Chains and arbitrary rotating portal-map composition are NOT
covered by this fixture suite.

Fixtures are deliberately outside src/main and src/test: they must never replace
real Minecraft/Sable classes in a mod artifact. These are NOT native/game integration
tests.

Additional dependency-free JDK 21 suites:
- `bash tests/render/run.sh`: production pass-cache isolation/invalidation, affine
  AABB / plane-section geometry (including a tilted long box and 1/4-block release) and
  the convex clip cells (`ClipCellsTest`: exact emptiness, a box minus prisms as disjoint
  cells, redundant planes dropped).
- `bash tests/gl/run.sh`: production program uploader/registry against a recording
  OpenGL API fixture. Tests all six sub-level cut slots, IP's slot 0 (uniform sync,
  stale slot disabled outside a portal pass, scoped Sable chunk equation),
  world/eye/vanilla input spaces, neutral reset, missing uniforms, relink invalidation
  and no registry-wide broadcasts.
  Does not create a GL context, execute GLSL or apply mixins.

Example from the project root (JDK 21 available in PATH):
```sh
bash tests/portal/run.sh
JOML_JAR=/path/to/joml-1.10.5.jar bash tests/portal/run-detector-fixtures.sh
bash tests/render/run.sh
bash tests/gl/run.sh
```
All runners use temporary output directories and remove them on exit.
