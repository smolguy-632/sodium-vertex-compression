layout(std140) uniform u_Globals {
    // BlockLightTest emitters. Declared ahead of the matrices so that every following field keeps
    // a naturally 16-byte aligned std140 offset, which is what UniformBufferManager's write order
    // assumes. xyz is view space; w packs the normalized emission, negative when carried.
    vec4 u_BtEmitters[12];
    vec4 u_BtColors[12];
    int u_BtCount;

    mat4 u_ProjectionMatrix;
    mat4 u_ModelViewMatrix;

    vec4 u_FogColor;
    vec2 u_EnvironmentFog;
    vec2 u_RenderFog;

    vec2 u_TexelSize;
    vec2 u_TexCoordShrink;

    float u_FadePeriodInv;
    bool u_UseRGSS;
};