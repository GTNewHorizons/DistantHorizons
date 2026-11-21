package com.seibel.distanthorizons.common.render.blaze.wrappers.uniform;

#if MC_VER <= MC_1_21_10
public class BlazeLodUniformBufferWrapper {}

#else

import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainBufferContainer;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3i;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainContainerUniformBufferWrapper;

public class BlazeLodUniformBufferWrapper extends BlazeUniformBufferWrapper implements IDhApiTerrainContainerUniformBufferWrapper
{
	
	private boolean uploaded = false;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public BlazeLodUniformBufferWrapper() { super(BlazeLodUniformBufferWrapper.class.getName()); }
	
	//endregion
	
	
	
	//========//
	// upload //
	//========//
	//region
	
	@Override
	public void tryUpload(IDhApiTerrainBufferContainer apiBufferContainer)
	{
		if (this.uploaded)
		{
			return;
		}
		
		DhApiVec3i minCornerBlockPos = apiBufferContainer.getMinCornerBlockPos();
		
		// upload data //
		this
			.putVec3i(
				minCornerBlockPos.x,
				minCornerBlockPos.y,
				minCornerBlockPos.z) // uModelOffset
			.finishAndUpload();
		
		this.uploaded = true;
	}
	
	//endregion
	
	
	
}
#endif