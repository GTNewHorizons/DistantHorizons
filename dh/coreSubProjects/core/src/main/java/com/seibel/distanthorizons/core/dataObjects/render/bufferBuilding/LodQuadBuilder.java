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

import java.nio.ByteBuffer;
import java.util.*;

import com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiTerrainVertexFormat;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.AbstractDhApiRenderDefinition;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.textures.BlockRenderSet;
import com.seibel.distanthorizons.core.dataObjects.render.textures.DhBlockModelQuad;
import com.seibel.distanthorizons.core.dataObjects.render.textures.SideShape;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.ByteBufferCheckoutWrapper;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * Used to create the quads before they are converted to render-able buffers. <br><br>
 *
 * Note: the magic number 6 you see throughout this method represents the number of sides on a cube.
 */
public class LodQuadBuilder implements AutoCloseable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	
	/** ThreadLocal is the simplest way to allow each LOD loading thread to have their own builder */
	private static final ThreadLocal<LodQuadBuilder> THREAD_LOCAL = ThreadLocal.withInitial(LodQuadBuilder::new);
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("LodQuadBuilder");
	
	/** smallest vertex size, used to size index buffers */
	public static final int BYTES_PER_VERTEX = EDhApiTerrainVertexFormat.POS_META_COLOR_MATERIAL_NORMAL_TEXTURE.bytesPerVertex;
	public static final int BYTES_PER_QUAD = BYTES_PER_VERTEX * 4;
	/**
	 * Blocks and models share the same vertex buffers and draw call,
	 * so these tell the shader to use the vertex's u/v or the box face mapping
	 */
	private static final byte VERTEX_UV_FLAG = 0b1;
	private static final byte BOX_FACE_UV_FLAG = 0b0;
	/** the shader pulls these toward the camera */
	private static final byte FLAT_NEAR_SIDE_FLAG = 0b10;
	
	public static final int[][][] DIRECTION_VERTEX_IBO_QUAD = new int[][][]
	//region
		{
			// X,Z //
			{ // UP
				{1, 0}, // 0
				{1, 1}, // 1
				{0, 1}, // 2
				{0, 0}, // 3
			},
			{ // DOWN
				{0, 0}, // 0
				{0, 1}, // 1
				{1, 1}, // 2
				{1, 0}, // 3
			},
			
			// X,Y //
			{ // NORTH
				{0, 0}, // 0
				{0, 1}, // 1
				{1, 1}, // 2
				
				{1, 0}, // 3
			},
			{ // SOUTH
				{1, 0}, // 0
				{1, 1}, // 1
				{0, 1}, // 2
				
				{0, 0}, // 3
			},
			
			// Z,Y //
			{ // WEST
				{0, 0}, // 0
				{1, 0}, // 1
				{1, 1}, // 2
				
				{0, 1}, // 3
			},
			{ // EAST
				{0, 1}, // 0
				{1, 1}, // 1
				{1, 0}, // 2
				
				{0, 0}, // 3
			},
		};
	//endregion
	
	
	
	@SuppressWarnings("unchecked")
	private final ArrayList<BufferQuad>[] opaqueQuads = (ArrayList<BufferQuad>[]) new ArrayList[6];
	@SuppressWarnings("unchecked")
	private final ArrayList<BufferQuad>[] transparentQuads = (ArrayList<BufferQuad>[]) new ArrayList[6];
	/** model quads are never merged */
	private final ArrayList<BufferQuad> opaqueModelQuads = new ArrayList<>();
	private final ArrayList<BufferQuad> transparentModelQuads = new ArrayList<>();
	/** opaque model quads drawn after SSAO */
	private final ArrayList<BufferQuad> modelQuadsWithoutSsao = new ArrayList<>();
	/** the buffers without SSAO only hold model quads */
	@SuppressWarnings("unchecked")
	private static final ArrayList<BufferQuad>[] NO_BOX_QUADS = (ArrayList<BufferQuad>[]) new ArrayList[0];
	
	/** 
	 * Caching the BufferQuad objects reduces overhead slightly. <br>
	 * Caching is handled per builder (vs globally in {@link BufferQuad} itself) 
	 * to prevent concurrency overhead.
	 */
	private final ArrayList<BufferQuad> bufferQuadCacheList = new ArrayList<>();
	
	private boolean doTransparency;
	private IClientLevelWrapper clientLevelWrapper;
	
	private EDhApiDebugRendering debugRenderingMode;
	private EDhApiGrassSideRendering grassSideRenderingMode;
	
	private int premergeCount = 0;
	private EDhApiTerrainVertexFormat vertexFormat;
	
	
	
	//=============//
	// constructor //
	//=============//
	//region
	
	private LodQuadBuilder() 
	{
		for (int i = 0; i < 6; i++)
		{
			this.opaqueQuads[i] = new ArrayList<>();
			this.transparentQuads[i] = new ArrayList<>();
		}
	}
	
	public static LodQuadBuilder getBuilder(boolean doTransparency, IClientLevelWrapper clientLevelWrapper, AbstractDhApiRenderDefinition renderDefinition)
	{
		LodQuadBuilder builder = THREAD_LOCAL.get();
		builder.set(doTransparency, clientLevelWrapper, renderDefinition);
		return builder;
	}
	private void set(boolean doTransparency, IClientLevelWrapper clientLevelWrapper, AbstractDhApiRenderDefinition renderDefinition)
	{
		this.doTransparency = doTransparency;
		
		this.clientLevelWrapper = clientLevelWrapper;
		
		this.debugRenderingMode = Config.Client.Advanced.Debugging.debugRenderingColors.get();
		this.grassSideRenderingMode = Config.Client.Advanced.Graphics.Quality.grassSideRendering.get();
		this.vertexFormat = renderDefinition.getTerrainVertexFormat();
		
		this.premergeCount = 0;
	}
	
	//endregion
	
	
	
	//===========//
	// add quads //
	//===========//
	//region
	
	public void addQuadAdj(
		EDhDirection dir, 
		short x, short y, short z,
		short width, short height,
		int color, short faceTextureId,
		byte irisBlockMaterialId, byte skyLight, byte blockLight, short topDrops, @Nullable BlockRenderSet waterloggedModelSet, boolean cutOut)
	{
		if (dir == EDhDirection.DOWN)
		{
			throw new IllegalArgumentException("addQuadAdj() is only for adj direction! Not UP or Down!");
		}
		
		
		ArrayList<BufferQuad> quadList;
		if (this.doTransparency && ColorUtil.getAlpha(color) < 255)
		{
			quadList = this.transparentQuads[dir.faceIndex];
		}
		else
		{
			quadList = this.opaqueQuads[dir.faceIndex]; 
		}
		
		// water is cut around the model inside it
		SideShape modelSideShape = (waterloggedModelSet != null) ? waterloggedModelSet.getSideShape(dir) : null;
		if (modelSideShape != null)
		{
			// the model covers the whole side
			if (modelSideShape.uncoveredFacePiece == null)
			{
				return;
			}
			
			// the piece is in its block's own position
			short blockX = (dir == EDhDirection.EAST) ? (short) (x - width) : x;
			short blockZ = (dir == EDhDirection.SOUTH) ? (short) (z - width) : z;
			for (int blockY = y; blockY < y + height; blockY++)
			{
				BufferQuad pieceQuad = this.getOrCreateBufferQuad();
				pieceQuad.set(blockX, (short) blockY, blockZ, (short) 1, (short) 1, color, faceTextureId, irisBlockMaterialId, skyLight, blockLight, dir);
				pieceQuad.modelQuad = modelSideShape.uncoveredFacePiece;
				// only the top block is lowered
				pieceQuad.topDrops = (blockY == y + height - 1) ? topDrops : 0;
				quadList.add(pieceQuad);
			}
			return;
		}
		
		BufferQuad quad = this.getOrCreateBufferQuad();
		quad.set(x, y, z, width, height, color, faceTextureId, irisBlockMaterialId, skyLight, blockLight, dir);
		quad.topDrops = topDrops;
		quad.cutOut = cutOut;
		if (!quadList.isEmpty()
			&& (
				quadList.get(quadList.size() - 1).tryMerge(quad, BufferMergeDirectionEnum.EastWest)
				|| quadList.get(quadList.size() - 1).tryMerge(quad, BufferMergeDirectionEnum.NorthSouthOrUpDown))
			)
		{
			this.premergeCount++;
			return;
		}
		
		quadList.add(quad);
	}
	
	// XZ
	public void addQuadUp(
		short minX, short maxY, short minZ, 
		short blockWidth, 
		int color, short faceTextureId,
		byte irisBlockMaterialId, 
		byte skylight, byte blocklight, short topDrops)
	{
		boolean isTransparent = (this.doTransparency && ColorUtil.getAlpha(color) < 255);
		ArrayList<BufferQuad> quadList = isTransparent 
				? this.transparentQuads[EDhDirection.UP.faceIndex] 
				: this.opaqueQuads[EDhDirection.UP.faceIndex];
		
		BufferQuad quad = this.getOrCreateBufferQuad();
		quad.set(minX, maxY, minZ, blockWidth, blockWidth, color, faceTextureId, irisBlockMaterialId, skylight, blocklight, EDhDirection.UP);
		quad.topDrops = topDrops;
		quadList.add(quad);
	}
	
	public void addQuadDown(
		short x, short y, short z, 
		short blockWidth, 
		int color, short faceTextureId,
		byte irisBlockMaterialId, 
		byte skylight, byte blocklight)
	{
		ArrayList<BufferQuad> quadArray = (this.doTransparency && ColorUtil.getAlpha(color) < 255)
				? this.transparentQuads[EDhDirection.DOWN.faceIndex]
				: this.opaqueQuads[EDhDirection.DOWN.faceIndex];
		
		BufferQuad quad = this.getOrCreateBufferQuad();
		quad.set(x, y, z, blockWidth, blockWidth, color, faceTextureId, irisBlockMaterialId, skylight, blocklight, EDhDirection.DOWN);
		quadArray.add(quad);
	}
	
	public void addModelQuad(
		DhBlockModelQuad modelQuad,
		short x, short y, short z,
		int offsetX256, int offsetY256, int offsetZ256,
		int color, short textureTileId,
		byte irisBlockMaterialId,
		byte skylight, byte blocklight,
		boolean isFlatNearSide)
	{
		ArrayList<BufferQuad> quadList;
		if (this.doTransparency && ColorUtil.getAlpha(color) < 255)
		{
			quadList = this.transparentModelQuads;
		}
		else
		{
			// quads vanilla draws without ambient occlusion skip SSAO
			quadList = modelQuad.ambientOcclusion ? this.opaqueModelQuads : this.modelQuadsWithoutSsao;
		}
		
		BufferQuad quad = this.getOrCreateBufferQuad();
		quad.set(x, y, z, (short) 1, (short) 1, color, textureTileId, irisBlockMaterialId, skylight, blocklight, modelQuad.shadeDirection);
		quad.modelQuad = modelQuad;
		quad.modelOffsetX256 = offsetX256;
		quad.modelOffsetY256 = offsetY256;
		quad.modelOffsetZ256 = offsetZ256;
		quad.isFlatNearSide = isFlatNearSide;
		quadList.add(quad);
	}
	
	//endregion
	
	
	
	//=================//
	// data finalizing //
	//=================//
	//region
	
	/** Uses Greedy meshing to merge this builder's Quads. */
	public void mergeQuads()
	{
		long mergeCount = 0; // can be used for debugging
		long preQuadsCount = this.getCurrentOpaqueQuadsCount() + this.getCurrentTransparentQuadsCount();
		if (preQuadsCount <= 1)
		{
			return;
		}
		
		for (int directionIndex = 0; directionIndex < 6; directionIndex++)
		{
			mergeCount += mergeQuadsInternal(this.opaqueQuads, directionIndex, BufferMergeDirectionEnum.EastWest);
			if (this.doTransparency)
			{
				mergeCount += mergeQuadsInternal(this.transparentQuads, directionIndex, BufferMergeDirectionEnum.EastWest);
			}
			
			
			// only run the second merge if the face is the top or bottom
			if (directionIndex == EDhDirection.UP.faceIndex 
				|| directionIndex == EDhDirection.DOWN.faceIndex)
			{
				mergeCount += mergeQuadsInternal(this.opaqueQuads, directionIndex, BufferMergeDirectionEnum.NorthSouthOrUpDown);
				if (this.doTransparency)
				{
					mergeCount += mergeQuadsInternal(this.transparentQuads, directionIndex, BufferMergeDirectionEnum.NorthSouthOrUpDown);
				}
			}
		}
		
		//long postQuadsCount = this.getCurrentOpaqueQuadsCount() + this.getCurrentTransparentQuadsCount();
		//LOGGER.trace("Merged "+mergeCount+"/"+preQuadsCount+"("+(mergeCount / (double) preQuadsCount)+") quads");
	}
	
	/** Merges all of this builder's quads for the given directionIndex (up, down, left, etc.) in the given direction */
	private static long mergeQuadsInternal(ArrayList<BufferQuad>[] list, int directionIndex, BufferMergeDirectionEnum mergeDirection)
	{
		if (list[directionIndex].size() <= 1)
		{
			return 0;
		}
		
		list[directionIndex].sort((objOne, objTwo) -> objOne.compare(objTwo, mergeDirection));
		
		long mergeCount = 0;
		ListIterator<BufferQuad> iter = list[directionIndex].listIterator();
		BufferQuad currentQuad = iter.next();
		while (iter.hasNext())
		{
			BufferQuad nextQuad = iter.next();
			
			if (currentQuad.tryMerge(nextQuad, mergeDirection))
			{
				// merge successful, attempt to merge the next quad
				mergeCount++;
				iter.set(null);
			}
			else
			{
				// merge fail, move on to the next quad
				currentQuad = nextQuad;
			}
		}
		list[directionIndex].removeIf(Objects::isNull);
		return mergeCount;
	}
	
	//endregion
	
	
	
	//==============//
	// buffer setup //
	//==============//
	//region
	
	public ArrayList<ByteBuffer> makeOpaqueVertexBuffers(PhantomArrayListCheckout checkout) { return this.makeVertexBuffers(checkout, this.opaqueQuads, this.opaqueModelQuads); }
	public ArrayList<ByteBuffer> makeTransparentVertexBuffers(PhantomArrayListCheckout checkout) { return this.makeVertexBuffers(checkout, this.transparentQuads, this.transparentModelQuads); }
	public ArrayList<ByteBuffer> makeWithoutSsaoVertexBuffers(PhantomArrayListCheckout checkout) { return this.makeVertexBuffers(checkout, NO_BOX_QUADS, this.modelQuadsWithoutSsao); }
	private ArrayList<ByteBuffer> makeVertexBuffers(PhantomArrayListCheckout checkout, ArrayList<BufferQuad>[] quadList, ArrayList<BufferQuad> modelQuadList)
	{
		ArrayList<ByteBuffer> byteBufferList = new ArrayList<>(3);
		
		int byteBufferSize = getMaxBufferByteSize();
		
		ByteBuffer buffer = null;
		// the box face directions, then the model quads
		for (int listIndex = 0; listIndex <= quadList.length; listIndex++)
		{
			ArrayList<BufferQuad> quads = (listIndex < quadList.length) ? quadList[listIndex] : modelQuadList;
			
			// ignore empty directions
			if (quads.isEmpty())
			{
				continue;
			}
			
			// put all the quads in this direction into the buffer
			for (int quadIndex = 0; quadIndex < quads.size(); quadIndex++)
			{
				// if this is the first iteration or the buffer is full, 
				// create a new buffer
				if (buffer == null 
					|| buffer.remaining() < this.vertexFormat.bytesPerVertex * 4)
				{
					if ((byteBufferList.size() + 1) > checkout.getByteBufferWrapperCount())
					{
						ByteBufferCheckoutWrapper wrapper = new ByteBufferCheckoutWrapper(byteBufferSize);
						checkout.addByteBufferWrapper(wrapper);
						buffer = wrapper.buffer;
					}
					else
					{
						buffer = checkout.getByteBuffer(byteBufferList.size(), byteBufferSize);
					}
					
					byteBufferList.add(buffer);
				}
				
				this.putQuad(buffer, quads.get(quadIndex));
			}
		}
		
		// rewind all the buffers so they can be read from
		for (int i = 0; i < byteBufferList.size(); i++)
		{
			buffer = byteBufferList.get(i);
			buffer.limit(buffer.position());
			buffer.rewind();
		}
		
		return byteBufferList;
	}
	private void putModelQuad(ByteBuffer bb, BufferQuad quad)
	{
		int[] corners = quad.modelQuad.cornerPositions;
		byte[] uvs = quad.modelQuad.cornerUvBytes;
		int unitsPerBlock = DhBlockModelQuad.CORNER_UNITS_PER_BLOCK;
		byte flags = VERTEX_UV_FLAG;
		if (quad.isFlatNearSide)
		{
			flags |= FLAT_NEAR_SIDE_FLAG;
		}
		
		for (int i = 0; i < 4; i++)
		{
			// how far into the block this corner is, in 256ths of a block
			int x256 = corners[i * 3] + quad.modelOffsetX256;
			int y256 = corners[i * 3 + 1] + quad.modelOffsetY256;
			int z256 = corners[i * 3 + 2] + quad.modelOffsetZ256;
			
			// nothing sticks out above lowered water
			if (quad.topDrops != 0)
			{
				float drop = FluidSurfaceUtil.getDropAt(quad.topDrops, (float) x256 / unitsPerBlock, (float) z256 / unitsPerBlock);
				y256 = Math.min(y256, Math.round((1 - drop) * unitsPerBlock));
			}
			
			this.putVertex(bb,
					(short) (quad.x + Math.floorDiv(x256, unitsPerBlock)), (short) (quad.y + Math.floorDiv(y256, unitsPerBlock)), (short) (quad.z + Math.floorDiv(z256, unitsPerBlock)),
					quad.color, (byte) quad.direction.faceIndex,
					quad.irisBlockMaterialId, quad.skyLight, quad.blockLight,
					quad.textureTileId,
					0, 0, 0,
					(byte) Math.floorMod(x256, unitsPerBlock), (byte) Math.floorMod(y256, unitsPerBlock), (byte) Math.floorMod(z256, unitsPerBlock),
					flags, uvs[i * 2], uvs[i * 2 + 1],
					quad.modelQuad.packedNormal);
		}
	}
	private void putQuad(ByteBuffer bb, BufferQuad quad)
	{
		if (quad.modelQuad != null)
		{
			this.putModelQuad(bb, quad);
			return;
		}
		
		int[][] quadBase = DIRECTION_VERTEX_IBO_QUAD[quad.direction.faceIndex];
		short widthEastWest = quad.widthEastWest;
		short widthNorthSouth = quad.widthNorthSouthOrHeight;
		byte normalIndex = (byte) quad.direction.faceIndex;
		EDhDirection.Axis axis = quad.direction.axis;
		for (int i = 0; i < quadBase.length; i++)
		{
			short dx, dy, dz;
			int mx, my, mz;
			switch (axis)
			{
				case X: // ZY
					dx = 0;
					dy = quadBase[i][1] == 1 ? widthNorthSouth : 0;
					dz = quadBase[i][0] == 1 ? widthEastWest : 0;
					mx = 0;
					my = quadBase[i][1] == 1 ? 1 : -1;
					mz = quadBase[i][0] == 1 ? 1 : -1;
					break;
				case Y: // XZ
					dx = quadBase[i][0] == 1 ? widthEastWest : 0;
					dy = 0;
					dz = quadBase[i][1] == 1 ? widthNorthSouth : 0;
					mx = quadBase[i][0] == 1 ? 1 : -1;
					my = 0;
					mz = quadBase[i][1] == 1 ? 1 : -1;
					break;
				case Z: // XY
					dx = quadBase[i][0] == 1 ? widthEastWest : 0;
					dy = quadBase[i][1] == 1 ? widthNorthSouth : 0;
					dz = 0;
					mx = quadBase[i][0] == 1 ? 1 : -1;
					my = quadBase[i][1] == 1 ? 1 : -1;
					mz = 0;
					break;
				default:
					throw new IllegalArgumentException("Invalid Axis enum: " + axis);
			}
			
			
			int color = quad.color;
			
			int drop = 0;
			if (quad.topDrops != 0 && (quad.direction == EDhDirection.UP || dy > 0))
			{
				boolean isMaxX = (quad.direction == EDhDirection.EAST) || dx > 0;
				boolean isMaxZ = (quad.direction == EDhDirection.SOUTH) || dz > 0;
				drop = FluidSurfaceUtil.getCornerDrop(quad.topDrops, isMaxX, isMaxZ);
			}
			
			// use custom side color logic for grass blocks
			if (quad.irisBlockMaterialId == EDhApiBlockMaterial.GRASS.index)
			{
				// only use dirt colors if debug rendering is disabled
				if (this.debugRenderingMode == EDhApiDebugRendering.OFF)
				{
					// determine if any custom coloring logic should be used
					if (this.grassSideRenderingMode != EDhApiGrassSideRendering.AS_GRASS)
					{
						// only change the vertex color if it's on the side or bottom
						if (quad.direction.axis.isHorizontal() || quad.direction == EDhDirection.DOWN)
						{
							if (this.grassSideRenderingMode == EDhApiGrassSideRendering.AS_DIRT
								// if we want the color to fade, only apply the dirt color to the bottom vertices
								|| ((this.grassSideRenderingMode == EDhApiGrassSideRendering.FADE_TO_DIRT
									|| this.grassSideRenderingMode == EDhApiGrassSideRendering.GRASS_TOP_BLOCK)
									&& quadBase[i][1] == 0)
								// always render the bottom as dirt
								|| quad.direction == EDhDirection.DOWN)
							{
								// for horizontal and bottom faces of grass blocks, use the  dirt color to
								// prevent green cliff walls
								color = this.clientLevelWrapper.getDirtBlockColor();
								color = ColorUtil.applyShade(color, this.clientLevelWrapper.getShade(quad.direction));
							}
						}
					}
				}
			}
			
			
			// block Y of this vertex
			short vertexY = (short) (quad.y + dy);
			byte subY = 0;
			// lower fluid corners
			if (drop != 0 && this.vertexFormat == EDhApiTerrainVertexFormat.POS_META_COLOR_MATERIAL_NORMAL_TEXTURE_OFFSET)
			{
				vertexY--;
				subY = (byte) ((16 - drop) * 16);
			}
			
			byte uvFlag = BOX_FACE_UV_FLAG;
			byte u = 0;
			byte v = 0;
			if (quad.cutOut)
			{
				uvFlag = VERTEX_UV_FLAG;
				// same orientation as the shader's box face uvs
				if (quad.direction == EDhDirection.NORTH || quad.direction == EDhDirection.EAST)
				{
					u = (byte) ((quadBase[i][0] == 1) ? 0 : 255);
				}
				else
				{
					u = (byte) ((quadBase[i][0] == 1) ? 255 : 0);
				}
				v = (byte) ((dy > 0) ? 0 : 255);
			}
			
			this.putVertex(bb, (short) (quad.x + dx), vertexY, (short) (quad.z + dz),
					quad.hasError ? ColorUtil.RED : color,
					quad.hasError ? 0 : normalIndex,
					quad.hasError ? 0 : quad.irisBlockMaterialId,
					quad.hasError ? 15 : quad.skyLight,
					quad.hasError ? 15 : quad.blockLight,
					quad.hasError ? 0 : quad.textureTileId,
					mx, my, mz,
					(byte) 0, subY, (byte) 0,
					uvFlag, u, v, (short) 0);
		}
	}
	private void putVertex(
		ByteBuffer bb, 
		short x, short y, short z, 
		int color, 
		byte normalIndex, byte irisBlockMaterialId, 
		byte skylight, byte blocklight,
		short textureTileId, 
		int mx, int my, int mz,
		byte subX, byte subY, byte subZ,
		byte flags, byte u, byte v, short normal)
	{
		bb.putShort(x);
		bb.putShort(y);
		bb.putShort(z);
		
		short meta = 0;
		{
			skylight %= 16;
			blocklight %= 16;
			meta |= (short) (skylight | (blocklight << 4));
			
			byte mircoOffset = 0;
			// mirco offset which is a xyz 2bit value
			// 0b00 = no offset
			// 0b01 = positive offset
			// 0b11 = negative offset
			// format is: 0b00zzyyxx
			if (mx != 0) { mircoOffset |= (byte) (mx > 0 ? 0b01 : 0b11); }
			if (my != 0) { mircoOffset |= (byte) (my > 0 ? 0b0100 : 0b1100); }
			if (mz != 0) { mircoOffset |= (byte) (mz > 0 ? 0b010000 : 0b110000); }
			meta |= (short) (mircoOffset << 8);
		}
		bb.putShort(meta);
		
		byte r = (byte) ColorUtil.getRed(color);
		byte g = (byte) ColorUtil.getGreen(color);
		byte b = (byte) ColorUtil.getBlue(color);
		byte a = this.doTransparency ? (byte) ColorUtil.getAlpha(color) : (byte) 255;
		bb.put(r);
		bb.put(g);
		bb.put(b);
		bb.put(a);
		
		// Block ID and normal index are used by the Iris format
		bb.put(irisBlockMaterialId);
		bb.put(normalIndex);
		
		// These two bytes were originally padding to keep the vertex a multiple of 4
		// and are treated as padding by Iris's mirror of this format, 
		// so the tile id is invisible to shader packs.
		bb.putShort(textureTileId);
		
		if (this.vertexFormat == EDhApiTerrainVertexFormat.POS_META_COLOR_MATERIAL_NORMAL_TEXTURE_OFFSET)
		{
			bb.put(subX);
			bb.put(subY);
			bb.put(subZ);
			bb.put(flags);
			bb.put(u);
			bb.put(v);
			bb.putShort(normal);
		}
	}
	
	//endregion
	
	
	
	//=========//
	// getters //
	//=========//
	//region
	
	public EDhApiTerrainVertexFormat getVertexFormat() { return this.vertexFormat; }
	
	/** True if grass sides can draw their top block texture */
	public boolean drawsGrassTopBlock()
	{
		return this.grassSideRenderingMode == EDhApiGrassSideRendering.GRASS_TOP_BLOCK
			&& this.debugRenderingMode == EDhApiDebugRendering.OFF
			&& this.vertexFormat == EDhApiTerrainVertexFormat.POS_META_COLOR_MATERIAL_NORMAL_TEXTURE_OFFSET;
	}
	
	public int getCurrentOpaqueQuadsCount()
	{
		int i = 0;
		for (ArrayList<BufferQuad> quadList : this.opaqueQuads)
		{
			i += quadList.size();
		}
		
		return i;
	}
	public int getCurrentTransparentQuadsCount()
	{
		if (!this.doTransparency)
		{
			return 0;
		}
		
		int i = 0;
		for (ArrayList<BufferQuad> quadList : this.transparentQuads)
		{
			i += quadList.size();
		}
		
		return i;
	}
	
	private static int maxBufferByteSize = -1;
	/** 
	 * The max number of bytes we allow for a single Vertex buffer.
	 * If an LOD has more data than this it will be split
	 * up into multiple buffers.
	 */
	public static int getMaxBufferByteSize()
	{
		if (maxBufferByteSize != -1)
		{
			return maxBufferByteSize;
		}
		
		// 2 MB
		// note: this is relatively small (10 MB was the previous max) to reduce stuttering
		// during the upload process by having smaller upload steps
		int maxVboByteSize = 2 * 1024 * 1024; 
		
		int maxQuadsPerBuffer = maxVboByteSize / BYTES_PER_QUAD;
		// integer truncation to remove decimal component
		int fullSizedBuffer = maxQuadsPerBuffer * BYTES_PER_QUAD;
		
		maxBufferByteSize = fullSizedBuffer;
		
		return fullSizedBuffer;
	}
	
	//endregion
	
	
	
	//=====================//
	// buffer quad pooling //
	//=====================//
	//region
	
	private BufferQuad getOrCreateBufferQuad()
	{
		// start from the back of the list so we don't have
		// to move the array around
		int index = bufferQuadCacheList.size() - 1;
		if (index < 0)
		{
			// cache empty, create a new object
			return new BufferQuad();
		}
		
		BufferQuad quad = bufferQuadCacheList.remove(index);
		if (quad != null) // shouldn't happen, but just in case
		{
			return quad;
		}
		
		return new BufferQuad();
	}
	
	private static void returnQuadsToCache(ArrayList<BufferQuad> quadCache, ArrayList<BufferQuad>[] quadsToReturn)
	{
		for (int i = 0; i < quadsToReturn.length; i++)
		{
			returnQuadsToCache(quadCache, quadsToReturn[i]);
		}
	}
	private static void returnQuadsToCache(ArrayList<BufferQuad> quadCache, ArrayList<BufferQuad> quadsToReturn)
	{
		// manual add and loop to reduce GC pressure due to addAll() doing unnecessary
		// array copies
		for (int i = 0; i < quadsToReturn.size(); i++)
		{
			quadCache.add(quadsToReturn.get(i));
		}
		
		quadsToReturn.clear();
	}
	
	//endregion
	
	
	
	//================//
	// base overrides //
	//================//
	//region
	
	// can be used/closed multiple times
	@Override 
	public void close()
	{
		returnQuadsToCache(this.bufferQuadCacheList, this.opaqueQuads);
		returnQuadsToCache(this.bufferQuadCacheList, this.transparentQuads);
		returnQuadsToCache(this.bufferQuadCacheList, this.opaqueModelQuads);
		returnQuadsToCache(this.bufferQuadCacheList, this.transparentModelQuads);
		returnQuadsToCache(this.bufferQuadCacheList, this.modelQuadsWithoutSsao);
	}
	
	//endregion
	
	
	
}
