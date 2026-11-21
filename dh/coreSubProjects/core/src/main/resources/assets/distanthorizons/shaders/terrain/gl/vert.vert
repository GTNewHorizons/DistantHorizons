#version 330 core

in uvec4 vPosition;
in vec4 color;
// x: iris material id, y: face normal index, zw: block texture tile id (little endian)
in uvec4 irisData;
// xyz: offset inside the block in 256ths, w: flags, see EDhApiTerrainVertexFormat
in uvec4 offsetData;
// xy: model texture position, zw: normal, see EDhApiTerrainVertexFormat
in uvec4 uvNormal;

out vec4 vPos;
out vec4 vertexColor;
out vec3 vertexWorldPos;
out float vertexYPos;
// block-grid position used to generate texture UVs, fract() of this repeats per block
out vec3 vBlockPos;
flat out uint vNormalIndex;
flat out uint vTextureTileId;
out vec2 vModelUv;
flat out uint vUsesModelUv;

uniform bool uIsWhiteWorld;
uniform bool uHasOffsetData;

uniform mat4 uCombinedMatrix;
uniform vec3 uModelOffset;
uniform float uWorldYOffset;

uniform sampler2D uLightMap;
uniform float uMircoOffset;

uniform float uEarthRadius;

uniform float uFrameMod8;
uniform float uViewWidth;
uniform float uViewHeight;

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
 * Vertex Shader
 * 
 * author: James Seibel
 * author: TomTheFurry
 * author: stduhpf
 * updated: coolGi
 *
 * version: 2025-12-22
 */
void main()
{
    vec3 position = vec3(vPosition.xyz);
    vModelUv = vec2(0.0);
    vUsesModelUv = 0u;
    if (uHasOffsetData)
    {
        position += vec3(offsetData.xyz) / 256.0;
        // model positions are signed
        if ((offsetData.w & 1u) != 0u)
        {
            position -= vec3(greaterThan(vPosition.xyz, uvec3(32767u))) * 65536.0;
        }
        vModelUv = vec2(uvNormal.xy) / 255.0;
        vUsesModelUv = offsetData.w & 1u;
    }
    
    vPos = vec4(position, vPosition.a); // This is so it can be passed to the fragment shader
    
    vBlockPos = position;
    vNormalIndex = irisData.y;
    vTextureTileId = irisData.z | (irisData.w << 8u);
    
    vertexWorldPos = position + uModelOffset;
    
    vertexYPos = position.y + uWorldYOffset;
    
    uint meta = vPosition.a;
    
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
        vertexColor *= color;
    }
    
    // keeps flat models from z-fighting with the block behind them
    if (uHasOffsetData && (offsetData.w & 2u) != 0u)
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
