# End Sea Shaderpack Render Hook Compatibility Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:systematic-debugging` first, then `superpowers:test-driven-development` for code changes, and `superpowers:verification-before-completion` before claiming the bug is fixed. Track progress by updating these checkboxes.

**Goal:** Make Simulated / Create Aeronautics End Sea render correctly when an Iris shaderpack is enabled.

**Corrected diagnosis:** The Nsight exports from `D:\Dev\mc-irisflw-dev\nsight\EndSea_Fragment Shader__java__Event_616__2026_06_14-17_02_24.json` and `D:\Dev\mc-irisflw-dev\nsight\EndSea_Vertex Shader__java__Event_616__2026_06_14-17_01_56.json` are shaderpack-off baseline captures. They prove what the healthy final End Sea composite draw looks like, but they do not prove that this draw exists with shaderpacks enabled. The current shaderpack-on evidence is stronger: the final End Sea drawcall cannot be found, so the primary fix must restore the End Sea render entry point in the Iris shaderpack path.

**Tech Stack:** Java 21, NeoForge, Sponge Mixin, Iris, Veil, Simulated/Create Aeronautics runtime integration, Gradle `--no-daemon`, NVIDIA Nsight Graphics for frame validation.

---

## Evidence Summary

- Healthy shaderpack-off baseline:
  - Fragment shader state name includes `fragment Shader simulated:end_sea`.
  - Vertex shader state name includes `vertex Shader simulated:end_sea`.
  - Fragment samplers include `SkySampler`, `ShadowStrengthSampler`, and `ShadowDepthSampler`.
  - `ShadowVolumeSize=128.00` and `StartY=-40.00` match Simulated End Sea behavior.
- Simulated final End Sea render source:
  - `D:\Dev\mc-irisflw-dev\Simulated-Project\simulated\common\src\main\java\dev\simulated_team\simulated\mixin\end_sea\LevelRendererMixin.java` injects into `LevelRenderer.renderLevel` before `LevelRenderer.renderDebug(...)` and calls `EndSeaRenderer.render(camera, gameRenderer)`.
  - `D:\Dev\mc-irisflw-dev\Simulated-Project\simulated\common\src\main\java\dev\simulated_team\simulated\content\end_sea\EndSeaRenderer.java` binds `simulated:end_sea` through `VeilRenderSystem.setShader(SHADER)` and draws 48 additive quad layers.
- Simulated End Sea shadow source:
  - `D:\Dev\mc-irisflw-dev\Simulated-Project\simulated\common\src\main\java\dev\simulated_team\simulated\SimulatedClient.java` registers `EndSeaShadowRenderer::renderShadowMap` on `VeilEventPlatform.INSTANCE.onVeilRenderLevelStage`.
  - `EndSeaShadowRenderer.renderShadowMap(...)` runs only at `VeilRenderLevelStageEvent.Stage.AFTER_LEVEL`.
- Iris shaderpack path:
  - `D:\Dev\mc-irisflw-dev\Iris\src\main\java\net\irisshaders\iris\mixin\MixinLevelRenderer.java` calls `pipeline.finalizeLevelRendering()` at `LevelRenderer.renderLevel` return, before later return injectors.
  - Iris tracks phases around `renderWorldBorder(...)` and `DebugRenderer.render(...)`, so the old Simulated injection point may be affected by Iris's pipeline/final-pass timing even if `renderLevel` itself still executes.
- Current shaderpack-on observation:
  - The End Sea final drawcall is not visible in the captured draw list. Therefore, do not treat `simulated:end_sea` replacement as the only root cause; first verify whether `EndSeaRenderer.render(...)` is called at all, and whether it draws before or after Iris finalization.
- Event-stage fallback observation:
  - `D:\Dev\mc-irisflw-dev\nsight\EndSea_Shaderpack_Fragment Shader__java__Event_1423__2026_06_14-18_49_28.json` proves the fallback restores a draw using `fragment Shader simulated:end_sea`.
  - `D:\Dev\mc-irisflw-dev\nsight\EndSea_Framebuffer__java__Event_1423__2026_06_14-18_49_44.json` shows that draw targets `Framebuffer minecraft:main` with a vanilla `GL_RGBA8` color attachment, not an Iris shaderpack render target.
  - Root cause for the remaining failure: the End Sea final draw is still being issued outside Iris's active gbuffer render-target binding, so Iris's final/composite passes do not see it.

## Design Direction

The fix should introduce an optional Simulated End Sea compatibility bridge owned by iris-veil-compat:

