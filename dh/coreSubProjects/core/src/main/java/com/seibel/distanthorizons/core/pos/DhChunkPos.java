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

package com.seibel.distanthorizons.core.pos;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPosMutable;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;

import java.util.Objects;

/**
 * immutable <br><br>
 * 
 * Dev note: if for some reason we want to store these as longs check the old commits. <br>
 * That logic was removed since it wasn't needed at the time
 *
 * @see DhChunkPosMutable
 */
public class DhChunkPos
{
	protected int x;
	public int getX() { return this.x; }
	
	protected int z;
	public int getZ() { return this.z; }
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public DhChunkPos(int x, int z)
	{
		this.x = x;
		this.z = z;
	}
	public DhChunkPos(DhBlockPos blockPos)
	{
		// >> 4 is the Same as divide by 16
		this(blockPos.getX() >> 4, blockPos.getZ() >> 4);
	}
	public DhChunkPos(DhBlockPos2D blockPos)
	{
		// >> 4 is the Same as div 16
		this(blockPos.x >> 4, blockPos.z >> 4);
	}
	public DhChunkPos(DhVec3d pos)
	{
		// >> 4 is the Same as div 16
		this(((int)pos.x) >> 4, ((int)pos.z) >> 4);
	}
	
	//endregion
	
	
	
	//=========//
	// methods //
	//=========//
	//region
	
	public DhBlockPos centerBlockPos() { return new DhBlockPos(8 + this.x << 4, 0, 8 + this.z << 4); }
	public DhBlockPos minCornerBlockPos() { return new DhBlockPos(this.x << 4, 0, this.z << 4); }
	
	public int getMinBlockX() { return this.x << 4; }
	public int getMinBlockZ() { return this.z << 4; }
	
	public int getMaxBlockX() 
	{
		int minBlockPos = this.getMinBlockX() + LodUtil.CHUNK_WIDTH;
		minBlockPos += (minBlockPos < 0) ? -1 : 0;
		return minBlockPos;
	}
	public int getMaxBlockZ() 
	{
		int minBlockPos = this.getMinBlockZ() + LodUtil.CHUNK_WIDTH;
		minBlockPos += (minBlockPos < 0) ? -1 : 0;
		return minBlockPos;
	}
	
	public DhBlockPos2D getMinBlockPos() { return new DhBlockPos2D(this.x << 4, this.z << 4); }
	
	public boolean contains(DhBlockPos pos)
	{
		int minBlockX = this.getMinBlockX();
		int minBlockZ = this.getMinBlockZ();
		int maxBlockX = minBlockX + LodUtil.CHUNK_WIDTH;
		int maxBlockZ = minBlockZ + LodUtil.CHUNK_WIDTH;
		
		return minBlockX <= pos.getX() && pos.getX() < maxBlockX
				&& minBlockZ <= pos.getZ() && pos.getZ() < maxBlockZ;
	}
	
	public boolean contains(DhBlockPos2D pos)
	{
		int minBlockX = this.getMinBlockX();
		int minBlockZ = this.getMinBlockZ();
		int maxBlockX = minBlockX + LodUtil.CHUNK_WIDTH;
		int maxBlockZ = minBlockZ + LodUtil.CHUNK_WIDTH;
		
		return minBlockX <= pos.x && pos.x < maxBlockX
				&& minBlockZ <= pos.z && pos.z < maxBlockZ;
	}
	
	public double distance(DhChunkPos other)
	{ return Math.sqrt(Math.pow(this.x - other.x, 2) + Math.pow(this.z - other.z, 2)); }
	
	public double squaredDistance(DhChunkPos other)
	{ return Math.pow(this.x - other.x, 2) + Math.pow(this.z - other.z, 2); }
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public boolean equals(Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		else if (obj == null || this.getClass() != obj.getClass())
		{
			return false;
		}
		else
		{
			DhChunkPos that = (DhChunkPos) obj;
			return this.x == that.x && this.z == that.z;
		}
	}
	
	@Override
	public int hashCode() { return Objects.hash(this.x, this.z); }
	
	@Override
	public String toString() { return "C[" + this.x + "," + this.z + "]"; }
	
	//endregion
	
	
	
}
