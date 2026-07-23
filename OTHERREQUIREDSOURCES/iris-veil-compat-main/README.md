<h1 align="center">Iris Veil Compat<br>
  <a href="https://github.com/leon-o/iris-veil-compat"><img src="https://img.shields.io/github/license/leon-o/iris-veil-compat" alt="License"></a>
    <br><br>
</h1>

# Iris Veil Compat
Allow mods using the Veil rendering engine (such as **Create: Aeronautics**) to render correctly when using Iris shaderpacks.

# Principle
[Veil](https://github.com/FoundryMC/Veil) is an advanced rendering SDK for Minecraft mods — it provides a shader infrastructure that other mods can build on. The most prominent consumer is [Create: Aeronautics](https://github.com/Creators-of-Aeronautics/Simulated-Project), which uses Veil for its airplane wings, glass panels, contrails, and other visual effects.

The problem: when you enable a shaderpack via Iris, Veil-rendered visuals bypass the shaderpack's pipeline entirely, causing them to appear broken or missing.

This mod automatically merges Veil shader code into the shaderpack's gbuffer programs at runtime, so Veil-based visuals (like Aeronautics planes) integrate seamlessly with your shaderpack.

# Implementation details

This mod intercepts Veil's shader compilation via mixins, capturing and caching the processed shader source. At render time, it injects Veil shader logic into the shaderpack's gbuffer vertex/fragment programs using AST-level patching (powered by [glsl-transformer](https://github.com/IrisShaders/glsl-transformer)), and creates an Iris `ShaderInstance` to replace the original Veil shader.

The cache is automatically invalidated when you switch or reload shaderpacks — no restart needed.

# Compatibility
- **Iris** 1.8.1+ (required)
- **Sodium**
- **Veil** 4.0.0+
- Tested with **Create: Aeronautics** (Spring, Laser) and **Sable**

# Credit
This project uses [glsl-transformer](https://github.com/IrisShaders/glsl-transformer) for shader AST manipulation.
