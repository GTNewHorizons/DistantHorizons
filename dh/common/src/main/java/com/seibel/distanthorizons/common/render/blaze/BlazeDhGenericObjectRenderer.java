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

package com.seibel.distanthorizons.common.render.blaze;

#if MC_VER <= MC_1_21_10
public class BlazeDhGenericObjectRenderer {}

#else

#if MC_VER <= MC_26_2_0
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
#else
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.vertex.VertexFormat;
#endif

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericRenderCleanupEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericRenderSetupEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBoxGroupShading;
import com.seibel.distanthorizons.common.render.blaze.objects.BlazeGenericObjectVertexContainer;
import com.seibel.distanthorizons.common.render.blaze.util.BlazeDhVertexFormatUtil;
import com.seibel.distanthorizons.common.render.blaze.wrappers.BlazeVertexFormatBuilder;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPassWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.RenderPipelineBuilderWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureViewWrapper;
import com.seibel.distanthorizons.common.render.blaze.wrappers.uniform.BlazeUniformBufferWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.LightMapWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.render.RenderParam;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiGenericRenderer;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;

import java.util.Collection;

/**
 * Handles rendering generic groups of {@link DhApiRenderableBox}.
 * 
 * @see IDhApiCustomRenderRegister
 * @see DhApiRenderableBox
 */
