package it.polimi.modaclouds.sdatests.validator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CpuStealValidator {

	private static final Logger logger = LoggerFactory
			.getLogger(CpuStealValidator.class);

	public static final String STOLEN = "cpuSteal.out";

	public static void main(String[] args) {
		perform(Paths.get("."));
	}

	public static void perform(Path parent) {
		Path stolen = Paths.get(parent.toString(), STOLEN);
		if (stolen == null || !stolen.toFile().exists())
			throw new RuntimeException(
					"CPU stolen file not found or wrong path ("
							+ stolen.toString() + ")");

		List<String> highValues = new ArrayList<String>();
		int window = 83;
		float partialSum = 0;
		int count = 0;

		try (Scanner input = new Scanner(stolen)) {

			while (input.hasNextLine()) {
				String line = input.nextLine();
				String[] splitted = line.split(",");
				Float value = Float.parseFloat(splitted[3]);
				if (value.floatValue() > 0.09) {
					highValues.add(line);
				}

				if (count < window) {
					count++;
					partialSum = partialSum + value.floatValue();
				} else {
					if (partialSum > 0)
						logger.debug("{}", partialSum / window);
					else
						logger.debug("0");
					count = 0;
					partialSum = 0;
				}
			}
		} catch (Exception e) {
			logger.error("Error while considering the CPU steal.", e);
		}

		for (String high : highValues) {
			logger.debug(high);
		}
	}

}
