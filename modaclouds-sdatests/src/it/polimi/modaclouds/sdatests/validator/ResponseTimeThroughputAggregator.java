package it.polimi.modaclouds.sdatests.validator;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class ResponseTimeThroughputAggregator {

	public static final String MONITORED_WORKLOAD = "monitored_workload.out";
	public static final String MONITORED_RESPONSETIME = "monitored_responseTime.out";

	public static void main(String[] args) {
		perform(Paths.get("."));
	}

	public static void perform(Path parent) {
		Scanner input;
		int counter = 0;
		String[] splitted;
		float partialSum = 0;
		int currentHour;
		float hourlyThrougput;
		float hourlyResponseTime;

		try {
			Path monitoredWorkload = Paths.get(parent.toString(),
					MONITORED_WORKLOAD);
			if (monitoredWorkload == null
					|| !monitoredWorkload.toFile().exists())
				throw new RuntimeException(
						"Monitored workload file not found or wrong path ("
								+ monitoredWorkload.toString() + ")");

			input = new Scanner(monitoredWorkload);

			currentHour = 1;

			while (input.hasNextLine()) {
				String line = input.nextLine();
				splitted = line.split(",");

				int value = 0;

				value = Integer.parseInt(splitted[3]);

				partialSum = partialSum + value;

				counter++;

				if (counter == 360) {
					hourlyThrougput = partialSum / (3600);
					System.out
							.println("current hour:" + currentHour
									+ " hourly avarage throughtput: "
									+ hourlyThrougput);
					partialSum = 0;
					counter = 0;
					currentHour++;
				}

			}

		} catch (IOException e) {
			e.printStackTrace();
		}

		counter = 0;
		partialSum = 0;

		try {
			Path monitoredResponseTime = Paths.get(parent.toString(),
					MONITORED_RESPONSETIME);
			if (monitoredResponseTime == null
					|| !monitoredResponseTime.toFile().exists())
				throw new RuntimeException(
						"Monitored response time file not found or wrong path ("
								+ monitoredResponseTime.toString() + ")");

			input = new Scanner(monitoredResponseTime);

			currentHour = 1;

			while (input.hasNextLine()) {
				String line = input.nextLine();
				splitted = line.split(",");

				float value = 0;

				value = Float.parseFloat(splitted[3]);

				partialSum = partialSum + value;

				counter++;

				if (counter == 1825) {
					hourlyResponseTime = partialSum / counter;
					System.out.println("current hour:" + currentHour
							+ " hourly avarage response time: "
							+ hourlyResponseTime);
					partialSum = 0;
					counter = 0;
					currentHour++;
				}

			}

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