public class BlazeDhGenericObjectRenderer implements IDhApiGenericRenderer
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	public static final BlazeDhGenericObjectRenderer INSTANCE = new BlazeDhGenericObjectRenderer();
	
	
	private static final GpuDevice GPU_DEVICE = RenderSystem.getDevice();
	private static final CommandEncoder COMMAND_ENCODER = GPU_DEVICE.createCommandEncoder();
	
	private static final DhApiRenderableBoxGroupShading DEFAULT_SHADING = DhApiRenderableBoxGroupShading.getUnshaded();
	
	private static final DhApiBeforeGenericObjectRenderEvent.EventParam EVENT_PARAM = new DhApiBeforeGenericObjectRenderEvent.EventParam();
	
	
	
	
	// rendering setup
	private boolean init = false;
	
	private RenderPipeline pipeline;
	
	private final BlazeUniformBufferWrapper vertUniformBufferWrapper = new BlazeUniformBufferWrapper("vertUniformBlock");
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private BlazeDhGenericObjectRenderer() { }
	
	@Override
	public void preRender(DhApiRenderParam renderEventParam)
	{
		if (this.init)
		{
			return;
		}
		this.init = true;
		
		this.createPipelines(renderEventParam);
	}
	private void createPipelines(DhApiRenderParam renderEventParam)
	{
		RenderPipelineBuilderWrapper pipelineBuilder = new RenderPipelineBuilderWrapper();
		{
			pipelineBuilder.withFaceCulling(true);
			pipelineBuilder.withDepthWrite(true);
			if (renderEventParam.renderDefinition.getDepthDirection() == EDhApiDepthDirection.FORWARD_Z)
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.LESS);
			}
			else
			{
				pipelineBuilder.withDepthTest(RenderPipelineBuilderWrapper.EDhDepthTest.GREATER);
			}
			pipelineBuilder.withBlend(BlendFunction.TRANSLUCENT); // TRANSLUCENT = new BlendFunction(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA);
			pipelineBuilder.withColorWrite(true);
			pipelineBuilder.withPolygonMode(RenderPipelineBuilderWrapper.EDhPolygonMode.FILL);
			pipelineBuilder.withName("generic_objects");
			
			pipelineBuilder.withVertexShader("generic/blaze/vert");
			pipelineBuilder.withFragmentShader("generic/blaze/frag");
			
			pipelineBuilder.withSampler("uLightMap");
			
			pipelineBuilder.withUniformBuffer("vertUniformBlock");
			
			VertexFormat vertexFormat = new BlazeVertexFormatBuilder()
				.add("vPosition", BlazeDhVertexFormatUtil.FLOAT_XYZ_POS)
				.add("aColor", BlazeDhVertexFormatUtil.RGBA_UBYTE_COLOR)
				.add("aMaterial", BlazeDhVertexFormatUtil.IRIS_MATERIAL)
				
				.add("paddingOne", BlazeDhVertexFormatUtil.BYTE_PAD)
				.add("paddingTwo", BlazeDhVertexFormatUtil.BYTE_PAD)
				.add("paddingThree", BlazeDhVertexFormatUtil.BYTE_PAD)
				.build();
			pipelineBuilder.withVertexFormat(vertexFormat);
			pipelineBuilder.withVertexMode(RenderPipelineBuilderWrapper.EDhVertexMode.TRIANGLES);
		}
		this.pipeline = pipelineBuilder.build();
	}
	
	//endregion
	
	
	
	//===========//
	// rendering //
	//===========//
	//region
	
	@Override
	public void render(DhApiRenderParam apiRenderEventParam, IDhApiRenderableBoxGroup boxGroup)
	{
		RenderParam renderEventParam = (RenderParam)apiRenderEventParam;
		
		DhApiRenderableBoxGroupShading shading = boxGroup.getShading();
		if (shading == null)
		{
			shading = DEFAULT_SHADING;
		}
		
		
		
		// uniforms //
		{
			// create data //
			
			DhMat4f projectionMvmMatrix = new DhMat4f(renderEventParam.dhProjectionMatrix);
			projectionMvmMatrix.multiply(renderEventParam.dhModelViewMatrix);
			
			DhVec3d camPos = MC_RENDER.getCameraExactPosition();
			
			
			// upload data //
			
			this.vertUniformBufferWrapper
				.putVec3i(
					LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().x),
					LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().y),
					LodUtil.getChunkPosFromDouble(boxGroup.getOriginBlockPos().z)
				) // uOffsetChunk
				.putVec3f(
					LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().x),
					LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().y),
					LodUtil.getSubChunkPosFromDouble(boxGroup.getOriginBlockPos().z)
				) // uOffsetSubChunk
				.putVec3i(
					LodUtil.getChunkPosFromDouble(camPos.x),
					LodUtil.getChunkPosFromDouble(camPos.y),
					LodUtil.getChunkPosFromDouble(camPos.z)
				) // uCameraPosChunk
				.putVec3f(
					LodUtil.getSubChunkPosFromDouble(camPos.x),
					LodUtil.getSubChunkPosFromDouble(camPos.y),
					LodUtil.getSubChunkPosFromDouble(camPos.z)
				) // uCameraPosSubChunk
				
				.putMat4f(projectionMvmMatrix) // uProjectionMvm
				.putInt(boxGroup.getSkyLight()) // uSkyLight
				.putInt(boxGroup.getBlockLight()) // uBlockLight
				
				.putFloat(shading.north)
				.putFloat(shading.south)
				.putFloat(shading.east)
				.putFloat(shading.west)
				.putFloat(shading.top)
				.putFloat(shading.bottom)
				
				.finishAndUpload()
			;
		}
		
		
		
		// rendering //
		try (RenderPassWrapper renderPassWrapper = new RenderPassWrapper(
			this::getRenderPassName,
			BlazeDhMetaRenderer.INSTANCE.dhColorTextureWrapper,
			BlazeDhMetaRenderer.INSTANCE.dhDepthTextureWrapper))
		{
			
			// update instance data //
			
			BlazeGenericObjectVertexContainer vertexContainer = (BlazeGenericObjectVertexContainer) boxGroup.getRenderVertexBufferContainer();
			
			LightMapWrapper lightMapWrapper = (LightMapWrapper) renderEventParam.lightmap;
			BlazeTextureViewWrapper lightmapTextureViewWrapper = lightMapWrapper.getTextureViewWrapper();
			renderPassWrapper.bindTexture("uLightMap", lightmapTextureViewWrapper);
			
			
			
			// Bind instance data //
			
			renderPassWrapper.setUniform("vertUniformBlock", this.vertUniformBufferWrapper);
			
			// set pipeline
			renderPassWrapper.setPipeline(this.pipeline);
			renderPassWrapper.setIndexBuffer(vertexContainer.indexGpuBuffer);
			
			renderPassWrapper.setVertexBuffer(vertexContainer.vboGpuBuffer);
			
			// Draw instanced
			if (vertexContainer.uploadedBoxCount > 0)
			{
				// 36 = 6 faces * 6 verticies per face
				renderPassWrapper.drawIndexed(vertexContainer.uploadedBoxCount * 36);
			}
		}
	}
	private String getRenderPassName() { return "distantHorizons:GenericObjectRenderer"; }
	
	@Override 
	public void postRender(DhApiRenderParam renderEventParam)
	{
		
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	@Override
	public void close()
	{
		// close is called outside the render thread and buffer closing must be done on the render thread
		RenderThreadTaskHandler.INSTANCE.queueRunningOnRenderThread("Generic Obj Cleanup", () ->
		{
			if (this.vertUniformBufferWrapper != null)
			{
				this.vertUniformBufferWrapper.close();
			}
		});
	}
	
	//endregion
	
	
	
}
#endif