- It must only activate when an Iris shaderpack is actually in use.
- It must not directly link against Simulated classes at compile time; use reflection so the mod still loads without Create Aeronautics / Simulated.
- It should call the same Simulated final renderer, `dev.simulated_team.simulated.content.end_sea.EndSeaRenderer.render(Camera, GameRenderer)`, from a render stage that still executes in the Iris shaderpack path.
- It should bind an Iris-managed after-translucent gbuffer framebuffer for draw buffer 0 before invoking Simulated's final renderer, matching Iris's `colortex0` / final composite convention.
- It should keep `simulated:end_sea` as a standalone Veil composite shader, not rewrite it into an Iris gbuffer shader.
- It should avoid interfering with Simulated's End Sea shadow-buffer pass.

## Files

- Create: `src/main/java/top/leonx/irisveil/compat/simulated/SimulatedEndSeaCompat.java`
  - Optional reflection facade for Simulated End Sea methods and diagnostics.
- Create: `src/main/java/top/leonx/irisveil/compat/simulated/SimulatedEndSeaIrisRenderer.java`
  - Client-only bridge that binds the Iris framebuffer and invokes the optional reflection facade.
- Modify: `src/main/java/top/leonx/irisveil/IrisVeilCompatClient.java`
  - Do not keep the late event-stage fallback once the Iris framebuffer hook is active.
- Create: `src/main/java/top/leonx/irisveil/mixin/iris/MixinLevelRendererEndSea.java`
  - Iris/LevelRenderer hook before `pipeline.finalizeLevelRendering()`.
- Modify: `src/main/java/top/leonx/irisveil/accessors/IrisRenderingPipelineAccessor.java`
  - Expose the End Sea framebuffer binding entry point implemented by the Iris pipeline mixin.
- Modify: `src/main/java/top/leonx/irisveil/mixin/iris/MixinIrisRenderingPipeline.java`
  - Cache and bind an Iris after-translucent gbuffer framebuffer for End Sea final composition.
- Modify: `src/main/resources/irisveil.mixins.iris.json`
  - Register the hook mixin if needed.
- Modify: `src/main/java/top/leonx/irisveil/compat/veil/IrisVeilShaderCache.java`
  - Keep or refine End Sea replacement opt-out and shadow-map opt-out.
- Modify: `src/main/java/top/leonx/irisveil/test/IrisVeilGameTest.java`
  - Add focused regression coverage for optional reflection and shader replacement decisions.

---

## Task 1: Add Diagnostics That Distinguish Missing Draw From Renamed Shader

**Files:**
- Create: `src/main/java/top/leonx/irisveil/compat/simulated/SimulatedEndSeaCompat.java`
- Modify: `src/main/java/top/leonx/irisveil/IrisVeilCompatClient.java`

- [x] **Step 1: Add an optional reflection facade**

Create `SimulatedEndSeaCompat` with cached method lookup for:

```java
dev.simulated_team.simulated.content.end_sea.EndSeaRenderer.render(Camera, GameRenderer)
dev.simulated_team.simulated.content.end_sea.EndSeaShadowRenderer.renderingShadowMap()
```

The facade must return false/no-op when classes are absent, methods are absent, or a linkage error occurs.

- [x] **Step 2: Add temporary debug counters**

Track these per-frame or log-once counters:

```text
shaderpack active
AFTER_LEVEL seen
EndSeaRenderer.render fallback invoked
EndSeaRenderer.render fallback failed
EndSeaShadowRenderer.renderingShadowMap true
```

Keep logging at `debug` unless a reflective call fails unexpectedly, then use `warn` with class/method context.

- [ ] **Step 3: Run a diagnostic client**

Run:

```powershell
./gradlew runClient --no-daemon
```

With the shaderpack enabled, reproduce the End Sea scene and inspect logs.

- [ ] **Step 4: Decide which failure mode is real**

Expected outcomes:

- If `AFTER_LEVEL` is seen and fallback invocation produces a drawcall, the final renderer was simply missing from the shaderpack path.
- If fallback invocation happens but still no drawcall appears, inspect early returns in `EndSeaRenderer.render(...)`: missing `EndSeaPhysics`, `EndSeaShadowRenderer.renderingShadowMap() == true`, or wrong framebuffer/pass timing.
- If `AFTER_LEVEL` is not seen, use a `LevelRenderer.renderLevel` mixin hook instead of a NeoForge/Veil event listener.

## Task 2: Lock Optional Simulated Reflection in Tests

**Files:**
- Modify: `src/main/java/top/leonx/irisveil/test/IrisVeilGameTest.java`
- Modify: `src/main/java/top/leonx/irisveil/compat/simulated/SimulatedEndSeaCompat.java`

- [x] **Step 1: Write a failing test for absent Simulated classes**

Add a GameTest that asserts:

