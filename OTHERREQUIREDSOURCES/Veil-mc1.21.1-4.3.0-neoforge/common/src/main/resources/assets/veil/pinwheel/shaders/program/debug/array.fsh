uniform sampler2DArray DiffuseSampler0;
uniform int Index;

in vec2 texCoord;

out vec4 outColor;

void main() {
    outColor = texture(DiffuseSampler0, vec3(texCoord, float(Index)));
}