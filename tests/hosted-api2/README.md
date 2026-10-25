# Hosted API boundary tests (0.6.0)

`run.sh`: 34 production callback/spatial/read-scope + 10 moving-network + 8 visualization-view + 11 position-specific height assertions against small fixtures. If `SABLE_SOURCES` points to the supplied Sable 2.0.6 source root, also compiles its **original** FragileBlockCallback and runs 7 assertions. These are NOT Minecraft GameTests.

`run-transform.sh`: actual Sponge Mixin + MixinExtras transformations on dependency bytecode, including upstream Sable/Flywheel and vanilla/Sodium render handlers. Adds ownership/provenance/idempotence audit (2752 assertions in this dependency set). Prepare with `./gradlew assemble jarJar writeHostedApi2Classpath -I tests/hosted-api2/write-classpath.gradle`.

An optional second argument adds compiled upstream PYM classes to the test classpath. There is no production PYM-specific hook in 0.6.0.

Additional regression runners: `tests/entity/run.sh` (identity/bounds/rollback), `tests/visual-liveness/run.sh` (ghost identity/state), `tests/render/run.sh` (including global-only BER gate), `tests/native/run-contact.sh /absolute/native/path` (actual native contact callbacks).

See root `060NOTES.MD` for changes, results, limitations and the known separate portal-detector fixture failure. No claim of an in-game shader/portal playtest.
