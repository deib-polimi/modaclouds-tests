package it.polimi.modaclouds.sdatests.validator;

import it.polimi.modaclouds.sdatests.validator.util.Workload;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkloadCSVBuilder {
	
	private static final Logger logger = LoggerFactory.getLogger(WorkloadCSVBuilder.class);

	public static void main(String[] args) {
		perform(Paths.get("."));
	}

	public static final String MONITORED_WORKLOAD = "monitored_workload.out";
	public static final String MONITORED_WORKLOAD_AGGREGATE = "monitored_workload.csv";
	public static final String FORECASTED_WORKLOAD = "forecasted_WL_%d.out";
	public static final String FORECASTED_WORKLOAD_AGGREGATE = "workload_timestep_%d.csv";

	public static final int WINDOW = 30;
	
	public static void perform(Path parent) {
		perform(parent, WINDOW);
	}

	public static void perform(Path parent, int window) {
		if (window < 1)
			window = 1;

		List<Workload> monitored = new ArrayList<Workload>();
		List<Workload> first = new ArrayList<Workload>();
		List<Workload> second = new ArrayList<Workload>();
		List<Workload> third = new ArrayList<Workload>();
		List<Workload> fourth = new ArrayList<Workload>();
		List<Workload> fifth = new ArrayList<Workload>();

		for (int i = 0; i <= 5; i++) {
			int cont = 1;

			if (i == 0) {
				Path monitoredWorkload = Paths.get(parent.toString(),
						MONITORED_WORKLOAD);
				if (monitoredWorkload == null
						|| !monitoredWorkload.toFile().exists())
					throw new RuntimeException(
							"Monitored workload file not found or wrong path ("
									+ monitoredWorkload.toString() + ")");

				try (Scanner input = new Scanner(monitoredWorkload);
						BufferedWriter writer = new BufferedWriter(
								new FileWriter(Paths.get(parent.toString(),
										MONITORED_WORKLOAD_AGGREGATE).toFile()))) {
					writer.write("TIMESTEP,METRIC\n");

					int j = 0;

					float sum = 0;
					
					logger.debug("workload at timestep {}", cont);
					
					while (input.hasNextLine()) {
						String line = input.nextLine();
						String[] splitted = line.split(",");
						float value = 0;

						value = Float.parseFloat(splitted[3]);
						logger.debug("{}", value);
						sum += value;
						
						j++;

						if (j == window - 1) {
							logger.debug("-------------------");
							int avg = (int) Math.round(sum / window);
							logger.debug("AVG={}", avg);
							writer.write(cont + "," + avg + "\n");
							monitored.add(new Workload(cont, avg));
							cont++;
							j = 0;
							sum = 0;
							logger.debug("workload at timestep {}", cont);
						}
					}
					
					logger.debug("-------------------");
					int avg = (int) Math.round(sum / window);
					logger.debug("AVG={}", avg);
					writer.write(cont + "," + avg + "\n");
					monitored.add(new Workload(cont, avg));

					writer.flush();
				} catch (Exception e) {
					logger.error("Error while considering the actual workload.", e);
				}
			} else {
				Path forecastedWorkload = Paths.get(parent.toString(),
						String.format(FORECASTED_WORKLOAD, i));
				if (forecastedWorkload == null
						|| !forecastedWorkload.toFile().exists())
					throw new RuntimeException(
							"Forecasted workload file not found or wrong path ("
									+ forecastedWorkload.toString() + ")");

				try (Scanner input = new Scanner(forecastedWorkload);
						BufferedWriter writer = new BufferedWriter(
								new FileWriter(Paths.get(
										parent.toString(),
										String.format(
												FORECASTED_WORKLOAD_AGGREGATE,
												i)).toFile()))) {
					writer.write("TIMESTEP,METRIC\n");
					while (input.hasNextLine()) {
						String line = input.nextLine();
						String[] splitted = line.split(",");
						float value = 0;

						value = Float.parseFloat(splitted[3]);

						writer.write(cont + "," + value + "\n");

						switch (i) {
						case 1:
							first.add(new Workload(cont, value));
						case 2:
							second.add(new Workload(cont, value));
						case 3:
							third.add(new Workload(cont, value));
						case 4:
							fourth.add(new Workload(cont, value));
						case 5:
							fifth.add(new Workload(cont, value));
						}
						cont++;
					}

					writer.flush();
				} catch (Exception e) {
					logger.error("Error while considering the forecasted workload.", e);
				}
			}
		}

		WorkloadGapCalculator.calculate(parent, monitored, first, second,
				third, fourth, fifth);
	}

}
