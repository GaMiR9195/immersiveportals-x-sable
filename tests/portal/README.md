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
