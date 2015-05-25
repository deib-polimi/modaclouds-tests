package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import util.Workload;

public class WorkloadCSVBuilder {

	public static void main(String[] args) {
		perform(Paths.get("."));
	}

	public static final String MONITORED_WORKLOAD = "monitored_workload.out";
	public static final String MONITORED_WORKLOAD_AGGREGATE = "monitored_workload.csv";
	public static final String FORECASTED_WORKLOAD = "forecasted_WL_%d.out";
	public static final String FORECASTED_WORKLOAD_AGGREGATE = "workload_timestep_%d.csv";

	public static void perform(Path parent) {

		List<Workload> monitored = new ArrayList<Workload>();
		List<Workload> first = new ArrayList<Workload>();
		List<Workload> second = new ArrayList<Workload>();
		List<Workload> third = new ArrayList<Workload>();
		List<Workload> fourth = new ArrayList<Workload>();
		List<Workload> fifth = new ArrayList<Workload>();

		Scanner input;
		String[] splitted;

		for (int i = 0; i <= 5; i++) {
			int cont = 1;
			BufferedWriter writer = null;

			if (i == 0) {
				try {
					Path monitoredWorkload = Paths.get(parent.toString(),
							MONITORED_WORKLOAD);
					if (monitoredWorkload == null
							|| !monitoredWorkload.toFile().exists())
						throw new RuntimeException(
								"Monitored workload file not found or wrong path ("
										+ monitoredWorkload.toString() + ")");

					input = new Scanner(monitoredWorkload);

					File tempFile = Paths.get(parent.toString(),
							MONITORED_WORKLOAD_AGGREGATE).toFile();
					writer = new BufferedWriter(new FileWriter(tempFile));
					writer.write("TIMESTEP,METRIC\n");

					int j = 0;

					int[] buffer = new int[30];
					while (input.hasNextLine()) {
						String line = input.nextLine();
						splitted = line.split(",");
						int value = 0;
						;

						value = Integer.parseInt(splitted[3]);

						if (j == 29) {
							buffer[j] = value;

							System.out.println("workload at timestep " + cont);
							int sum = 0;
							for (int temp : buffer) {
								System.out.println(temp);
								sum = sum + temp;
							}
							System.out.println("-------------------");
							System.out.println("AVG=" + sum / 30);
							writer.write(cont + "," + sum / 30 + "\n");
							monitored.add(new Workload(cont, sum / 30));
							cont++;
							j = 0;
						} else {
							buffer[j] = value;
							j++;

						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					try {
						writer.flush();
						writer.close();
					} catch (Exception e) {
					}
				}
			} else {
				try {
					Path forecastedWorkload = Paths.get(parent.toString(),
							String.format(FORECASTED_WORKLOAD, i));
					if (forecastedWorkload == null
							|| !forecastedWorkload.toFile().exists())
						throw new RuntimeException(
								"Forecasted workload file not found or wrong path ("
										+ forecastedWorkload.toString() + ")");

					input = new Scanner(forecastedWorkload);

					File tempFile = Paths.get(parent.toString(),
							String.format(FORECASTED_WORKLOAD_AGGREGATE, i)).toFile();
					writer = new BufferedWriter(new FileWriter(tempFile));
					writer.write("TIMESTEP,METRIC\n");
					while (input.hasNextLine()) {
						String line = input.nextLine();
						splitted = line.split(",");
						float value = 0;
						;

						value = Float.parseFloat(splitted[4]);

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
				} catch (Exception e) {
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

		WorkloadGapCalculator.calculate(parent, monitored, first, second, third,
				fourth, fifth);
	}

}