```java
helper.assertFalse(
    SimulatedEndSeaCompat.isRenderingEndSeaShadowMap(
        "missing.simulated.EndSeaShadowRenderer",
        "renderingShadowMap"),
    "Missing optional Simulated classes should be treated as inactive");
```

- [x] **Step 2: Write a failing test for a fake shadow-map state provider**

Add a nested fake class in `IrisVeilGameTest`:

```java
public static class FakeExternalShadowRenderer {
    private static boolean rendering;

    public static boolean renderingShadowMap() {
        return rendering;
    }
}
```

Assert true when `rendering = true` and false when `rendering = false`.

- [x] **Step 3: Implement only enough helper code to pass**

Expose testable package/static methods for class/method probes. Do not reference Simulated classes directly.

- [x] **Step 4: Verify**

Run:

```powershell
./gradlew build --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

## Task 3: Restore the Final End Sea Draw in the Shaderpack Path

**Files:**
- Modify: `src/main/java/top/leonx/irisveil/IrisVeilCompatClient.java`
- Create or modify: `src/main/java/top/leonx/irisveil/compat/simulated/SimulatedEndSeaCompat.java`
- Optional create: `src/main/java/top/leonx/irisveil/mixin/iris/MixinLevelRendererEndSea.java`
- Optional modify: `src/main/resources/irisveil.mixins.iris.json`

- [x] **Step 1: First try an event-stage fallback**

Register a client-side render stage listener. Use NeoForge `RenderLevelStageEvent` or Veil's platform event, matching Simulated's shadow stage:

```java
if (stage == AFTER_LEVEL && IrisVeilCompat.isShaderPackInUse()) {
    SimulatedEndSeaCompat.render(camera, Minecraft.getInstance().gameRenderer);
}
```

This deliberately runs only with a shaderpack enabled so shaderpack-off behavior remains Simulated-owned.

- [x] **Step 2: Verify drawcall restoration**

Capture a shaderpack-on frame. Expected:

- A final End Sea composite draw exists.
- Its shader is either `simulated:end_sea` or a clearly equivalent Veil final composite draw with the same geometry and samplers.
- The draw happens before Iris final output is presented, or otherwise appears in the final image.

Result: the drawcall exists, but the framebuffer capture shows it still targets vanilla `minecraft:main`; continue with the LevelRenderer/Iris framebuffer hook.

- [x] **Step 3: If the event-stage fallback is too late, move to a LevelRenderer hook**

Create a mixin that calls the same reflection facade from a stable point near Iris's world-border/debug phase, before `pipeline.finalizeLevelRendering()`:

```java
@Inject(
    method = "renderLevel",
    at = @At(
        value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/LevelRenderer;renderWorldBorder(Lnet/minecraft/client/Camera;)V",
        shift = At.Shift.AFTER
    )
)
```

Use this only if the event path either is not fired or renders after Iris finalization.

Implementation: `MixinLevelRendererEndSea` now calls `SimulatedEndSeaIrisRenderer.renderIntoIrisFramebuffer(...)` after `renderWorldBorder(...)`. The helper binds a cached Iris framebuffer created from `renderTargets.createGbufferFramebuffer(flippedAfterTranslucent, new int[]{0})`, invokes the optional Simulated renderer, and restores `Minecraft.getInstance().getMainRenderTarget().bindWrite(false)` afterward.

- [ ] **Step 4: Add duplicate-render protection if needed**

If Nsight shows both Simulated's original draw and the fallback draw with shaderpack enabled, add a per-frame guard in `SimulatedEndSeaCompat`:

```text
lastFallbackRenderTick
lastOriginalRenderTick if an optional mixin is added
```

Avoid double additive blending.

## Task 4: Keep End Sea Out of Generic Iris Gbuffer Replacement

**Files:**
- Modify: `src/main/java/top/leonx/irisveil/compat/veil/IrisVeilShaderCache.java`
- Modify: `src/main/java/top/leonx/irisveil/test/IrisVeilGameTest.java`

- [x] **Step 1: Treat this as defense in depth, not the primary fix**

The reason iris-veil-compat normally replaces Veil shaders with Iris gbuffer-compatible shader instances is to make ordinary Veil world geometry write into the shaderpack's expected gbuffer pipeline, so the shaderpack can light/composite it correctly. End Sea's final draw is different: it is an additive standalone composite effect with custom samplers.

- [x] **Step 2: Add or keep a test for End Sea shader eligibility**

Assert:

```java
helper.assertFalse(
    IrisVeilShaderCache.shouldReplaceShader(ResourceLocation.fromNamespaceAndPath("simulated", "end_sea")),
    "End Sea final composite shader uses custom samplers and should not be treated as gbuffer geometry");

