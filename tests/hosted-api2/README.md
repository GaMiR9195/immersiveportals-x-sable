# Hosted API boundary tests (0.6.0)

`run.sh`: 34 production callback/spatial/read-scope + 37 processing-pass/world-frame (`ProcessingPassTest`: owner-local passes, framed enumeration, rolling frames, listener units, native step outside passes) + 10 moving-network + 13 visualization-view + 11 position-specific height assertions against small fixtures. If `SABLE_SOURCES` points to the supplied Sable 2.0.6 source root, also compiles its **original** FragileBlockCallback and runs 7 assertions. These are NOT Minecraft GameTests.

`run-transform.sh`: actual Sponge Mixin + MixinExtras transformations on dependency bytecode, including upstream Sable/Flywheel and vanilla/Sodium render handlers, plus the sable-api-fix seams (inclusive entity getter, ship/actor tick frames, physics listener units, storage audience, SoundEngine frames, Flywheel scene membership, ClientLevel entity lookup). Adds ownership/provenance/idempotence audit (2752 assertions in this dependency set). Prepare with `./gradlew assemble jarJar writeHostedApi2Classpath -I tests/hosted-api2/write-classpath.gradle`.

`run-dead-injectors.sh`: applies the complete `ipl_sable.mixins.json` (it uses `defaultRequire: 0`) to every target and lists `ipl$` handlers that were merged but are invoked nowhere — injectors that silently never applied (an unused private helper is listed too). Sable's own mixins are only partially present in the harness, so handlers aimed at methods merged by Sable itself also appear.

An optional second argument adds compiled upstream PYM classes to the test classpath. There is no production PYM-specific hook in 0.6.0.

Additional regression runners: `tests/entity/run.sh` (identity/bounds/rollback), `tests/visual-liveness/run.sh` (ghost identity/state), `tests/render/run.sh` (including global-only BER gate), `tests/native/run-contact.sh /absolute/native/path` (actual native contact callbacks).

See root `060NOTES.MD` for changes, results, limitations and the known separate portal-detector fixture failure. No claim of an in-game shader/portal playtest.
