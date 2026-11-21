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

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/** The quads on one side of a model that a neighbor can hide */
public class SideShape
{
	/** shapes are shared by their content */
	private static final ConcurrentHashMap<BlockRenderRegistry.ContentKey, SideShape> SHAPE_BY_CONTENT = new ConcurrentHashMap<>();
	
	private final EDhDirection side;
	private final DhBlockModelQuad[] quads;
	/** null if the solid quads cover the whole face */
	@Nullable
	public final DhBlockModelQuad uncoveredFacePiece;
	/** neighbor shape on the opposite side -> true if it hides this shape */
	private final ConcurrentHashMap<SideShape, Boolean> hiddenByNeighborShape = new ConcurrentHashMap<>();
	
	
	
	/** Part of a side face, wound and textured like a box face */
	private static DhBlockModelQuad createFacePiece(EDhDirection side, int minU, int minV, int maxU, int maxV)
	{
		float edge = (side.axisDirection == EDhDirection.AxisDirection.POSITIVE) ? 1 : 0;
		float[] xByVertex = new float[4];
		float[] yByVertex = new float[4];
		float[] zByVertex = new float[4];
		float[] uByVertex = new float[4];
		float[] vByVertex = new float[4];
		for (int i = 0; i < 4; i++)
		{
			int[] corner = LodQuadBuilder.DIRECTION_VERTEX_IBO_QUAD[side.faceIndex][i];
			float u = (float) ((corner[0] == 1) ? maxU : minU) / DhBlockModelQuad.CORNER_UNITS_PER_BLOCK;
			float v = (float) ((corner[1] == 1) ? maxV : minV) / DhBlockModelQuad.CORNER_UNITS_PER_BLOCK;
			xByVertex[i] = (side.axis == EDhDirection.Axis.X) ? edge : u;
			yByVertex[i] = v;
			zByVertex[i] = (side.axis == EDhDirection.Axis.X) ? u : edge;
			uByVertex[i] = (side == EDhDirection.NORTH || side == EDhDirection.EAST) ? 1 - u : u;
			vByVertex[i] = 1 - v;
		}
		
		// drawn with the water's tile
		return new DhBlockModelQuad(xByVertex, yByVertex, zByVertex, uByVertex, vByVertex, BlockFaceTexture.createSolidColor(ColorUtil.INVISIBLE), side, false, null, true);
	}
	
	/** The side's whole face with each solid quad that crosses it cut off */
	@Nullable
	private static DhBlockModelQuad getUncoveredFacePiece(EDhDirection side, DhBlockModelQuad[] quads)
	{
		// u runs along x on north and south faces
		int uIndex = (side.axis == EDhDirection.Axis.X) ? 2 : 0;
		int minU = 0;
		int minV = 0;
		int maxU = DhBlockModelQuad.CORNER_UNITS_PER_BLOCK;
		int maxV = DhBlockModelQuad.CORNER_UNITS_PER_BLOCK;
		for (DhBlockModelQuad quad : quads)
		{
			// the shader cuts out clear pixels
			if (quad.texture.hasClearPixels())
			{
				continue;
			}
			
			// corner 0 and its opposite corner 2, 3 values each
			int[] corners = quad.cornerPositions;
			int quadMinU = Math.min(corners[uIndex], corners[6 + uIndex]);
			int quadMaxU = Math.max(corners[uIndex], corners[6 + uIndex]);
			int quadMinV = Math.min(corners[1], corners[7]);
			int quadMaxV = Math.max(corners[1], corners[7]);
			if (quadMinU <= minU && quadMaxU >= maxU)
			{
				// crosses the whole width, cut it off the bottom or top
				minV = (quadMinV <= minV) ? Math.max(minV, quadMaxV) : minV;
				maxV = (quadMaxV >= maxV) ? Math.min(maxV, quadMinV) : maxV;
			}
			else if (quadMinV <= minV && quadMaxV >= maxV)
			{
				// crosses the whole height, cut it off the left or right
				minU = (quadMinU <= minU) ? Math.max(minU, quadMaxU) : minU;
				maxU = (quadMaxU >= maxU) ? Math.min(maxU, quadMinU) : maxU;
			}
		}
		
		// the solid quads cover the whole face
		if (minU >= maxU || minV >= maxV)
		{
			return null;
		}
		return createFacePiece(side, minU, minV, maxU, maxV);
	}
	
	private SideShape(EDhDirection side, DhBlockModelQuad[] quads)
	{
		this.side = side;
		this.quads = quads;
		// water is only cut on its sides
		this.uncoveredFacePiece = side.axis.isHorizontal() ? getUncoveredFacePiece(side, quads) : null;
	}
	
	/** Null if no quads are on that side */
	@Nullable
	public static SideShape getSideShape(DhBlockModelQuad[] modelQuads, EDhDirection side)
	{
		ArrayList<DhBlockModelQuad> sideQuads = new ArrayList<>();
		for (DhBlockModelQuad quad : modelQuads)
		{
			if (quad.cullDirection == side)
			{
				sideQuads.add(quad);
			}
		}
		
		if (sideQuads.isEmpty())
		{
			return null;
		}
		
		// the side, then each quad's corners and if it's solid
		int byteCount = Byte.BYTES;
		for (DhBlockModelQuad quad : sideQuads)
		{
			byteCount += (quad.cornerPositions.length * Integer.BYTES) + Byte.BYTES;
		}
		ByteBuffer buffer = ByteBuffer.allocate(byteCount);
		buffer.put((byte) side.faceIndex);
		for (DhBlockModelQuad quad : sideQuads)
		{
			for (int position : quad.cornerPositions)
			{
				buffer.putInt(position);
			}
			buffer.put((byte) (quad.texture.hasClearPixels() ? 0 : 1));
		}
		
		BlockRenderRegistry.ContentKey contentKey = new BlockRenderRegistry.ContentKey(buffer.array());
		return SHAPE_BY_CONTENT.computeIfAbsent(contentKey, (newKey) -> new SideShape(side, sideQuads.toArray(new DhBlockModelQuad[0])));
	}
	
	/** True if every quad is inside a solid quad of the neighbor's shape */
	private boolean calculateHiddenBy(SideShape neighborShape)
	{
		for (DhBlockModelQuad quad : this.quads)
		{
			boolean quadHidden = false;
			for (DhBlockModelQuad neighborQuad : neighborShape.quads)
			{
				// the shader cuts out clear pixels
				if (quad.isInsideNeighborQuad(neighborQuad, this.side)
					&& !neighborQuad.texture.hasClearPixels())
				{
					quadHidden = true;
					break;
				}
			}
			
			if (!quadHidden)
			{
				return false;
			}
		}
		return true;
	}
	
	/** True if the neighbor's shape on the opposite side hides every quad */
	public boolean isHiddenBy(SideShape neighborShape)
	{
		// worked out once per pair of shapes
		return this.hiddenByNeighborShape.computeIfAbsent(neighborShape, this::calculateHiddenBy);
	}
	
}
