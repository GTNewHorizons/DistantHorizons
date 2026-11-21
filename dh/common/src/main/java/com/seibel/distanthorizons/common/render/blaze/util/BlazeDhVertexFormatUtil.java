package com.seibel.distanthorizons.common.render.blaze.util;

#if MC_VER <= MC_1_21_10
public class BlazeDhVertexFormatUtil {}

#else

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderRegistry;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import org.jetbrains.annotations.NotNull;

#if MC_VER <= MC_26_1_2
import com.mojang.blaze3d.vertex.VertexFormatElement;
#elif MC_VER <= MC_26_2_0
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.GpuFormat;
#else
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import com.mojang.renderpearl.api.GpuFormat;
#endif


/**
 * The following site includes information about supported Vulkan formats:
 * https://vulkan.gpuinfo.org/listbufferformats.php?age=recent&apiversion=1.2
 * 
 * @see LodQuadBuilder
 */
public class BlazeDhVertexFormatUtil
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	@NotNull public static final VertexFormatElement SCREEN_POS;
	@NotNull public static final VertexFormatElement RGBA_FLOAT_COLOR;
	
	/** 
	 * first 3 unsigned shorts (XYZ) are position, 
	 * last short (W) is for metadata (light, micro-offset, etc). <br>
	 * Using a 4 element format is necessary for GPUs that only support 16 byte wide formats.
	 * (Vs the 12 bytes that 3 shorts alone would use.)
	 */
	@NotNull public static final VertexFormatElement SHORT_XYZ_POS_AND_W_META;
	@NotNull public static final VertexFormatElement BYTE_PAD;
	/** offset inside the block in 256ths and flags, see EDhApiTerrainVertexFormat */
	@NotNull public static final VertexFormatElement OFFSET_DATA;
	@NotNull public static final VertexFormatElement RGBA_UBYTE_COLOR;
	@NotNull public static final VertexFormatElement IRIS_MATERIAL;
	/** iris material, normal index, {@link BlockRenderRegistry} tile id (2 bytes, 0 = flat color) */
	@NotNull public static final VertexFormatElement MATERIAL_NORMAL_TILE;
	/** texture u, v and normal (2 bytes), reserved for future block model work, see EDhApiTerrainVertexFormat */
	@NotNull public static final VertexFormatElement UV_NORMAL;
	
	@NotNull public static final VertexFormatElement FLOAT_XYZ_POS;
	
	
	
	static
	{
		LOGGER.debug("Attempting to register ["+VertexFormatElement.class.getSimpleName()+"]...");
		
		try
		{
			#if MC_VER <= MC_1_21_11
			SCREEN_POS = VertexFormatElement.register(/*id*/22, /*index*/0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION, /*count*/ 2);
			RGBA_FLOAT_COLOR = VertexFormatElement.register(/*id*/23, /*index*/0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.COLOR, /*count*/ 4);
			
			SHORT_XYZ_POS_AND_W_META = VertexFormatElement.register(/*id*/24, /*index*/0, VertexFormatElement.Type.USHORT, VertexFormatElement.Usage.POSITION, /*count*/ 4);
			BYTE_PAD = VertexFormatElement.register(/*id*/25, /*index*/0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.GENERIC, /*count*/ 1);
			
			OFFSET_DATA = VertexFormatElement.register(/*id*/26, /*index*/0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.GENERIC, /*count*/ 4);
			RGBA_UBYTE_COLOR = VertexFormatElement.register(/*id*/27, /*index*/0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.COLOR, /*count*/ 4);
			IRIS_MATERIAL = VertexFormatElement.register(/*id*/28, /*index*/0, VertexFormatElement.Type.BYTE, VertexFormatElement.Usage.GENERIC, /*count*/ 1);
			MATERIAL_NORMAL_TILE = VertexFormatElement.register(/*id*/29, /*index*/0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.GENERIC, /*count*/ 4);
			
			FLOAT_XYZ_POS = VertexFormatElement.register(/*id*/30, /*index*/0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION, /*count*/ 3);
			
			UV_NORMAL = VertexFormatElement.register(/*id*/31, /*index*/0, VertexFormatElement.Type.UBYTE, VertexFormatElement.Usage.GENERIC, /*count*/ 4);
			#elif MC_VER <= MC_26_1_2
			SCREEN_POS = VertexFormatElement.register(/*id*/22, /*index*/0, VertexFormatElement.Type.FLOAT, false, /*count*/ 2);
			RGBA_FLOAT_COLOR = VertexFormatElement.register(/*id*/23, /*index*/0, VertexFormatElement.Type.FLOAT, false, /*count*/ 4);
			
			SHORT_XYZ_POS_AND_W_META = VertexFormatElement.register(/*id*/24, /*index*/0, VertexFormatElement.Type.USHORT, false, /*count*/ 4);
			BYTE_PAD = VertexFormatElement.register(/*id*/25, /*index*/0, VertexFormatElement.Type.BYTE, false, /*count*/ 1);
			
			OFFSET_DATA = VertexFormatElement.register(/*id*/26, /*index*/0, VertexFormatElement.Type.UBYTE, false, /*count*/ 4);
			RGBA_UBYTE_COLOR = VertexFormatElement.register(/*id*/27, /*index*/0, VertexFormatElement.Type.UBYTE, true, /*count*/ 4);
			IRIS_MATERIAL = VertexFormatElement.register(/*id*/28, /*index*/0, VertexFormatElement.Type.BYTE, false, /*count*/ 1);
			MATERIAL_NORMAL_TILE = VertexFormatElement.register(/*id*/29, /*index*/0, VertexFormatElement.Type.UBYTE, false, /*count*/ 4);
			
			FLOAT_XYZ_POS = VertexFormatElement.register(/*id*/30, /*index*/0, VertexFormatElement.Type.FLOAT, false, /*count*/ 3);
			
			UV_NORMAL = VertexFormatElement.register(/*id*/31, /*index*/0, VertexFormatElement.Type.UBYTE, false, /*count*/ 4);
			#elif MC_VER <= MC_26_1_2
			
			SCREEN_POS = VertexFormatElement.register(/*id*/22, /*index*/0, GpuFormat.RG32_FLOAT); // 2 floats
			RGBA_FLOAT_COLOR = VertexFormatElement.register(/*id*/23, /*index*/0, GpuFormat.RGBA32_FLOAT); // 4 floats
			
			SHORT_XYZ_POS_AND_W_META = VertexFormatElement.register(/*id*/24, /*index*/0, GpuFormat.RGBA16_UINT); // 4 ushorts
			BYTE_PAD = VertexFormatElement.register(/*id*/25, /*index*/0, GpuFormat.R8_UINT); // 1 byte
			
			OFFSET_DATA = VertexFormatElement.register(/*id*/26, /*index*/0, GpuFormat.RGBA8_UINT); // 4 ubytes
			RGBA_UBYTE_COLOR = VertexFormatElement.register(/*id*/27, /*index*/0, GpuFormat.RGBA8_UNORM); // 4 ubytes
			IRIS_MATERIAL = VertexFormatElement.register(/*id*/28, /*index*/0, GpuFormat.R8_UINT); // 1 byte
			MATERIAL_NORMAL_TILE = VertexFormatElement.register(/*id*/29, /*index*/0, GpuFormat.RGBA8_UINT); // 4 ubytes
			
			FLOAT_XYZ_POS = VertexFormatElement.register(/*id*/30, /*index*/0, GpuFormat.RGB32_FLOAT); // 3 floats
			
			UV_NORMAL = VertexFormatElement.register(/*id*/31, /*index*/0, GpuFormat.RGBA8_UINT); // 4 ubytes
			
			#else
			
			SCREEN_POS = new VertexFormatElement("Screen Pos", Float.BYTES * 2, GpuFormat.RG32_FLOAT);
			RGBA_FLOAT_COLOR = new VertexFormatElement("RGBA Float Color", Float.BYTES * 4, GpuFormat.RGBA32_FLOAT);
			
			SHORT_XYZ_POS_AND_W_META = new VertexFormatElement("Short XYZ Pos and W Meta", Short.BYTES * 4, GpuFormat.RGBA16_UINT);
			BYTE_PAD = new VertexFormatElement("Byte Pad", 1, GpuFormat.R8_UINT);
			
			OFFSET_DATA = new VertexFormatElement("DH Offset Data", 4, GpuFormat.RGBA8_UINT);
			RGBA_UBYTE_COLOR = new VertexFormatElement("Rgba Ubyte Color", 4, GpuFormat.RGBA8_UNORM);
			IRIS_MATERIAL = new VertexFormatElement("Iris Material", 1, GpuFormat.R8_UINT);
			MATERIAL_NORMAL_TILE = new VertexFormatElement("DH Material Normal Tile", 4, GpuFormat.RGBA8_UINT);
			
			FLOAT_XYZ_POS = new VertexFormatElement("Float XYZ Pos", Float.BYTES * 3, GpuFormat.RGB32_FLOAT);
			
			UV_NORMAL = new VertexFormatElement("DH UV Normal", 4, GpuFormat.RGBA8_UINT);
			#endif
		}
		catch (Exception e)
		{
			String message = "Unable to register one or more ["+VertexFormatElement.class.getSimpleName()+"] this is likely caused by another mod registering their own custom ["+VertexFormatElement.class.getSimpleName()+"]'s. This should be fixed in the next major Minecraft version.";
			
			IMinecraftClientWrapper mc = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
			mc.crashMinecraft(message, new Exception(message, e));
			
			// here to make the compiler happy, the process should shut down before this
			throw new RuntimeException(e);
		}
		
		LOGGER.debug("Successfully registered ["+VertexFormatElement.class.getSimpleName()+"].");
	}
	
	
	
	
	
}
#endif