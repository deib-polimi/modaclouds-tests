package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResultsBuilder {
	
	private static final Logger logger = LoggerFactory.getLogger(ResultsBuilder.class);

	public static final String RESULT = "results.csv";
	
	public static void main(String[] args) {
		perform(Paths.get("."), new String[] { "reg", "save", "answ" });
	}
	
	public static Map<String, List<Double>> getAsMap(Path f, String[] ss) {
		if (f == null || !f.toFile().exists())
			throw new RuntimeException("File not found or wrong path ("
					+ f.toString() + ")");
		
		if (ss == null || ss.length == 0)
			throw new RuntimeException("You should specify at least one column name.");
		
		HashMap<String, List<Double>> res = new HashMap<String, List<Double>>();
		
		HashMap<String, Integer> columnsNeeded = new HashMap<String, Integer>();
		
		try (Scanner sc = new Scanner(f)) {
			{
				String header = sc.nextLine();
				String[] columns = header.split(",");
				
				for (int c = 0; c < columns.length; ++c) {
					boolean done = false;
					for (int i = 0; i < ss.length && !done; ++i) {
						if (columns[c].trim().equalsIgnoreCase(ss[i])) {
							done = true;
							columnsNeeded.put(ss[i], c);
						}
					}
				}
			}
			
			if (columnsNeeded.size() == 0)
				throw new RuntimeException("No column matched in the given file!");
			
			for (String key : columnsNeeded.keySet())
				res.put(key, new ArrayList<Double>());
			
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				String[] values = line.split(",");
				
				for (String key : columnsNeeded.keySet())
					try {
						res.get(key).add(Double.parseDouble(values[columnsNeeded.get(key)]));
					} catch (Exception e) { } 
			}
		} catch (Exception e) {
			logger.error("Error while dealing with the file.", e);
		}
		
		return res;
	}
	
	public static final String DEMAND_COLUMN_PREFIX = "AvarageEstimatedDemand_";
	public static final String CPU_UTIL_COLUMN = "AvarageCPUUtil";
	public static final String WORKLOAD_COLUMN = "workload";
	public static final int TIME_SLOT_SIZE = 5*60;
	
	
	public static void perform(Path parent, String[] methodsNames) {
		if (methodsNames == null || methodsNames.length == 0)
			throw new RuntimeException("You should specify at least one method name.");
		
		Path demand = Paths.get(parent.toString(), DemandValidator.RESULT);
		if (demand == null || !demand.toFile().exists())
			throw new RuntimeException("Demand file not found or wrong path ("
					+ demand.toString() + ")");
		
		ArrayList<Path> methods = new ArrayList<Path>();
		for (int i = 1; i <= methodsNames.length; ++i) {
			Path method = Paths.get(parent.toString(), "method" + i, WorkloadGapCalculator.RESULT);
			if (method == null || !method.toFile().exists())
				throw new RuntimeException("Method file not found or wrong path ("
						+ method.toString() + ")");
			methods.add(method);
		}
		
		String[] neededColumnsDemands = new String[methodsNames.length + 1];
		for (int i = 0; i < methodsNames.length; ++i)
			neededColumnsDemands[i] = DEMAND_COLUMN_PREFIX + methodsNames[i];
		neededColumnsDemands[neededColumnsDemands.length - 1] = CPU_UTIL_COLUMN;
		
		int maxCommonLength = 0;
		
		logger.info("Reading the demands file...");
		
		Map<String, List<Double>> demands = getAsMap(demand, neededColumnsDemands);
		maxCommonLength = demands.get(CPU_UTIL_COLUMN).size();
		
		logger.info("Reading the workloads for the methods...");
		
		ArrayList<List<Double>> methodsWorkloads = new ArrayList<List<Double>>();
		for (Path p : methods) {
			List<Double> tmp = getAsMap(p, new String[] { WORKLOAD_COLUMN }).get(WORKLOAD_COLUMN);
			if (tmp.size() < maxCommonLength)
				maxCommonLength = tmp.size();
			methodsWorkloads.add(tmp);
		}
		
		logger.info("Writing the results file...");
		
		try (PrintWriter out = new PrintWriter(Paths.get(parent.toString(), RESULT).toFile())) {
			for (int i = 1; i <= methodsNames.length; ++i)
				out.printf("DemandM%1$d,X%1$d,", i);
			out.println("Uactual,Umeasured,Uaoverm");
			
			for (int i = 0; i < maxCommonLength; ++i) {
				double u = 0;
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < methodsNames.length; ++j) {
					double d = demands.get(DEMAND_COLUMN_PREFIX + methodsNames[j]).get(i);
					double x = methodsWorkloads.get(j).get(i) / TIME_SLOT_SIZE;
					sb.append(d + "," + x + ",");
					u += d*x;
				}
				u /= methodsNames.length;
				double uMeasured = demands.get(CPU_UTIL_COLUMN).get(i);
				
				sb.append(u + "," + uMeasured + "," + u / uMeasured);
				
				out.println(sb.toString());
			}
			
			out.flush();
			
			logger.info("Done!");
		} catch (Exception e) {
			logger.error("Error while dealing with the result file.", e);
		}
	}

}
