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

package com.seibel.distanthorizons.api.objects.render;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiDirection;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockModelOverrideEvent;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;

/**
 * A single quad (4 cornered face) of a block's model,
 * added via {@link DhApiBlockModelOverrideEvent.EventParam#addQuad(DhApiModelQuad)}. <br>
 * A model can have any number of quads, each one is its own {@link DhApiModelQuad}. <br><br>
 *
 * Each ByCorner array has 4 values, one per corner of this quad,
 * so the first corner is at (xByCorner[0], yByCorner[0], zByCorner[0]). <br>
 * Corners go counter-clockwise when looking at the quad's visible side,
 * the same order Minecraft's baked quads use. <br>
 * Any corner can be first. <br>
 * Only the visible side is drawn, add a second quad with the corners reversed to draw both sides. <br>
 * All 4 corners must be on the same plane.
 *
 * @author Luis Vervaet
 * @version 2026-11-04
 * @since API 7.3.0
 */
public class DhApiModelQuad
{
	/** in blocks, allows for float rounding */
	private static final float MAX_DISTANCE_FROM_PLANE = 1.0f / 256.0f;
	
	/** in blocks, relative to the block's minimum corner */
	public final float[] xByCorner;
	public final float[] yByCorner;
	public final float[] zByCorner;
	/** 0 to 1 across the texture, (0,0) is the top left */
	public final float[] uByCorner;
	public final float[] vByCorner;
	/** 
	 * 16 by 16 ARGB pixels, indexed with {@link TextureUtil#getPixelIndex(int, int)}. <br>
	 * Pixels with an alpha of 0 are cut out.
	 */
	public final int[] argbPixels;
	/** the side whose neighbor can hide this quad, null if no neighbor can */
	public final EDhApiDirection cullDirection;
	
	
	
	public DhApiModelQuad(
		float[] xByCorner, float[] yByCorner, float[] zByCorner,
		float[] uByCorner, float[] vByCorner,
		int[] argbPixels, EDhApiDirection cullDirection) throws IllegalArgumentException
	{
		throwIfNotFourCorners("xByCorner", xByCorner);
		throwIfNotFourCorners("yByCorner", yByCorner);
		throwIfNotFourCorners("zByCorner", zByCorner);
		throwIfNotFourCorners("uByCorner", uByCorner);
		throwIfNotFourCorners("vByCorner", vByCorner);
		throwIfCornersNotOnSamePlane(xByCorner, yByCorner, zByCorner);
		
		int pixelCount = TextureUtil.TEXTURE_WIDTH_AND_HEIGHT * TextureUtil.TEXTURE_WIDTH_AND_HEIGHT;
		if (argbPixels == null || argbPixels.length != pixelCount)
		{
			throw new IllegalArgumentException("[argbPixels] must have ["+pixelCount+"] values, one per pixel.");
		}
		
		this.xByCorner = xByCorner;
		this.yByCorner = yByCorner;
		this.zByCorner = zByCorner;
		this.uByCorner = uByCorner;
		this.vByCorner = vByCorner;
		this.argbPixels = argbPixels;
		this.cullDirection = cullDirection;
	}
	
	private static void throwIfNotFourCorners(String name, float[] values) throws IllegalArgumentException
	{
		if (values == null || values.length != 4)
		{
			throw new IllegalArgumentException("["+name+"] must have 4 values, one per corner.");
		}
	}
	
	private static void throwIfCornersNotOnSamePlane(float[] xByCorner, float[] yByCorner, float[] zByCorner) throws IllegalArgumentException
	{
		// the diagonals' cross product is the quad's normal
		float firstDiagonalX = xByCorner[2] - xByCorner[0];
		float firstDiagonalY = yByCorner[2] - yByCorner[0];
		float firstDiagonalZ = zByCorner[2] - zByCorner[0];
		float secondDiagonalX = xByCorner[3] - xByCorner[1];
		float secondDiagonalY = yByCorner[3] - yByCorner[1];
		float secondDiagonalZ = zByCorner[3] - zByCorner[1];
		float normalX = (firstDiagonalY * secondDiagonalZ) - (firstDiagonalZ * secondDiagonalY);
		float normalY = (firstDiagonalZ * secondDiagonalX) - (firstDiagonalX * secondDiagonalZ);
		float normalZ = (firstDiagonalX * secondDiagonalY) - (firstDiagonalY * secondDiagonalX);
		float normalLength = (float) Math.sqrt((normalX * normalX) + (normalY * normalY) + (normalZ * normalZ));
		if (normalLength == 0)
		{
			// zero area quads are always flat
			return;
		}
		
		// corners 1 and 3 are always the same distance off
		float distanceFromPlane = (((xByCorner[1] - xByCorner[0]) * normalX)
			+ ((yByCorner[1] - yByCorner[0]) * normalY)
			+ ((zByCorner[1] - zByCorner[0]) * normalZ)) / normalLength;
		if (Math.abs(distanceFromPlane) > MAX_DISTANCE_FROM_PLANE)
		{
			throw new IllegalArgumentException("All 4 corners must be on the same plane, corners [1] and [3] are ["+distanceFromPlane+"] blocks off it.");
		}
	}
	
}
