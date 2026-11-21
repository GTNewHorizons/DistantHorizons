package com.seibel.distanthorizons.forge17.modAccessor;

import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IRpleAccessor;
import net.minecraft.client.renderer.texture.DynamicTexture;

import com.falsepattern.rple.api.client.RPLELightMapAPI;

/**
 * RPLE = Right Proper Lighting Engine <Br>
 * adds colored lighting <br><br>
 * 
 * Client only, uses client classes and must not be loaded on a dedicated server.
 */
public class RpleClientAccessor extends RpleCommonAccessor implements IRpleAccessor
{
	private final DynamicTexture lightmapTexture;
	private final int[] lightmapColors;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public RpleClientAccessor()
	{
		this.lightmapTexture = new DynamicTexture(16, 16);
		this.lightmapColors = this.lightmapTexture.getTextureData();
	}
	
	//endregion
	
	
	
	//==========//
	// lightmap //
	//==========//
	//region
	
	@Override
	public void updateLightmap()
	{
		RPLELightMapAPI.getMixedLightMapData(this.lightmapColors);
		this.lightmapTexture.updateDynamicTexture();
	}
	
	@Override
	public int getLightmapTextureId() { return this.lightmapTexture.getGlTextureId(); }
	
	//endregion
	
	
	
}
