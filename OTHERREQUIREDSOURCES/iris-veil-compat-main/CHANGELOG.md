### 0.3.0
- Fixed End Sea rendering issues when using Iris shaderpacks.
- Fixed Rope and Spring shadows disappearing with some shaderpacks.
- Improved compatibility with Photon, Complementary, BSL, and Bliss for affected Veil effects.

### 0.2.0
- Added Iris shadow pass support for Veil shaders so block entity shadows render into the shadow map instead of gbuffers.
- Preserved shaderpack shadow alpha-test behavior when building Veil shadow programs.
- Fixed Veil fragment bridge symbol isolation to avoid conflicts with shaderpack globals and helper functions, including SEUS PTGI HRR 3 `ScreenSize` and `frameTimeCounter` cases.
- Reduced noisy per-frame Veil shader cache and translucency diagnostic logging.

### 0.1.0
- Initial release.
- Create Aeronautics now renders correctly with Iris shaderpacks (verified with Spring and Laser).
- Shader compatibility refreshes automatically when switching or reloading shaderpacks.
