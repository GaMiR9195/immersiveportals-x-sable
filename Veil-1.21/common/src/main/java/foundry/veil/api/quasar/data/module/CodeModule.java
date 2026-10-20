package foundry.veil.api.quasar.data.module;

/**
 * A particle module fully defined in code.
 */
public interface CodeModule extends ParticleModuleData {

    default ModuleType<?> getType() {
        throw new UnsupportedOperationException(this.getClass().getSimpleName() + " is not serializazble");
    }
}
