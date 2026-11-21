package com.seibel.distanthorizons.api.interfaces.render.renderDef.objects;

import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3i;

/**
 * Holds all the necessary information data Distant Horizons
 * needs to render a single LOD.
 * 
 * @since API 7.3.0
 * @version 2026-11-09
 */
public interface IDhApiTerrainBufferContainer
{
	IDhApiVertexBufferWrapper[] getVboOpaqueWrappers();
	IDhApiVertexBufferWrapper[] getVboTransparentWrappers();
	/** opaque quads drawn after SSAO */
	IDhApiVertexBufferWrapper[] getVboWithoutSsaoWrappers();
	
	IDhApiTerrainContainerUniformBufferWrapper getUniformContainer();
	
	/** @see EDhApiDetailLevel */
	byte getDetailLevel();
	int getLodPosX();
	int getLodPosZ();
	
	/** the position closest to minimum X/Z infinity and the level's lowest Y */
	DhApiVec3i getMinCornerBlockPos();
	
	
	
}
