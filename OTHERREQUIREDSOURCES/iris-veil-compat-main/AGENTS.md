# AGENTS.md - Development Guide for iris-veil-compat

## Project Overview

iris-veil-compat is a standalone Minecraft NeoForge mod that bridges Veil shader rendering through Iris shaderpacks. It intercepts Veil shader compilation and replaces it with Iris-compatible shaders for gbuffer-compatible output.

## Technology Stack

- **Language**: Java 21
- **Build System**: Gradle with NeoForge ModDev plugin
- **Mappings**: Parchment (Yarn-based)
- **Platforms**: NeoForge
- **Dependencies**: Iris, Veil, Sodium, Create, Create Aeronautics, Sable

## Build Commands

**Important: Always use `--no-daemon` flag when running Gradle commands to avoid daemon-related issues.**

### Standard Build
```bash
./gradlew build --no-daemon
```
Builds the mod JAR in `build/libs/`

### Run Development Client
```bash
./gradlew runClient --no-daemon
```
Launches Minecraft with the mod and all localRuntime dependencies (Iris, Sodium, Veil, Create, Aeronautics, Sable)

### Run Dedicated Server
```bash
./gradlew runServer --no-daemon
```

### Run with Game Tests
```bash
./gradlew runGameTestServer --no-daemon
```
Runs NeoForge GameTests (configured for `top.leonx.irisveil` namespace)

### Generate Assets
```bash
./gradlew runData --no-daemon
```
Runs data generation (required after modifying recipes/loot tables/tags)

### Clean Build
```bash
./gradlew clean --no-daemon
```

### IDE Sync
```bash
./gradlew genIdeGradleEntries --no-daemon
```
Generates IDE configuration for IntelliJ/Eclipse

## Project Structure

```
src/main/java/top/leonx/irisveil/     # Main source code
  - IrisVeilCompat.java               # Main mod entry point
  - accessors/                        # Mixin accessor interfaces
    - IrisRenderingPipelineAccessor.java
    - ProgramSourceAccessor.java
    - ProgramDirectivesAccessor.java
  - compat/veil/                      # Veil compatibility
    - GlslTransformerVeilPatcher.java
    - IrisVeilProgramLinker.java
    - IrisVeilShaderCache.java
  - compat/veil/mixin/                # Veil mixins
    - MixinDirectShaderCompiler.java
    - MixinShaderProgramShard.java
  - mixin/iris/                       # Iris mixins
    - MixinIrisRenderingPipeline.java
    - MixinProgramSource.java
    - MixinProgramDirectives.java
  - test/                             # Game tests
    - IrisVeilGameTest.java
```

## Rendering Compatibility Architecture

The mod bridges Veil shaders into Iris shaderpacks through the following pipeline:

1. **MixinDirectShaderCompiler** captures Veil's processed vertex shader source during compilation
2. **IrisVeilShaderCache** stores processed sources and manages cache invalidation on shaderpack reload
3. **IrisVeilProgramLinker** creates Iris `ShaderInstance` objects for Veil shaders by:
   - Getting the Iris shaderpack's block program source
   - Patching the vertex shader with `GlslTransformerVeilPatcher` to accept Veil format
   - Creating a new `ShaderInstance` via the Iris pipeline
4. **MixinShaderProgramShard** intercepts Veil shader setup and replaces with Iris shader when shaderpack is active

## Code Style Guidelines

### General Conventions

- **Package naming**: `top.leonx.irisveil` (reverse domain)
- **Class naming**: PascalCase (e.g., `IrisVeilCompat`)
- **Method/variable naming**: camelCase (e.g., `isShaderPackInUse()`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MOD_ID`)
- **Final modifier**: Use for all constants

### Imports

- Use explicit imports (no wildcards)
- Order: java.*, javax.*, net.minecraft.*, org.spongepowered.*, third-party, project
- Group imports by package with blank lines between groups

### Mixin Conventions

- Mixins go in `mixin/<target-package>/` directories
- Naming: `Mixin<TargetClass>.java`
- Use `@Unique` for methods that must not be renamed
- Use `remap = false` when injecting into non-remapped methods
- Accessors go in `accessors/` package with `*Accessor` suffix
- Mixin JSONs in `src/main/resources/` named `irisveil.mixins.<target>.json`

### Error Handling

- Use `IrisVeilCompat.LOGGER` for logging (SLF4J)
- Log levels: `warn()`, `info()`, `debug()`, `error()`
- Provide context in error messages
- Use `try-catch` for potentially failing operations
- Never leave empty catch blocks - always log at debug level minimum

### Null Safety

- Prefer `@Nullable` annotations when null is possible
- Use `Objects.requireNonNull()` for parameters that must not be null
- Prefer `Optional` for return values that may be absent

## Testing

### Manual Testing

1. Run `./gradlew runClient --no-daemon` to launch Minecraft with the mod
2. Enable shader packs in Iris settings
3. Load a world with Veil-using mods (e.g., Create Aeronautics)
4. Verify Veil shaders render correctly with Iris shaderpack active

### Game Tests

The project uses NeoForge's built-in game testing framework. Tests are run against the `top.leonx.irisveil` namespace.

```bash
./gradlew runGameTestServer --no-daemon
```

## Dependency Management

### Runtime Dependencies (localRuntime)

The following mods are configured as `localRuntime` dependencies for testing:

- **Iris** (`maven.modrinth:iris`) - Shader loader
- **Sodium** (`maven.modrinth:sodium`) - Rendering optimization
- **Veil** (`foundry.veil:veil-neoforge`) - Shader infrastructure
- **Create** (`com.simibubi.create:create`) - Mechanical mod
- **Ponder** (`net.createmod.ponder:ponder-neoforge`) - Create dependency
- **Registrate** (`com.tterrag.registrate:Registrate`) - Create dependency
- **Create Aeronautics** (`maven.modrinth:create-aeronautics`) - Aircraft/planes
- **Sable** (`maven.modrinth:sable`) - Moving sub-levels (Aeronautics dependency)

These are only loaded during `runClient`/`runGameTestServer` and are not bundled in the output JAR.

## Common Development Tasks

### Adding a New Mixin

1. Create mixin class in appropriate `mixin/` subdirectory
2. Create/extend accessor interface if needed in `accessors/`
3. Register in appropriate `irisveil.mixins.<target>.json`
4. Add `client` or appropriate array entry

### Updating Dependencies

Edit versions in `gradle.properties`:
- `minecraft_version` - Minecraft version
- `neo_version` - NeoForge version
- `iris_version` - Iris version
- `veil_version` - Veil version
- `sodium_version` - Sodium version
- `create_version` - Create version
- `aeronautics_version` - Create Aeronautics version
- `sable_version` - Sable version

### Building for Release

```bash
./gradlew build --no-daemon
```

Output JAR will be in `build/libs/` with version in filename.

## External Resources

- [NeoForge Docs](https://docs.neoforged.net/)
- [Iris API](https://irisshaders.github.io/)
- [Veil](https://github.com/FoundryMC/Veil)
- [Parchment Mappings](https://github.com/ParchmentMC/Parchment)
- [glsl-transformer](https://github.com/IrisShaders/glsl-transformer)
