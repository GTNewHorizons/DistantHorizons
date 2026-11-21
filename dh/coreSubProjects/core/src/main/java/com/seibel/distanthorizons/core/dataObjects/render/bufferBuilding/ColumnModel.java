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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderRegistry;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderSet;
import com.seibel.distanthorizons.core.dataObjects.render.textures.DhBlockModelQuad;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import org.jetbrains.annotations.Nullable;

/** Draws a data point with its block model */
public class ColumnModel
{
	// same position seeds as vanilla's offset calculations
	private static final int SEED_X_MULTIPLIER = 3129871;
	private static final long SEED_Z_MULTIPLIER = 116129781L;
	private static final long SEED_SQUARED_MULTIPLIER = 42317861L;
	private static final long SEED_LINEAR_MULTIPLIER = 11L;
	private static final int SEED_SHIFT = 16;
	
	// each offset uses 4 bits of the seed
	private static final int MODEL_OFFSET_MASK = 0xF;
	private static final float MODEL_OFFSET_STEPS = 15.0F;
	private static final int MODEL_OFFSET_Y_SHIFT = 4;
	private static final int MODEL_OFFSET_Z_SHIFT = 8;
	/** in blocks */
	private static final float HORIZONTAL_MODEL_OFFSET_RANGE = 0.5F;
	
	
	
	/** False for models without a full face on that side */
	static boolean hasFullFace(@Nullable BlockRenderSet set, EDhDirection side)
	{
		return set == null
			|| set.modelQuads.length == 0
			|| set.blockState.isFaceFull(side)
			// water fills waterlogged ones
			|| set.blockState.isWaterlogged();
	}
	
	/** Transparent neighbors never hide quads */
	private static boolean neighborHidesSide(long neighbor, @Nullable BlockRenderSet neighborSet, BlockRenderSet modelSet, EDhDirection side, boolean transparencyEnabled)
	{
		return RenderDataPointUtil.doesDataPointExist(neighbor)
			&& !(transparencyEnabled && RenderDataPointUtil.getAlpha(neighbor) < 255)
			&& (hasFullFace(neighborSet, side.opposite()) || modelSet.isSideHiddenBy(neighborSet, side));
	}
	
	/** Fluid points only store the light at their top */
	private static byte getSkyLightInFluid(byte topSkyLight, int fluidMaxY, int blockY)
	{
		int blocksBelowTop = fluidMaxY - 1 - blockY;
		return (byte) Math.max(topSkyLight - blocksBelowTop, LodUtil.MIN_MC_LIGHT);
	}
	
