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

package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.factories.IDhApiWrapperFactory;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.render.DhApiModelQuad;

import java.util.ArrayList;
import java.util.List;

/**
 * This event is fired the first time DH needs the model of an {@link IDhApiBlockStateWrapper}
 * and can be used to replace or add to that model.
 * Once the model has been resolved, that same model will be reused for all subsequent instances
 * of that {@link IDhApiBlockStateWrapper}. <br>
 * (I.E. each block state can only have one model, you can't define
 * different models based on the world position or block entity data.)
 * <Br><Br>
 *
 * This event will only trigger for {@link IDhApiBlockStateWrapper}s that have been registered
 * via {@link DhApiBlockStateWrapperCreatedEvent.EventParam#setAllowApiModelOverride(boolean)}. <br>
 * It can fire on any of DH's threads.
 * <Br><Br>
 *
 * How to use: <br>
 * - The model starts as the block's own Minecraft model. <br>
 * - {@link EventParam#clearQuads()} removes it. <br>
 * - {@link EventParam#addModelFromBlock(IDhApiBlockStateWrapper)} adds another block's model. <br>
 * - {@link EventParam#addQuad(DhApiModelQuad)} adds your own quads. <br>
 * - If no quads are left the block is drawn as a box.
 * <Br><Br>
 *
 * Quads are rendered in order: the block's own model, then copied blocks, then added quads. <br>
 * Models are only drawn on block detail LODs, when block models are enabled in the config.
 *
 * @author Luis Vervaet
 * @version 2026-11-04
 * @since API 7.3.0
 * @see DhApiModelQuad
 */
public abstract class DhApiBlockModelOverrideEvent implements IDhApiEvent<DhApiBlockModelOverrideEvent.EventParam>
{
	public abstract void onBlockModelOverridden(DhApiEventParam<EventParam> event);
	
	
	//=========================//
	// internal DH API methods //
	//=========================//
	
	@Override
	public final void fireEvent(DhApiEventParam<EventParam> event) { this.onBlockModelOverridden(event); }
	
	
	//==================//
	// parameter object //
	//==================//
	
	public static class EventParam implements IDhApiEventParam
	{
		private final IDhApiBlockStateWrapper blockStateWrapper;
		
		private boolean keepBlockModel = true;
		private final ArrayList<IDhApiBlockStateWrapper> copiedBlocks = new ArrayList<>();
		private final ArrayList<DhApiModelQuad> addedQuads = new ArrayList<>();
		
		
		
		//=============//
		// constructor //
		//=============//
		//region
		
		public EventParam(IDhApiBlockStateWrapper blockStateWrapper) { this.blockStateWrapper = blockStateWrapper; }
		
		//endregion
		
		
		
		//=================//
		// getters/setters //
		//=================//
		//region
		
		public IDhApiBlockStateWrapper getBlockStateWrapper() { return this.blockStateWrapper; }
		
		/** Removes the block's own Minecraft model and anything added so far */
		public void clearQuads()
		{
			this.keepBlockModel = false;
			this.copiedBlocks.clear();
			this.addedQuads.clear();
		}
		
		/** Adds one of your own quads to the model */
		public void addQuad(DhApiModelQuad quad) { this.addedQuads.add(quad); }
		
		/** 
		 * Adds the Minecraft model of the given block. <br>
		 * Other blocks' wrappers can be found via {@link IDhApiWrapperFactory}.
		 */
		public void addModelFromBlock(IDhApiBlockStateWrapper blockStateWrapper) { this.copiedBlocks.add(blockStateWrapper); }
		
		//endregion
		
		
		
		//=========================//
		// internal DH API methods //
		//=========================//
		//region
		
		public boolean getKeepBlockModel() { return this.keepBlockModel; }
		public List<IDhApiBlockStateWrapper> getCopiedBlocks() { return this.copiedBlocks; }
		public List<DhApiModelQuad> getAddedQuads() { return this.addedQuads; }
		
		//endregion
		
		
		
		/**
		 * Returns the same instance of this event.
		 * Every handler adds to the same model.
		 */
		@Override
		public EventParam copy() { return this; }
		@Override
		public boolean getCopyBeforeFire() { return false; }
		
	}
	
}
