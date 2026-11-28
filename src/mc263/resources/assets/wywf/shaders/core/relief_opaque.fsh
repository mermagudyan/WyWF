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
uniform sampler2D EyePattern;
layout(location = 0) flat in vec3 Tint;
layout(location = 1) flat in int Material;
layout(location = 5) flat in float Haze;
layout(location = 0) out vec4 fragColor;
void main() {
    vec3 color = Tint;
    if (Material == 246 && Effects.z > 0.0) {
        ivec2 px = ivec2(gl_FragCoord.xy) % 448;
        vec4 first = texelFetch(EyePattern, px, 0);
        vec4 second = texelFetch(EyePattern, px + ivec2(0, 448), 0);
        vec4 pattern = mix(first, second, Effects.y);
        color = mix(Tint, mix(pattern.rgb, Fog.xyz, Haze), pattern.a);
    }
    fragColor = vec4(color, 1.0);
}
