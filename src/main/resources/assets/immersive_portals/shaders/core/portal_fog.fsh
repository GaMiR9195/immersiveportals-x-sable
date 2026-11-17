#version 150

// The fog of the outer view over the distance from its camera to the portal plane, laid over
// the composited portal content. Same curve as vanilla fog.glsl.

uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;

in vec3 cameraRelativePos;

out vec4 fragColor;

void main() {
    float distance = FogShape == 0
        ? length(cameraRelativePos)
        : max(length(cameraRelativePos.xz), abs(cameraRelativePos.y));
    if (distance <= FogStart) {
        discard;
    }
    float fog = distance < FogEnd ? smoothstep(FogStart, FogEnd, distance) : 1.0;
    fragColor = vec4(FogColor.rgb, fog * FogColor.a);
}
