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
uniform sampler2D OpaqueColor;
uniform sampler2D OpaqueDepth;
uniform isampler2D IceField;
layout(location = 0) flat in vec3 Tint;
layout(location = 1) flat in int Material;
layout(location = 2) in vec2 UV;
layout(location = 3) flat in int Detail;
layout(location = 4) in vec3 WaterPoint;
layout(location = 5) flat in float Haze;
layout(location = 0) out vec4 fragColor;

float iceWeight(ivec2 at, float height) {
    int entry = texelFetch(IceField, at, 0).r;
    return height <= float(entry >> 8) + 1.0001 ? float(entry & 255) / 255.0 : 0.0;
}

float iceStrength(vec3 point) {
    float side = Fog.w - 1.0;
    if (side <= 0.0 || any(lessThan(point.xz, vec2(0.0))) || any(greaterThan(point.xz, vec2(side)))) return 0.0;
    ivec2 cell = ivec2(min(floor(point.xz), vec2(side - 1.0)));
    vec2 offset = point.xz - vec2(cell);
    float first = mix(iceWeight(cell, point.y), iceWeight(cell + ivec2(1, 0), point.y), offset.x);
    float last = mix(iceWeight(cell + ivec2(0, 1), point.y), iceWeight(cell + ivec2(1, 1), point.y), offset.x);
    return mix(first, last, offset.y);
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy);
    float ground = texelFetch(OpaqueDepth, px, 0).r;
    bool water = Material == 254;
    if (!water && ground >= 1.0 && Settings.z > 0.0) discard;
    if (!water && Detail != 0 && (UV.y > .80 && (UV.x < .16 || UV.x > .87)
            || UV.y > .94 && (UV.x < .35 || UV.x > .64))) discard;
    vec3 backdrop = texelFetch(OpaqueColor, px, 0).rgb;
    float distance = clamp((ground - gl_FragCoord.z) * Settings.w * Angles.z * Settings.x, 0.0, 36.0);
    float alpha = water ? (ground >= 1.0 ? 1.0 : 1.0 - .71 / (1.0 + distance * .42)) : .8;
    bool berry = Material == 244 && Detail != 0 && (
        UV.y > .54 && UV.y < .70 && UV.x > .19 && UV.x < .33 ||
        UV.y > .32 && UV.y < .48 && UV.x > .66 && UV.x < .80);
    vec3 color = mix(backdrop, berry ? mix(vec3(191, 73, 64) / 255.0, Fog.xyz, Haze) : Tint, alpha);
    if (water && ground < 1.0 && distance > 0.0 && Fog.w > 1.0) {
        float ray = (ground - gl_FragCoord.z) * Settings.w;
        vec3 point = WaterPoint - ray * vec3(Angles.w * Angles.x, Angles.z * Settings.x, Angles.w * Angles.y);
        float fade = iceStrength(point) * min(1.0, distance);
        vec3 ice = mix(Tint, vec3(10, 24, 45) / 255.0, .72);
        color = mix(color, ice, fade * fade * (3.0 - 2.0 * fade));
    }
    fragColor = vec4(color, 1.0);
}
