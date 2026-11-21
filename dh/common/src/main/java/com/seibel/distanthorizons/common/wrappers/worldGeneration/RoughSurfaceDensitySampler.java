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
package com.seibel.distanthorizons.common.wrappers.worldGeneration;

#if MC_VER <= MC_1_18_2

/** this surface generator only works for MC 1.19.2 and newer */
public class RoughSurfaceDensitySampler { }
#else

#if MC_VER <= MC_26_2_0
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
#else
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Interval;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Objects;
#endif

public final class RoughSurfaceDensitySampler
{
	//========================//
	// constructors/variables //
	//========================//
	//region
	
	#if MC_VER <= MC_26_2_0
	private final DensityFunction density;
	private final ThreadLocal<DhCacheContainer> cacheThreadLocal = new ThreadLocal<>();
	
	public RoughSurfaceDensitySampler(DensityFunction density)
	{ this.density = density; }
	
	#else
	private static final ContextKey<DhCacheContainer> CACHE_CONTEXT_KEY = new ContextKey<>(
		Identifier.fromNamespaceAndPath("distanthorizons", "rough_surface_request"));
	
	private final DensitySampler densitySampler;
	private final int cacheCount;
	private final ThreadLocal<DhCacheContainer> cacheThreadLocal = new ThreadLocal<>();
	
	public RoughSurfaceDensitySampler(RandomState randomState, DensityFunction densityFunction)
	{
		DhCacheRoot root = new DhCacheRoot(densityFunction);
		this.densitySampler = randomState.getSampler(root);
		this.cacheCount = root.cacheCount;
	}
	
	#endif
	
	//endregion
	
	
	
	//==========================//
	// thread local cache logic //
	//==========================//
	//region
	
	public void createThreadLocal()
	{
		if (this.cacheThreadLocal.get() != null)
		{
			throw new IllegalStateException("Rough surface sampling is already active");
		}
		
		#if MC_VER <= MC_26_2_0
		this.cacheThreadLocal.set(new DhCacheContainer(this.density.mapAll(new DhCacheVisitor())));
		#else
		this.cacheThreadLocal.set(new DhCacheContainer(this.cacheCount, this.densitySampler));
		#endif
	}
	
	public void removeThreadLocal() { this.cacheThreadLocal.remove(); }
	
	//endregion
	
	
	
	//==================//
	// 26.2.0 and older //
	//==================//
	//region
	#if MC_VER <= MC_26_2_0
	
	public double sampleValue(int blockX, int blockY, int blockZ)
	{
		DhCacheContainer cacheContainer = this.cacheThreadLocal.get();
		if (cacheContainer == null)
		{
			throw new IllegalStateException("Rough surface sampling is not active");
		}
		
		cacheContainer.sampleContext.blockX = blockX;
		cacheContainer.sampleContext.blockY = blockY;
		cacheContainer.sampleContext.blockZ = blockZ;
		
		return cacheContainer.densityFunction.compute(cacheContainer.sampleContext);
	}
	
	private static final class DhCacheContainer
	{
		private final DensityFunction densityFunction;
		private final DhSampleContext sampleContext = new DhSampleContext();
		
		private DhCacheContainer(DensityFunction densityFunction)
		{ this.densityFunction = densityFunction; }
	}
	
	private static final class DhCacheVisitor implements DensityFunction.Visitor
	{
		private final Map<DensityFunction, DensityFunction> mapped = new HashMap<>();
		
		@Override
		public @NotNull DensityFunction apply(@NotNull DensityFunction densityFunction)
		{
			return this.mapped.computeIfAbsent(densityFunction, (input) ->
			{
				if (input instanceof DensityFunctions.MarkerOrMarked)
				{
					return new DhCachedFunction(((DensityFunctions.MarkerOrMarked) input).wrapped());
				}
				else
				{
					return input;
				}
			});
		}
	}

	private static final class DhCachedFunction implements DensityFunction.SimpleFunction
	{
		private final DensityFunction densityFunction;
		
		private int blockX = 0;
		private int blockY = 0;
		private int blockZ = 0;
		private double cachedValue = 0.0;
		private boolean hasCachedValue = false;
		private boolean cacheMatchesBlockY = false;
		
		private DhCachedFunction(DensityFunction densityFunction)
		{ this.densityFunction = densityFunction; }
		
