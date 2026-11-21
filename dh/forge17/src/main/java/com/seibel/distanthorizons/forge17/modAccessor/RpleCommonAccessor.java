package com.seibel.distanthorizons.forge17.modAccessor;

import com.seibel.distanthorizons.common.wrappers.modAccessor.IRpleCommonAccessor;
import net.minecraft.block.Block;

import com.falsepattern.rple.api.common.ServerColorHelper;
import com.falsepattern.rple.api.common.block.RPLEBlock;

/**
 * RPLE = Right Proper Lighting Engine <Br>
 * adds colored lighting <br><br>
 * 
 * Only contains methods that are safe to use on a dedicated server,
 * client specific methods are in {@link RpleClientAccessor}.
 */
public class RpleCommonAccessor implements IRpleCommonAccessor
{
	@Override
	public String getModName() { return "rple"; }
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	@Override
	public int getColor(Block block, int meta)
	{
		short color = RPLEBlock.of(block)
			.rple$getBrightnessColor(meta);
		return ServerColorHelper.lightValueFromRGB16(color);
	}
	
	//endregion
	
	
	
}
