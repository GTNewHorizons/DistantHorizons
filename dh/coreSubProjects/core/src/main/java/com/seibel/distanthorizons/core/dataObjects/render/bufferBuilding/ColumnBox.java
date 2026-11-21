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

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTransparency;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderRegistry;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderSet;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ColumnBox
{
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	/** 
	 * if the skylight has this value that means
	 * that block position is covered/occluded by an adjacent block/column.
	 */
	private static final byte SKYLIGHT_COVERED = -1;
	
	
	
	//=========//
	// builder //
	//=========//
	//region
	
	public static void addBoxQuadsToBuilder(
		LodQuadBuilder builder, PhantomArrayListCheckout phantomArrayCheckout, IClientLevelWrapper clientLevelWrapper,
		short blockWidth, short yHeight,
		short minX, short minY, short minZ,
		// the raw datapoint color is used for culling purposes and is handled separately to prevent culling issues when the color is modified
		int color, int rawDataPointColor, byte irisBlockMaterialId, byte skyLight, byte blockLight,
		long topData, long bottomData, 
		ColumnRenderView[] adjData, boolean[] isAdjDataSameDetailLevel,
		@Nullable BlockRenderSet topSet, @Nullable BlockRenderSet bottomSet,
		AdjacentColumnSets adjColumnSets,
		short @Nullable [] faceTileIdsByDirectionOrdinal, int @Nullable [] faceColors, short topDrops, @Nullable BlockRenderSet waterloggedModelSet)
	{
		//================//
		// variable setup //
		//================//
		
		short maxX = (short) (minX + blockWidth);
		short maxY = (short) (minY + yHeight);
		short maxZ = (short) (minZ + blockWidth);
		byte skyLightTop = skyLight;
		byte skyLightBottom = RenderDataPointUtil.doesDataPointExist(bottomData) ? RenderDataPointUtil.getLightSky(bottomData) : LodUtil.MIN_MC_LIGHT;
		
		boolean transparencyEnabled = Config.Client.Advanced.Graphics.Quality.transparency.get() == EDhApiTransparency.COMPLETE;
		
		boolean isTransparent = ColorUtil.getAlpha(color) < 255 && transparencyEnabled;
		boolean overVoid = !RenderDataPointUtil.doesDataPointExist(bottomData);
		boolean isTopTransparent = RenderDataPointUtil.getAlpha(topData) < 255 && transparencyEnabled;
		boolean isBottomTransparent = RenderDataPointUtil.getAlpha(bottomData) < 255 && transparencyEnabled;
		
		// defaulting to a value far below what we can normally render means we
		// don't need to have an additional "is cave culling enabled" check
		int caveCullingMaxY = Integer.MIN_VALUE;
		if (Config.Client.Advanced.Graphics.Culling.enableCaveCulling.get())
		{
			caveCullingMaxY = Config.Client.Advanced.Graphics.Culling.caveCullingHeight.get() - clientLevelWrapper.getMinHeight();
		}
		
		
		
		// if there isn't any data below this LOD, make this LOD's color opaque to prevent seeing void through transparent blocks
		// Note: this LOD should still be considered transparent for this method's checks, otherwise rendering bugs may occur
		if (!RenderDataPointUtil.doesDataPointExist(bottomData))
		{
			color = ColorUtil.setAlpha(color, 255);
		}
		
		// never let air render,
		// air is only used for holding sky/block light data
		if (irisBlockMaterialId == EDhApiBlockMaterial.AIR.index)
		{
			return;
		}
		
		
		
		//==========================//
		// add top and bottom faces //
		//==========================//
		
		// top face
		{
			boolean skipTop = RenderDataPointUtil.doesDataPointExist(topData)
					&& (RenderDataPointUtil.getYMin(topData) == maxY)
					&& (!isTopTransparent || (isTransparent && isSameTransparentBlock(topData, rawDataPointColor, irisBlockMaterialId)))
					// lowered tops don't touch the block above
					&& topDrops == 0
					// models without a full bottom don't hide this face
					&& ColumnModel.hasFullFace(topSet, EDhDirection.DOWN);
			
			if (!skipTop)
			{
				int topColor = getFaceColor(color, faceColors, EDhDirection.UP);
				builder.addQuadUp(
					minX, maxY, minZ, 
					blockWidth, 
					ColorUtil.applyShade(topColor, clientLevelWrapper.getShade(EDhDirection.UP)), tryGetTextureId(faceTileIdsByDirectionOrdinal, EDhDirection.UP),
					irisBlockMaterialId, 
					skyLightTop, blockLight, topDrops);
			}
		}
		
		// bottom face 
		{
			boolean skipBottom = RenderDataPointUtil.doesDataPointExist(bottomData)
					&& (RenderDataPointUtil.getYMax(bottomData) == minY)
					&& (!isBottomTransparent || (isTransparent && isSameTransparentBlock(bottomData, rawDataPointColor, irisBlockMaterialId)))
					// lowered fluids don't reach the bottom of the block above
					&& !(RenderDataPointUtil.isFluid(bottomData) && RenderDataPointUtil.getBlockMaterialId(bottomData) != irisBlockMaterialId)
					// models without a full top don't hide this face
					&& ColumnModel.hasFullFace(bottomSet, EDhDirection.UP);
			if (!skipBottom)
			{
				int bottomColor = getFaceColor(color, faceColors, EDhDirection.DOWN);
				builder.addQuadDown(
					minX, minY, minZ, 
					blockWidth, 
					ColorUtil.applyShade(bottomColor, clientLevelWrapper.getShade(EDhDirection.DOWN)), tryGetTextureId(faceTileIdsByDirectionOrdinal, EDhDirection.DOWN), 
					irisBlockMaterialId, 
					skyLightBottom, blockLight);
			}
		}
		
		
		
		//========================================//
		// add North, south, east, and west faces //
		//========================================//
		
		// NORTH face
		{
			int northColor = getFaceColor(color, faceColors, EDhDirection.NORTH);
			ColumnRenderView adjCol = adjData[EDhDirection.NORTH.compassIndex];
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.NORTH.compassIndex];
			// if the adjacent column is null that generally means the adjacent area hasn't been generated yet
			if (adjCol == null)
			{
				// Add an adjacent face if this is opaque face or transparent over the void.
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(
						EDhDirection.NORTH, 
						minX, minY, minZ, 
						blockWidth, yHeight, 
						northColor, tryGetTextureId(faceTileIdsByDirectionOrdinal, EDhDirection.NORTH),
						irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight, topDrops, waterloggedModelSet, false);
				}
			}
			else
			{
				makeAdjVerticalQuad(
					builder, phantomArrayCheckout, clientLevelWrapper,
					adjCol, faceTileIdsByDirectionOrdinal,
					adjColumnSets,
					adjSameDetailLevel, caveCullingMaxY, EDhDirection.NORTH, 
					minX, minY, minZ, blockWidth, yHeight,
					northColor, rawDataPointColor, irisBlockMaterialId, blockLight, topDrops, waterloggedModelSet);
			}
		}
		
		// SOUTH face
		{
			int southColor = getFaceColor(color, faceColors, EDhDirection.SOUTH);
			ColumnRenderView adjCol = adjData[EDhDirection.SOUTH.compassIndex];
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.SOUTH.compassIndex];
			if (adjCol == null)
			{
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(
						EDhDirection.SOUTH, 
						minX, minY, maxZ, 
						blockWidth, yHeight, 
						southColor, tryGetTextureId(faceTileIdsByDirectionOrdinal, EDhDirection.SOUTH),
						irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight, topDrops, waterloggedModelSet, false);
				}
			}
			else
			{
				makeAdjVerticalQuad(
					builder, phantomArrayCheckout, clientLevelWrapper,
					adjCol, faceTileIdsByDirectionOrdinal,
					adjColumnSets,
					adjSameDetailLevel, caveCullingMaxY, EDhDirection.SOUTH,
					minX, minY, maxZ, blockWidth, yHeight,
					southColor, rawDataPointColor, irisBlockMaterialId, blockLight, topDrops, waterloggedModelSet);
			}
		}
		
		// WEST face
		{
			int westColor = getFaceColor(color, faceColors, EDhDirection.WEST);
			ColumnRenderView adjCol = adjData[EDhDirection.WEST.compassIndex];
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.WEST.compassIndex];
			if (adjCol == null)
			{
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(
						EDhDirection.WEST, 
						minX, minY, minZ, 
						blockWidth, yHeight, 
						westColor, tryGetTextureId(faceTileIdsByDirectionOrdinal, EDhDirection.WEST),
						irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight, topDrops, waterloggedModelSet, false);
				}
			}
			else
			{
				makeAdjVerticalQuad(
					builder, phantomArrayCheckout, clientLevelWrapper,
					adjCol, faceTileIdsByDirectionOrdinal,
					adjColumnSets,
					adjSameDetailLevel, caveCullingMaxY, EDhDirection.WEST, 
					minX, minY, minZ, blockWidth, yHeight,
					westColor, rawDataPointColor, irisBlockMaterialId, blockLight, topDrops, waterloggedModelSet);
			}
		}
		
		// EAST face
		{
			int eastColor = getFaceColor(color, faceColors, EDhDirection.EAST);
			ColumnRenderView adjCol = adjData[EDhDirection.EAST.compassIndex];
			boolean adjSameDetailLevel = isAdjDataSameDetailLevel[EDhDirection.EAST.compassIndex];
			if (adjCol == null)
			{
				if (!isTransparent || overVoid)
				{
					builder.addQuadAdj(
						EDhDirection.EAST, 
						maxX, minY, minZ, 
						blockWidth, yHeight, 
						eastColor, tryGetTextureId(faceTileIdsByDirectionOrdinal, EDhDirection.EAST),
						irisBlockMaterialId, LodUtil.MAX_MC_LIGHT, blockLight, topDrops, waterloggedModelSet, false);
				}
			}
			else
			{
				makeAdjVerticalQuad(
					builder, phantomArrayCheckout, clientLevelWrapper,
					adjCol, faceTileIdsByDirectionOrdinal,
					adjColumnSets,
					adjSameDetailLevel, caveCullingMaxY, EDhDirection.EAST, 
					maxX, minY, minZ, blockWidth, yHeight,
					eastColor, rawDataPointColor, irisBlockMaterialId, blockLight, topDrops, waterloggedModelSet);
			}
		}
	}
	
	private static void makeAdjVerticalQuad(
		LodQuadBuilder builder, PhantomArrayListCheckout phantomArrayCheckout, IClientLevelWrapper clientLevelWrapper,
		@NotNull ColumnRenderView adjColumnView, short @Nullable [] faceTileIdsByDirectionOrdinal,
		AdjacentColumnSets adjColumnSets,
		boolean adjacentIsSameDetailLevel, int caveCullingMaxY, EDhDirection direction,
		short x, short yMin, short z, short horizontalBlockWidth, short ySize,
		int color, int dataPointColor, byte irisBlockMaterialId, byte blockLight, short topDrops, @Nullable BlockRenderSet waterloggedModelSet)
	{
		// pooled arrays
		LongArrayList segments = phantomArrayCheckout.getLongArray(0, 0);
		LongArrayList newSegments = phantomArrayCheckout.getLongArray(1, 0);
		
		
		
		//==================//
		// create face with //
		// no adjacent data //
		//==================//
		
		color = ColorUtil.applyShade(color, clientLevelWrapper.getShade(direction));
		
		if (adjColumnView.size == 0
			|| RenderDataPointUtil.hasZeroHeight(adjColumnView.get(0)))
		{
			builder.addQuadAdj(
				direction, 
				x, yMin, z, 
				horizontalBlockWidth, ySize, 
				color, tryGetTextureId(faceTileIdsByDirectionOrdinal, direction),
				irisBlockMaterialId, 
				LodUtil.MAX_MC_LIGHT, blockLight, topDrops, waterloggedModelSet, false);
			return;
		}
		
		
		
		//=================================//
		// determine face visibility/light //
		//=================================//
		
		boolean transparencyEnabled = Config.Client.Advanced.Graphics.Quality.transparency.get() == EDhApiTransparency.COMPLETE;
		boolean inputTransparent = ColorUtil.getAlpha(color) < 255 && transparencyEnabled;
		short yMax = (short) (yMin + ySize);
		
		
		int adjCount = adjColumnView.size;
		
		// Start with the entire range at max light
		segments.add(YSegmentUtil.encode(yMin, yMax, LodUtil.MAX_MC_LIGHT, false));
		
		// Process each adjacent datapoint and split segments as needed
		// (index -1 is a special case for the void datapoint)
		for (int adjIndex = -1; adjIndex < adjCount; adjIndex++)
		{
			long adjPoint = (adjIndex != -1) 
				? adjColumnView.get(adjIndex) 
				: adjColumnView.getVoid();
			short adjMinY = RenderDataPointUtil.getYMin(adjPoint);
			short adjMaxY = RenderDataPointUtil.getYMax(adjPoint);
			
			// skip empty adjacent points
			// or points below this one
			if (!RenderDataPointUtil.doesDataPointExist(adjPoint)
				|| RenderDataPointUtil.hasZeroHeight(adjPoint)
				|| yMax <= adjMinY)
			{
				continue;
			}
			
			
			long adjAbovePoint = (adjIndex > 0) ? adjColumnView.get(adjIndex - 1) : RenderDataPointUtil.EMPTY_DATA;
			
			long adjBelowPoint;
			if (adjIndex != -1)
			{
				adjBelowPoint = (adjIndex + 1 < adjCount) 
					? adjColumnView.get(adjIndex + 1) 
					: adjColumnView.getVoid();
			}
			else
			{
				adjBelowPoint = adjColumnView.getVoid();
			}
			
			// the datapoint below this may be empty due to how datapoint reducing
			// is done. In that case use the void datapoint.
			if (adjBelowPoint == RenderDataPointUtil.EMPTY_DATA)
			{
				adjBelowPoint = adjColumnView.getVoid();
			}
			
			boolean adjOverVoid = !RenderDataPointUtil.doesDataPointExist(adjBelowPoint);
			boolean adjTransparent = 
				!adjOverVoid
				&& RenderDataPointUtil.getAlpha(adjPoint) < 255
				&& transparencyEnabled;
			
			BlockRenderSet adjSet = (adjIndex != -1) ? adjColumnSets.getDataPointRenderSet(direction, adjIndex) : null;
			// models without a full face toward this one can't hide its side,
			// otherwise a slab placed next to stone would leave a hole in the stone
			boolean adjSeeThrough = adjTransparent
				|| !ColumnModel.hasFullFace(adjSet, direction.opposite());
			
			byte adjSkyLight = RenderDataPointUtil.getLightSky(adjPoint);
			byte lightToApply;
			// whether the adjacent data point hides transparent faces
			boolean adjCoversRange = true;
			
			// the same fluid never needs a face between them
			boolean isSameFluid = RenderDataPointUtil.isFluid(adjPoint)
				&& RenderDataPointUtil.getBlockMaterialId(adjPoint) == irisBlockMaterialId;

			// lowered lava doesn't fill the top of its block,
			// the same fluid next to it shares its corner heights so there's no gap between them
			if (!adjTransparent
				&& !isSameFluid
				&& RenderDataPointUtil.isSurfaceLowered(adjPoint, adjAbovePoint))
			{
				adjMaxY--;
			}
			
			if (!adjSeeThrough)
			{
				// Adjacent is opaque
				
				// The following logic is done to provide a little bit of overdraw to
				// prevent holes when low detail LODs are replaced by higher-detail ones
				// when moving.
				// If not done higher quality LODs can cause holes due to not 
				// covering the whole face like the lower detail LODs they replaced,
				// while still culling most LODs that are covered by other blocks.
				
				boolean onBorder = 
					(direction == EDhDirection.WEST && x == 0)
					|| (direction == EDhDirection.NORTH && z == 0)
					|| (direction == EDhDirection.EAST && x == ((horizontalBlockWidth) * (ColumnRenderSource.WIDTH)))
					|| (direction == EDhDirection.SOUTH && z == ((horizontalBlockWidth) * (ColumnRenderSource.WIDTH)));
				
				boolean isLit =
					RenderDataPointUtil.getLightSky(adjPoint) != LodUtil.MIN_MC_LIGHT
					|| RenderDataPointUtil.getLightBlock(adjPoint) != LodUtil.MIN_MC_LIGHT;
				
				// render the face if...
				boolean useAdjLighting =
					// we're on the border... (holes can only happen on LOD borders since faces inside an LOD will always be the same detail level)
					onBorder
					// ...this face has some sort of lighting... (0 light generally means the face is covered by other blocks)
					&& isLit
					// ...and is above the culling height
					&& RenderDataPointUtil.getYMax(adjPoint) >= caveCullingMaxY
					// ...and isn't next to the same fluid (lava has light of 15)
					&& !isSameFluid;
				
				lightToApply = useAdjLighting ? adjSkyLight : SKYLIGHT_COVERED;
			}
			else if (inputTransparent
				&& isSameTransparentBlock(adjPoint, dataPointColor, irisBlockMaterialId))
			{
				// Both are the same transparent block (glass next to glass, water next to water),
				// hide the shared face.
				// The below light can't be used for this since it is full sky light
				// whenever the transparent block doesn't block light (IE glass).
				lightToApply = SKYLIGHT_COVERED;
			}
			else
			{
				// Adjacent is a different transparent block (IE water next to glass),
				// the face is visible through it, use below light
				// models use their own light
				lightToApply = adjTransparent ? RenderDataPointUtil.getLightSky(adjBelowPoint) : adjSkyLight;
				adjCoversRange = false;
			}
			
			
			// Apply light to the range [adjMinY, adjMaxY)
			applyLightToRangeAndPopulateNewSgements(segments, newSegments, adjMinY, adjMaxY, lightToApply, adjCoversRange);
			{
				// swap references so we can use the newly populated segments
				LongArrayList temp = segments;
				segments = newSegments;
				newSegments = temp;
			}
			
			// Fill overhang area [adjMaxY, adjAboveMinY) with adjSkyLight
			short adjAboveMinY = RenderDataPointUtil.getYMin(adjAbovePoint);
			if (adjMaxY < adjAboveMinY)
			{
				applyLightToRangeAndPopulateNewSgements(segments, newSegments, adjMaxY, adjAboveMinY, adjSkyLight, false);
				{
					LongArrayList temp = segments;
					segments = newSegments;
					newSegments = temp;
				}
			}
		}
		
		
		
		//=======================//
		// Create vertical faces //
		// from segments         //
		//=======================//
		
		short grassSideTileId = BlockRenderRegistry.UNTEXTURED_ID;
		short grassOverlayTileId = BlockRenderRegistry.UNTEXTURED_ID;
		int grassOverlayColor = color;
		if (irisBlockMaterialId == EDhApiBlockMaterial.GRASS.index
			&& faceTileIdsByDirectionOrdinal != null
			&& faceTileIdsByDirectionOrdinal.length > BlockRenderRegistry.GRASS_OVERLAY_TILE_INDEX
			&& faceTileIdsByDirectionOrdinal[BlockRenderRegistry.GRASS_SIDE_TILE_INDEX] != BlockRenderRegistry.UNTEXTURED_ID
			// the texture would stretch on wider LODs
			&& horizontalBlockWidth == 1
			&& builder.drawsGrassTopBlock())
		{
			grassSideTileId = faceTileIdsByDirectionOrdinal[BlockRenderRegistry.GRASS_SIDE_TILE_INDEX];
			grassOverlayTileId = faceTileIdsByDirectionOrdinal[BlockRenderRegistry.GRASS_OVERLAY_TILE_INDEX];
			
			// the rest of the side is dirt
			color = ColorUtil.applyShade(clientLevelWrapper.getDirtBlockColor(), clientLevelWrapper.getShade(direction));
			irisBlockMaterialId = EDhApiBlockMaterial.DIRT.index;
		}
		boolean hasGrassOverlay = (grassOverlayTileId != BlockRenderRegistry.UNTEXTURED_ID);
		// cut bases use the dirt color
		int grassSideColor = hasGrassOverlay ? color : ColorUtil.applyShade(ColorUtil.WHITE, clientLevelWrapper.getShade(direction));
		
		for (int i = 0; i < segments.size(); i++)
		{
			long segment = segments.getLong(i);
			int quadTopY = YSegmentUtil.getEndY(segment);
			
			// the top block shows the grass side
			if (grassSideTileId != BlockRenderRegistry.UNTEXTURED_ID
				&& quadTopY == yMax)
			{
				tryAddVerticalFaceWithSkyLightToBuilder(
					builder, direction,
					x, z, horizontalBlockWidth,
					grassSideColor, grassSideTileId,
					irisBlockMaterialId, blockLight,
					YSegmentUtil.getSkyLight(segment), YSegmentUtil.isCovered(segment),
					inputTransparent,
					yMax, yMax - 1,
					(short) 0, waterloggedModelSet, hasGrassOverlay
				);
				if (hasGrassOverlay)
				{
					tryAddVerticalFaceWithSkyLightToBuilder(
						builder, direction,
						x, z, horizontalBlockWidth,
						grassOverlayColor, grassOverlayTileId,
						irisBlockMaterialId, blockLight,
						YSegmentUtil.getSkyLight(segment), YSegmentUtil.isCovered(segment),
						inputTransparent,
						yMax, yMax - 1,
						(short) 0, waterloggedModelSet, true
					);
				}
				quadTopY--;
			}
			
			tryAddVerticalFaceWithSkyLightToBuilder(
				builder, direction,
				x, z, horizontalBlockWidth,
				color, tryGetTextureId(faceTileIdsByDirectionOrdinal, direction),
				irisBlockMaterialId, blockLight,
				YSegmentUtil.getSkyLight(segment), YSegmentUtil.isCovered(segment),
				inputTransparent, 
				quadTopY, YSegmentUtil.getStartY(segment),
				(YSegmentUtil.getEndY(segment) == yMax) ? topDrops : 0, waterloggedModelSet, false
			);
		}
	}
	
	/**
	 * Apply the new light value over the given y range,
	 * splitting segments as needed
	 * and putting the new segments into "newSegments"
	 * <p>
	 * source: claude.ai
	 */
	private static void applyLightToRangeAndPopulateNewSgements(
			LongArrayList segments, LongArrayList newSegments, 
			short rangeStart, short rangeEnd, 
			byte newLight, boolean coversRange)
	{
		// clear the pooled array that the new segments will go into
		newSegments.clear();
		
		for (int i = 0; i < segments.size(); i++)
		{
			long seg = segments.getLong(i);
			short endY = YSegmentUtil.getEndY(seg);
			short startY = YSegmentUtil.getStartY(seg);
			byte skyLight = YSegmentUtil.getSkyLight(seg);
			boolean covered = YSegmentUtil.isCovered(seg);
			
			// No overlap
			if (endY <= rangeStart 
				|| startY >= rangeEnd)
			{
				newSegments.add(seg);
				continue;
			}
			
			// Partial or complete overlap - need to split
			
			// Part before the range
			if (startY < rangeStart)
			{
				newSegments.add(YSegmentUtil.encode(startY, rangeStart, skyLight, covered));
			}
			
			// Overlapping part - take minimum light
			short overlapStart = (short)Math.max(startY, rangeStart);
			short overlapEnd = (short)Math.min(endY, rangeEnd);
			byte minLight = (byte) Math.min(newLight, skyLight);
			newSegments.add(YSegmentUtil.encode(overlapStart, overlapEnd, minLight, covered || coversRange));
			
			// Part after the range
			if (endY > rangeEnd)
			{
				newSegments.add(YSegmentUtil.encode(rangeEnd, endY, skyLight, covered));
			}
		}
	}
	
	private static void tryAddVerticalFaceWithSkyLightToBuilder(
		LodQuadBuilder builder, EDhDirection direction,
		short x, short z, short horizontalWidth,
		int color, short faceTextureId, 
		byte irisBlockMaterialId, 
		byte blockLight, byte lastSkyLight, boolean covered,
		boolean inputTransparent, 
		int quadTopY, int quadBottomY, short topDrops, @Nullable BlockRenderSet waterloggedModelSet, boolean cutOut
		)
	{
		// invalid positions will have a negative skylight
		if (lastSkyLight < 0)
		{
			return;
		}
		
		// Don't add transparent vertical faces
		// unless the adjacent position is empty.
		// This is done to prevent walls between water blocks in the ocean.
		if (inputTransparent
			&& covered)
		{
			return;
		}
		
		// don't add negative/empty height faces
		short height = (short) (quadTopY - quadBottomY);
		if (height <= 0)
		{
			return;
		}
		
		builder.addQuadAdj(
			direction, 
			x, (short) quadBottomY, z, 
			horizontalWidth, height, 
			color, faceTextureId,
			irisBlockMaterialId, lastSkyLight, blockLight, topDrops, waterloggedModelSet, cutOut);
	}
	
	//endregion
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	private static short tryGetTextureId(short @Nullable [] faceTileIdsByDirectionOrdinal, EDhDirection direction)
	{
		if (faceTileIdsByDirectionOrdinal == null)
		{
			return BlockRenderRegistry.UNTEXTURED_ID;
		}
		
		return faceTileIdsByDirectionOrdinal[direction.faceIndex];
	}
	
	/** @return the face's own color if it has one, otherwise the LOD's color */
	private static int getFaceColor(int lodColor, int @Nullable [] faceColors, EDhDirection direction)
	{
		if (faceColors == null
			|| faceColors[direction.faceIndex] == BlockRenderSet.USE_LOD_COLOR)
		{
			return lodColor;
		}
		
		// the LOD's alpha is kept so transparency doesn't change
		return ColorUtil.setAlpha(faceColors[direction.faceIndex], ColorUtil.getAlpha(lodColor));
	}
	
	/**
	 * Returns true if the adjacent data point is the same transparent block,
	 * in which case the face between them shouldn't be rendered. <br><br>
	 *
	 * The material alone isn't enough since most transparent blocks
	 * share the same material (IE all stained glass colors are UNKNOWN),
	 * so the color is compared as well. <br>
	 * Water is the exception, its biome tint can differ between neighbors
	 * and comparing the color would create walls inside oceans.
	 */
	private static boolean isSameTransparentBlock(long adjData, int dataPointColor, byte irisBlockMaterialId)
	{
		return RenderDataPointUtil.getBlockMaterialId(adjData) == irisBlockMaterialId
			&& (irisBlockMaterialId == EDhApiBlockMaterial.WATER.index
				|| RenderDataPointUtil.getColor(adjData) == dataPointColor);
	}
	
	//endregion
	
	
	
	//================//
	// helper classes //
	//================//
	//region
	
	/** 
	 * encodes height/light data into a long
	 * to reduce object allocations.
	 */
	private static class YSegmentUtil
	{
		private static final int HEIGHT_WIDTH = Short.SIZE;
		private static final int SKY_LIGHT_WIDTH = Byte.SIZE;
		
		private static final int START_Y_MASK = (int) Math.pow(2, HEIGHT_WIDTH) - 1;
		private static final int END_Y_MASK = (int) Math.pow(2, HEIGHT_WIDTH) - 1;
		private static final int SKY_LIGHT_MASK = (int) Math.pow(2, SKY_LIGHT_WIDTH) - 1;
		
		private static final int START_Y_OFFSET = 0;
		private static final int END_Y_OFFSET = START_Y_OFFSET + HEIGHT_WIDTH;
		private static final int SKY_LIGHT_OFFSET = END_Y_OFFSET + HEIGHT_WIDTH;
		private static final int COVERED_OFFSET = SKY_LIGHT_OFFSET + SKY_LIGHT_WIDTH;
		
		
		
		public static long encode(short startY, short endY, byte skyLight, boolean covered)
		{
			long data = 0L;
			data |= (long) (startY & START_Y_MASK) << START_Y_OFFSET;
			data |= (long) (endY & END_Y_MASK) << END_Y_OFFSET;
			data |= (long) (skyLight & SKY_LIGHT_MASK) << SKY_LIGHT_OFFSET;
			data |= (covered ? 1L : 0L) << COVERED_OFFSET;
			return data;
		}
		
		public static short getStartY(long data) { return (short) ((data >> START_Y_OFFSET) & START_Y_MASK); }
		public static short getEndY(long data) { return (short) ((data >> END_Y_OFFSET) & END_Y_MASK); }
		public static byte getSkyLight(long data) { return (byte) ((data >> SKY_LIGHT_OFFSET) & SKY_LIGHT_MASK); }
		public static boolean isCovered(long data) { return ((data >> COVERED_OFFSET) & 1L) != 0; }
		
	}
	
	//endregion
	
	
	
}