		@Override
		public double compute(DensityFunction.FunctionContext functionContext)
		{
			if (!(functionContext instanceof DhSampleContext))
			{
				return this.densityFunction.compute(functionContext);
			}
			
			DhSampleContext sampleContext = (DhSampleContext) functionContext;
			if (this.hasCachedValue 
				&& this.blockX == sampleContext.blockX 
				&& this.blockZ == sampleContext.blockZ
				&& 
				(
					!this.cacheMatchesBlockY 
					|| this.blockY == sampleContext.blockY
				)
			)
			{
				if (this.cacheMatchesBlockY)
				{
					// Enclosing caches must retain a cached child's Y dependency.
					sampleContext.yReads++;
				}
				return this.cachedValue;
			}
			return this.sample(sampleContext);
		}
		
		private double sample(DhSampleContext point)
		{
			// Reuse a column value only when its evaluation never reads Y.
			long beforeYReads = point.yReads;
			double result = this.densityFunction.compute(point);
			this.blockX = point.blockX;
			this.blockY = point.blockY;
			this.blockZ = point.blockZ;
			this.cachedValue = result;
			this.cacheMatchesBlockY = (beforeYReads != point.yReads);
			this.hasCachedValue = true;
			return result;
		}
		
		@Override
		public void fillArray(double @NotNull [] output, DensityFunction.@NotNull ContextProvider provider)
		{ this.densityFunction.fillArray(output, provider); }
		
		@Override public double minValue() { return this.densityFunction.minValue(); }
		@Override public double maxValue() { return this.densityFunction.maxValue(); }
		
		@Override
		public @NotNull KeyDispatchDataCodec<? extends DensityFunction> codec()
		{ return this.densityFunction.codec(); }
	}
	
	private static final class DhSampleContext implements DensityFunction.FunctionContext
	{
		private int blockX;
		private int blockY;
		private int blockZ;
		private long yReads;
		
		@Override public int blockX() { return this.blockX; }
		
		@Override
		public int blockY()
		{
			this.yReads++;
			return this.blockY;
		}
		
		@Override public int blockZ() { return this.blockZ; }
	}
	
	#endif
	//endregion
	
	
	
	//==================//
	// 26.3.0 and newer //
	//==================//
	//region
	
	#if MC_VER <= MC_26_2_0
	#else
	
	public float sampleValue(int x, int y, int z)
	{ return this.getCacheContainer().densityBound.sampleValue(x, y, z); }
	
	//void sampleVolume(DensityBuffer output, DensityVolume volume)
	//{ this.activeRequest().bound.sampleVolume(output, volume); }
	
	private DhCacheContainer getCacheContainer()
	{
		DhCacheContainer cacheContainer = this.cacheThreadLocal.get();
		if (cacheContainer == null)
		{
			throw new IllegalStateException("Thread local missing, make sure the local was created before world gen started.");
		}
		return cacheContainer;
	}
	
	
	
	private static final class DhCacheContainer
	{
		private final DhCacheCell[] cacheCells;
		private final DensitySampler.Bound densityBound;
		
		private DhCacheContainer(int count, DensitySampler sampler) 
		{
			this.cacheCells = new DhCacheCell[count];
			
			ContextMap fields = ContextMap.builder()
				.set(Beardifier.CONTEXT_KEY, Beardifier.EMPTY)
				.set(CACHE_CONTEXT_KEY, this)
				.build();
			this.densityBound = sampler.bind(SamplerContext.builder().setUserFields(fields).build());
		}
		
		private DhCacheCell getCacheCell(int index)
		{
			DhCacheCell cell = this.cacheCells[index];
			if (cell == null)
			{
				this.cacheCells[index] = cell = new DhCacheCell();
			}
			return cell;
		}
	}
	
	private static final class DhCacheCell
	{
		private long blockPosLong;
		/** defaults to NaN if not set */
		private float value = Float.NaN;
		private DensityVolume densityVolume;
		private DensityBuffer densityBuffer;
	}
	
	private static final class DhCacheRoot implements DensityFunction
	{
		private final DensityFunction densityFunction;
		public int cacheCount;
		
		private DhCacheRoot(DensityFunction densityFunction) { this.densityFunction = densityFunction; }
		
		@Override
		public @NonNull DensitySampler compileSampler(@NonNull CompileContext context)
		{
			DhCacheGraph cacheGraph = new DhCacheGraph(context);
			DensitySampler densitySampler = cacheGraph.prepare(this.densityFunction).compileSampler(context);
			this.cacheCount = cacheGraph.nextSlot;
			return densitySampler;
		}
		
		// Keep graph preparation inside the native compile context.
		@Override
		public @NonNull DensityFunction rewriteChildren(@NonNull DfRewriteRule rule) { return this; }
		
		@Override
		public @NonNull Interval range() { return this.densityFunction.range(); }
		
		@Override
		public int domainAxes() { return ALL_AXES; }
		
		@Override
		public @NonNull MapCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
	}
	
