#version 330
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in vec2 Corner;
layout(location = 1) in ivec4 Face;
layout(location = 2) in ivec2 Box;
layout(std140) uniform ReliefScene {
    vec4 Angles;
    vec4 Projection;
    vec4 Terrain;
    vec4 Fog;
    vec4 Settings;
    vec4 Effects;
};
layout(location = 0) flat out vec3 Tint;
layout(location = 1) flat out int Material;
layout(location = 2) out vec2 UV;
layout(location = 3) flat out int Detail;
layout(location = 4) out vec3 WaterPoint;
layout(location = 5) flat out float Haze;

vec3 rgb(int color) {
    return vec3((color >> 16) & 255, (color >> 8) & 255, color & 255) / 255.0;
}

vec3 bounds(int packed) {
    return vec3(packed & 255, (packed >> 8) & 255, (packed >> 16) & 255) / 128.0;
}

vec3 light(vec3 rgbValue, int direction, float shadow) {
    const float levels[6] = float[6](1.00, .99, .73, .97, .76, .66);
    float level = levels[direction] * (1.0 - .25 * shadow);
    float gray = dot(rgbValue, vec3(54.0, 183.0, 19.0) / 256.0);
    float cool = max(0.0, 1.0 - level) * 18.0 / 255.0;
    float sun = max(0.0, level - .85) * 22.0 / 255.0;
    return clamp((rgbValue + .04 * (rgbValue - gray)) * level + vec3(sun, sun * .6 + cool * .4, cool), 0.0, 1.0);
}

void main() {
    uint parameters = uint(Box.y) >> 24u;
    int direction = int(parameters & 7u);
    int kind = Box.x >> 24;
    Material = int(uint(Face.w) >> 24u);
    UV = Corner;
    Detail = 0;
    WaterPoint = vec3(0.0);
    Haze = 0.0;
    bool facing = direction == 0 || (direction == 1 && Angles.x < 0.0) || (direction == 2 && Angles.x > 0.0)
        || (direction == 3 && Angles.y < 0.0) || (direction == 4 && Angles.y > 0.0);
    bool plant = kind == 2 || kind == 3;
    bool flatPlant = Terrain.w < 2.5 || abs(Angles.w) < .02;
    if (direction != 0 && abs(Angles.w) < 1e-6) facing = false;
    if ((!facing && kind != 1) || (kind == 3 && flatPlant)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        Tint = vec3(0.0);
        return;
    }

    vec3 local;
    if (direction == 0 || direction == 5) local = vec3(Corner.x, direction == 0 ? 1.0 : 0.0, Corner.y);
    else if (direction <= 2) local = vec3(direction == 2 ? 1.0 : 0.0, Corner.x, Corner.y);
    else local = vec3(Corner.x, Corner.y, direction == 4 ? 1.0 : 0.0);
    if (kind == 1) {
        local = vec3(.5 + .4082483 * (local.x - local.y), (local.x + local.y + local.z) / 3.0,
            .5 + .23570226 * (2.0 * local.z - local.x - local.y));
    } else if (kind >= 4) {
        float RimHeight = float(Box.y & 0xFFFFFF);
        local = direction <= 2 ? vec3(0.0, Corner.x * RimHeight, Corner.y)
            : vec3(Corner.x, Corner.y * RimHeight, 0.0);
        UV = vec2(Corner.x, local.y);
    } else if (plant) {
        if (flatPlant) {
            float size = Terrain.w < 2.5 ? .75 : .25;
            local = vec3((1.0 - size) * .5 + Corner.x * size, .625, (1.0 - size) * .5 + Corner.y * size);
        } else {
            float u = .1875 + .625 * Corner.x;
            local = vec3(u, Corner.y, kind == 2 ? u : 1.0 - u);
            Detail = Terrain.w >= 8.0 ? 1 : 0;
        }
    } else local = mix(bounds(Box.x), bounds(Box.y), local);

    bool xWall = direction == 1 || direction == 2;
    bool zWall = direction == 3 || direction == 4;
    WaterPoint = vec3(Face.xyz) + vec3(xWall ? 0.0 : Corner.x,
        xWall ? Corner.x : zWall ? Corner.y : 0.0, zWall ? 0.0 : Corner.y);
    vec3 block = vec3(Face.xyz) + local - vec3(Terrain.x, Terrain.y, Terrain.x);
    float distance = Angles.x * block.x + Angles.y * block.z;
    float screenX = Angles.y * block.x - Angles.x * block.z;
    float blockY = block.y * Settings.x;
    float screenY = Angles.z * distance - Angles.w * blockY;
    float depth = Angles.w * distance + Angles.z * blockY;
    gl_Position = vec4(screenX * Projection.x + Projection.z, screenY * Projection.y + Projection.w,
        .5 - depth * Terrain.z, 1.0);

    Tint = rgb(Face.w);
    if (kind >= 4) {
        Tint = Fog.xyz;
    } else if (kind != 1) {
        if (Material == 254 && (Face.w & 0xFFFFFF) == 0) Tint = rgb(0x1B464E);
        if (Material == 253) {
            Tint = mix(Tint, rgb(0x181821), .83 * clamp((Angles.z - .38) / .35, 0.0, 1.0));
            Tint = light(Tint, direction, float(parameters >> 3u) / 31.0);
        }
        if (Settings.y > 0.0) {
            float last = 2.0 * Terrain.x - 1.0;
            float margin = min(min(float(Face.x), last - float(Face.x)), min(float(Face.z), last - float(Face.z)));
            float edge = max(0.0, 1.0 - margin / Settings.y);
            Haze = edge * edge;
            Tint = mix(Tint, Fog.xyz, Haze);
        }
    }
}
