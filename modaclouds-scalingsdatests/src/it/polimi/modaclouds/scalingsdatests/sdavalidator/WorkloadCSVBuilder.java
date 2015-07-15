package it.polimi.modaclouds.scalingsdatests.sdavalidator;

import it.polimi.modaclouds.scalingsdatests.sdavalidator.util.Datum;
import it.polimi.modaclouds.scalingsdatests.sdavalidator.util.Workload;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkloadCSVBuilder {

	private static final Logger logger = LoggerFactory
			.getLogger(WorkloadCSVBuilder.class);

	public static void main(String[] args) {
		perform(Paths.get("."), Validator.FIRST_INSTANCES_TO_SKIP);
	}

	public static final String MONITORED_WORKLOAD = "monitored_workload.out";
	public static final String MONITORED_WORKLOAD_AGGREGATE = "monitored_workload.csv";
	public static final String FORECASTED_WORKLOAD = "forecasted_WL_%d.out";
	public static final String FORECASTED_WORKLOAD_AGGREGATE = "workload_timestep_%d.csv";

	public static void perform(Path parent, int firstInstancesToSkip) {

		// It's always 1: the SDAs and the "normal" monitoring rules use the
		// same time window now.
		int window = 1;

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
									+ monitoredWorkload == null ? "null"
									: monitoredWorkload.toString() + ")");

				try (BufferedWriter writer = new BufferedWriter(new FileWriter(
						Paths.get(parent.toString(),
								MONITORED_WORKLOAD_AGGREGATE).toFile()))) {
					List<Datum> data = Datum
							.getAllData(monitoredWorkload, true).get(
									Datum.MIXED);

					writer.write("TIMESTEP,METRIC\n");

					int j = 0;

					float sum = 0;

					for (Datum d : data) {
						sum += d.value;

						j++;

						if (j == window) {
							int avg = (int) Math.round(sum / window);
							writer.write(cont + "," + avg + "\n");
							monitored.add(new Workload(cont, avg));
							cont++;
							j = 0;
							sum = 0;
						}
					}

					writer.flush();
				} catch (Exception e) {
					logger.error(
							"Error while considering the actual workload.", e);
				}
			} else {
				Path forecastedWorkload = Paths.get(parent.toString(),
						String.format(FORECASTED_WORKLOAD, i));
				if (forecastedWorkload == null
						|| !forecastedWorkload.toFile().exists())
					throw new RuntimeException(
							"Forecasted workload file not found or wrong path ("
									+ forecastedWorkload == null ? "null"
									: forecastedWorkload.toString() + ")");

				try (BufferedWriter writer = new BufferedWriter(
						new FileWriter(Paths
								.get(parent.toString(),
										String.format(
												FORECASTED_WORKLOAD_AGGREGATE,
												i)).toFile()))) {
					List<Datum> data = Datum.getAllData(forecastedWorkload,
							true).get(Datum.MIXED);

					writer.write("TIMESTEP,METRIC\n");
					for (Datum d : data) {
						float value = d.value.floatValue();

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
					logger.error(
							"Error while considering the forecasted workload.",
							e);
				}
			}
		}

		WorkloadGapCalculator.calculate(parent, monitored, first, second,
				third, fourth, fifth, firstInstancesToSkip);
	}

}
