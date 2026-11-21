package com.seibel.distanthorizons.api.enums.rendering;

/**
 * Which buffers a terrain render pass draws
 *
 * @since API 7.3.0
 * @version 2026-11-10
 */
public enum EDhApiTerrainRenderPass
{
	OPAQUE,
	/** opaque quads drawn after SSAO */
	OPAQUE_WITHOUT_SSAO,
	TRANSPARENT,
}
