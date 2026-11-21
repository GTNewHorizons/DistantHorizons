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

package com.seibel.distanthorizons.api.interfaces.render.renderDef;

import java.nio.ByteBuffer;

/**
 * Handles uploading block textures used by DH
 * for rendering.
 *
 * @since API 7.3.0
 * @version 2026-10-31
 */
public interface IDhApiBlockTextureAtlas
{
	void writeToTexture(
		ByteBuffer pixelBuffer,
		int destinationX, int destinationY,
		int tileWidth, int tileHeight);
	
	void tryCreateOrResize(int width, int height);
	
	/** can be implemented for pre-upload setup */
	void beforeWriteToTexture();
	/** can be implemented for post-upload cleanup */
	void afterWriteToTexture();
	
	
	
}
