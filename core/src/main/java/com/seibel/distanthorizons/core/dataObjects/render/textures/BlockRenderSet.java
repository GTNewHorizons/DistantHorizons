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

package com.seibel.distanthorizons.core.dataObjects.render.textures;

import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import org.jetbrains.annotations.Nullable;

/** The face tiles, colors and model shared by block states that look the same, see {@link BlockRenderRegistry} */
public class BlockRenderSet
{
	/** a face color that means the face uses the LOD's color instead (biome tinted faces) */
	public static final int USE_LOD_COLOR = 0;
	
	/** indexed by {@link EDhDirection#faceIndex}, fluids and grass blocks have extra tiles after the 6 faces */
	public final short[] faceTileIds;
	/** each face's own color or {@link #USE_LOD_COLOR}, indexed by {@link EDhDirection#faceIndex} */
	public final int[] faceColors;
	/** empty if the block state isn't drawn with its model */
	public final DhBlockModelQuad[] modelQuads;
	/** indexed the same as modelQuads */
	public final short[] modelQuadTileIds;
	/** each quad's own color or {@link #USE_LOD_COLOR}, indexed the same as modelQuads */
	public final int[] modelQuadColors;
	/** the first block state registered with this set, null for the shared flat set */
	@Nullable
	public final IBlockStateWrapper blockState;
	/** each side's shared shape, by {@link EDhDirection#faceIndex} */
	private final SideShape[] sideShapes = new SideShape[EDhDirection.ALL.length];
	/** flat models sitting less than 1/16 from a side */
	public final boolean isFlatNearSide;
	
	
	
	/** True for flat models less than 1/16 from a side */
	private static boolean calculateIsFlatNearSide(DhBlockModelQuad[] modelQuads)
	{
		if (modelQuads.length == 0)
		{
			return false;
		}
		
		int unitsPerBlock = DhBlockModelQuad.CORNER_UNITS_PER_BLOCK;
		int maxDistance256 = unitsPerBlock / 16;
		for (int axis = 0; axis < 3; axis++)
		{
			int position256 = modelQuads[0].cornerPositions[axis];
			boolean isFlat = true;
			for (DhBlockModelQuad quad : modelQuads)
			{
				for (int i = 0; i < 4; i++)
				{
					if (quad.cornerPositions[i * 3 + axis] != position256)
					{
						isFlat = false;
					}
				}
			}
			
			if (!isFlat)
			{
				continue;
			}
			
			if ((position256 > 0 && position256 < maxDistance256)
				|| (position256 > unitsPerBlock - maxDistance256 && position256 < unitsPerBlock))
			{
				return true;
			}
		}
		return false;
	}
	
	public BlockRenderSet() 
	{
		// 6 faces on a cube
		this(new short[6], new int[6], new DhBlockModelQuad[0], new short[0], new int[0], null);
	}
	public BlockRenderSet(short[] faceTileIds, int[] faceColors, DhBlockModelQuad[] modelQuads, short[] modelQuadTileIds, int[] modelQuadColors, @Nullable IBlockStateWrapper blockState)
	{
		this.faceTileIds = faceTileIds;
		this.faceColors = faceColors;
		this.modelQuads = modelQuads;
		this.modelQuadTileIds = modelQuadTileIds;
		this.modelQuadColors = modelQuadColors;
		this.blockState = blockState;
		
		for (EDhDirection side : EDhDirection.ALL)
		{
			this.sideShapes[side.faceIndex] = SideShape.getSideShape(modelQuads, side);
		}
		
		this.isFlatNearSide = calculateIsFlatNearSide(modelQuads);
	}
	
	/** Null if no quads are on that side */
	@Nullable
	public SideShape getSideShape(EDhDirection side) { return this.sideShapes[side.faceIndex]; }
	
	/** True if the neighbor's quads hide every quad on that side */
	public boolean isSideHiddenBy(BlockRenderSet neighbor, EDhDirection side)
	{
		SideShape shape = this.sideShapes[side.faceIndex];
		SideShape neighborShape = neighbor.sideShapes[side.opposite().faceIndex];
		
		// sides without quads can't hide or be hidden
		if (shape == null || neighborShape == null)
		{
			return false;
		}
		return shape.isHiddenBy(neighborShape);
	}
	
}