	/** Fills in whether that side hides this block's quads and the sky light coming from it */
	private static void checkSide(
		EDhDirection side, short blockY, BlockRenderSet modelSet, short pointMinY, short pointMaxY, byte skyLight,
		long topData, long bottomData, @Nullable BlockRenderSet topSet, @Nullable BlockRenderSet bottomSet,
		ColumnRenderView[] adjColumnViews, AdjacentColumnSets adjColumnSets, boolean transparencyEnabled,
		boolean[] hiddenSides, byte[] sideLights)
	{
		int faceIndex = side.faceIndex;
		EDhDirection neighborSide = side.opposite();
		if (side == EDhDirection.UP)
		{
			sideLights[faceIndex] = modelSet.blockState.isWaterlogged() ? getSkyLightInFluid(skyLight, pointMaxY, blockY) : skyLight;
			// stacked blocks in the same point
			hiddenSides[faceIndex] = (blockY + 1 < pointMaxY)
				? modelSet.blockState.isFaceFull(neighborSide) || modelSet.isSideHiddenBy(modelSet, side)
				: RenderDataPointUtil.getYMin(topData) == pointMaxY && neighborHidesSide(topData, topSet, modelSet, side, transparencyEnabled);
			return;
		}
		else if (side == EDhDirection.DOWN)
		{
			sideLights[faceIndex] = RenderDataPointUtil.doesDataPointExist(bottomData) ? RenderDataPointUtil.getLightSky(bottomData) : LodUtil.MIN_MC_LIGHT;
			hiddenSides[faceIndex] = (blockY > pointMinY)
				? modelSet.blockState.isFaceFull(neighborSide) || modelSet.isSideHiddenBy(modelSet, side)
				: RenderDataPointUtil.getYMax(bottomData) == pointMinY && neighborHidesSide(bottomData, bottomSet, modelSet, side, transparencyEnabled);
			return;
		}
		
		// the neighbor column is sorted top down
		ColumnRenderView adjColumn = adjColumnViews[side.compassIndex];
		for (int adjIndex = 0; adjIndex < adjColumn.size; adjIndex++)
		{
			long adjPoint = adjColumn.get(adjIndex);
			if (!RenderDataPointUtil.doesDataPointExist(adjPoint))
			{
				break;
			}
			
			// the air above a lower point
			if (RenderDataPointUtil.getYMax(adjPoint) <= blockY)
			{
				hiddenSides[faceIndex] = false;
				sideLights[faceIndex] = RenderDataPointUtil.getLightSky(adjPoint);
				return;
			}
			
			if (RenderDataPointUtil.getYMin(adjPoint) <= blockY)
			{
				hiddenSides[faceIndex] = neighborHidesSide(adjPoint, adjColumnSets.getDataPointRenderSet(side, adjIndex), modelSet, side, transparencyEnabled);
				byte adjSkyLight = RenderDataPointUtil.getLightSky(adjPoint);
				if (RenderDataPointUtil.isFluid(adjPoint))
				{
					adjSkyLight = getSkyLightInFluid(adjSkyLight, RenderDataPointUtil.getYMax(adjPoint), blockY);
				}
				sideLights[faceIndex] = hiddenSides[faceIndex] ? LodUtil.MIN_MC_LIGHT : adjSkyLight;
				return;
			}
		}
		
		hiddenSides[faceIndex] = false;
		sideLights[faceIndex] = LodUtil.MAX_MC_LIGHT;
	}
	
	/**
	 * Offset seed so grass isn't always centered.
	 * Based on vanilla's Mth.getSeed with the height always 0
	 */
	private static long getModelOffsetSeed(int blockX, int blockZ)
	{
		long seed = (long) (blockX * SEED_X_MULTIPLIER) ^ (long) blockZ * SEED_Z_MULTIPLIER;
		seed = (seed * seed * SEED_SQUARED_MULTIPLIER) + (seed * SEED_LINEAR_MULTIPLIER);
		return seed >> SEED_SHIFT;
	}
	
	/** Based on vanilla's offset for x and z, in blocks */
	private static float getHorizontalModelOffset(long seed, float maxOffset)
	{
		float seedFraction = (seed & MODEL_OFFSET_MASK) / MODEL_OFFSET_STEPS;
		float offset = (seedFraction - 0.5F) * HORIZONTAL_MODEL_OFFSET_RANGE;
		return MathUtil.clamp(-maxOffset, offset, maxOffset);
	}
	
	/** Based on vanilla's offset for y, in blocks */
	private static float getVerticalModelOffset(long seed, float maxOffset)
	{
		float seedFraction = ((seed >> MODEL_OFFSET_Y_SHIFT) & MODEL_OFFSET_MASK) / MODEL_OFFSET_STEPS;
		return (seedFraction - 1.0F) * maxOffset;
	}
	
