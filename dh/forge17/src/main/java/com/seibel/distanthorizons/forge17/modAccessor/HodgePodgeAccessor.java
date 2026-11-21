package com.seibel.distanthorizons.forge17.modAccessor;

import com.seibel.distanthorizons.common.wrappers.modAccessor.IHodgePodgeCommonAccessor;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import net.minecraftforge.common.ForgeChunkManager;

import com.mitchej123.hodgepodge.SimulationDistanceHelper;

import java.lang.reflect.Method;

public class HodgePodgeAccessor implements IHodgePodgeCommonAccessor
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	/**
	 * SimulationDistanceHelper.setTicketSimulation(Ticket, boolean),
	 * null if the installed HodgePodge version doesn't have it yet. <br>
	 * Looked up via reflection so DH can still be compiled against and run with older versions.
	 */
	private static final Method SET_TICKET_SIMULATION_METHOD = getSetTicketSimulationMethod();



	@Override
	public String getModName() { return "HodgePodge"; }



	//============//
	// simulation //
	//============//
	//region

	@Override
	public void excludeTicketFromSimulation(ForgeChunkManager.Ticket ticket)
	{
		if (SET_TICKET_SIMULATION_METHOD == null)
		{
			LOGGER.warn("HodgePodge: installed version doesn't support excluding tickets from simulation, "
				+ "with simulation distance enabled the chunks DH generates will be simulated while they're loaded. Update HodgePodge to fix this.");
			return;
		}

		try
		{
			SET_TICKET_SIMULATION_METHOD.invoke(null, ticket, false);
		}
		catch (ReflectiveOperationException e)
		{
			LOGGER.warn("HodgePodge: failed to exclude DH's world gen ticket from simulation, error: ["+e+"].", e);
		}
	}

	private static Method getSetTicketSimulationMethod()
	{
		try
		{
			return SimulationDistanceHelper.class.getMethod("setTicketSimulation", ForgeChunkManager.Ticket.class, boolean.class);
		}
		catch (NoSuchMethodException e)
		{
			return null;
		}
	}

	//endregion



}
