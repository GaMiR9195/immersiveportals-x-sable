# Portal regression tests

`run.sh`: dependency-free test of the production continuous SAT core, JDK 21.

`run-detector-fixtures.sh`: production PortalCrossingDetector compiled against small,
explicit Minecraft/Sable/portal API fixtures plus the real JOML 1.10.5 library.
The pose fixture reproduces companion's transform order; the mapping fixture only
implements translation. Rotated body tests do use real JOML quaternions. Arbitrary
rotating portal-map composition is NOT covered by this fixture suite.

Fixtures are deliberately outside src/main and src/test: they must never replace
real Minecraft/Sable classes in a mod artifact. These are NOT native/game integration
tests. See the Russian README-PORTAL-FIX.md for the mandatory game test matrix.

Additional dependency-free JDK 21 suites:
- `bash tests/render/run.sh`: production pass-cache isolation/invalidation and affine
  AABB / plane-section geometry, including a tilted long box and 1/4-block release.
- `bash tests/gl/run.sh`: production program uploader/registry against a recording
  OpenGL API fixture. Tests both cut slots, world/eye/vanilla input spaces, neutral
  reset, missing uniforms, relink invalidation and no registry-wide broadcasts.
  Does not create a GL context, execute GLSL or apply mixins.

Example from the project root (JDK 21 available in PATH):
```sh
bash tests/portal/run.sh
JOML_JAR=/path/to/joml-1.10.5.jar bash tests/portal/run-detector-fixtures.sh
bash tests/render/run.sh
bash tests/gl/run.sh
```
All runners use temporary output directories and remove them on exit.
