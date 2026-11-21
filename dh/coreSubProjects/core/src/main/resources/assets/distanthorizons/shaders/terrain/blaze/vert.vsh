#version 330
// needed for "layout(location = 0)" required as as of MC 26.3
#extension GL_ARB_separate_shader_objects : require

layout(location = 0) in uvec4 vPositionAndMeta; // Meta contains light and micro-offset data
layout(location = 1) in vec4 vColor;
// x: iris material, y: normal index, zw: block texture tile id (0 = flat color)
layout(location = 2) in uvec4 materialNormalTile;
// xyz: offset inside the block in 256ths, w: flags, see EDhApiTerrainVertexFormat
layout(location = 3) in uvec4 offsetData;
// xy: model texture position, zw: normal, see EDhApiTerrainVertexFormat
layout(location = 4) in uvec4 uvNormal;

// order matters, this must match the fragment shader's inputs
layout(location = 0) out vec3 vPos;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec3 vertexWorldPos;
// block-grid position used to generate texture UVs, fract() of this repeats per block
layout(location = 3) out vec3 vBlockPos;
layout(location = 4) flat out uint vNormalIndex;
layout(location = 5) flat out uint vTextureTileId;
layout(location = 7) out vec2 vModelUv;
layout(location = 8) flat out uint vUsesModelUv;

layout (std140) uniform vertUniqueUniformBlock
{
    ivec3 uModelOffset;
};

layout (std140) uniform vertSharedUniformBlock 
{ 
    bool uIsWhiteWorld;
    
    float uWorldYOffset;
    float uMircoOffset;
    float uEarthRadius;
    
    float uFrameMod8;
    float uViewWidth;
    float uViewHeight;

    ivec3 uCorseCameraPos;
    vec3 uFineCameraPos;
    mat4 uCombinedMatrix;
};

uniform sampler2D uLightMap;

// fraction of their distance flat models are pulled toward the camera
const float FLAT_MODEL_PULL = 0.001;

vec2 jitterOffsets[8] = vec2[8](
    vec2( 0.125, -0.375),
    vec2(-0.125,  0.375),
    vec2( 0.625,  0.125),
    vec2( 0.375, -0.625),
    vec2(-0.625,  0.625),
    vec2(-0.875, -0.125),
    vec2( 0.375, -0.875),
    vec2( 0.875,  0.875)
);

vec2 TAAJitter(vec2 coord, float w) 
{
    vec2 offset = jitterOffsets[int(uFrameMod8)] * (w / vec2(uViewWidth, uViewHeight));
    return coord + offset;
}

/** 
 * LOD terrain Vertex Shader
 */
void main()
{
    vec3 vPosition = vPositionAndMeta.xyz;
    uint meta = vPositionAndMeta.w;
    vPosition += vec3(offsetData.xyz) / 256.0;
    // model positions are signed
    if ((offsetData.w & 1u) != 0u)
    {
        vPosition -= vec3(greaterThan(vPositionAndMeta.xyz, uvec3(32767u))) * 65536.0;
    }
    
    vPos = vPosition; // This is so it can be passed to the fragment shader
    
    vBlockPos = vec3(vPosition.xyz);
    vNormalIndex = materialNormalTile.y;
    vTextureTileId = materialNormalTile.z | (materialNormalTile.w << 8u);
    vModelUv = vec2(uvNormal.xy) / 255.0;
    vUsesModelUv = offsetData.w & 1u;
    
    ivec3 relOffset = uModelOffset - uCorseCameraPos; // ints needed for extreme (10 million +) distances
    vertexWorldPos = vPosition.xyz + (vec3(relOffset) - uFineCameraPos);
    
    float vertexYPos = vPosition.y + uWorldYOffset;
    
    uint mirco = (meta & 0xFF00u) >> 8u; // mirco offset which is a xyz 2bit value
    // 0b00 = no offset
    // 0b01 = positive offset
    // 0b11 = negative offset
    // format is: 0b00zzyyxx
    float mx = (mirco & 1u)!=0u ? uMircoOffset : 0.0;
    mx = (mirco & 2u)!=0u ? -mx : mx;
    //float my = (mirco & 4u)!=0u ? uMircoOffset : 0.0;
    //my = (mirco & 8u)!=0u ? -my : my;
    float mz = (mirco & 16u)!=0u ? uMircoOffset : 0.0;
    mz = (mirco & 32u)!=0u ? -mz : mz;
    
    vertexWorldPos.x += mx;
    //vertexWorldPos.y += my;
    vertexWorldPos.z += mz;
    
    // apply the earth curvature if needed
    if (uEarthRadius < -1.0f || uEarthRadius > 1.0f)
    {
        // vertex transformation logic - stduhpf
        float localRadius = uEarthRadius + vertexYPos;
        float phi = length(vertexWorldPos.xz) / localRadius;
        vertexWorldPos.y += (cos(phi) - 1.0) * localRadius;
        vertexWorldPos.xz = vertexWorldPos.xz * sin(phi) / phi;
    }
    
    uint lights = meta & 0xFFu;
    float skyLight = (float(lights/16u)+0.5) / 16.0;
    float blockLight = (mod(float(lights), 16.0)+0.5) / 16.0;
    vertexColor = vec4(texture(uLightMap, vec2(skyLight, blockLight)).xyz, 1.0);
    
    if (!uIsWhiteWorld)
    {
        vertexColor *= vColor;
    }
    
    // keeps flat models from z-fighting with the block behind them
    if ((offsetData.w & 2u) != 0u)
    {
        vertexWorldPos *= 1.0 - FLAT_MODEL_PULL;
    }
    
    gl_Position = uCombinedMatrix * vec4(vertexWorldPos, 1.0);
    
    // -1 if TAA is diabled
    if (uFrameMod8 > 0)
    {
		// jittering the model around is necessary to smooth out TAA properly
        gl_Position.xy = TAAJitter(gl_Position.xy, gl_Position.w);
    }
}
