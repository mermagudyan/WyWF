#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) flat in vec3 Tint;
layout(location = 2) in vec2 UV;
layout(location = 0) out vec4 fragColor;

void main() {
    float fade = 1.0 - clamp(UV.y / 8.0, 0.0, 1.0);
    fragColor = vec4(Tint, fade * fade * (3.0 - 2.0 * fade));
}
