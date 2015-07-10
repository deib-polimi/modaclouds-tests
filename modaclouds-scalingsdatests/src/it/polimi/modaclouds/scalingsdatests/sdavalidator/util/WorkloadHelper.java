package it.polimi.modaclouds.scalingsdatests.sdavalidator.util;

import java.util.List;

public class WorkloadHelper {

	public static Workload getWorkloadByTimestep(List<Workload> ws, int timestep) {

		Workload toReturn = null;

		for (Workload w : ws) {
			if (w.getTimestep() == timestep) {
				toReturn = w;
			}
		}

		return toReturn;
	}
}