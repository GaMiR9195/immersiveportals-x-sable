#version 150

// Passes on the first-person hand, which IrisForegroundMask marks in the stencil. Iris draws the
// hand with depth in [0.4375, 0.5625], nearer than the depth it captured before the hand.

uniform sampler2D DepthSampler;
uniform sampler2D NoHandDepthSampler;

out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(DepthSampler, pixel, 0).r;
    float noHand = texelFetch(NoHandDepthSampler, pixel, 0).r;
    if (depth >= noHand || depth > 0.5626) {
        discard;
    }
    fragColor = vec4(0.0);
}