	public static void addModelQuadsToBuilder(
		LodQuadBuilder quadBuilder, IClientLevelWrapper clientLevelWrapper,
		BlockRenderSet modelSet, boolean useTextures, boolean useQuadColors,
		short blockMinX, short blockMinY, short blockMinZ, short height,
		int worldBlockX, int worldBlockZ,
		int color, byte irisBlockMaterialId, byte skyLight, byte blockLight,
		long topData, long bottomData, @Nullable BlockRenderSet topSet, @Nullable BlockRenderSet bottomSet,
		ColumnRenderView[] adjColumnViews, AdjacentColumnSets adjColumnSets, boolean[] hiddenSides, byte[] sideLights)
	{
		float saturationMultiplier = Config.Client.Advanced.Graphics.Quality.saturationMultiplier.get();
		float brightnessMultiplier = Config.Client.Advanced.Graphics.Quality.brightnessMultiplier.get();
		boolean transparencyEnabled = Config.Client.Advanced.Graphics.Quality.transparency.get() == EDhApiTransparency.COMPLETE;
		short pointMaxY = (short) (blockMinY + height);
		
		// stacked blocks share the same offset
		int unitsPerBlock = DhBlockModelQuad.CORNER_UNITS_PER_BLOCK;
		long offsetSeed = getModelOffsetSeed(worldBlockX, worldBlockZ);
		float maxHorizontalOffset = modelSet.blockState.getMaxHorizontalModelOffset();
		float maxVerticalOffset = modelSet.blockState.getMaxVerticalModelOffset();
		int offsetX256 = Math.round(getHorizontalModelOffset(offsetSeed, maxHorizontalOffset) * unitsPerBlock);
		int offsetY256 = Math.round(getVerticalModelOffset(offsetSeed, maxVerticalOffset) * unitsPerBlock);
		int offsetZ256 = Math.round(getHorizontalModelOffset(offsetSeed >> MODEL_OFFSET_Z_SHIFT, maxHorizontalOffset) * unitsPerBlock);
		
		// the model is drawn per block of the point
		for (short blockY = (short) (pointMaxY - 1); blockY >= blockMinY; blockY--)
		{
			for (EDhDirection side : EDhDirection.ALL)
			{
				checkSide(side, blockY, modelSet, blockMinY, pointMaxY, skyLight,
					topData, bottomData, topSet, bottomSet, adjColumnViews, adjColumnSets, transparencyEnabled,
					hiddenSides, sideLights);
			}
			
			byte brightestLight = LodUtil.MIN_MC_LIGHT;
			for (byte sideLight : sideLights)
			{
				brightestLight = (byte) Math.max(brightestLight, sideLight);
			}
			
			for (int quadIndex = 0; quadIndex < modelSet.modelQuads.length; quadIndex++)
			{
				DhBlockModelQuad modelQuad = modelSet.modelQuads[quadIndex];
				if (modelQuad.cullDirection != null
					&& hiddenSides[modelQuad.cullDirection.faceIndex])
				{
					continue;
				}
				
				int quadColor = color;
				if (useQuadColors
					&& modelSet.modelQuadColors[quadIndex] != BlockRenderSet.USE_LOD_COLOR)
				{
					// the LOD's alpha is kept
					quadColor = ColumnRenderBufferBuilder.applyColorSettings(modelSet.modelQuadColors[quadIndex], saturationMultiplier, brightnessMultiplier);
					quadColor = ColorUtil.setAlpha(quadColor, ColorUtil.getAlpha(color));
				}
				quadColor = ColorUtil.applyShade(quadColor, clientLevelWrapper.getShade(modelQuad.shadeDirection));
				
				short tileId = useTextures ? modelSet.modelQuadTileIds[quadIndex] : BlockRenderRegistry.UNTEXTURED_ID;
				
				// quads inside the block are lit by its brightest side
				byte quadSkyLight = (modelQuad.cullDirection != null) ? sideLights[modelQuad.cullDirection.faceIndex] : brightestLight;
				
				quadBuilder.addModelQuad(modelQuad, blockMinX, blockY, blockMinZ, offsetX256, offsetY256, offsetZ256, quadColor, tileId, irisBlockMaterialId, quadSkyLight, blockLight, modelSet.isFlatNearSide);
			}
		}
	}
	
}
