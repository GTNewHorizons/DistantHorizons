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

package com.seibel.distanthorizons.api.enums.rendering;

/**
 * POS_META_COLOR_MATERIAL_NORMAL_TEXTURE,			<br>
 * POS_META_COLOR_MATERIAL_NORMAL_TEXTURE_OFFSET,	<br><br>
 *
 * The layout of each vertex in DH's terrain buffers.
 *
 * @since API 7.3.0
 * @version 2026-11-07
 */
public enum EDhApiTerrainVertexFormat
{
	/**
	 * 16 bytes per vertex, positions are whole blocks so fluids aren't lowered. <br>
	 * <code>
	 * ushort x, y, z <br>
	 * ushort meta (sky light, block light, micro offset) <br>
	 * ubyte r, g, b, a <br>
	 * ubyte material id <br>
	 * ubyte normal index <br>
	 * ushort texture tile id
	 * </code>
	 */
	POS_META_COLOR_MATERIAL_NORMAL_TEXTURE(16),
	
	/**
	 * 24 bytes per vertex, the same as {@link #POS_META_COLOR_MATERIAL_NORMAL_TEXTURE} followed by: <br>
	 * <code>
	 * ushort x, y, z <br>
	 * ushort meta (sky light, block light, micro offset) <br>
	 * ubyte r, g, b, a <br>
	 * ubyte material id <br>
	 * ubyte normal index <br>
	 * ushort texture tile id <br>
	 * ubyte sub-block offset xyz, and flags w <br>
	 * ubyte u, v <br>
	 * ushort normal
	 * </code>
	 * The offsets are in 256ths of a block and are added to the position (lowered fluid surfaces). <br>
	 * Model and cut-out vertices set flag bit 0 and use u, v, other box faces leave the flags, u, v, and normal as 0. <br>
	 * Flat models less than 1/16 from a side also set flag bit 1, they're pulled toward the camera. <br>
	 * Flagged vertices read x, y, z as signed shorts since model corners can be below 0.
	 */
	POS_META_COLOR_MATERIAL_NORMAL_TEXTURE_OFFSET(24);
	
	
	
	public final int bytesPerVertex;
	
	EDhApiTerrainVertexFormat(int bytesPerVertex) 
	{
		this.bytesPerVertex = bytesPerVertex;
		if (this.bytesPerVertex % 4 != 0)
		{
			// This is just for dev time validation
			throw new UnsupportedOperationException("Vertex Formats must be multiples of 4.");
		}
	}
	
	
	
}
