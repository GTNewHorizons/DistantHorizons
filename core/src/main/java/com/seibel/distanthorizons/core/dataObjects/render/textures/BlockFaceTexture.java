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

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBlockTextureOverrideEvent;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.coreapi.util.TextureUtil;

/**
 * A small texture representing one face of a block state,
 * baked from the block's model so LODs can show the block's
 * actual texture instead of a single flat color. <br><br>
 */
public class BlockFaceTexture
{
	public final int width;
	public final int height;
	/**
	 * Pixel colors in ARGB order. <br>
	 * Indexed via <code>(v * width) + u</code> where (0,0) is the face's top left pixel. <br><br>
	 * 
	 * If changed {@link DhApiBlockTextureOverrideEvent}
	 * will need to be updated.
	 */
	public final int[] argbPixels;
	
	public final boolean uploadAsColorRatio;
	
	
	
	//==============//
	// constructors //
	//==============//
	//region
	
	public static BlockFaceTexture createSolidColor(int argbColor)
	{ return new BlockFaceTexture(1, 1, new int[] { argbColor }, false); }
	
	public static BlockFaceTexture createErrorGridTexture()
	{
		// similar pink/black grid used by source engine games for missing textures
		int[] argbPixels = new int[] 
		{
			ColorUtil.HOT_PINK, // top left
			ColorUtil.BLACK, // top right
			
			ColorUtil.BLACK, // bottom left
			ColorUtil.HOT_PINK, // bottom right
		};
		return new BlockFaceTexture(2, 2, argbPixels, false); 
	}
	
	public static BlockFaceTexture createTexture(int width, int height, int[] argbPixels)
	{ return new BlockFaceTexture(width, height, argbPixels, true); }
	
	/** Each quarter turn moves the texture's bottom edge to its right edge */
	public BlockFaceTexture createWithQuarterTurns(int quarterTurns)
	{
		int turns = Math.floorMod(quarterTurns, 4);
		if (turns == 0 
			|| this.width != this.height) // we can't turn a non-square texture (shouldn't happen, but just in case)
		{
			return this;
		}
		
		int size = this.width;
		int[] turnedPixels = new int[this.argbPixels.length];
		for (int v = 0; v < size; v++)
		{
			for (int u = 0; u < size; u++)
			{
				int sourceU;
				int sourceV;
				if (turns == 1)
				{
					sourceU = size - 1 - v;
					sourceV = u;
				}
				else if (turns == 2)
				{
					sourceU = size - 1 - u;
					sourceV = size - 1 - v;
				}
				else
				{
					sourceU = v;
					sourceV = size - 1 - u;
				}
				turnedPixels[(v * size) + u] = this.argbPixels[(sourceV * size) + sourceU];
			}
		}
		return new BlockFaceTexture(this.width, this.height, turnedPixels, this.uploadAsColorRatio);
	}
	
	/** Fills clear pixels the way vanilla fills leaves */
	public BlockFaceTexture createWithDarkClearPixels()
	{
		int darkestColor = 0;
		int darkestBrightness = Integer.MAX_VALUE;
		for (int argbPixel : this.argbPixels)
		{
			int brightness = ColorUtil.getRed(argbPixel) + ColorUtil.getGreen(argbPixel) + ColorUtil.getBlue(argbPixel);
			if (ColorUtil.getAlpha(argbPixel) != 0
				&& brightness < darkestBrightness)
			{
				darkestColor = argbPixel;
				darkestBrightness = brightness;
			}
		}
		
		int darkColor = ColorUtil.argbToInt(255,
			ColorUtil.getRed(darkestColor) * 3 / 4,
			ColorUtil.getGreen(darkestColor) * 3 / 4,
			ColorUtil.getBlue(darkestColor) * 3 / 4);
		int[] filledPixels = this.argbPixels.clone();
		for (int i = 0; i < filledPixels.length; i++)
		{
			if (ColorUtil.getAlpha(filledPixels[i]) == 0)
			{
				filledPixels[i] = darkColor;
			}
		}
		return new BlockFaceTexture(this.width, this.height, filledPixels, this.uploadAsColorRatio);
	}
	
	private BlockFaceTexture(int width, int height, int[] argbPixels, boolean uploadAsColorRatio)
	{
		this.width = width;
		this.height = height;
		this.argbPixels = argbPixels;
		this.uploadAsColorRatio = uploadAsColorRatio;
	}
	
	//endregion
	
	
	
	//========//
	// pixels //
	//========//
	//region
	
	/** True if any pixel is fully see-through */
	public boolean hasClearPixels()
	{
		for (int argbPixel : this.argbPixels)
		{
			if (ColorUtil.getAlpha(argbPixel) == 0)
			{
				return true;
			}
		}
		return false;
	}
	
	//endregion
	
	
	
	//=====//
	// API //
	//=====//
	//region
	
	public void updateFromApiEvent(DhApiBlockTextureOverrideEvent.EventParam eventParam)
	{
		for (int u = 0; u < TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; u++)
		{
			for (int v = 0; v < TextureUtil.TEXTURE_WIDTH_AND_HEIGHT; v++)
			{
				int newColor = eventParam.getColorAsInt(u, v);
				this.argbPixels[TextureUtil.getPixelIndex(u, v)] = newColor;
			}
		}
	}
	
	//endregion
	
	
	
}
