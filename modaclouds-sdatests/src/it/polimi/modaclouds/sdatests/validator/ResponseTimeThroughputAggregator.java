package it.polimi.modaclouds.sdatests.validator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseTimeThroughputAggregator {

	private static final Logger logger = LoggerFactory
			.getLogger(ResponseTimeThroughputAggregator.class);

	public static final String MONITORED_WORKLOAD = "monitored_workload.out";
	public static final String MONITORED_RESPONSETIME = "monitored_responseTime.out";

	public static void main(String[] args) {
		perform(Paths.get("."));
	}

	public static void perform(Path parent) {
		int counter = 0;
		float partialSum = 0;
		int currentHour;
		float hourlyThrougput;
		float hourlyResponseTime;

		Path monitoredWorkload = Paths.get(parent.toString(),
				MONITORED_WORKLOAD);
		if (monitoredWorkload == null || !monitoredWorkload.toFile().exists())
			throw new RuntimeException(
					"Monitored workload file not found or wrong path ("
							+ monitoredWorkload.toString() + ")");

		try (Scanner input = new Scanner(monitoredWorkload)) {
			currentHour = 1;

			while (input.hasNextLine()) {
				String line = input.nextLine();
				String[] splitted = line.split(",");

				int value = 0;

				value = Integer.parseInt(splitted[3]);

				partialSum = partialSum + value;

				counter++;

				if (counter == 360) {
					hourlyThrougput = partialSum / (3600);
					logger.debug(
							"current hour: {}, hourly avarage throughtput: {}",
							currentHour, hourlyThrougput);
					partialSum = 0;
					counter = 0;
					currentHour++;
				}

			}

		} catch (Exception e) {
			logger.error("Error while reading the monitored workload file.", e);
		}

		counter = 0;
		partialSum = 0;

		Path monitoredResponseTime = Paths.get(parent.toString(),
				MONITORED_RESPONSETIME);
		if (monitoredResponseTime == null
				|| !monitoredResponseTime.toFile().exists())
			throw new RuntimeException(
					"Monitored response time file not found or wrong path ("
							+ monitoredResponseTime.toString() + ")");

		try (Scanner input = new Scanner(monitoredResponseTime)) {

			currentHour = 1;

			while (input.hasNextLine()) {
				String line = input.nextLine();
				String[] splitted = line.split(",");

				float value = 0;

				value = Float.parseFloat(splitted[3]);

				partialSum = partialSum + value;

				counter++;

				if (counter == 1825) {
					hourlyResponseTime = partialSum / counter;
					logger.debug(
							"current hour: {}, hourly avarage response time: {}",
							currentHour, hourlyResponseTime);
					partialSum = 0;
					counter = 0;
					currentHour++;
				}

			}

		} catch (Exception e) {
			logger.error(
					"Error while reading the monitored response time file.", e);
		}
	}

}
