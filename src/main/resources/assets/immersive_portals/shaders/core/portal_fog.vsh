#version 150
// ipl:no-clip (drawn under the stencil of the portal)

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 cameraRelativePos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    cameraRelativePos = Position;
}
