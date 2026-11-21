package com.seibel.distanthorizons.common.wrappers.block;

#if MC_VER > MC_1_7_10
import com.seibel.distanthorizons.common.wrappers.McObjectConverter;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import net.minecraft.client.Minecraft;
#if MC_VER <= MC_1_12_2
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.*;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.color.BlockColors;
import net.minecraft.util.math.BlockPos;
#else
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
#endif
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

#if MC_VER >= MC_1_19_2
import net.minecraft.util.RandomSource;
#else
#endif

#if MC_VER < MC_1_21_5
import net.minecraft.client.renderer.block.model.BakedQuad;
#if MC_VER > MC_1_12_2
import net.minecraft.client.resources.model.BakedModel;
#endif
#elif MC_VER <= MC_1_21_11
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BakedQuad;
#else
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
#endif

public class QuadWrapper
{
	#if MC_VER <= MC_1_12_2
	private static final Minecraft MC = Minecraft.getMinecraft();
	#else
	private static final Minecraft MC = Minecraft.getInstance();
	#endif
	
	#if MC_VER < MC_1_19_2
	private static final Random RANDOM = new Random(0);
	#else
	/** Note: this object isn't thread safe and must be put in a lock */
	private static final RandomSource RANDOM = RandomSource.create();
	#endif
	
	/**
	 * Methods using MC's "RandomSource" object aren't thread safe <br>
	 * so we need to put locks around that logic. <br>
	 * specifically:
	 * <code>
	 * getBlockModel(blockState).getQuads(blockState, direction, RANDOM)
	 * </code>
	 */
	private static final ReentrantLock GETTER_LOCK = new ReentrantLock();
	
	
	
	@Nullable
	#if MC_VER <= MC_1_12_2
	public static List<BakedQuad> getUnculledQuads(IBlockState blockState) throws Exception
	#else
	public static List<BakedQuad> getUnculledQuads(BlockState blockState) throws Exception
	#endif
	{ return getQuadsForDirection(blockState, null); }
	
	/**
	 * throws Exception is to document that rarely MC will throw errors if this method
	 * is called on the wrong block (even though in that case it should just return null).
	 */
	@Nullable
	#if MC_VER <= MC_1_12_2
	public static List<BakedQuad> getQuadsForDirection(IBlockState blockState, @Nullable EDhDirection dhDirection) throws Exception
	#else
	public static List<BakedQuad> getQuadsForDirection(BlockState blockState, @Nullable EDhDirection dhDirection) throws Exception
	#endif
	{ return getQuadsForDirection(blockState, dhDirection, null); }
	
	/** Quads whose model turns ambient occlusion off are also added to quadsWithoutAmbientOcclusion */
	@Nullable
	#if MC_VER <= MC_1_12_2
	public static List<BakedQuad> getQuadsForDirection(IBlockState blockState, @Nullable EDhDirection dhDirection, @Nullable Set<BakedQuad> quadsWithoutAmbientOcclusion) throws Exception
	#else
	public static List<BakedQuad> getQuadsForDirection(BlockState blockState, @Nullable EDhDirection dhDirection, @Nullable Set<BakedQuad> quadsWithoutAmbientOcclusion) throws Exception
	#endif
	{
		GETTER_LOCK.lock();
		try
		{
			#if MC_VER <= MC_1_12_2
			@Nullable EnumFacing direction = McObjectConverter.convert(dhDirection);
			#else
			@Nullable Direction direction = McObjectConverter.convert(dhDirection);
			#endif
			
			
			
			//===============//
			// quad handling //
			//===============//
			//region
			
			List<BakedQuad> quads;
			
			#if MC_VER <= MC_1_12_2
			try {
				BlockModelShapes modelShapes = MC.getBlockRendererDispatcher().getBlockModelShapes();
				IBakedModel model = modelShapes.getModelForState(blockState);
				// tile entity blocks only have the missing model
				if (model == modelShapes.getModelManager().getMissingModel())
				{
					quads = Collections.emptyList();
				}
				else
				{
					quads = model.getQuads(blockState, direction, RANDOM.nextLong());
					if (quadsWithoutAmbientOcclusion != null && !model.isAmbientOcclusion(blockState))
					{
						quadsWithoutAmbientOcclusion.addAll(quads);
					}
				}
			}
			catch (Exception e)
			{
				quads = Collections.emptyList();
			}
			#elif MC_VER < MC_1_21_5
			BakedModel model = MC.getModelManager().getBlockModelShaper().getBlockModel(blockState);
			quads = model.getQuads(blockState, direction, RANDOM);
			if (quadsWithoutAmbientOcclusion != null && !model.useAmbientOcclusion())
			{
				quadsWithoutAmbientOcclusion.addAll(quads);
			}
			#elif MC_VER <= MC_1_21_11
			List<BlockModelPart> blockModelPartList = MC.getModelManager().getBlockModelShaper().
				getBlockModel(blockState).collectParts(RANDOM);
			
			quads = new ArrayList<>();
			if (blockModelPartList != null)
			{
				for (int i = 0; i < blockModelPartList.size(); i++)
				{
					// if direction is null this will return the unculled quads
					List<BakedQuad> partQuads = blockModelPartList.get(i).getQuads(direction);
					quads.addAll(partQuads);
					if (quadsWithoutAmbientOcclusion != null && !blockModelPartList.get(i).useAmbientOcclusion())
					{
						quadsWithoutAmbientOcclusion.addAll(partQuads);
					}
				}
			}
			#else
			List<BlockStateModelPart> blockModelPartList = new ArrayList<>();
			MC.getModelManager()
				.getBlockStateModelSet()
				.get(blockState)
				.collectParts(RANDOM, blockModelPartList);
			
			quads = new ArrayList<>();
			for (int i = 0; i < blockModelPartList.size(); i++)
			{
				// if direction is null this will return the unculled quads
				List<BakedQuad> partQuads = blockModelPartList.get(i).getQuads(direction);
				quads.addAll(partQuads);
				if (quadsWithoutAmbientOcclusion != null && !blockModelPartList.get(i).useAmbientOcclusion())
				{
					quadsWithoutAmbientOcclusion.addAll(partQuads);
				}
			}
			#endif
			
			//endregion
			
			return quads;
		}
		finally
		{
			GETTER_LOCK.unlock();
		}
	}
	
	
}
#endif
