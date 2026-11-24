package com.seibel.distanthorizons.forge17;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiHeightFogMixMode;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiMutableFogRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import net.minecraftforge.client.event.EntityViewRenderEvent.FogDensity;
import org.lwjgl.opengl.GL11;

public final class ForgeFogOverride extends DhApiBeforeFogRenderEvent
{
    private static final ForgeFogOverride INSTANCE = new ForgeFogOverride();

    private float density;

    private ForgeFogOverride() {}

    static void register()
    {
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, INSTANCE);
    }

    public static void capture(FogDensity event, boolean canceled)
    {
        INSTANCE.density = 0.0f;
        if (canceled && event.density > 0.0f && Float.isFinite(event.density)
            && GL11.glGetInteger(GL11.GL_FOG_MODE) == GL11.GL_EXP2)
        {
            INSTANCE.density = event.density;
        }
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<EventParam> event)
    {
        IMinecraftRenderWrapper render = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
        if (this.density == 0.0f || render.isFogStateSpecial())
        {
            return;
        }

        DhApiMutableFogRenderParam fog = event.value.getFogRenderParam();
        int lodDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() * LodUtil.CHUNK_WIDTH;
        fog.setFogColor(render.getFogColor(event.value.getRenderParam().partialTicks));
        fog.setFarFogFalloff(EDhApiFogFalloff.EXPONENTIAL_SQUARED);
        fog.setFarFogStartPercent(0.0f);
        fog.setFarFogEndPercent(1.0f);
        fog.setFarFogMinThickness(0.0f);
        fog.setFarFogMaxThickness(1.0f);
        fog.setFarFogDensity(this.density * lodDistance);
        fog.setHeightFogMixingMode(EDhApiHeightFogMixMode.SPHERICAL);
    }
}
