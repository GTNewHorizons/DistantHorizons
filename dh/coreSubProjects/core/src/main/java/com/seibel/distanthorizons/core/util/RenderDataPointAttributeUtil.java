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

package com.seibel.distanthorizons.core.util;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;

/** Set and fluid data for each render data point, stored in one byte array per ColumnRenderSource */
public class RenderDataPointAttributeUtil
{
	// each data point is [set palette index, 2 bytes][fluid amount], size in bytes
	public static final int RECORD_SIZE_IN_BYTES = 3;

	// waterlogged blocks are always full, so they're stored as one past full to save space
	private static final byte WATERLOGGED_FLUID_AMOUNT = 9;



	public static void setRecord(ByteArrayList data, int offset, int setPaletteIndex, int fluidAmount, boolean waterlogged)
	{
		data.set(offset, (byte) (setPaletteIndex >> 8));
		data.set(offset + 1, (byte) setPaletteIndex);
		data.set(offset + 2, waterlogged ? WATERLOGGED_FLUID_AMOUNT : (byte) fluidAmount);
	}

	public static int getSetPaletteIndex(ByteArrayList data, int offset) { return ((data.getByte(offset) & 0xFF) << 8) | (data.getByte(offset + 1) & 0xFF); }
	public static int getFluidAmount(ByteArrayList data, int offset) { return Math.min(data.getByte(offset + 2), LodUtil.MAX_FLUID_AMOUNT); }
	public static boolean isWaterlogged(ByteArrayList data, int offset) { return data.getByte(offset + 2) == WATERLOGGED_FLUID_AMOUNT; }

}
