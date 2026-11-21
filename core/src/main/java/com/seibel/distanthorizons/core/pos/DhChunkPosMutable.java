package com.seibel.distanthorizons.core.pos;

import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;

public class DhChunkPosMutable extends DhChunkPos
{
	public void setX(int x) { this.x = x; }
	public void setZ(int z) { this.z = z; }
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public DhChunkPosMutable(int x, int z) { super(x,z); }
	public DhChunkPosMutable() { super(0, 0); }
	public DhChunkPosMutable(DhChunkPos pos) { super(pos.x ,pos.z); }
	
	public DhChunkPosMutable(DhBlockPos2D pos) { super(pos); }
	public DhChunkPosMutable(DhBlockPos pos) { super(pos); }
	
	//endregion
	
	
	
	//==========//
	// mutators //
	//==========//
	//region
	
	public void set(DhChunkPos pos)
	{
		this.x = pos.x;
		this.z = pos.z;
	}
	
	public void set(DhBlockPos pos)
	{
		// >> 4 is the Same as divide by 16
		this.x = pos.getX() >> 4;
		this.z = pos.getZ() >> 4;
	}
	
	//endregion
	
	
}
