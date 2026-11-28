#version 330
#extension GL_ARB_separate_shader_objects : require

layout(std140) uniform ReliefScene {
    vec4 Angles;
    vec4 Projection;
    vec4 Terrain;
    vec4 Fog;
    vec4 Settings;
    vec4 Effects;
};
uniform sampler2D SelectedMask;
uniform sampler2D SceneDepth;
layout(location = 0) out vec4 fragColor;

bool selected(ivec2 pixel, float front) {
    return texelFetch(SelectedMask, pixel, 0).r > .5 && texelFetch(SceneDepth, pixel, 0).r <= front;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(SelectedMask, 0);
    fragColor = vec4(0.0);
    if (any(lessThan(pixel, ivec2(1))) || any(greaterThanEqual(pixel, size - 1))) return;
    if (texelFetch(SelectedMask, pixel, 0).r > .5) return;
    float front = texelFetch(SceneDepth, pixel, 0).r + .15 * Terrain.z;
    if (selected(pixel + ivec2(1, 0), front) || selected(pixel - ivec2(1, 0), front)
            || selected(pixel + ivec2(0, 1), front) || selected(pixel - ivec2(0, 1), front))
        fragColor = vec4(1.0, 227.0 / 255.0, 145.0 / 255.0, 1.0);
}
