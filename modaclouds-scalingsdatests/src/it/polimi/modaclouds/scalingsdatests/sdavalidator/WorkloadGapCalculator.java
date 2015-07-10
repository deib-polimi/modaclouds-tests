package it.polimi.modaclouds.scalingsdatests.sdavalidator;

import it.polimi.modaclouds.scalingsdatests.sdavalidator.util.Workload;
import it.polimi.modaclouds.scalingsdatests.sdavalidator.util.WorkloadHelper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkloadGapCalculator {

	private static final Logger logger = LoggerFactory
			.getLogger(WorkloadGapCalculator.class);

	public static final String RESULT = "gap_analisys.csv";

	public static void calculate(Path parent, List<Workload> monitored,
			List<Workload> first, List<Workload> second, List<Workload> third,
			List<Workload> fourth, List<Workload> fifth, int firstInstancesToSkip) {
		
		if (firstInstancesToSkip < 0)
			firstInstancesToSkip = 0;
		
		if (monitored.size() < 6 + firstInstancesToSkip)
			logger.debug("You have only {} monitored workload values, while you needed at least {}. Next time perform a longer test.", monitored.size(), firstInstancesToSkip);

		float avarageGapFirst = 0;
		float avarageGapSecond = 0;
		float avarageGapThird = 0;
		float avarageGapFourth = 0;
		float avarageGapFifth = 0;

		float real;
		float firstPrediction;
		float secondPrediction;
		float thirdPrediction;
		float fourthPrediction;
		float fifthPrediction;

		int cont = 0;

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(Paths
				.get(parent.toString(), RESULT).toFile()))) {

			writer.write("workload,"
					+ "prediction_1_timestep, error_1_timestep,"
					+ "prediction_2_timestep, error_2_timestep,"
					+ "prediction_3_timestep, error_3_timestep,"
					+ "prediction_4_timestep, error_4_timestep,"
					+ "prediction_5_timestep, error_5_timestep \n");

			for (int i = 6 + firstInstancesToSkip; i <= monitored.size(); i++) {

				try {
					real = WorkloadHelper.getWorkloadByTimestep(monitored, i)
							.getValue();
					firstPrediction = WorkloadHelper.getWorkloadByTimestep(first,
							i - 1).getValue();
					secondPrediction = WorkloadHelper.getWorkloadByTimestep(second,
							i - 2).getValue();
					thirdPrediction = WorkloadHelper.getWorkloadByTimestep(third,
							i - 3).getValue();
					fourthPrediction = WorkloadHelper.getWorkloadByTimestep(fourth,
							i - 4).getValue();
					fifthPrediction = WorkloadHelper.getWorkloadByTimestep(fifth,
							i - 5).getValue();
	
					writer.write(real + "," + firstPrediction + ","
							+ Math.abs((real - firstPrediction) / real) * 100
							+ "%," + secondPrediction + ","
							+ Math.abs((real - secondPrediction) / real) * 100
							+ "%," + thirdPrediction + ","
							+ Math.abs((real - thirdPrediction) / real) * 100
							+ "%," + fourthPrediction + ","
							+ Math.abs((real - fourthPrediction) / real) * 100
							+ "%," + fifthPrediction + ","
							+ Math.abs((real - fifthPrediction) / real) * 100
							+ "%\n");
	
					avarageGapFirst += Math.abs((real - firstPrediction) / real);
					avarageGapSecond += Math.abs((real - secondPrediction) / real);
					avarageGapThird += Math.abs((real - thirdPrediction) / real);
					avarageGapFourth += Math.abs((real - fourthPrediction) / real);
					avarageGapFifth += Math.abs((real - fifthPrediction) / real);
	
					cont++;
				} catch (Exception e) { }
			}
			writer.write(EMPTY + "," + EMPTY + "," + avarageGapFirst / cont
					* 100 + "% ," + EMPTY + "," + avarageGapSecond / cont * 100
					+ "% ," + EMPTY + "," + avarageGapThird / cont * 100
					+ "% ," + EMPTY + "," + avarageGapFourth / cont * 100
					+ "% ," + EMPTY + "," + avarageGapFifth / cont * 100 + "%");

			writer.flush();
		} catch (Exception e) {
			logger.error("Error while analyzing the gap.", e);
		}
	}
	
	public static final String EMPTY = "--------";
}
