# Hosted API pass 2 tests

`run.sh` compiles production boundary/reconciliation classes against small ownership fixtures: 44 assertions. This is not a Minecraft GameTest suite.

`run-transform.sh` uses a custom test-only Mixin service with actual dependency class bytes. It applies Sponge Mixin + MixinExtras, including relevant upstream Sable/Flywheel mixins. It does not instantiate a Minecraft world or renderer. Prepare dependency paths with `./gradlew assemble writeHostedApi2Classpath -I tests/hosted-api2/write-classpath.gradle`.

Optional second argument: compiled PYM classes from the supplied upstream source, to also include its BlockGetter overwrite and the optional PYM target. No upstream PYM class is bundled in the mod.

See `docs/hosted-api2/ПРОВЕРКИ.md` for results, versions, limitations and the known separate portal-detector failure.
