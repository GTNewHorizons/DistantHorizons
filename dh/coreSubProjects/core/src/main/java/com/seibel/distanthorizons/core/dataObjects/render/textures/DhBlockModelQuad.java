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
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import org.jetbrains.annotations.Nullable;

/** One quad of a block's model */
public class DhBlockModelQuad
{
	/** corner positions are stored in 256ths of a block */
	public static final int CORNER_UNITS_PER_BLOCK = 256;
	private static final int CORNER_COUNT = 4;
	/** x, y and z */
	private static final int POSITIONS_PER_CORNER = 3;
	private static final int MAX_UNSIGNED_BYTE = 255;
	
	/** x, y, z of each of the 4 corners in 256ths of a block */
	public final int[] cornerPositions = new int[12];
	/** u, v of each of the 4 corners in 255ths of the texture */
	public final byte[] cornerUvBytes = new byte[8];
	public final BlockFaceTexture texture;
	/** the side whose neighbor can hide this quad */
	@Nullable
	public final EDhDirection cullDirection;
	/** the quad's facing, octahedral encoded (x low byte, y high byte) */
	public final short packedNormal;
	/** the side vanilla shades the quad as */
	public final EDhDirection shadeDirection;
	/** takes the block's tint color */
	public final boolean tinted;
	/**
	 * vanilla draws the quad with ambient occlusion.
	 * Some models like tall grass skip it so they aren't too dark
	 */
	public final boolean ambientOcclusion;
	
	
	
	private static DhVec3f getNormal(float[] xByVertex, float[] yByVertex, float[] zByVertex)
	{
		// the diagonals' cross product works for any 4 corners
		DhVec3f normal = new DhVec3f(xByVertex[2] - xByVertex[0], yByVertex[2] - yByVertex[0], zByVertex[2] - zByVertex[0]);
		normal.crossProduct(new DhVec3f(xByVertex[3] - xByVertex[1], yByVertex[3] - yByVertex[1], zByVertex[3] - zByVertex[1]));
		return normal;
	}
	
	/** ties go to up/down, then north/south, like vanilla */
	private static EDhDirection getShadeDirection(DhVec3f normal)
	{
		float absX = Math.abs(normal.x);
		float absY = Math.abs(normal.y);
		float absZ = Math.abs(normal.z);
		if (absY >= absX && absY >= absZ)
		{
			return (normal.y >= 0) ? EDhDirection.UP : EDhDirection.DOWN;
		}
		else if (absZ >= absX)
		{
			return (normal.z >= 0) ? EDhDirection.SOUTH : EDhDirection.NORTH;
		}
		return (normal.x >= 0) ? EDhDirection.EAST : EDhDirection.WEST;
	}
	
	private static short getPackedNormal(DhVec3f normal)
	{
		normal.mul(1 / (Math.abs(normal.x) + Math.abs(normal.y) + Math.abs(normal.z)));
		
		float octX = normal.x;
		float octY = normal.y;
		if (normal.z < 0)
		{
			octX = (1 - Math.abs(normal.y)) * (normal.x >= 0 ? 1 : -1);
			octY = (1 - Math.abs(normal.x)) * (normal.y >= 0 ? 1 : -1);
		}
		
		int encodedX = Math.round((octX * 0.5f + 0.5f) * MAX_UNSIGNED_BYTE);
		int encodedY = Math.round((octY * 0.5f + 0.5f) * MAX_UNSIGNED_BYTE);
		return (short) (encodedX | (encodedY << 8));
	}
	
	/** all inputs are in the range [0,1] */
	public DhBlockModelQuad(
		float[] xByVertex, float[] yByVertex, float[] zByVertex, float[] uByVertex, float[] vByVertex,
		BlockFaceTexture texture, @Nullable EDhDirection cullDirection, boolean tinted, @Nullable EDhDirection shadeDirectionOverride, boolean ambientOcclusion)
	{
		this.texture = texture;
		this.cullDirection = cullDirection;
		this.tinted = tinted;
		this.ambientOcclusion = ambientOcclusion;
		
		for (int i = 0; i < 4; i++)
		{
			this.cornerPositions[i * 3] = Math.round(xByVertex[i] * CORNER_UNITS_PER_BLOCK);
			this.cornerPositions[i * 3 + 1] = Math.round(yByVertex[i] * CORNER_UNITS_PER_BLOCK);
			this.cornerPositions[i * 3 + 2] = Math.round(zByVertex[i] * CORNER_UNITS_PER_BLOCK);
			this.cornerUvBytes[i * 2] = (byte) Math.round(uByVertex[i] * MAX_UNSIGNED_BYTE);
			this.cornerUvBytes[i * 2 + 1] = (byte) Math.round(vByVertex[i] * MAX_UNSIGNED_BYTE);
		}
		
		DhVec3f normal = getNormal(xByVertex, yByVertex, zByVertex);
		this.shadeDirection = (shadeDirectionOverride != null) ? shadeDirectionOverride : getShadeDirection(normal);
		this.packedNormal = getPackedNormal(normal);
	}
	
	/** in 256ths of a block */
	public DhVec3d getCorner(int corner)
	{
		int xIndex = corner * POSITIONS_PER_CORNER;
		return new DhVec3d(this.cornerPositions[xIndex], this.cornerPositions[xIndex + 1], this.cornerPositions[xIndex + 2]);
	}
	
	/** True if the point is on this flat quad, edges included */
	public boolean isPointOnQuad(DhVec3d point)
	{
		// the quad's facing, from its diagonals
		DhVec3d firstDiagonal = this.getCorner(2);
		firstDiagonal.subtract(this.getCorner(0));
		DhVec3d secondDiagonal = this.getCorner(3);
		secondDiagonal.subtract(this.getCorner(1));
		DhVec3d facing = firstDiagonal.copy();
		facing.crossProduct(secondDiagonal);
		
		for (int corner = 0; corner < CORNER_COUNT; corner++)
		{
			int nextCorner = (corner + 1) % CORNER_COUNT;
			DhVec3d cornerPosition = this.getCorner(corner);
			
			DhVec3d edge = this.getCorner(nextCorner);
			edge.subtract(cornerPosition);
			DhVec3d toPoint = point.copy();
			toPoint.subtract(cornerPosition);
			DhVec3d edgeCross = edge.copy();
			edgeCross.crossProduct(toPoint);
			
			// off this quad's plane or outside this edge
			if (toPoint.dotProduct(facing) != 0 || edgeCross.dotProduct(facing) < 0)
			{
				return false;
			}
		}
		
		// zero area quads cover nothing
		return facing.dotProduct(facing) != 0;
	}
	
	/** True if this quad fits inside the neighbor's quad on that side */
	public boolean isInsideNeighborQuad(DhBlockModelQuad neighborQuad, EDhDirection side)
	{
		for (int corner = 0; corner < CORNER_COUNT; corner++)
		{
			// in the neighbor block's own positions
			DhVec3d movedCorner = this.getCorner(corner);
			movedCorner.add(
				-side.normal.x * CORNER_UNITS_PER_BLOCK,
				-side.normal.y * CORNER_UNITS_PER_BLOCK,
				-side.normal.z * CORNER_UNITS_PER_BLOCK);
			
			if (!neighborQuad.isPointOnQuad(movedCorner))
			{
				return false;
			}
		}
		return true;
	}
	
}
