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

package com.seibel.distanthorizons.core.render;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShadowCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.AbstractDhApiRenderDefinition;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiVertexBufferWrapper;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dependencyInjection.ModAccessorInjector;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import com.seibel.distanthorizons.core.render.renderer.cullingFrustum.DhFrustumBounds;
import com.seibel.distanthorizons.core.render.renderer.cullingFrustum.NeverCullFrustum;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.function.Function;

/**
 * This object tells the {@link LodRenderer} what buffers to render
 */
public class RenderBufferHandler implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);

	private static final IIrisAccessor IRIS_ACCESSOR = ModAccessorInjector.INSTANCE.get(IIrisAccessor.class);
	
	// static values for re-use to reduce GC pressure
	private static final float[] JOML_TRANSPOSE_ARRAY = new float[16];
	private static final Matrix4f WORLD_VIEW_JOML_MATRIX = new Matrix4f();
	private static final Matrix4f WORLD_VIEW_PROJ_JOML_MATRIX = new Matrix4f();
	private static final DhMat4f FRUSTOM_DH_MATRIX = new DhMat4f();
	
	
	/** contains all relevant data */
	public final LodQuadTree lodQuadTree;
	
	private final SortedArraySet<LodBufferContainer> loadedNearToFarBuffers;
	private AbstractDhApiRenderDefinition currentRenderDefinition;
	/** camera position the buffers are sorted relative to, updated in {@link #buildRenderList} */
	private double sortCameraBlockX;
	private double sortCameraBlockZ;
	/** temp array to prevent threading issues and prevent re-allocating the same array each frame */
	private final ArrayList<LodRenderSection> tempProcessNodeList = new ArrayList<>();
	
	private long visibleBufferCount;
	//private long visibleVertexCount;
	
	private long culledBufferCount;
	//private long culledVertexCount;
	
	private long shadowVisibleBufferCount;
	//private long shadowVisibleVertexCount;
	
	private long shadowCulledBufferCount;
	//private long shadowCulledVertexCount;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	public RenderBufferHandler(LodQuadTree lodQuadTree) 
	{ 
		this.lodQuadTree = lodQuadTree;
		
		IDhApiCullingFrustum coreCameraFrustum = DhApi.overrides.get(IDhApiCullingFrustum.class, IOverrideInjector.CORE_PRIORITY);
		if (coreCameraFrustum == null)
		{
			DhApi.overrides.bind(IDhApiCullingFrustum.class, new DhFrustumBounds());
		}
		
		// by default the shadow pass shouldn't have any frustum culling
		IDhApiShadowCullingFrustum coreShadowFrustum = DhApi.overrides.get(IDhApiShadowCullingFrustum.class, IOverrideInjector.CORE_PRIORITY);
		if (coreShadowFrustum == null)
		{
			DhApi.overrides.bind(IDhApiShadowCullingFrustum.class, new NeverCullFrustum());
		}
		
		this.loadedNearToFarBuffers = new SortedArraySet<>(this::sortBufferContainersNearToFar);
	}
	private int sortBufferContainersNearToFar(LodBufferContainer loadedBufferA, LodBufferContainer loadedBufferB)
	{ return Double.compare(this.distanceSquaredToCamera(loadedBufferA.pos), this.distanceSquaredToCamera(loadedBufferB.pos)); }
	/**
	 * horizontal distance from the camera to the center of the section. <br>
	 * The center is used instead of the closest point since the section sizes
	 * are also chosen by the distance to their center, the closest point would
	 * sort larger sections in front of the smaller sections next to them.
	 */
	private double distanceSquaredToCamera(long pos)
	{
		double halfWidth = DhSectionPos.getBlockWidth(pos) / 2.0;
		
		double x = (DhSectionPos.getMinCornerBlockX(pos) + halfWidth) - this.sortCameraBlockX;
		double z = (DhSectionPos.getMinCornerBlockZ(pos) + halfWidth) - this.sortCameraBlockZ;
		return (x * x) + (z * z);
	}
	
	//endregion
	
	
	
	//=================//
	// render building //
	//=================//
	//region
	
	/**
	 * The following buildRenderList sorting method is based on the following reddit post: <br>
	 * <a href="https://www.reddit.com/r/VoxelGameDev/comments/a0l8zc/correct_depthordering_for_translucent_discrete/">correct_depth_ordering_for_translucent_discrete</a>
	 */
	public void buildRenderList(RenderParam renderParams)
	{
		if (ModInfo.IS_DEV_BUILD)
		{
			if (!RenderThreadTaskHandler.INSTANCE.isCurrentThread())
			{
				LodUtil.assertNotReach("Should only be run on the render thread");
			}
		}
		
		// clear the old list so we can start fresh
		this.loadedNearToFarBuffers.clear();
		this.currentRenderDefinition = renderParams.renderDefinition;
		
		
		
		//====================================//
		// get and update the culling frustum //
		//====================================//
		
		// get the culling frustum
		boolean enableFrustumCulling;
		IDhApiCullingFrustum frustum;
		boolean isShadowPass = (IRIS_ACCESSOR != null && IRIS_ACCESSOR.isRenderingShadowPass());
		if (isShadowPass)
		{
			enableFrustumCulling = !Config.Client.Advanced.Graphics.Culling.disableShadowPassFrustumCulling.get();
			frustum = DhApi.overrides.get(IDhApiShadowCullingFrustum.class);
		}
		else
		{
			enableFrustumCulling = !Config.Client.Advanced.Graphics.Culling.disableFrustumCulling.get();
			frustum = DhApi.overrides.get(IDhApiCullingFrustum.class);
		}
		
		
		// update the frustum if necessary
		if (enableFrustumCulling)
		{
			int worldMinY = renderParams.clientLevelWrapper.getMinHeight();
			int worldHeight = renderParams.clientLevelWrapper.getMaxHeight();
			
			renderParams.mcModelViewMatrix.putValuesInArray(JOML_TRANSPOSE_ARRAY);
			WORLD_VIEW_JOML_MATRIX
				.setTransposed(JOML_TRANSPOSE_ARRAY)
				.translate(
					-(float) renderParams.exactCameraPosition.x,
					-(float) renderParams.exactCameraPosition.y,
					-(float) renderParams.exactCameraPosition.z);
			
			renderParams.dhProjectionMatrix.putValuesInArray(JOML_TRANSPOSE_ARRAY);
			WORLD_VIEW_PROJ_JOML_MATRIX
				.setTransposed(JOML_TRANSPOSE_ARRAY)
				.mul(WORLD_VIEW_JOML_MATRIX);

			FRUSTOM_DH_MATRIX.set(WORLD_VIEW_PROJ_JOML_MATRIX);
			frustum.update(worldMinY, worldMinY + worldHeight, FRUSTOM_DH_MATRIX);
		}
		
		
		
		//=========================//
		// Update the section list //
		//=========================//
		//region
		
		if (isShadowPass)
		{
			this.shadowCulledBufferCount = 0;
			//this.shadowCulledVertexCount = 0;
		}
		else
		{
			this.culledBufferCount = 0;
			//this.culledVertexCount = 0;
		}
		
		// setup iterator with culling frustum
		this.lodQuadTree.populateListWithEnabledRenderSections(this.tempProcessNodeList);
		for (LodRenderSection renderSection : this.tempProcessNodeList)
		{
			if (renderSection == null)
			{
				continue;
			}
			
			
			try
			{
				if (enableFrustumCulling)
				{
					int blockMinX = DhSectionPos.getMinCornerBlockX(renderSection.pos);
					int blockMinZ = DhSectionPos.getMinCornerBlockZ(renderSection.pos);
					int blockWidth = DhSectionPos.getBlockWidth(renderSection.pos);
					byte detailLevel = DhSectionPos.getDetailLevel(renderSection.pos);
					if (!frustum.intersects(blockMinX, blockMinZ, blockWidth, detailLevel))
					{
						if (isShadowPass)
						{
							this.shadowCulledBufferCount++;
							//this.shadowCulledVertexCount += countAllVerticies(renderSection.renderBufferContainer);
						}
						else
						{
							this.culledBufferCount++;
							//this.culledVertexCount += countAllVerticies(renderSection.renderBufferContainer);
						}
						
						continue;
					}
				}
			}
			catch (Exception e)
			{
				// don't cull if there was an unexpected issue
				LOGGER.error("Unexpected issue during culling for node pos: ["+DhSectionPos.toString(renderSection.pos)+"], error: ["+e.getMessage()+"].", e);
			}
			
			
			try
			{
				LodBufferContainer bufferContainer = renderSection.renderBufferContainer;
				
				if (bufferContainer == null
					|| !renderSection.getRenderingEnabled()
					|| !bufferContainer.buffersUploaded)
				{
					// shouldn't happen, but just in case
					continue;
				}
				
				if (bufferContainer.isIncompatibleWith(renderParams.renderDefinition))
				{
					// if the render data doesn't match, we'll need it to be re-generated
					renderSection.renderDataDirty = true;
					continue;
				}

				this.loadedNearToFarBuffers.add(bufferContainer);
			}
			catch (Exception e)
			{
				LOGGER.error("Error updating QuadTree render source at [" + DhSectionPos.toString(renderSection.pos) + "], error: ["+e.getMessage()+"].", e);
			}
		}
		
		// near to far sorting is needed for transparent rendering (drawn far to near)
		this.sortCameraBlockX = renderParams.exactCameraPosition.x;
		this.sortCameraBlockZ = renderParams.exactCameraPosition.z;
		this.loadedNearToFarBuffers.sort();
		if (isShadowPass)
		{
			this.shadowVisibleBufferCount = this.loadedNearToFarBuffers.size();
			//this.shadowVisibleVertexCount = countAllVerticies(this.loadedNearToFarBuffers);
		}
		else
		{
			this.visibleBufferCount = this.loadedNearToFarBuffers.size();
			//this.visibleVertexCount = countAllVerticies(this.loadedNearToFarBuffers);
		}
		
		//endregion
		
	}
	
	//endregion
	
	
	
	//=================//
	// vertex counting //
	//=================//
	//region
	
	private static long countAllVerticies(SortedArraySet<LodBufferContainer> containers)
	{
		long vertexCount = 0L;
		{
			vertexCount += countVertices(containers, RenderBufferHandler::getOpaqueBufferWrappers);
			vertexCount += countVertices(containers, RenderBufferHandler::getTransparentBufferWrappers);
			vertexCount += countVertices(containers, RenderBufferHandler::getOpaqueNoSsaoBufferWrappers);
		}
		return vertexCount;
	}
	private static long countAllVerticies(LodBufferContainer container)
	{
		long vertexCount = 0L;
		{
			vertexCount += countVertices(container, RenderBufferHandler::getOpaqueBufferWrappers);
			vertexCount += countVertices(container, RenderBufferHandler::getTransparentBufferWrappers);
			vertexCount += countVertices(container, RenderBufferHandler::getOpaqueNoSsaoBufferWrappers);
		}
		return vertexCount;
	}
	private static long countVertices(
		SortedArraySet<LodBufferContainer> bufferContainerList, 
		Function<LodBufferContainer, IDhApiVertexBufferWrapper[]> getBufferWrapperArrayFunc)
	{
		long vertexCount = 0;
		for (int containerIndex = 0; containerIndex < bufferContainerList.size(); containerIndex++)
		{
			LodBufferContainer bufferContainer = bufferContainerList.get(containerIndex);
			if (bufferContainer != null)
			{
				vertexCount += countVertices(bufferContainer, getBufferWrapperArrayFunc);
			}
		}
		return vertexCount;
	}
	private static long countVertices(
		LodBufferContainer bufferContainer,
		Function<LodBufferContainer, IDhApiVertexBufferWrapper[]> getBufferWrapperArrayFunc)
	{
		long vertexCount = 0;
		IDhApiVertexBufferWrapper[] bufferWrapperArray = getBufferWrapperArrayFunc.apply(bufferContainer);
		for (int bufferIndex = 0; bufferIndex < bufferWrapperArray.length; bufferIndex++)
		{
			IDhApiVertexBufferWrapper bufferWrapper = bufferWrapperArray[bufferIndex];
			if (bufferWrapper != null)
			{
				vertexCount += bufferWrapper.getVertexCount();
			}
		}
		return vertexCount;
	}
	private static IDhApiVertexBufferWrapper[] getOpaqueBufferWrappers(LodBufferContainer container) { return container.getVboOpaqueWrappers(); }
	private static IDhApiVertexBufferWrapper[] getTransparentBufferWrappers(LodBufferContainer container) { return container.getVboTransparentWrappers(); }
	private static IDhApiVertexBufferWrapper[] getOpaqueNoSsaoBufferWrappers(LodBufferContainer container) { return container.getVboWithoutSsaoWrappers(); }
	
	//endregion
	
	
	
	//================//
	// render methods //
	//================//
	//region
	
	public SortedArraySet<LodBufferContainer> getColumnRenderBuffers(RenderParam renderParams)
	{
		// If we don't check this, when the renderer changes, it might reuse the wrong/old objects
		if (this.currentRenderDefinition != renderParams.renderDefinition)
		{
			// the render definition changed, rebuild everything to use the new objects 
			this.buildRenderList(renderParams);
		}
		
		return this.loadedNearToFarBuffers;
	}
	
	//endregion
	
	
	
	//=========//
	// F3 menu //
	//=========//
	//region
	
	public String getVboRenderDebugMenuString()
	{
		// vertex count logic commented out to reduce performance impact and due to not being needed most of the time
		
		String bufferCountText = F3Screen.NUMBER_FORMAT.format(this.visibleBufferCount);
		//String vertexCountText = F3Screen.NUMBER_FORMAT.format(this.visibleVertexCount);
		if (!Config.Client.Advanced.Graphics.Culling.disableFrustumCulling.get())
		{
			bufferCountText += "/" + F3Screen.NUMBER_FORMAT.format(this.visibleBufferCount + this.culledBufferCount);
			//vertexCountText += "/" + F3Screen.NUMBER_FORMAT.format(this.visibleVertexCount + this.culledVertexCount);
		}
		return "VBO Render Count: [" + bufferCountText + "]";
		//return "Render Count, VBO: ["+bufferCountText+"] Vertex: ["+vertexCountText+"]";
	}
	public String getShadowPassRenderDebugMenuString()
	{
		boolean hasIrisShaders = (IRIS_ACCESSOR != null && IRIS_ACCESSOR.isShaderPackInUse());
		if (!hasIrisShaders)
		{
			return null;
		}
		
		String countText = F3Screen.NUMBER_FORMAT.format(this.shadowVisibleBufferCount);
		if (!Config.Client.Advanced.Graphics.Culling.disableFrustumCulling.get())
		{
			countText += "/" + F3Screen.NUMBER_FORMAT.format(this.shadowVisibleBufferCount + this.shadowCulledBufferCount);
		}
		return "Shadow VBO Render Count: [" + countText + "]";
	}
	
	//endregion
	
	
	
	//=========//
	// cleanup //
	//=========//
	//region
	
	@Override
	public void close() { this.lodQuadTree.close(); }
	
	//endregion
	
	
	
}
