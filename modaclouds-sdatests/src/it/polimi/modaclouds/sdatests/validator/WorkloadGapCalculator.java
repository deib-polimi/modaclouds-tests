package it.polimi.modaclouds.sdatests.validator;

import it.polimi.modaclouds.sdatests.validator.util.Workload;
import it.polimi.modaclouds.sdatests.validator.util.WorkloadHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class WorkloadGapCalculator {

	public static final String RESULT = "gap_analisys.csv";

	public static void calculate(Path parent, List<Workload> monitored,
			List<Workload> first, List<Workload> second, List<Workload> third,
			List<Workload> fourth, List<Workload> fifth) {

		BufferedWriter writer = null;
		File tempFile = Paths.get(parent.toString(), RESULT).toFile();

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

		try {
			writer = new BufferedWriter(new FileWriter(tempFile));

			writer.write("workload,"
					+ "prediction_1_timestep, error_1_timestep,"
					+ "prediction_2_timestep, error_2_timestep,"
					+ "prediction_3_timestep, error_3_timestep,"
					+ "prediction_4_timestep, error_4_timestep,"
					+ "prediction_5_timestep, error_5_timestep \n");

			for (int i = 6; i <= monitored.size(); i++) {

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

				avarageGapFirst = avarageGapFirst
						+ Math.abs((real - firstPrediction) / real);
				avarageGapSecond = avarageGapSecond
						+ Math.abs((real - secondPrediction) / real);
				avarageGapThird = avarageGapThird
						+ Math.abs((real - thirdPrediction) / real);
				avarageGapFourth = avarageGapFourth
						+ Math.abs((real - fourthPrediction) / real);
				avarageGapFifth = avarageGapFifth
						+ Math.abs((real - fifthPrediction) / real);

				cont++;
			}
			writer.write("--------," + "--------," + avarageGapFirst / cont
					* 100 + "% ," + "--------," + avarageGapSecond / cont * 100
					+ "% ," + "--------," + avarageGapThird / cont * 100
					+ "% ," + "--------," + avarageGapFourth / cont * 100
					+ "% ," + "--------," + avarageGapFifth / cont * 100 + "%");

		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				writer.flush();
				writer.close();
			} catch (Exception e) {
			}
		}
	}
}
