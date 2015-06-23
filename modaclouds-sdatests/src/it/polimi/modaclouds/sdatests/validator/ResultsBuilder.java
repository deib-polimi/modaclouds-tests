package it.polimi.modaclouds.sdatests.validator;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResultsBuilder {
	
	private static final Logger logger = LoggerFactory.getLogger(ResultsBuilder.class);

	public static final String RESULT = "results.csv";
	public static final String RESULT_REQS = "requests.csv";
	public static final String RESULT_WORKLOAD = "workload.csv";
	public static final String RESULT_RES_TIMES = "responseTimes.csv";
	
	public static void mainAaa(String[] args) {
		perform(Paths.get("."), new String[] { "reg", "save", "answ" }, 2);
	}
	
	private static Map<String, List<Double>> getAsMap(Path f, String[] ss) {
		if (f == null || !f.toFile().exists())
			throw new RuntimeException("File not found or wrong path ("
					+ f == null ? "null" : f.toString() + ")");
		
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
	
	public static final String JMETER_LOG = "test_aggregate.jtl";
	
	private static Map<String, Integer> getRequestsPerPage(Path parent, boolean onlyOk) {
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		
		boolean goOn = true;
		for (int i = 1; goOn; ++i) {
			Path jmeterAggregate = Paths.get(parent.getParent().getParent().getParent().toString(), "client" + i, JMETER_LOG);
			if (!jmeterAggregate.toFile().exists()) {
				goOn = false;
				continue;
			}
			
			Map<String, Integer> tmp = getRequestsPerPageFromSingleFile(jmeterAggregate, onlyOk);
			for (String key : tmp.keySet()) {
				Integer val = res.get(key);
				if (val == null)
					val = 0;
				res.put(key, val += tmp.get(key));
			}
		}
		
		return res;
	}
	
	private static Map<String, Integer> getRequestsPerPageFromSingleFile(Path f, boolean onlyOk) {
		if (f == null || !f.toFile().exists())
			throw new RuntimeException("File not found or wrong path ("
					+ f == null ? "null" : f.toString() + ")");
		
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		
		try (Scanner sc = new Scanner(f)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				String[] values = line.split(",");
				
				String page = values[2];
				String resultReq = values[4];
				if (onlyOk && !resultReq.equals("OK"))
					continue;
				
				Integer val = res.get(page);
				if (val == null)
					val = 0;

				res.put(page, val+1);
			}
		} catch (Exception e) {
			logger.error("Error while dealing with the file.", e);
		}
		
		return res;
	}
	
	private static Map<String, String> getLatenciesPerPage(Path parent, boolean onlyOk) {
		LinkedHashMap<String, String> res = new LinkedHashMap<String, String>();
		
		boolean goOn = true;
		for (int i = 1; goOn; ++i) {
			Path jmeterAggregate = Paths.get(parent.getParent().getParent().getParent().toString(), "client" + i, JMETER_LOG);
			if (!jmeterAggregate.toFile().exists()) {
				goOn = false;
				continue;
			}
			
			Map<String, String> tmp = getLatenciesPerPageFromSingleFile(jmeterAggregate, onlyOk);
			for (String key : tmp.keySet()) {
				String val = tmp.get(key);
				res.put(key + "_client" + i, val);
			}
		}
		
		return res;
	}
	
	private static Map<String, String> getLatenciesPerPageFromSingleFile(Path f, boolean onlyOk) {
		if (f == null || !f.toFile().exists())
			throw new RuntimeException("File not found or wrong path ("
					+ f == null ? "null" : f.toString() + ")");
		
		HashMap<String, String> res = new HashMap<String, String>();
		
		HashMap<String, Integer> sums = new HashMap<String, Integer>();
		HashMap<String, Integer> mins = new HashMap<String, Integer>();
		HashMap<String, Integer> maxs = new HashMap<String, Integer>();
		HashMap<String, Integer> counts = new HashMap<String, Integer>();
		
		try (Scanner sc = new Scanner(f)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				String[] values = line.split(",");
				
				String page = values[2];
				String resultReq = values[4];
				if (onlyOk && !resultReq.equals("OK"))
					continue;
				
				Integer latency = Integer.parseInt(values[11]);
				
				Integer sum = sums.get(page);
				if (sum == null)
					sum = 0;
				
				Integer count = counts.get(page);
				if (count == null)
					count = 0;
				
				Integer max = maxs.get(page);
				if (max == null)
					max = Integer.MIN_VALUE;
				Integer min = mins.get(page);
				if (min == null)
					min = Integer.MAX_VALUE;
				
				if (latency > max)
					max = latency;
				if (latency < min)
					min = latency;

				sums.put(page, sum+latency);
				counts.put(page, count+1);
				maxs.put(page, max);
				mins.put(page, min);
			}
		} catch (Exception e) {
			logger.error("Error while dealing with the file.", e);
		}
		
		for (String key : sums.keySet()) {
			int sum = sums.get(key);
			int count = counts.get(key);
			int max = maxs.get(key);
			int min = mins.get(key);
			
			res.put(key, doubleFormatter.format((double)sum/count) + "," + min + "," + max);
		}
		
		return res;
	}
	
	public static final String DEMAND_COLUMN_PREFIX = "AvarageEstimatedDemand_";
	public static final String CPU_UTIL_COLUMN = "AvarageCPUUtil";
	public static final String WORKLOAD_COLUMN = "workload";
	public static final int TIME_SLOT_SIZE = 5*60;
	
	private static DecimalFormat doubleFormatter() {
		DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols(Locale.getDefault());
		otherSymbols.setDecimalSeparator('.');
		DecimalFormat myFormatter = new DecimalFormat("0.000", otherSymbols);
		return myFormatter;
	}
	private static DecimalFormat doubleFormatter = doubleFormatter();
	
	public static void perform(Path parent, String[] methodsNames, int cores) {
		perform(parent, methodsNames, WorkloadCSVBuilder.WINDOW, true, cores);
	}
	
	private static List<List<Double>> methodsWorkloads = null;
	private static Map<Integer, Integer> methodsWorkloadTot = null;
	private static Map<String, List<Double>> demands = null;
	private static int maxCommonLength = -1;
	
	private static void init(Path parent, String[] methodsNames) {
		if (methodsNames == null || methodsNames.length == 0)
			throw new RuntimeException("You should specify at least one method name.");
		
		Path demand = Paths.get(parent.toString(), DemandValidator.RESULT);
		if (demand == null || !demand.toFile().exists())
			throw new RuntimeException("Demand file not found or wrong path ("
					+ demand == null ? "null" : demand.toString() + ")");
		
		ArrayList<Path> methods = new ArrayList<Path>();
		for (int i = 1; i <= methodsNames.length; ++i) {
			Path method = Paths.get(parent.toString(), "method" + i, WorkloadGapCalculator.RESULT);
			if (method == null || !method.toFile().exists())
				throw new RuntimeException("Method file not found or wrong path ("
						+ method == null ? "null" : method.toString() + ")");
			methods.add(method);
		}
		
		String[] neededColumnsDemands = new String[methodsNames.length + 1];
		for (int i = 0; i < methodsNames.length; ++i)
			neededColumnsDemands[i] = DEMAND_COLUMN_PREFIX + methodsNames[i];
		neededColumnsDemands[neededColumnsDemands.length - 1] = CPU_UTIL_COLUMN;
		
		maxCommonLength = 0;
		
		logger.info("Reading the demands file...");
		
		demands = getAsMap(demand, neededColumnsDemands);
		maxCommonLength = demands.get(CPU_UTIL_COLUMN).size();
		
		logger.info("Reading the workloads for the methods...");
		
		methodsWorkloads = new ArrayList<List<Double>>();
		for (Path p : methods) {
			List<Double> tmp = getAsMap(p, new String[] { WORKLOAD_COLUMN }).get(WORKLOAD_COLUMN);
			if (tmp.size() < maxCommonLength)
				maxCommonLength = tmp.size();
			methodsWorkloads.add(tmp);
		}
		
		methodsWorkloadTot = new HashMap<Integer, Integer>(); 
		for (int i = 0; i < methodsNames.length; ++i)
			methodsWorkloadTot.put(i, 0);
		
		for (int i = 0; i < maxCommonLength; ++i)
			for (int j = 0; j < methodsNames.length; ++j)
				methodsWorkloadTot.put(j, methodsWorkloadTot.get(j) + methodsWorkloads.get(j).get(i).intValue());
	}
	
	public static void createDemandAnalysis(Path parent, String[] methodsNames, int cores) {
		logger.info("Creating the demand analysis report...");
		
		if (methodsNames == null || methodsNames.length == 0)
			throw new RuntimeException("You should specify at least one method name.");
		
		if (methodsWorkloads == null || methodsWorkloadTot == null || demands == null || maxCommonLength == -1)
			init(parent, methodsNames);
		
		try (PrintWriter out = new PrintWriter(Paths.get(parent.toString(), RESULT).toFile())) {
			for (int i = 0; i < methodsNames.length; ++i)
				out.printf("Demand_%1$s,X_%1$s,", methodsNames[i]);
			out.println("U_actual,U_measured,U_aoverm");
			
			for (int i = 0; i < maxCommonLength; ++i) {
				double u = 0;
				StringBuilder sb = new StringBuilder();
				for (int j = 0; j < methodsNames.length; ++j) {
					double d = demands.get(DEMAND_COLUMN_PREFIX + methodsNames[j]).get(i);
					double x = methodsWorkloads.get(j).get(i) / (TIME_SLOT_SIZE * cores);
					sb.append(doubleFormatter.format(d) + "," + doubleFormatter.format(x) + ",");
					u += d*x;
				}
				u /= methodsNames.length;
				double uMeasured = demands.get(CPU_UTIL_COLUMN).get(i);
				
				sb.append(doubleFormatter.format(u) + "," + doubleFormatter.format(uMeasured) + "," + doubleFormatter.format(u / uMeasured));
				
				out.println(sb.toString());
			}
			
			out.flush();
			
		} catch (Exception e) {
			logger.error("Error while dealing with the result file.", e);
		}
	}
	
	public static void createRequestsAnalysis(Path parent, String[] methodsNames, int window, boolean printOnlyTotalRequests) {
		logger.info("Creating the requests analysis report...");
		
		if (methodsNames == null || methodsNames.length == 0)
			throw new RuntimeException("You should specify at least one method name.");
		
		if (methodsWorkloads == null || methodsWorkloadTot == null || demands == null || maxCommonLength == -1)
			init(parent, methodsNames);
		
		try (PrintWriter out = new PrintWriter(Paths.get(parent.toString(), RESULT_REQS).toFile())) {
			if (!printOnlyTotalRequests)
				for (int i = 0; i < methodsNames.length; ++i)
					out.printf("Requests_%s,", methodsNames[i]);
			out.print("TotalRequestsConsidered,");
			
			Map<String, Integer> requestsPerPage = getRequestsPerPage(parent, false);
			if (!printOnlyTotalRequests)
				for (String key : requestsPerPage.keySet())
					out.printf("ActualRequests_%s,", key);
			out.print("TotalActualRequests,");
			
			Map<String, Integer> requestsPerPageOnlyOk = getRequestsPerPage(parent, true);
			if (!printOnlyTotalRequests)
				for (String key : requestsPerPageOnlyOk.keySet())
					out.printf("ActualRequestsOk_%s,", key);
			out.println("TotalActualRequestsOk,PercentageLost,PercentageOkLost");
			
			int consideredRequests = 0;
			
			// NOTE:
			// all the datum must be considered times window, because they're an average computed on that window!
			// plus the first 5 data are skipped, and they should be considered too in the count.
			
			for (int j = 0; j < methodsNames.length; ++j) {
				List<Double> workload = methodsWorkloads.get(j);
				int methodTot = methodsWorkloadTot.get(j) * window; 
				methodTot += workload.get(0).intValue() * 5 * window;
				for (int i = maxCommonLength; i < workload.size(); ++i)
					methodTot += workload.get(i).intValue() * window;
				if (!printOnlyTotalRequests)
					out.print(methodTot + ",");
				consideredRequests += methodTot;
			}
			out.print(consideredRequests + ",");
			
			int actualRequests = 0;
			
			for (String key : requestsPerPage.keySet()) {
				int methodTot = requestsPerPage.get(key);
				if (!printOnlyTotalRequests)
					out.print(methodTot + ",");
				actualRequests += methodTot;
			}
			out.print(actualRequests + ",");
			
			int actualRequestsOk = 0;
			
			for (String key : requestsPerPageOnlyOk.keySet()) {
				int methodTot = requestsPerPageOnlyOk.get(key);
				if (!printOnlyTotalRequests)
					out.print(methodTot + ",");
				actualRequestsOk += methodTot;
			}
			out.println(actualRequestsOk + "," + doubleFormatter.format(((actualRequests - consideredRequests)/(double)actualRequests) * 100) + "%," + doubleFormatter.format((actualRequestsOk - consideredRequests)/(double)actualRequestsOk * 100) + "%");
			
			out.flush();
			
		} catch (Exception e) {
			logger.error("Error while dealing with the result file.", e);
		}
	}
	
	public static void createWorkloadAnalysis(Path parent, String[] methodsNames, int timesteps) {
		logger.info("Creating the workload analysis report...");
		
		if (methodsNames == null || methodsNames.length == 0)
			throw new RuntimeException("You should specify at least one method name.");
		if (timesteps < 1)
			throw new RuntimeException("You need at least 1 timestep!");
		
		if (methodsWorkloads == null || methodsWorkloadTot == null || demands == null || maxCommonLength == -1)
			init(parent, methodsNames);
		
		try (PrintWriter out = new PrintWriter(Paths.get(parent.toString(), RESULT_WORKLOAD).toFile())) {
			out.print("ErrorTimestep1");
			for (int i = 2; i <= timesteps; ++i)
				out.printf(",ErrorTimestep%d", i);
			out.println();
			
			double[] sums = new double[timesteps];
			for (int i = 0; i < timesteps; ++i)
				sums[i] = 0.0;
			
			for (int i = 1; i <= methodsNames.length; ++i) {
				try (Scanner sc = new Scanner(Paths.get(parent.toString(), "method" + i, WorkloadGapCalculator.RESULT))) {
					while (sc.hasNextLine()) {
						String line = sc.nextLine();
						if (line.contains(WorkloadGapCalculator.EMPTY)) {
							String[] splitted = line.split(",");
							for (int k = 0, j = 2; k < timesteps; ++k, j+=2)
								sums[k] += Double.valueOf(splitted[j].replaceAll("%", ""));
						}
					}
				}
			}
			
			out.printf("%s%%", doubleFormatter.format(sums[0] / methodsNames.length));
			for (int i = 1; i < timesteps; ++i)
				out.printf(",%s%%", doubleFormatter.format(sums[i] / methodsNames.length));
			out.println();
		} catch (Exception e) {
			logger.error("Error while dealing with the result file.", e);
		}
	}
	
	public static void createResponseTimesAnalysis(Path parent, String[] methodsNames) {
		logger.info("Creating the response times analysis report...");
		
		if (methodsNames == null || methodsNames.length == 0)
			throw new RuntimeException("You should specify at least one method name.");
		
		if (methodsWorkloads == null || methodsWorkloadTot == null || demands == null || maxCommonLength == -1)
			init(parent, methodsNames);
		
		try (PrintWriter out = new PrintWriter(Paths.get(parent.toString(), RESULT_RES_TIMES).toFile())) {
			out.println("Method,AvgResponseTime,MinResponseTime,MaxResponseTime");
			
			Map<String, String> latenciesPerPage = getLatenciesPerPage(parent, true);
			
			for (String key : latenciesPerPage.keySet()) {
				String res = latenciesPerPage.get(key);
				out.printf("%s_JMeter,%s\n", key, res);
			}
			
			Map<String, String> glassfishLatenciesPerPage = getLatenciesPerPageFromGlassfish(parent, methodsNames);
			
			for (String key : glassfishLatenciesPerPage.keySet()) {
				String res = glassfishLatenciesPerPage.get(key);
				out.printf("%s_Glassfish,%s\n", key, res);
			}
			
			out.flush();
			
		} catch (Exception e) {
			logger.error("Error while dealing with the result file.", e);
		}
	}
	
	private static Map<String, String> getLatenciesPerPageFromGlassfish(Path parent, String[] methodsNames) throws Exception {
		LinkedHashMap<String, String> res = new LinkedHashMap<String, String>();
		
		boolean goOn = true;
		for (int i = 1; goOn; ++i) {
			for (String method : methodsNames) {
				Path jsonFile = Paths.get(parent.getParent().getParent().getParent().toString(), "client" + i, method + ".json");
				if (!jsonFile.toFile().exists()) {
					goOn = false;
					continue;
				}
				
				res.put(method + "_client" + i, parseGlassfishJson(jsonFile));
			}
		}
		
		return res;
	}
	
	private static String parseGlassfishJson(Path jsonFile) throws Exception {
		String json = FileUtils.readFileToString(jsonFile.toFile());
		
		JSONObject obj = new JSONObject(json);
		JSONObject extraProperties = obj.getJSONObject("extraProperties");
		JSONObject entity = extraProperties.getJSONObject("entity");
		
		JSONObject maxtime = entity.getJSONObject("maxtime");
		JSONObject processingtime = entity.getJSONObject("processingtime");
		
		return processingtime.getInt("count") + ",0," + maxtime.getInt("count");
	}
	
	public static final int DEFAULT_TIMESTEPS = 5;
	
	public static void perform(Path parent, String[] methodsNames, int window, boolean printOnlyTotalRequests, int cores) {
		createDemandAnalysis(parent, methodsNames, cores);
		createRequestsAnalysis(parent, methodsNames, window, printOnlyTotalRequests);
		createWorkloadAnalysis(parent, methodsNames, DEFAULT_TIMESTEPS);
		createResponseTimesAnalysis(parent, methodsNames);
	}

}
