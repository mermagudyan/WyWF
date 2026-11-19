#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) flat in vec3 Tint;
layout(location = 0) out vec4 fragColor;
void main() {
    fragColor = vec4(Tint, 1.0);
}
