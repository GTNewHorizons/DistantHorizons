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

package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTerrainVertexFormat;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.AbstractDhApiRenderDefinition;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.ColumnRenderSource;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderRegistry;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderSet;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.dataObjects.render.columnViews.ColumnRenderView;
import org.jetbrains.annotations.Nullable;

/**
 * Used to populate the buffers in a {@link ColumnRenderSource} object.
 *
 * @see ColumnRenderSource
 */
public class ColumnRenderBufferBuilder
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("Column Buffer Builder");
	
	
	
	//==============//
	// vbo building //
	//==============//
	
	/** Looks up every set a section's palette uses once */
	@Nullable
	private static BlockRenderSet[] getSetsByPaletteIndex(ColumnRenderSource renderSource)
	{
		if (!renderSource.hasSetIds())
		{
			return null;
		}
		
		BlockRenderSet[] setsByPaletteIndex = new BlockRenderSet[renderSource.getPaletteSize()];
		for (int paletteIndex = 0; paletteIndex < setsByPaletteIndex.length; paletteIndex++)
		{
			setsByPaletteIndex[paletteIndex] = BlockRenderRegistry.INSTANCE.getRenderSet(renderSource.getPaletteSetId(paletteIndex));
		}
		return setsByPaletteIndex;
	}
	
	/** The set of a point */
	@Nullable
	static BlockRenderSet getDataPointRenderSet(BlockRenderSet @Nullable [] setsByPaletteIndex, ColumnRenderSource renderSource, int relX, int relZ, int index)
	{
		if (setsByPaletteIndex == null)
		{
			return null;
		}
		
		// the palette can grow while a build is running
		int paletteIndex = renderSource.getDataPointPaletteIndex(relX, relZ, index);
		return (paletteIndex < setsByPaletteIndex.length) ? setsByPaletteIndex[paletteIndex] : null;
	}
	
	public static void makeLodRenderData(
			LodQuadBuilder quadBuilder, ColumnRenderSource renderSource, IClientLevelWrapper clientLevelWrapper, AbstractDhApiRenderDefinition renderDefinition,
			ColumnRenderSource[] adjRegions, ColumnRenderSource[][] diagonalRegions, boolean[] isSameDetailLevel)
	{
		//=============//
		// debug check //
		//=============//
		
		// can be used to limit which section positions are build and thus, rendered
		// useful when debugging a specific section
		boolean columnBuilderDebugEnabled = Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugEnable.get();
		if (columnBuilderDebugEnabled)
		{
			if (DhSectionPos.getDetailLevel(renderSource.pos) == Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugDetailLevel.get()
				&& DhSectionPos.getX(renderSource.pos) == Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugXPos.get()
				&& DhSectionPos.getZ(renderSource.pos) == Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugZPos.get())
			{
				int breakpoint = 0;
			}
			else
			{
				return;
			}
		}
		
		
		
		// only formats with an offset can lower vertices
		boolean lowerFluidSurfaces = (quadBuilder.getVertexFormat() == EDhApiTerrainVertexFormat.POS_META_COLOR_MATERIAL_NORMAL_TEXTURE_OFFSET);
		boolean drawModels = BlockRenderRegistry.modelsDrawnAtDetailLevel(renderSource.getDataDetailLevel(), renderDefinition);
		
		//===================//
		// build each column //
		//===================//
		
		// pooled arrays for ColumnBox use
		try (PhantomArrayListCheckout phantomArrayCheckout = ARRAY_LIST_POOL.checkoutLongArrays(2);
			ColumnRenderView columnRenderData = ColumnRenderView.getPooled();
			ColumnRenderView northAdjView = ColumnRenderView.getPooled();
			ColumnRenderView southAdjView = ColumnRenderView.getPooled();
			ColumnRenderView eastAdjView = ColumnRenderView.getPooled();
			ColumnRenderView westAdjView = ColumnRenderView.getPooled();
			ColumnRenderView fluidNeighborView = ColumnRenderView.getPooled())
		{
			ColumnRenderView[] adjColumnViews = new ColumnRenderView[EDhDirection.CARDINAL_COMPASS.length];
			adjColumnViews[EDhDirection.NORTH.compassIndex] = northAdjView;
			adjColumnViews[EDhDirection.SOUTH.compassIndex] = southAdjView;
			adjColumnViews[EDhDirection.EAST.compassIndex] = eastAdjView;
			adjColumnViews[EDhDirection.WEST.compassIndex] = westAdjView;
			short[] flowingFaceTileIds = new short[EDhDirection.ALL.length];
			int[] adjustedFaceColors = new int[EDhDirection.ALL.length];
			boolean[] hiddenModelSides = new boolean[EDhDirection.ALL.length];
			byte[] modelSideLights = new byte[EDhDirection.ALL.length];
			BlockRenderSet[] setsByPaletteIndex = getSetsByPaletteIndex(renderSource);
			IBlockStateWrapper water = WRAPPER_FACTORY.getWaterBlockStateWrapper(clientLevelWrapper);
			short waterSetId = BlockRenderRegistry.INSTANCE.getOrRegisterBlockStateSetId(water, clientLevelWrapper);
			BlockRenderSet waterSet = BlockRenderRegistry.INSTANCE.getRenderSet(waterSetId);
			
			
			byte thisDetailLevel = renderSource.getDataDetailLevel();
			int sectionMinBlockX = DhSectionPos.getMinCornerBlockX(renderSource.pos);
			int sectionMinBlockZ = DhSectionPos.getMinCornerBlockZ(renderSource.pos);
			
			// each neighbor section's sets
			BlockRenderSet[][] adjSectionSets = new BlockRenderSet[EDhDirection.CARDINAL_COMPASS.length][];
			for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS)
			{
				ColumnRenderSource adjRegion = adjRegions[direction.compassIndex];
				if (drawModels && adjRegion != null && adjRegion.getDataDetailLevel() == thisDetailLevel)
				{
					adjSectionSets[direction.compassIndex] = getSetsByPaletteIndex(adjRegion);
				}
			}
			
			// where each neighbor column was read from
			AdjacentColumnSets adjColumnSets = new AdjacentColumnSets();
			for (int relX = 0; relX < ColumnRenderSource.WIDTH; relX++)
			{
				for (int relZ = 0; relZ < ColumnRenderSource.WIDTH; relZ++)
				{
					renderSource.populateColumnView(columnRenderData, relX, relZ);
					
					// ignore empty columns
					if (columnRenderData.size == 0
						|| !RenderDataPointUtil.doesDataPointExist(columnRenderData.get(0))
						|| RenderDataPointUtil.hasZeroHeight(columnRenderData.get(0)))
					{
						continue;
					}
					
					
					//=============//
					// debug limit //
					//=============//
					
					// can be used to limit the buffer building to a specific relative position.
					// useful for debugging a single column
					if (columnBuilderDebugEnabled)
					{
						int wantedX = Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugXRow.get();
						if (wantedX >= 0 && relX != wantedX)
						{
							continue;
						}
						int wantedZ = Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugZRow.get();
						if (wantedZ >= 0 && relZ != wantedZ)
						{
							continue;
						}
					}
					
					
					
					//==================================//
					// get adjacent render data columns //
					//==================================//
					
					// clear the old data so we can handle if one of the adjacent columns is missing/empty
					adjColumnViews[EDhDirection.NORTH.compassIndex].clear();
					adjColumnViews[EDhDirection.SOUTH.compassIndex].clear();
					adjColumnViews[EDhDirection.EAST.compassIndex].clear();
					adjColumnViews[EDhDirection.WEST.compassIndex].clear();
					
					for (EDhDirection direction : EDhDirection.CARDINAL_COMPASS)
					{
						adjColumnSets.clear(direction);
						try
						{
							int xAdj = relX + direction.normal.x;
							int zAdj = relZ + direction.normal.z;
							boolean isCrossRenderSourceBoundary =
								(xAdj < 0 || xAdj >= ColumnRenderSource.WIDTH)
								|| (zAdj < 0 || zAdj >= ColumnRenderSource.WIDTH);
							
							ColumnRenderSource adjRenderSource;
							byte adjDetailLevel;
							
							
							
							//=========================//
							// get the adjacent render //
							// source if present       //
							//=========================//
							
							if (!isCrossRenderSourceBoundary)
							{
								// the adjacent position is inside this same render source
								adjRenderSource = renderSource;
								adjDetailLevel = thisDetailLevel;
							}
							else
							{
								// the adjacent position is outside this render source
								
								// skip empty sections
								adjRenderSource = adjRegions[direction.compassIndex];
								if (adjRenderSource == null)
								{
									continue;
								}
								
								adjDetailLevel = adjRenderSource.getDataDetailLevel();
								if (adjDetailLevel == thisDetailLevel)
								{
									// if the adjacent position is outside this render source,
									// wrap the position around so it's inside the adjacent source
									
									if (xAdj < 0)
									{
										xAdj += ColumnRenderSource.WIDTH;
									}
									if (xAdj >= ColumnRenderSource.WIDTH)
									{
										xAdj -= ColumnRenderSource.WIDTH;
									}
									
									if (zAdj < 0)
									{
										zAdj += ColumnRenderSource.WIDTH;
									}
									if (zAdj >= ColumnRenderSource.WIDTH)
									{
										zAdj -= ColumnRenderSource.WIDTH;
									}
								}
							}
							
							
							
							//========================//
							// get the adjacent views //
							//========================//
							
							// the old logic handled additional cases, but they never appeared to fire,
							// so just these two cases should be fine
							boolean expectedDetailLevels = (adjDetailLevel == thisDetailLevel) || (adjDetailLevel > thisDetailLevel);
							if (!expectedDetailLevels)
							{
								LodUtil.assertNotReach("Mismatch between adjacent detail level ["+adjDetailLevel+"] and this render source's detail level ["+thisDetailLevel+"]. Detail levels should be adj >= this.");
							}
							
							adjRenderSource.populateColumnView(adjColumnViews[direction.compassIndex], xAdj, zAdj);
							
							// neighbors without models are boxes
							if (drawModels && adjDetailLevel == thisDetailLevel && adjRenderSource.columnDrawsModels(xAdj, zAdj))
							{
								BlockRenderSet[] adjSets = isCrossRenderSourceBoundary ? adjSectionSets[direction.compassIndex] : setsByPaletteIndex;
								adjColumnSets.set(direction, adjRenderSource, adjSets, xAdj, zAdj);
							}
						}
						catch (RuntimeException e)
						{
							LOGGER.warn("Failed to get adj data for relative pos: [" + thisDetailLevel + ":" + relX + "," + relZ + "] at [" + direction + "], Error: [" + e.getMessage() + "].", e);
						}
					} // for adjacent directions
					
					
					
					//==========================//
					// build this render column //
					//==========================//
					
					boolean modelsInThisColumn = drawModels && renderSource.columnDrawsModels(relX, relZ);
					
					for (int i = 0; i < columnRenderData.size; i++)
					{
						// can be uncommented to limit which vertical LOD is generated
						if (Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugEnable.get())
						{
							int wantedColumnIndex = Config.Client.Advanced.Debugging.ColumnBuilderDebugging.columnBuilderDebugColumnIndex.get();
							if (wantedColumnIndex >= 0
								&& i != wantedColumnIndex)
							{
								continue;
							}
						}
						
						long data = columnRenderData.get(i);
						// If the data is not render-able (Void or non-existing) we stop since there is
						// no data left in this position
						if (RenderDataPointUtil.hasZeroHeight(data)
							|| !RenderDataPointUtil.doesDataPointExist(data))
						{
							break;
						}
						
						long topDataPoint = (i - 1) >= 0 ? columnRenderData.get(i - 1) : RenderDataPointUtil.EMPTY_DATA;
						long bottomDataPoint = (i + 1) < columnRenderData.size ? columnRenderData.get(i + 1) : columnRenderData.getVoid();
						
						// the datapoint below this may be empty due to how datapoint reducing
						// is done. In that case use the void datapoint.
						if (bottomDataPoint == RenderDataPointUtil.EMPTY_DATA)
						{
							bottomDataPoint = columnRenderData.getVoid();
						}
						
						BlockRenderSet renderSet = getDataPointRenderSet(setsByPaletteIndex, renderSource, relX, relZ, i);
						
						BlockRenderSet modelSet = null;
						if (modelsInThisColumn && renderSet != null && renderSet.modelQuads.length != 0)
						{
							modelSet = renderSet;
						}
						
						// waterlogged models are drawn inside water
						BlockRenderSet faceSet = (modelSet != null && modelSet.blockState.isWaterlogged()) ? waterSet : renderSet;
						
						// determine which textures this data point's quads should use
						short[] faceTileIdsByDirectionOrdinal = null;
						int[] faceColors = null;
						if (faceSet != null && renderSource.usesTextures())
						{
							faceTileIdsByDirectionOrdinal = faceSet.faceTileIds;
							faceColors = faceSet.faceColors;
						}
						
						// the points above and below
						BlockRenderSet topSet = null;
						BlockRenderSet bottomSet = null;
						if (modelsInThisColumn)
						{
							topSet = (i > 0) ? getDataPointRenderSet(setsByPaletteIndex, renderSource, relX, relZ, i - 1) : null;
							boolean bottomIsInColumn = (i + 1 < columnRenderData.size) && columnRenderData.get(i + 1) != RenderDataPointUtil.EMPTY_DATA;
							bottomSet = bottomIsInColumn ? getDataPointRenderSet(setsByPaletteIndex, renderSource, relX, relZ, i + 1) : null;
						}
						
						short topDrops = FluidSurfaceUtil.createNoDrop();
						if (lowerFluidSurfaces && RenderDataPointUtil.isFluid(data))
						{
							topDrops = FluidSurfaceUtil.createCornerDrop(columnRenderData, i, renderSource, adjRegions, diagonalRegions, relX, relZ, fluidNeighborView);
						}
						
						// sloped fluid surfaces turn their texture downhill
						EDhDirection flowDirection = FluidSurfaceUtil.getFlowDirection(topDrops);
						if (flowDirection != null
							&& faceTileIdsByDirectionOrdinal != null
							&& faceTileIdsByDirectionOrdinal.length > BlockRenderRegistry.FLUID_FLOW_TILE_INDEX)
						{
							System.arraycopy(faceTileIdsByDirectionOrdinal, 0, flowingFaceTileIds, 0, flowingFaceTileIds.length);
							flowingFaceTileIds[EDhDirection.UP.faceIndex] = faceTileIdsByDirectionOrdinal[BlockRenderRegistry.FLUID_FLOW_TILE_INDEX + flowDirection.compassIndex];
							faceTileIdsByDirectionOrdinal = flowingFaceTileIds;
						}
						
						addRenderDataPointToBuilder(
							clientLevelWrapper, phantomArrayCheckout,
							data, topDataPoint, bottomDataPoint,
							adjColumnViews, isSameDetailLevel, faceTileIdsByDirectionOrdinal, faceColors, adjustedFaceColors,
							hiddenModelSides, modelSideLights,
							thisDetailLevel, relX, relZ, sectionMinBlockX, sectionMinBlockZ,
							quadBuilder, topDrops, modelSet, renderSource.usesTextures(),
							topSet, bottomSet, adjColumnSets);
					}
				}// for z
			}// for x
		}// phantom checkout
		
		quadBuilder.mergeQuads();
	}
	private static void addRenderDataPointToBuilder(
			IClientLevelWrapper clientLevelWrapper, PhantomArrayListCheckout phantomArrayCheckout,
			long renderData, long topRenderData, long bottomRenderData, 
			ColumnRenderView[] adjColumnViews, boolean[] isSameDetailLevel, short @Nullable [] faceTileIdsByDirectionOrdinal, 
			int @Nullable [] faceColors, int[] adjustedFaceColors,
			boolean[] hiddenModelSides, byte[] modelSideLights,
			byte detailLevel, int renderSourceOffsetPosX, int renderSourceOffsetPosZ, int sectionMinBlockX, int sectionMinBlockZ,
			LodQuadBuilder quadBuilder, short topDrops, @Nullable BlockRenderSet modelSet, boolean useTextures,
			@Nullable BlockRenderSet topSet, @Nullable BlockRenderSet bottomSet,
			AdjacentColumnSets adjColumnSets)
	{
		long sectionPos = DhSectionPos.encode(detailLevel, renderSourceOffsetPosX, renderSourceOffsetPosZ);
		
		short blockWidth = (short) DhSectionPos.getDetailLevelWidthInBlocks(detailLevel);
		short blockMinX = (short) DhSectionPos.getMinCornerBlockX(sectionPos);
		short blockMinY = RenderDataPointUtil.getYMin(renderData);
		short blockMinZ = (short) DhSectionPos.getMinCornerBlockZ(sectionPos);
		short blockMaxY = (short) (RenderDataPointUtil.getYMax(renderData) - blockMinY);
		
		if (blockMaxY == 0)
		{
			return;
		}
		else if (blockMaxY < 0)
		{
			throw new IllegalArgumentException("Negative y size for the renderDataPoint! Data: [" + RenderDataPointUtil.toString(renderData) + "].");
		}
		
		byte blockMaterialId = RenderDataPointUtil.getBlockMaterialId(renderData);
		
		
		
		int color;
		int[] finalFaceColors = null;
		boolean fullBright = false;
		EDhApiDebugRendering debugging = Config.Client.Advanced.Debugging.debugRenderingColors.get();
		switch (debugging)
		{
			case OFF:
			{
				float saturationMultiplier = Config.Client.Advanced.Graphics.Quality.saturationMultiplier.get();
				float brightnessMultiplier = Config.Client.Advanced.Graphics.Quality.brightnessMultiplier.get();
				color = applyColorSettings(RenderDataPointUtil.getColor(renderData), saturationMultiplier, brightnessMultiplier);
				
				// grass sides fade from the LOD's color, see LodQuadBuilder
				if (faceColors != null
					&& blockMaterialId != EDhApiBlockMaterial.GRASS.index)
				{
					for (int faceIndex = 0; faceIndex < faceColors.length; faceIndex++)
					{
						adjustedFaceColors[faceIndex] = (faceColors[faceIndex] == BlockRenderSet.USE_LOD_COLOR)
							? BlockRenderSet.USE_LOD_COLOR
							: applyColorSettings(faceColors[faceIndex], saturationMultiplier, brightnessMultiplier);
					}
					finalFaceColors = adjustedFaceColors;
				}
				break;
			}
			case SHOW_DETAIL:
			{
				color = LodUtil.DEBUG_DETAIL_LEVEL_COLORS[detailLevel];
				fullBright = true;
				break;
			}
			case SHOW_BLOCK_MATERIAL:
			{
				
				switch (EDhApiBlockMaterial.getFromIndex(blockMaterialId))
				{
					case UNKNOWN:
					case AIR: // shouldn't normally be rendered, but just in case
						color = ColorUtil.HOT_PINK;
						break;
					
					case LEAVES:
						color = ColorUtil.DARK_GREEN;
						break;
					case STONE:
						color = ColorUtil.GRAY;
						break;
					case WOOD:
						color = ColorUtil.BROWN;
						break;
					case METAL:
						color = ColorUtil.DARK_GRAY;
						break;
					case DIRT:
						color = ColorUtil.LIGHT_BROWN;
						break;
					case LAVA:
						color = ColorUtil.ORANGE;
						break;
					case DEEPSLATE:
						color = ColorUtil.BLACK;
						break;
					case SNOW:
						color = ColorUtil.WHITE;
						break;
					case SAND:
						color = ColorUtil.TAN;
						break;
					case TERRACOTTA:
						color = ColorUtil.DARK_ORANGE;
						break;
					case NETHER_STONE:
						color = ColorUtil.DARK_RED;
						break;
					case WATER:
						color = ColorUtil.BLUE;
						break;
					case GRASS:
						color = ColorUtil.GREEN;
						break;
					case ILLUMINATED:
						color = ColorUtil.YELLOW;
						break;
					
					default:
						// undefined color
						color = ColorUtil.CYAN;
						break;
				}
				
				fullBright = true;
				break;
			}
			case SHOW_OVERLAPPING_QUADS:
			{
				color = ColorUtil.WHITE;
				fullBright = true;
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown debug mode: " + debugging);
		}
		
		if (modelSet != null)
		{
			// only the water around the model is see-through
			boolean waterlogged = modelSet.blockState.isWaterlogged();
			int modelColor = waterlogged ? ColorUtil.setAlpha(color, 255) : color;
			ColumnModel.addModelQuadsToBuilder(
				quadBuilder, clientLevelWrapper,
				modelSet, useTextures, debugging == EDhApiDebugRendering.OFF,
				blockMinX, blockMinY, blockMinZ, blockMaxY,
				sectionMinBlockX + blockMinX, sectionMinBlockZ + blockMinZ,
				modelColor, blockMaterialId,
				RenderDataPointUtil.getLightSky(renderData),
				fullBright ? LodUtil.MAX_MC_LIGHT : RenderDataPointUtil.getLightBlock(renderData),
				topRenderData, bottomRenderData, topSet, bottomSet,
				adjColumnViews, adjColumnSets, hiddenModelSides, modelSideLights);
			
			// waterlogged models also draw their water
			if (!waterlogged)
			{
				return;
			}
		}
		
		ColumnBox.addBoxQuadsToBuilder(
			quadBuilder, phantomArrayCheckout, clientLevelWrapper,
			blockWidth, blockMaxY,
			blockMinX, blockMinY, blockMinZ,
			color, RenderDataPointUtil.getColor(renderData), // the raw datapoint colors is used for culling purposes and is handled separately to prevent culling issues when the color is modified
			blockMaterialId,
			RenderDataPointUtil.getLightSky(renderData),
			fullBright ? LodUtil.MAX_MC_LIGHT : RenderDataPointUtil.getLightBlock(renderData),
			topRenderData, bottomRenderData, adjColumnViews, isSameDetailLevel,
			topSet, bottomSet, adjColumnSets,
			faceTileIdsByDirectionOrdinal, finalFaceColors, topDrops, modelSet);
	}
	
	/** applies the saturation and brightness settings */
	static int applyColorSettings(int color, float saturationMultiplier, float brightnessMultiplier)
	{
		if (saturationMultiplier == 1.0 && brightnessMultiplier == 1.0)
		{
			return color;
		}
		
		float[] ahsv = ColorUtil.argbToAhsv(color);
		return ColorUtil.ahsvToArgb(ahsv[0], ahsv[1], ahsv[2] * saturationMultiplier, ahsv[3] * brightnessMultiplier);
	}
	
}
