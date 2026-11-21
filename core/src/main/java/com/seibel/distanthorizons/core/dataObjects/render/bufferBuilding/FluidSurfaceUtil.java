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
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import org.jetbrains.annotations.Nullable;

/** Fluid surface heights, based on vanilla's LiquidBlockRenderer */
public class FluidSurfaceUtil
{
	/** denotes how many distinct heights we can store */
	public static final int DROP_UNITS_PER_BLOCK = 16;
	
	/** vanilla's fluid height is amount / 9, so a full source sits at 8/9 of a block */
	private static final float FLUID_HEIGHT_DIVISOR = 9.0f;
	
	// each corner's drop is stored in 4 bits of a short
	private static final int NORTH_WEST_SHIFT = 0;
	private static final int NORTH_EAST_SHIFT = 4;
	private static final int SOUTH_WEST_SHIFT = 8;
	private static final int SOUTH_EAST_SHIFT = 12;
	
	private static final int CORNER_DROP_MASK = 0xF;
	
	
	
	/** aka Empty or default data */
	public static short createNoDrop() { return 0; }
	
	/** Returns how far each corner is lowered, 4 bits per corner. 0 if the same fluid is above it */
	public static short createCornerDrop(
		ColumnRenderView column, int dataIndex,
		ColumnRenderSource renderSource, ColumnRenderSource[] adjRenderSources, ColumnRenderSource[][] diagonalRenderSources, int relX, int relZ, ColumnRenderView neighborView)
	{
		long data = column.get(dataIndex);
		byte materialId = RenderDataPointUtil.getBlockMaterialId(data);
		int y = RenderDataPointUtil.getYMax(data) - 1;

		if (renderSource.isDataPointWaterlogged(relX, relZ, dataIndex))
		{
			return 0;
		}

		float height = getHeight(column, renderSource, relX, relZ, materialId, y);
		if (height >= 1.0f)
		{
			return 0;
		}

		float north = getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX, relZ - 1, materialId, y);
		float south = getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX, relZ + 1, materialId, y);
		float west = getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX - 1, relZ, materialId, y);
		float east = getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX + 1, relZ, materialId, y);

		float northWest = getCornerHeight(height, west, north, getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX - 1, relZ - 1, materialId, y));
		float northEast = getCornerHeight(height, east, north, getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX + 1, relZ - 1, materialId, y));
		float southWest = getCornerHeight(height, west, south, getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX - 1, relZ + 1, materialId, y));
		float southEast = getCornerHeight(height, east, south, getNeighborHeight(renderSource, adjRenderSources, diagonalRenderSources, neighborView, relX + 1, relZ + 1, materialId, y));

		int cornerDrops = 0;
		cornerDrops |= toDrop(northWest) << NORTH_WEST_SHIFT;
		cornerDrops |= toDrop(northEast) << NORTH_EAST_SHIFT;
		cornerDrops |= toDrop(southWest) << SOUTH_WEST_SHIFT;
		cornerDrops |= toDrop(southEast) << SOUTH_EAST_SHIFT;
		return (short) cornerDrops;
	}
	
	public static int getCornerDrop(short cornerDrops, boolean isMaxX, boolean isMaxZ)
	{
		int shift;
		if (isMaxZ)
		{
			shift = isMaxX ? SOUTH_EAST_SHIFT : SOUTH_WEST_SHIFT;
		}
		else
		{
			shift = isMaxX ? NORTH_EAST_SHIFT : NORTH_WEST_SHIFT;
		}
		
		return (cornerDrops >> shift) & CORNER_DROP_MASK;
	}
	
	/** How far the top is lowered at that spot, in blocks, blended between the corners */
	public static float getDropAt(short cornerDrops, float x, float z)
	{
		int northWest = getCornerDrop(cornerDrops, false, false);
		int northEast = getCornerDrop(cornerDrops, true, false);
		int southWest = getCornerDrop(cornerDrops, false, true);
		int southEast = getCornerDrop(cornerDrops, true, true);
		
		float north = northWest + ((northEast - northWest) * x);
		float south = southWest + ((southEast - southWest) * x);
		return (north + ((south - north) * z)) / DROP_UNITS_PER_BLOCK;
	}
	
	public static boolean isFlat(short cornerDrops)
	{
		int northWest = getCornerDrop(cornerDrops, false, false);
		return getCornerDrop(cornerDrops, true, false) == northWest
			&& getCornerDrop(cornerDrops, false, true) == northWest
			&& getCornerDrop(cornerDrops, true, true) == northWest;
	}
	
	/** Returns the direction the surface slopes down towards, null if it's flat */
	@Nullable
	public static EDhDirection getFlowDirection(short cornerDrops)
	{
		int northWest = getCornerDrop(cornerDrops, false, false);
		int northEast = getCornerDrop(cornerDrops, true, false);
		int southWest = getCornerDrop(cornerDrops, false, true);
		int southEast = getCornerDrop(cornerDrops, true, true);
		
		// positive when the east or south side is lower
		int eastSlope = (northEast + southEast) - (northWest + southWest);
		int southSlope = (southWest + southEast) - (northWest + northEast);
		if (eastSlope == 0 && southSlope == 0)
		{
			return null;
		}
		
		if (Math.abs(eastSlope) > Math.abs(southSlope))
		{
			return (eastSlope > 0) ? EDhDirection.EAST : EDhDirection.WEST;
		}
		else
		{
			return (southSlope > 0) ? EDhDirection.SOUTH : EDhDirection.NORTH;
		}
	}
	
	
	
	//================//
	// helper methods //
	//================//
	//region
	
	/** 1 if the same fluid is above, the fluid's height, 0 for air, -1 for solid */
	private static float getHeight(ColumnRenderView column, ColumnRenderSource source, int posX, int posZ, byte materialId, int y)
	{
		if (column.size == 0)
		{
			return -1.0f;
		}

		for (int i = 0; i < column.size; i++)
		{
			long data = column.get(i);
			if (!RenderDataPointUtil.doesDataPointExist(data) || RenderDataPointUtil.hasZeroHeight(data))
			{
				break;
			}
			if (RenderDataPointUtil.getYMin(data) > y)
			{
				continue;
			}
			// y is in the air gap above this data point
			if (RenderDataPointUtil.getYMax(data) <= y)
			{
				return 0.0f;
			}

			byte dataMaterialId = RenderDataPointUtil.getBlockMaterialId(data);
			if (dataMaterialId != materialId)
			{
				return (dataMaterialId == EDhApiBlockMaterial.AIR.index) ? 0.0f : -1.0f;
			}
			if (hasSameFluidAbove(column, i, y))
			{
				return 1.0f;
			}

			// like vanilla, the water in waterlogged blocks counts as a source
			return source.getDataPointFluidAmount(posX, posZ, i) / FLUID_HEIGHT_DIVISOR;
		}
		return 0.0f;
	}
	
	private static boolean hasSameFluidAbove(ColumnRenderView column, int index, int y)
	{
		long data = column.get(index);
		if (RenderDataPointUtil.getYMax(data) > y + 1)
		{
			return true;
		}
		if (index == 0)
		{
			return false;
		}

		return RenderDataPointUtil.isSameFluidOnTop(data, column.get(index - 1));
	}
	
	/** @return a value between 0 and 1.0 (inclusive), 1.0 being full block height and 0 being  */
	private static float getCornerHeight(float height, float sideX, float sideZ, float diagonal)
	{
		if (sideX >= 1.0f || sideZ >= 1.0f)
		{
			return 1.0f;
		}

		float weightedSum = 0.0f;
		float totalWeight = 0.0f;
		// vanilla only uses the diagonal if one of the sides has fluid
		if (sideX > 0.0f || sideZ > 0.0f)
		{
			if (diagonal >= 1.0f)
			{
				return 1.0f;
			}
			weightedSum += getWeight(diagonal) * diagonal;
			totalWeight += getWeight(diagonal);
		}

		weightedSum += (getWeight(height) * height) + (getWeight(sideX) * sideX) + (getWeight(sideZ) * sideZ);
		totalWeight += getWeight(height) + getWeight(sideX) + getWeight(sideZ);
		return weightedSum / totalWeight;
	}
	private static float getWeight(float height) { return (height >= 0.8f) ? 10.0f : ((height >= 0.0f) ? 1.0f : 0.0f); }
	
	private static float getNeighborHeight(
		ColumnRenderSource renderSource, ColumnRenderSource[] adjRenderSources, ColumnRenderSource[][] diagonalRenderSources, ColumnRenderView neighborView,
		int relX, int relZ, byte materialId, int y)
	{
		boolean isOutsideX = relX < 0 || relX >= ColumnRenderSource.WIDTH;
		boolean isOutsideZ = relZ < 0 || relZ >= ColumnRenderSource.WIDTH;

		ColumnRenderSource source = renderSource;
		if (isOutsideX && isOutsideZ)
		{
			// diagonalRenderSources is stored as [west, east][north, south]
			source = diagonalRenderSources[(relX < 0) ? 0 : 1][(relZ < 0) ? 0 : 1];
		}
		else if (isOutsideX)
		{
			source = adjRenderSources[(relX < 0) ? EDhDirection.WEST.compassIndex : EDhDirection.EAST.compassIndex];
		}
		else if (isOutsideZ)
		{
			source = adjRenderSources[(relZ < 0) ? EDhDirection.NORTH.compassIndex : EDhDirection.SOUTH.compassIndex];
		}

		// neighboring sections at a different detail level don't line up with this one
		if (source == null || source.getDataDetailLevel() != renderSource.getDataDetailLevel())
		{
			return -1.0f;
		}

		int sourceX = Math.floorMod(relX, ColumnRenderSource.WIDTH);
		int sourceZ = Math.floorMod(relZ, ColumnRenderSource.WIDTH);
		source.populateColumnView(neighborView, sourceX, sourceZ);
		return getHeight(neighborView, source, sourceX, sourceZ, materialId, y);
	}
	
	/** height should be a value between 0 and 1.0 (inclusive) */
	private static int toDrop(float height) { return Math.min(Math.round((1.0f - height) * DROP_UNITS_PER_BLOCK), 15); }
	
	//endregion
	
	
	
}
