#include veil:fog

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out vec4 lightmapColor;

void main() {
    vec4 WorldPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * WorldPosition;
    vertexDistance = length(WorldPosition.xyz);
    texCoord0 = UV0;
    vertexColor = Color;
    lightmapColor = texelFetch(Sampler2, UV2 / 16, 0);
}