helper.assertTrue(
    IrisVeilShaderCache.shouldReplaceShader(ResourceLocation.fromNamespaceAndPath("simulated", "spring/spring")),
    "Ordinary Veil RenderType shaders should remain eligible for Iris replacement");
```

- [x] **Step 3: Keep the replacement predicate narrow**

Implement:

```java
private static final ResourceLocation SIMULATED_END_SEA =
    ResourceLocation.fromNamespaceAndPath("simulated", "end_sea");

public static boolean shouldReplaceShader(ResourceLocation shaderPath) {
    return !SIMULATED_END_SEA.equals(shaderPath);
}
```

Use the predicate at the start of `IrisVeilShaderCache.getOrCreate(ResourceLocation shaderPath)`.

## Task 5: Protect Simulated End Sea Shadow-Buffer Rendering

**Files:**
- Modify: `src/main/java/top/leonx/irisveil/compat/veil/IrisVeilShaderCache.java`
- Modify: `src/main/java/top/leonx/irisveil/compat/veil/mixin/MixinShaderProgramShard.java`
- Modify: `src/main/java/top/leonx/irisveil/test/IrisVeilGameTest.java`

- [x] **Step 1: Keep the shadow-map opt-out**

When Simulated is rendering `simulated:end_sea_shadows`, return `null` from `IrisVeilShaderCache.getOrCreate(...)` so `MixinShaderProgramShard` leaves the original Veil/vanilla shader state in place.

- [x] **Step 2: Fix local cache staleness**

If a `ShaderProgramShard` cached an Iris replacement before the End Sea shadow pass, include the optional external render state in the local cache key:

```java
int externalRenderStateGen = IrisVeilShaderCache.getExternalRenderStateGeneration();
```

Invalidate when it changes.

- [ ] **Step 3: Verify shadow producer draws**

In Nsight, inspect the shadow-buffer producer sequence. Expected:

- Draws target `simulated:end_sea_shadows` or its dynamic framebuffer.
- They do not use `gbuffers_veil_*` or `shadow_veil_*` replacement programs during Simulated's End Sea shadow-map render.
- `spread_end_sea` post passes still run afterward.

## Task 6: Manual Nsight Acceptance Criteria

**Files:**
- No source changes.

- [ ] **Step 1: Capture shaderpack-off baseline if needed**

Use the existing Event 616 exports as the known-good shape:

```text
D:\Dev\mc-irisflw-dev\nsight\EndSea_Fragment Shader__java__Event_616__2026_06_14-17_02_24.json
D:\Dev\mc-irisflw-dev\nsight\EndSea_Vertex Shader__java__Event_616__2026_06_14-17_01_56.json
```

- [ ] **Step 2: Capture shaderpack-on after the fix**

Expected:

- The final End Sea drawcall exists in the shaderpack-on capture.
- Its geometry matches the 48-layer quad stack from `EndSeaRenderer`.
- Its inputs include `Position`, `Color`, `UV0`, and `UV2`, or an equivalent transformed layout.
- Its fragment stage consumes `SkySampler`, `ShadowDepthSampler`, and `ShadowStrengthSampler`, unless the shaderpack path intentionally preserves the effect through an equivalent wrapper.
- The effect is visible in the final frame around physical structures near `y <= -40`.

- [ ] **Step 3: Check for double rendering**

Expected:

- Exactly one final End Sea composite draw per frame.
- No double-bright additive duplicate.

## Task 7: Final Verification

**Files:**
- No source changes unless verification reveals a new root cause.

- [x] **Step 1: Run build**

```powershell
./gradlew build --no-daemon
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 2: Run diff checks**

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors beyond existing line-ending warnings; only intended files modified.

Result: `git diff --check` reports only CRLF/LF conversion warnings from Git on Windows. `git status --short` shows the intended source, mixin config, plan, and Simulated compatibility files modified/added.

- [ ] **Step 3: Attempt GameTest server**

```powershell
./gradlew runGameTestServer --no-daemon
```

Known current blocker: the dedicated server may fail because `irisflw` loads `net.minecraft.client.gui.screens.Screen` on `DEDICATED_SERVER`. If this happens, record it as an environment/dependency blocker, not proof that this fix failed.

## Self-Review

- The plan treats shaderpack-off Nsight data as baseline only.
- The primary path restores the missing shaderpack-on final End Sea drawcall.
- Shader replacement opt-out is retained as defense in depth because End Sea's final shader is a standalone additive composite effect, not ordinary gbuffer geometry.
- The plan avoids compile-time dependency on Simulated classes.
- No placeholders remain.
