// Copyright 2026 Nyanime contributors. Apache-2.0.
// Standard HSV cylinder -> RGB conversion, expressed by its six hue sectors.
vec3 hsv2rgb(vec3 hsv) {
    float sector = fract(hsv.x) * 6.0;
    float chroma = hsv.z * hsv.y;
    float secondary = chroma * (1.0 - abs(mod(sector, 2.0) - 1.0));
    vec3 rgb;
    if (sector < 1.0) rgb = vec3(chroma, secondary, 0.0);
    else if (sector < 2.0) rgb = vec3(secondary, chroma, 0.0);
    else if (sector < 3.0) rgb = vec3(0.0, chroma, secondary);
    else if (sector < 4.0) rgb = vec3(0.0, secondary, chroma);
    else if (sector < 5.0) rgb = vec3(secondary, 0.0, chroma);
    else rgb = vec3(chroma, 0.0, secondary);
    return rgb + vec3(hsv.z - chroma);
}
#pragma glslify: export(hsv2rgb)
