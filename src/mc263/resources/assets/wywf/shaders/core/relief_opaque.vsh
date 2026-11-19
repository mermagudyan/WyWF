#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 Corner;
layout(location = 1) in ivec4 Face;
layout(std140) uniform ReliefScene {
    vec4 Angles;
    vec4 Projection;
    vec4 Terrain;
};
layout(location = 0) flat out vec3 Tint;

void main() {
    int direction = Face.w >> 24;
    float sinYaw = Angles.x;
    float cosYaw = Angles.y;
    bool facing = direction == 0 || (direction == 1 && sinYaw < 0.0) || (direction == 2 && sinYaw > 0.0)
            || (direction == 3 && cosYaw < 0.0) || (direction == 4 && cosYaw > 0.0);
    if (!facing) {
        gl_Position = vec4(2.0, 2.0, 1.0, 1.0);
        Tint = vec3(0.0);
        return;
    }
    vec3 block = vec3(Face.xyz);
    if (direction == 0) block += vec3(Corner.x, 1.0, Corner.y);
    else if (direction <= 2) block += vec3(direction == 2 ? 1.0 : 0.0, Corner.x, Corner.y);
    else block += vec3(Corner.x, Corner.y, direction == 4 ? 1.0 : 0.0);
    block -= vec3(Terrain.x, Terrain.y, Terrain.x);
    float distance = sinYaw * block.x + cosYaw * block.z;
    float x = cosYaw * block.x - sinYaw * block.z;
    float y = Angles.z * distance - Angles.w * block.y;
    float depth = Angles.w * distance + Angles.z * block.y;
    gl_Position = vec4(x * Projection.x + Projection.z, y * Projection.y + Projection.w,
            0.5 - depth * Terrain.z, 1.0);
    Tint = vec3((Face.w >> 16) & 255, (Face.w >> 8) & 255, Face.w & 255) / 255.0;
}
