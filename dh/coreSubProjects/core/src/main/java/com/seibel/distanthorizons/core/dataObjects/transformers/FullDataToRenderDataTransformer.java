/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.dataObjects.transformers;

import com.seibel.distanthorizons.api.enums.config.EDhApiBlocksToAvoid;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.AbstractDhApiRenderDefinition;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderRegistry;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPosMutable;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.*;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.seibel.distanthorizons.core.logging.DhLogger;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.function.LongPredicate;

/**
 * Handles converting {@link FullDataSourceV2}'s to {@link ColumnRenderSource}.
 */
public class FullDataToRenderDataTransformer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	
	private static final LongOpenHashSet BROKEN_POS_SET = new LongOpenHashSet();
	private static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("Data Transformer");
	
	private static HashSet<IBlockStateWrapper> snowLayerBlockStates = null;
	
	
	
	//==============================//
	// public transformer interface //
	//==============================//
	//region
	
	@Nullable
	public static ColumnRenderSource transformFullDataToRenderSource(
			@Nullable FullDataSourceV2 fullDataSource, @Nullable IClientLevelWrapper levelWrapper, AbstractDhApiRenderDefinition renderDefinition)
	{
		if (fullDataSource == null)
		{
			return null;
		}
		else if (levelWrapper == null)
		{
			// if the client is no longer loaded in the world, render sources cannot be created 
			return null;
		}
		
		
		try
		{
			return transformCompleteFullDataToColumnData(levelWrapper, renderDefinition, fullDataSource);
		}
		catch (InterruptedException e)
		{
			return null;
		}
	}
	
	//endregion
	
	
	
	//==============//
	// transformers //
	//==============//
	//region
	
	/**
	 * Creates a LodNode for a chunk in the given world.
	 *
	 * @throws IllegalArgumentException thrown if either the chunk or world is null.
	 * @throws InterruptedException Can be caused by interrupting the thread upstream.
	 * Generally thrown if the method is running after the client leaves the current world.
	 */
	private static ColumnRenderSource transformCompleteFullDataToColumnData(
		IClientLevelWrapper levelWrapper, AbstractDhApiRenderDefinition renderDefinition, FullDataSourceV2 fullDataSource) throws InterruptedException
	{
		final long pos = fullDataSource.getPos();
		final byte dataDetail = fullDataSource.getDataDetailLevel();
		
		final int maxVertSliceCount = Config.Client.Advanced.Graphics.Quality.verticalQuality.get().calculateMaxNumberOfVerticalSlicesAtDetailLevel(fullDataSource.getDataDetailLevel());
		
		
		
		final ColumnRenderSource columnSource = ColumnRenderSource.createEmpty(pos, maxVertSliceCount, levelWrapper.getMinHeight(), renderDefinition);
		if (fullDataSource.isEmpty)
		{
			return columnSource;
		}
		
		columnSource.markNotEmpty();
		int baseX = DhSectionPos.getMinCornerBlockX(pos);
		int baseZ = DhSectionPos.getMinCornerBlockZ(pos);
		
		try(ColumnRenderView columnArrayView = ColumnRenderView.getPooled();
			PhantomArrayListCheckout phantomCheckout = ARRAY_LIST_POOL.checkoutLongArrays(2);
			ColumnRenderView tempExpandingColumnView = ColumnRenderView.getPooled();
			RenderDataPointReducingList reducingList = new RenderDataPointReducingList())
		{
			DhBlockPosMutable mutableBlockPos = new DhBlockPosMutable();
			for (int x = 0; x < FullDataSourceV2.WIDTH; x++)
			{
				for (int z = 0; z < FullDataSourceV2.WIDTH; z++)
				{
					columnSource.populateColumnView(columnArrayView, x, z);
					LongArrayList dataColumn = fullDataSource.getColumnAtRelPos(x, z);
					
					// old LODs stretched models down, they're read as surface until regenerated
					if (fullDataSource.columnGenerationSteps.getByte(FullDataSourceV2.relativePosToIndex(x, z)) < EDhApiWorldGenerationStep.FEATURES.value)
					{
						columnSource.disableModelsInColumn(x, z);
					}
					
					updateOrReplaceRenderDataViewColumnWithFullDataColumn(
						levelWrapper, renderDefinition, fullDataSource,
						// bit shift is to account for LODs with a detail level greater than 0 so the block pos is correct
						baseX + BitShiftUtil.pow(x, dataDetail), baseZ + BitShiftUtil.pow(z, dataDetail),
						columnArrayView, dataColumn,
						columnSource, x, z,
						// pooled references so we don't need to re-allocate/get them 4000 times per render source
						phantomCheckout, tempExpandingColumnView, reducingList, mutableBlockPos);
				}
			}
		}
		
		return columnSource;
	}
	
	/** The full data block at the given height */
	@Nullable
	private static IBlockStateWrapper getBlockAtY(FullDataSourceV2 fullDataSource, LongArrayList fullDataColumn, int blockY)
	{
		// the column is sorted top down
		int low = 0;
		int high = fullDataColumn.size() - 1;
		while (low <= high)
		{
			int middle = (low + high) >>> 1;
			long fullData = fullDataColumn.getLong(middle);
			int bottomY = FullDataPointUtil.getBottomY(fullData);
			if (blockY < bottomY)
			{
				// below this point
				low = middle + 1;
			}
			else if (blockY >= bottomY + FullDataPointUtil.getHeight(fullData))
			{
				// above this point
				high = middle - 1;
			}
			else
			{
				try
				{
					return fullDataSource.mapping.getBlockStateWrapper(FullDataPointUtil.getId(fullData));
				}
				catch (IndexOutOfBoundsException ignore)
				{
					// broken mappings are logged during color resolution
					return null;
				}
			}
		}
		return null;
	}
	
	/** The brightest sky light from the see-through blocks next to this block */
	private static int getSideSkyLight(FullDataSourceV2 fullDataSource, IBlockStateWrapper block, int blockX, int blockZ, int blockY)
	{
		int relX = blockX & (FullDataSourceV2.WIDTH - 1);
		int relZ = blockZ & (FullDataSourceV2.WIDTH - 1);
		
		int brightestLight = LodUtil.MIN_MC_LIGHT;
		for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS)
		{
			// full faces block the light
			if (block.isFaceFull(direction))
			{
				continue;
			}
			
			LongArrayList adjColumn = fullDataSource.tryGetColumnAtRelPos(relX + direction.normal.x, relZ + direction.normal.z);
			IBlockStateWrapper adjBlock = (adjColumn != null) ? getBlockAtY(fullDataSource, adjColumn, blockY) : null;
			if (adjBlock == null
				|| adjBlock.getOpacity() != LodUtil.BLOCK_FULLY_TRANSPARENT)
			{
				continue;
			}
			
			// the column is sorted top down
			for (int i = 0; i < adjColumn.size(); i++)
			{
				long adjData = adjColumn.getLong(i);
				if (FullDataPointUtil.getBottomY(adjData) <= blockY)
				{
					brightestLight = Math.max(brightestLight, FullDataPointUtil.getSkyLight(adjData) - 1);
					break;
				}
			}
		}
		return brightestLight;
	}
	
	/** Updates the given {@link ColumnRenderView} to match the incoming Full data {@link LongArrayList} */
	private static void updateOrReplaceRenderDataViewColumnWithFullDataColumn(
		IClientLevelWrapper levelWrapper, AbstractDhApiRenderDefinition renderDefinition,
		FullDataSourceV2 fullDataSource, int blockX, int blockZ, 
		ColumnRenderView columnArrayView, 
		LongArrayList fullDataColumn,
		// only needed for set ids and fluid amounts, see applyTopBlockData
		ColumnRenderSource columnSource, int sourceRelX, int sourceRelZ,
		// pooled references
		PhantomArrayListCheckout phantomCheckout, ColumnRenderView tempExpandingColumnView, 
		RenderDataPointReducingList reducingList, DhBlockPosMutable mutableBlockPos)
	{
		// we can't do anything if the full data is missing or empty
		if (fullDataColumn == null 
			|| fullDataColumn.size() == 0)
		{
			return;
		}
		
		boolean modelsInThisColumn = BlockRenderRegistry.modelsDrawnAtDetailLevel(fullDataSource.getDataDetailLevel(), renderDefinition)
			&& columnSource.columnDrawsModels(sourceRelX, sourceRelZ);
		
		int fullDataLength = fullDataColumn.size();
		if (fullDataLength <= columnArrayView.maxVerticalSliceCount)
		{
			// Directly use the arrayView since it fits.
			setRenderColumnView(levelWrapper, modelsInThisColumn, fullDataSource, blockX, blockZ, columnArrayView, fullDataColumn, mutableBlockPos);
		}
		else
		{
			LongArrayList dataArrayList = phantomCheckout.getLongArray(0, fullDataLength);
			LongArrayList voidDataArrayList = phantomCheckout.getLongArray(1, fullDataLength);
			
			// expand the ColumnArrayView to fit the new larger max vertical size
			tempExpandingColumnView.populate(
				dataArrayList, voidDataArrayList,
				fullDataLength, 0, 0, fullDataLength);
			setRenderColumnView(levelWrapper, modelsInThisColumn, fullDataSource, blockX, blockZ, tempExpandingColumnView, fullDataColumn, mutableBlockPos);
			
			LongPredicate pointIsModel = (renderData) -> false;
			if (modelsInThisColumn)
			{
				pointIsModel = (renderData) ->
				{
					IBlockStateWrapper block = getBlockAtY(fullDataSource, fullDataColumn, RenderDataPointUtil.getYMax(renderData) - 1);
					return block != null && BlockRenderRegistry.usesModel(block);
				};
			}
			columnArrayView.changeVerticalSizeFrom(tempExpandingColumnView, reducingList, pointIsModel);
		}
		
		applyTopBlockData(levelWrapper, modelsInThisColumn, fullDataSource, columnArrayView, fullDataColumn, columnSource, sourceRelX, sourceRelZ);
	}
	
	/**
	 * Determines the set id and fluid amount for each finished render data point
	 * by finding the full data block at the data point's top. <br>
	 * The top block is used since merged data points keep their top block's color,
	 * keeping the texture consistent with the color it multiplies. <br><br>
	 *
	 * This runs after any vertical size reduction so it doesn't matter
	 * how the data points were merged along the way.
	 */
	private static void applyTopBlockData(
			IClientLevelWrapper levelWrapper, boolean modelsInThisColumn,
			FullDataSourceV2 fullDataSource, ColumnRenderView renderColumnData, LongArrayList fullDataColumn,
			ColumnRenderSource columnSource, int sourceRelX, int sourceRelZ)
	{
		for (int renderIndex = 0; renderIndex < renderColumnData.size; renderIndex++)
		{
			long renderData = renderColumnData.get(renderIndex);
			if (!RenderDataPointUtil.doesDataPointExist(renderData)
				|| RenderDataPointUtil.hasZeroHeight(renderData))
			{
				break;
			}
			
			int topBlockY = RenderDataPointUtil.getYMax(renderData) - 1;
			byte materialId = RenderDataPointUtil.getBlockMaterialId(renderData);
			
			// find the full data point containing the render data point's top block,
			// the full data column is sorted top down
			short setId = BlockRenderRegistry.FLAT_SET_ID;
			// liquids default to full, everything else has no liquid
			int fluidAmount = RenderDataPointUtil.isFluid(renderData) ? LodUtil.MAX_FLUID_AMOUNT : LodUtil.NO_FLUID_AMOUNT;
			boolean waterlogged = false;
			for (int fullIndex = 0; fullIndex < fullDataColumn.size(); fullIndex++)
			{
				long fullData = fullDataColumn.getLong(fullIndex);
				int bottomY = FullDataPointUtil.getBottomY(fullData);
				if (bottomY > topBlockY)
				{
					continue;
				}
				
				if (topBlockY < bottomY + FullDataPointUtil.getHeight(fullData))
				{
					try
					{
						IBlockStateWrapper block = fullDataSource.mapping.getBlockStateWrapper(FullDataPointUtil.getId(fullData));
						
						// If this render data is snow, but the block ID doesn't match,
						// try getting the block above it.
						// This is a hacky fix for snow on LOD borders rendering with the
						// underlying grass/dirt block, instead of as snow.
						if (materialId == EDhApiBlockMaterial.SNOW.index
							&& block.getMaterialId() != materialId
							&& fullIndex > 0)
						{
							// Note: this is a hack.
							// Since the Render data and set ids aren't in the same data array it's difficult to map
							// them together, but this guess works well enough for now.
							
							fullData = fullDataColumn.getLong(fullIndex - 1);
							IBlockStateWrapper newBlock = fullDataSource.mapping.getBlockStateWrapper(FullDataPointUtil.getId(fullData));
							// don't use air since that'll remove the texture
							if (!newBlock.isAir())
							{
								block = newBlock;
							}
						}
						
						// blocks that are replaced with water (like surface seagrass) should look like water,
						// this is the same check setRenderColumnView uses to replace them
						if (isReplacedWithWater(levelWrapper, modelsInThisColumn, fullDataSource, fullDataColumn, fullIndex))
						{
							block = WRAPPER_FACTORY.getWaterBlockStateWrapper(levelWrapper);
						}
						
						// This now also runs for fluid changes, so we gotta check if set ids are stored
						if (columnSource.hasSetIds())
						{
							setId = BlockRenderRegistry.INSTANCE.getOrRegisterBlockStateSetId(block, levelWrapper);
						}
						
						// Blocks that are replaced with water (like lily pads) don't have fluid, those stay full
						if (block.getFluidAmount() != LodUtil.NO_FLUID_AMOUNT)
						{
							fluidAmount = block.getFluidAmount();
						}
						// the water around models lowers like normal water
						waterlogged = block.isWaterlogged() && !isDrawnAsModel(block, modelsInThisColumn);
					}
					catch (IndexOutOfBoundsException ignore)
					{
						// broken mappings are logged during color resolution, render flat here
					}
				}
				
				// when no data point contains the top block (IE merged across an air gap)
				// the data point renders flat
				break;
			}
			
			columnSource.setDataPointAttributes(sourceRelX, sourceRelZ, renderIndex, setId, fluidAmount, waterlogged);
		}
	}
	
	/** Surface blocks like seagrass are drawn as water if there's air or a lily pad directly above them */
	private static boolean isReplacedWithWater(
			IClientLevelWrapper levelWrapper, boolean modelsInThisColumn, FullDataSourceV2 fullDataSource, 
			LongArrayList fullDataColumn, int fullDataIndex)
	{
		try
		{
			IBlockStateWrapper block = fullDataSource.mapping.getBlockStateWrapper(FullDataPointUtil.getId(fullDataColumn.getLong(fullDataIndex)));
			// models show the real block
			if (isDrawnAsModel(block, modelsInThisColumn))
			{
				return false;
			}
			
			if (!WRAPPER_FACTORY.getWaterSubsurfaceReplacementBlocks(levelWrapper).contains(block))
			{
				return false;
			}
			
			if (fullDataIndex == 0)
			{
				return true;
			}
			
			IBlockStateWrapper blockAbove = fullDataSource.mapping.getBlockStateWrapper(FullDataPointUtil.getId(fullDataColumn.getLong(fullDataIndex - 1)));
			return blockAbove.isAir()
				|| WRAPPER_FACTORY.getWaterSurfaceReplacementBlocks(levelWrapper).contains(blockAbove);
		}
		catch (IndexOutOfBoundsException ignore)
		{
			// broken mappings are logged during color resolution
			return false;
		}
	}
	
	/** Models are only drawn at block detail */
	private static boolean isDrawnAsModel(IBlockStateWrapper block, boolean modelsInThisColumn)
	{ return modelsInThisColumn && BlockRenderRegistry.usesModel(block); }
	
	/** Models without a full bottom */
	private static boolean canSeeBlockBelow(IBlockStateWrapper block, boolean modelsInThisColumn)
	{ return isDrawnAsModel(block, modelsInThisColumn) && !block.isFaceFull(EDhDirection.DOWN); }
	
	/** Models only merge with the same block */
	private static boolean canMergeBlocks(IBlockStateWrapper block, IBlockStateWrapper lastBlock, boolean modelsInThisColumn)
	{
		if (block == lastBlock)
		{
			return true;
		}
		return !isDrawnAsModel(block, modelsInThisColumn)
			&& !isDrawnAsModel(lastBlock, modelsInThisColumn);
	}
	
	private static void setRenderColumnView(
			IClientLevelWrapper levelWrapper, boolean modelsInThisColumn, FullDataSourceV2 fullDataSource,
			int blockX, int blockZ,
			ColumnRenderView renderColumnData, LongArrayList fullColumnData, DhBlockPosMutable mutableBlockPos)
	{
		//===============//
		// config values //
		//===============//
		
		boolean ignoreNonCollidingBlocks = (Config.Client.Advanced.Graphics.Culling.blocksToIgnore.get() == EDhApiBlocksToAvoid.NON_COLLIDING);
		boolean colorBelowWithAvoidedBlocks = Config.Client.Advanced.Graphics.Culling.tintWithAvoidedBlocks.get();
		
		final ObjectOpenHashSet<IBlockStateWrapper> blockStatesToIgnore = WRAPPER_FACTORY.getRendererIgnoredBlocks(levelWrapper);
		final ObjectOpenHashSet<IBlockStateWrapper> caveBlockStatesToIgnore = WRAPPER_FACTORY.getRendererIgnoredCaveBlocks(levelWrapper);
		final ObjectOpenHashSet<IBlockStateWrapper> waterSurfaceReplacementBlocks = WRAPPER_FACTORY.getWaterSurfaceReplacementBlocks(levelWrapper);
		final IBlockStateWrapper water = WRAPPER_FACTORY.getWaterBlockStateWrapper(levelWrapper);
		
		
		// build snow block cache if needed
		if (snowLayerBlockStates == null)
		{
			snowLayerBlockStates = new HashSet<>();
			
			// 1.13+ uses "minecraft:snow" for the layer block,
			// 1.12.2 and older use "minecraft:snow_layer" ("minecraft:snow" is only the full block).
			String[] blockNames = { "minecraft:snow", "minecraft:snow_layer" };
			
			// ignore snow layers 1-3, everything above should be considered a full block
			for (String blockName : blockNames)
			{
				for (int layers = 1; layers <= 3; layers++)
				{
					IBlockStateWrapper wrapper = WRAPPER_FACTORY.deserializeBlockStateWrapperOrGetDefault(blockName + "_STATE_{layers:" + layers + "}", levelWrapper);
					
					// a failed lookup returns a default state with no "layers" property
					// (the full snow block on 1.12.2), which must not be treated as a layer
					if (wrapper.getSerialString().contains("layers"))
					{
						snowLayerBlockStates.add(wrapper);
					}
				}
			}
		}
		
		int caveCullingMaxY = Config.Client.Advanced.Graphics.Culling.caveCullingHeight.get() - levelWrapper.getMinHeight();
		boolean caveCullingEnabled = 
			Config.Client.Advanced.Graphics.Culling.enableCaveCulling.get()
			&& (
				// dimensions with a ceiling will be all caves so we don't want cave culling
				!levelWrapper.hasCeiling()
				// the end has a lot of overhangs with 0 lighting above the void, which look broken with
				// the current cave culling logic (this could probably be improved, but just skipping it works best for now)
				&& !levelWrapper.getDimensionType().isTheEnd()
			);
		
		boolean isColumnVoid = true;
		
		int lastColor = 0;
		int lastBottom = -10_000;
		IBlockStateWrapper lastBlock = null;
		boolean airAfterLastBlock = false;
		
		// there are several instances where we'll want
		// to copy the top datapoint down and override the one below it
		int colorToApplyToNextBlock = -1;
		int skylightToApplyToNextBlock = -1;
		int blocklightToApplyToNextBlock = -1;
		
		int renderDataIndex = 0;
		
		
		
		//==================================//
		// convert full data to render data //
		//==================================//
		
		FullDataPointIdMap fullDataMapping = fullDataSource.mapping;
		
		mutableBlockPos.setX(blockX);
		mutableBlockPos.setZ(blockZ);
		
		// goes from the top down
		for (int fullDataIndex = 0; fullDataIndex < fullColumnData.size(); fullDataIndex++)
		{
			long fullData = fullColumnData.getLong(fullDataIndex);
			
			int bottomY = FullDataPointUtil.getBottomY(fullData);
			int blockHeight = FullDataPointUtil.getHeight(fullData);
			int topY = bottomY + blockHeight;
			int id = FullDataPointUtil.getId(fullData);
			int blockLight = FullDataPointUtil.getBlockLight(fullData);
			int skyLight = FullDataPointUtil.getSkyLight(fullData);
			
			// using the top Y provides better sampling for biomes and fixes an issue where
			// cave biomes are used instead of the surface biome
			mutableBlockPos.setY(topY - 1 + levelWrapper.getMinHeight());
			
			IBiomeWrapper biome;
			IBlockStateWrapper block;
			try
			{
				biome = fullDataMapping.getBiomeWrapper(id);
				block = fullDataMapping.getBlockStateWrapper(id);
			}
			catch (IndexOutOfBoundsException e)
			{
				if (!BROKEN_POS_SET.contains(fullDataMapping.getPos()))
				{
					BROKEN_POS_SET.add(fullDataMapping.getPos());
					String levelId = levelWrapper.getDhIdentifier();
					LOGGER.warn("Unable to get data point with id ["+id+"] " +
							"(Max possible ID: ["+fullDataMapping.getMaxValidId()+"]) " +
							"for pos ["+fullDataMapping.getPos()+"] in level ["+levelId+"]. " +
							"Error: ["+e.getMessage()+"]. " +
							"Further errors for this position won't be logged.");
				}
				
				// don't render broken data
				continue;
			}
			
			// can be un-commented for testing floating islands
			// it's recommended to place a single netherrack block as a marker 
			// and a glowstone block to trigger an LOD update
			//if (DhSectionPos.encode((byte)6, -8, 2) == fullDataSource.getPos()
			//	//&& block.getMaterialId() == EDhApiBlockMaterial.NETHER_STONE.index
			//	&& block.getSerialString().contains("glowstone")
			//)
			//{
			//	int k = 0;
			//}
			
			
			
			//==================//
			// special case:    // 
			// air gap leak fix //
			//==================//
			
			if (block.isAir())
			{
				// we don't want to apply the last block over an air gap,
				// doing so can cause weird lighting behavior for cave berries
				// when hanging above water
				colorToApplyToNextBlock = -1;
				skylightToApplyToNextBlock = -1;
				blocklightToApplyToNextBlock = -1;
				airAfterLastBlock = true;
			}
			
			
			
			//=================//
			// special case:   //
			// water under ice //
			//=================//
			
			// is this block water and the one above it ice?
			if (block.getMaterialId() == EDhApiBlockMaterial.WATER.index
				&& lastBlock != null
				&& lastBlock.isIceBlock()
				&& !airAfterLastBlock)
			{
				// Replace the water with ice to prevent rendering water
				// under the frozen lakes (which looks bad).
				block = lastBlock;
			}
			
			
			
			//====================//
			// ignored block and  //
			// cave culling check //
			//====================//
			
			// surface blocks replaced with water (seagrass) still tint the water they become
			int replacedBlockColor = 0;
			if (isReplacedWithWater(levelWrapper, modelsInThisColumn, fullDataSource, fullColumnData, fullDataIndex))
			{
				replacedBlockColor = levelWrapper.getBlockColor(mutableBlockPos, biome, fullDataSource, block);
				block = water;
			}
			
			boolean ignoreBlock = blockStatesToIgnore.contains(block);
			boolean caveBlock = caveBlockStatesToIgnore.contains(block);
			if (caveBlock
				// caves also ignore transparent/non-solid blocks (IE grass and plants) without each being defined
				|| !block.isSolid()
				|| block.isLiquid()
				|| block.getOpacity() < LodUtil.BLOCK_FULLY_OPAQUE
				|| isDrawnAsModel(block, modelsInThisColumn))
			{
				if (caveCullingEnabled
					// assume this data point is underground if it has no sky-light
					&& skyLight == LodUtil.MIN_MC_LIGHT
					// ignore caves above a certain height to prevent floating islands from having walls underneath them
					&& topY < caveCullingMaxY
					// cave culling shouldn't happen when at the top of the world
					&& renderDataIndex != 0 && fullDataIndex != 0
					// cave culling can't happen when at the bottom of the world
					&& (fullDataIndex + 1) < fullColumnData.size())
				{
					// we need to get the next sky/block lights because
					// the air block here will always have a light of 0/0 due to only the top of the LOD's light being saved.
					long nextFullData = fullColumnData.getLong(fullDataIndex + 1);
					int nextSkyLight = FullDataPointUtil.getSkyLight(nextFullData);
					
					if (nextSkyLight == LodUtil.MIN_MC_LIGHT
						&& ColorUtil.getAlpha(lastColor) == 255
						&& !isDrawnAsModel(lastBlock, modelsInThisColumn))
					{
						// replace the previous block with new bottom
						long columnData = renderColumnData.get(renderDataIndex - 1);
						columnData = RenderDataPointUtil.setYMin(columnData, bottomY);
						renderColumnData.set(renderDataIndex - 1, columnData);
					}
					
					continue;
				}
				
				
				if (ignoreBlock)
				{
					// if this is the bottom datapoint
					// save it so lighting appears properly for floating
					// islands over the void
					if (fullDataIndex == fullColumnData.size() - 1)
					{
						setVoidAir(
							renderColumnData, renderDataIndex,
							bottomY, blockHeight,
							skyLight, blockLight
						);
					}
					
					// this is a merged block and a cave block, so it shouldn't be rendered
					continue;
				}
			}
			else if (ignoreBlock)
			{
				// this is an ignored block, but shouldn't be merged like a cave block
				
				// applying this sky light to the next block should prevent black spots for opaque covering blocks 
				skylightToApplyToNextBlock = skyLight;
				continue;
			}
			
			
			
			//=======================//
			// non-solid block check //
			//=======================//
			
			// models are drawn instead of skipped
			boolean drawnAsModel = isDrawnAsModel(block, modelsInThisColumn);
			
			boolean ignoreNonSolidBlock =
				ignoreNonCollidingBlocks
				&& !block.isSolid()
				&& !block.isLiquid()
				&& block.getOpacity() != LodUtil.BLOCK_FULLY_OPAQUE
				&& !drawnAsModel;
			
			// handle height reduction
			boolean isSnowLayer = snowLayerBlockStates.contains(block) && !drawnAsModel;
			boolean isWaterSurfaceReplacement = waterSurfaceReplacementBlocks.contains(block) && !drawnAsModel;
			if (isSnowLayer || isWaterSurfaceReplacement)
			{
				int surfaceBlockColor = 0;
				if (isWaterSurfaceReplacement)
				{
					surfaceBlockColor = levelWrapper.getBlockColor(mutableBlockPos, biome, fullDataSource, block);
					// replace the block with water
					block = WRAPPER_FACTORY.getWaterBlockStateWrapper(levelWrapper);
				}
				
				// sometimes a datapoint will be multiple blocks tall,
				// in that case we just want to drop the top by 1
				blockHeight -= 1;
				if (blockHeight == 0)
				{
					// this block was entirely removed, just color the block below it
					ignoreNonSolidBlock = true;
					
					
					if (isSnowLayer)
					{
						// snow is a special case where it should always tint the block
						// below it, if not done grass will appear as gray
						int snowColor = levelWrapper.getBlockColor(mutableBlockPos, biome, fullDataSource, block);
						colorToApplyToNextBlock = ColorUtil.setAlpha(snowColor, 255);
					}
					else //if (isWaterSurfaceReplacement)
					{
						// tint the water below with the surface block (lilypads), but keep it see-through
						int waterColor = levelWrapper.getBlockColor(mutableBlockPos, biome, fullDataSource, block);
						colorToApplyToNextBlock = tintWaterColor(waterColor, surfaceBlockColor);
					}
				}
			}
			
			if (ignoreNonSolidBlock)
			{
				int ignoredColor = levelWrapper.getBlockColor(mutableBlockPos, biome, fullDataSource, block);
				int ignoredAlpha = ColorUtil.getAlpha(ignoredColor);
				
				// water surface replacements already tinted the water below
				if (colorBelowWithAvoidedBlocks && !isWaterSurfaceReplacement)
				{
					// don't transfer the color when alpha is 0
					// this prevents issues if grass is transparent
					if (ignoredAlpha != 0)
					{
						colorToApplyToNextBlock = ColorUtil.setAlpha(ignoredColor, 255);
					}
				}
				
				// Don't transfer the lighting when alpha is 0
				// (the block below should have its own lighting).
				if (ignoredAlpha != 0)
				{
					// Lighting is transferred even when "colorBelowWithAvoidedBlocks"
					// is false, since otherwise the blocks underneath may have a light value of "0"
					// which makes things look darker than they should.
					// This can specifically manifest as grid lines on LOD borders
					// (not entire sure why grid lines on LOD borders, maybe it has to do with the fact that those LODs aren't occluded?).
					skylightToApplyToNextBlock = skyLight;
					blocklightToApplyToNextBlock = blockLight;
				}
				
				
				// if this is the bottom datapoint
				// save it so lighting appears properly for floating
				// islands over the void
				if (fullDataIndex == fullColumnData.size() - 1)
				{
					setVoidAir(
						renderColumnData, renderDataIndex,
						bottomY, blockHeight,
						skyLight, blockLight
					);
				}
				
				// skip this non-colliding block
				continue;
			}
			
			
			int color;
			// use the override values if necessary
			if (colorToApplyToNextBlock == -1)
			{
				// use this block's color
				// waterlogged models are drawn inside water
				IBlockStateWrapper colorBlock = (drawnAsModel && block.isWaterlogged()) ? water : block;
				color = levelWrapper.getBlockColor(mutableBlockPos, biome, fullDataSource, colorBlock);
				if (replacedBlockColor != 0)
				{
					color = tintWaterColor(color, replacedBlockColor);
				}
				
				// use the skylight override if present
				if (skylightToApplyToNextBlock != -1)
				{
					skyLight = skylightToApplyToNextBlock;
					// remove the override so we don't accidentally override the next datapoint
					skylightToApplyToNextBlock = -1;
				}
				
				if (blocklightToApplyToNextBlock != -1)
				{
					blockLight = blocklightToApplyToNextBlock;
					blocklightToApplyToNextBlock = -1;
				}
			}
			else
			{
				// use the previous block's info as available
				
				color = colorToApplyToNextBlock;
				colorToApplyToNextBlock = -1;
				
				// use the skylight override if present
				if (skylightToApplyToNextBlock != -1)
				{
					skyLight = skylightToApplyToNextBlock;
					// remove the override so we don't accidentally override the next datapoint
					skylightToApplyToNextBlock = -1;
				}
				
				if (blocklightToApplyToNextBlock != -1)
				{
					blockLight = blocklightToApplyToNextBlock;
					blocklightToApplyToNextBlock = -1;
				}
			}
			
			// models aren't lit from the side by the sky light pass
			if (skyLight == LodUtil.MIN_MC_LIGHT
				&& isDrawnAsModel(block, modelsInThisColumn))
			{
				skyLight = getSideSkyLight(fullDataSource, block, blockX, blockZ, topY - 1);
			}
			
			
			
			//=============================//
			// merge same-colored adjacent //
			//=============================//
			
			// check if they share a top-bottom face and if they have same color
			if (color == lastColor 
				&& bottomY + blockHeight == lastBottom  
				&& renderDataIndex > 0
				&& canMergeBlocks(block, lastBlock, modelsInThisColumn))
			{
				//replace the previous block with new bottom
				long columnData = renderColumnData.get(renderDataIndex - 1);
				columnData = RenderDataPointUtil.setYMin(columnData, bottomY);
				renderColumnData.set(renderDataIndex - 1, columnData);
			}
			else
			{
				// add the block
				isColumnVoid = false;
				long columnData = RenderDataPointUtil.createDataPoint(bottomY + blockHeight, bottomY, color, skyLight, blockLight, block.getMaterialId());
				renderColumnData.set(renderDataIndex, columnData);
				renderDataIndex++;
			}
			lastBottom = bottomY;
			lastColor = color;
			lastBlock = block;
			airAfterLastBlock = false;
			
			if (canSeeBlockBelow(block, modelsInThisColumn))
			{
				skylightToApplyToNextBlock = skyLight;
				blocklightToApplyToNextBlock = blockLight;
			}
		}
		
		
		if (isColumnVoid)
		{
			renderColumnData.set(0, RenderDataPointUtil.EMPTY_DATA);
		}
	}
	
	/** Mixes the block's color into the water's color, the water keeps its see-through alpha */
	private static int tintWaterColor(int waterColor, int blockColor)
	{
		if (ColorUtil.getAlpha(blockColor) == 0)
		{
			return waterColor;
		}
		
		return ColorUtil.argbToInt(ColorUtil.getAlpha(waterColor),
			(ColorUtil.getRed(blockColor) + ColorUtil.getRed(waterColor)) / 2,
			(ColorUtil.getGreen(blockColor) + ColorUtil.getGreen(waterColor)) / 2,
			(ColorUtil.getBlue(blockColor) + ColorUtil.getBlue(waterColor)) / 2);
	}
	
	/**
	 * Necessary to handle adjacent sky/block lighting
	 * over the void.
	 */
	private static void setVoidAir(
		ColumnRenderView renderColumnData, int renderDataIndex,
		int bottomY, int blockHeight,
		int skyLight, int blockLight)
	{
		// get the last render point (if present)
		long lastDatapoint = RenderDataPointUtil.EMPTY_DATA;
		if (renderDataIndex > 0)
		{
			lastDatapoint = renderColumnData.get(renderDataIndex - 1);
		}
		
		// Get the max y position from the previous render data point's bottom Y.
		// This is necessary for lighting to appear correctly for adjacent air data points over the void.
		int maxY = (bottomY + blockHeight);
		if (lastDatapoint != RenderDataPointUtil.EMPTY_DATA)
		{
			maxY = RenderDataPointUtil.getYMin(lastDatapoint);
		}
		
		long columnData = RenderDataPointUtil.createDataPoint(
			maxY, 0, // min Y since this datapoint should go all the way down to the void
			ColorUtil.INVISIBLE, skyLight, blockLight,
			// use air's material since this datapoint shouldn't be rendered, 
			// it's only present to handle sky/block lighting
			EDhApiBlockMaterial.AIR.index);
		renderColumnData.setVoid(columnData);
	}
	
	//endregion
	
	
	
}
