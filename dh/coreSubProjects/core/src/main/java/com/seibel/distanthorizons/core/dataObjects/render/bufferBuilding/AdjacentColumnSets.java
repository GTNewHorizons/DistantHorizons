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

import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderSet;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import org.jetbrains.annotations.Nullable;

/** Where each adjacent column was read from */
public class AdjacentColumnSets
{
	private final ColumnRenderSource[] renderSources = new ColumnRenderSource[EDhDirection.CARDINAL_COMPASS.length];
	private final BlockRenderSet[][] setsByPaletteIndex = new BlockRenderSet[EDhDirection.CARDINAL_COMPASS.length][];
	private final int[] relXs = new int[EDhDirection.CARDINAL_COMPASS.length];
	private final int[] relZs = new int[EDhDirection.CARDINAL_COMPASS.length];
	
	
	
	public void clear(EDhDirection direction) { this.setsByPaletteIndex[direction.compassIndex] = null; }
	
	public void set(EDhDirection direction, ColumnRenderSource renderSource, BlockRenderSet @Nullable [] setsByPaletteIndex, int relX, int relZ)
	{
		this.renderSources[direction.compassIndex] = renderSource;
		this.setsByPaletteIndex[direction.compassIndex] = setsByPaletteIndex;
		this.relXs[direction.compassIndex] = relX;
		this.relZs[direction.compassIndex] = relZ;
	}
	
	@Nullable
	public BlockRenderSet getDataPointRenderSet(EDhDirection direction, int index)
	{
		int compassIndex = direction.compassIndex;
		return ColumnRenderBufferBuilder.getDataPointRenderSet(this.setsByPaletteIndex[compassIndex], this.renderSources[compassIndex], this.relXs[compassIndex], this.relZs[compassIndex], index);
	}
	
}