	private static final class DhCacheGraph
	{
		private final DensityFunction.CompileContext compileContext;
		private final HashMap<DensityFunction, DhPreparedDensity> preparedDensityByFunction = new HashMap<>();
		private int nextSlot;
		
		
		
		private DhCacheGraph(DensityFunction.CompileContext compileContext)
		{ this.compileContext = compileContext; }
		
		
		
		public DensityFunction prepare(DensityFunction input)
		{
			// Match native cache deduplication before uniform-axis slicing.
			return DfRewriteRule.SLICE_UNIFORM_AXES.rewrite(this.rewrite(input));
		}
		
		private DensityFunction rewrite(DensityFunction input)
		{
			DensityFunction densityFunction = DfRewriteRule.INLINE_REFERENCE.rewrite(input);
			if (densityFunction instanceof CacheFunction cacheFunction)
			{
				return this.getCachedDensity(cacheFunction.input());
			}
			else
			{
				return densityFunction.rewriteChildren(this::rewrite);
			}
		}
		
		private DhPreparedDensity getCachedDensity(DensityFunction input)
		{
			DhPreparedDensity dhPreparedDensity = this.preparedDensityByFunction.get(input);
			if (dhPreparedDensity == null)
			{
				int index = this.nextSlot++;
				DensityFunction childDensity = this.prepare(input);
				DensitySampler sampler = new DhCachedDensitySampler(index, childDensity.compileSampler(this.compileContext));
				dhPreparedDensity = new DhPreparedDensity(childDensity.range(), childDensity.domainAxes(), sampler);
				this.preparedDensityByFunction.put(input, dhPreparedDensity);
			}
			return dhPreparedDensity;
		}
	}
	
	private static final class DhCachedDensitySampler implements DensitySampler
	{
		private final int index;
		private final DensitySampler densitySampler;
		
		public DhCachedDensitySampler(int index, DensitySampler densitySampler)
		{
			this.index = index;
			this.densitySampler = densitySampler;
		}
		
		private DhCacheCell getCacheCell(SamplerContext context)
		{
			DhCacheContainer cacheContainer = context.getField(CACHE_CONTEXT_KEY);
			Objects.requireNonNull(cacheContainer); // we set this earlier, so it should never be null
			return cacheContainer.getCacheCell(this.index);
		}
		
		@Override
		public float sampleValue(@NotNull SamplerContext samplerContext, int x, int y, int z) // XYZ are block pos
		{
			DhCacheCell cacheCell = this.getCacheCell(samplerContext);
			long blockPosLong = BlockPos.asLong(x, y, z);
			if (cacheCell.blockPosLong == blockPosLong
				&& !Float.isNaN(cacheCell.value))
			{
				return cacheCell.value;
			}
			
			// Scalar cache entries never come from a sampled volume.
			float value = this.densitySampler.sampleValue(samplerContext, x, y, z);
			cacheCell.blockPosLong = blockPosLong;
			cacheCell.value = value;
			return value;
		}
		
		@Override
		public void sampleVolume(@NotNull SamplerContext samplerContext, @NotNull DensityBuffer outputByffer, @NotNull DensityVolume densityVolume)
		{
			DhCacheCell cacheCell = this.getCacheCell(samplerContext);
			if (cacheCell.densityBuffer == null
				|| !densityVolume.equals(cacheCell.densityVolume))
			{
				DensityBuffer buffer = DensityBuffer.createUnpooled(densityVolume.size());
				this.densitySampler.sampleVolume(samplerContext, buffer, densityVolume);
				cacheCell.densityVolume = densityVolume;
				cacheCell.densityBuffer = buffer;
			}
			
			outputByffer.copyFrom(cacheCell.densityBuffer);
		}
	}
	
	private static final class DhPreparedDensity implements DensityFunction
	{
		private final Interval range;
		private final int domainAxes;
		private final DensitySampler sampler;
		
		public DhPreparedDensity(Interval range, int domainAxes, DensitySampler sampler)
		{
			this.range = range;
			this.domainAxes = domainAxes;
			this.sampler = sampler;
		}
		
		
		@Override
		public @NonNull DensitySampler compileSampler(@NonNull CompileContext context)
		{ return this.sampler; }
		
		@Override
		public @NonNull DensityFunction rewriteChildren(@NonNull DfRewriteRule rule)
		{ return this; }
		
		@Override public @NonNull Interval range() { return this.range; }
		@Override public @Axes int domainAxes() { return this.domainAxes; }
		
		@Override
		public @NonNull MapCodec<? extends DensityFunction> codec()
		{ throw new UnsupportedOperationException(); }
	}
	
	#endif
	//endregion
	
	
	
}
#endif
