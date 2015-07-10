package it.polimi.modaclouds.scalingsdatests.sdavalidator;

import it.polimi.modaclouds.scalingsdatests.sdavalidator.util.Datum;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseTimeValidator {

	private static final Logger logger = LoggerFactory
			.getLogger(ResponseTimeValidator.class);
	
	public static void main(String[] args) {
		perform(Paths.get("."), 2, Validator.FIRST_INSTANCES_TO_SKIP, Validator.DEFAULT_WINDOW, DEFAULT_AVG_WINDOWS);
	}

	public static final String RESULT = "responseTimesAnalysis%s.csv";
	
	public static final String FORECASTED_DEMAND = "d.out";
	public static final String MONITORED_RESPONSETIME = "rt.out";
	public static final String MONITORED_WORKLOAD = "wl.out";
	
	public static final int[] DEFAULT_AVG_WINDOWS = new int[] { 5*60, 10*60 };
	
	private static DecimalFormat doubleFormatter() {
		DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols(Locale.getDefault());
		otherSymbols.setDecimalSeparator('.');
		DecimalFormat myFormatter = new DecimalFormat("0.0#########", otherSymbols);
		return myFormatter;
	}
	private static DecimalFormat doubleFormatter = doubleFormatter();

	public static void perform(Path parent, int cores, int firstInstancesToSkip, int window, int[] avgWindows) {
		if (avgWindows == null || avgWindows.length == 0)
			throw new RuntimeException("You need to specify at least one avg window!");
		
		Path demandFile = Paths.get(parent.toString(), FORECASTED_DEMAND);
		if (demandFile == null || !demandFile.toFile().exists())
			throw new RuntimeException("Estimated demand file not found or wrong path ("
					+ demandFile == null ? "null" : demandFile.toString() + ")");
		
		Path rtFile = Paths.get(parent.toString(), MONITORED_RESPONSETIME);
		if (rtFile == null || !rtFile.toFile().exists())
			throw new RuntimeException("Monitored response time file not found or wrong path ("
					+ rtFile == null ? "null" : rtFile.toString() + ")");
		
		Path workloadFile = Paths.get(parent.toString(), MONITORED_WORKLOAD);
		if (workloadFile == null || !workloadFile.toFile().exists())
			throw new RuntimeException("Monitored workload file not found or wrong path ("
					+ workloadFile == null ? "null" : workloadFile.toString() + ")");
		
		if (firstInstancesToSkip < 0)
			firstInstancesToSkip = Validator.FIRST_INSTANCES_TO_SKIP;
		if (window <= 0)
			window = Validator.DEFAULT_WINDOW;
		
		for (int i = 0; i < avgWindows.length; ++i)
			if (avgWindows[i] < window)
				avgWindows[i] = window;
		
		try {
			Map<String, List<Datum>> demands = Datum.getAllData(demandFile, false);
			Map<String, List<Datum>> rts = Datum.getAllData(rtFile, false);
			Map<String, List<Datum>> workloads = Datum.getAllData(workloadFile, false);
			
			int maxForecastedDemands = Integer.MAX_VALUE;
			for (List<Datum> demand : demands.values()) {
				if (maxForecastedDemands > demand.size())
					maxForecastedDemands = demand.size();
			}
		
			for (int avgWindow : avgWindows) {
				logger.info("Considering the avg window {} for the monitoring rule window {}...", avgWindow, window);
				
				try (PrintWriter out = new PrintWriter(Paths.get(
								parent.toString(), String.format(RESULT, avgWindow)).toFile(), "UTF-8")) {
					
					for (String s : demands.keySet())
						out.printf("Demand_%1$s,X_%1$s,R_measured_%1$s,R_actual_%1$s,R_aoverm_%1$s,GAP_R_%1$s,", Datum.getActualResourceId(s));
					out.printf("U\n", avgWindow);
					
					Map<String, Integer> iRTs = new HashMap<String, Integer>();
					Map<String, Integer> iDemands = new HashMap<String, Integer>();
					Map<String, Integer> iWorkloads = new HashMap<String, Integer>();
					for (String s : demands.keySet()) {
						iRTs.put(s, firstInstancesToSkip);
						iDemands.put(s, firstInstancesToSkip);
						iWorkloads.put(s, firstInstancesToSkip);
					}
					
					boolean keepConsideringData = true;
					double countR = 0;
					Map<String, Double> sumComputedRTs = new HashMap<String, Double>();
					Map<String, Double> sumMonitoredRTs = new HashMap<String, Double>();
					for (String s : demands.keySet()) {
						sumComputedRTs.put(s, 0.0);
						sumMonitoredRTs.put(s, 0.0);
					}
					
					while (keepConsideringData) {
						double u = 0;
						
						Map<String, Double> avgDemands = new HashMap<String, Double>();
						Map<String, Double> avgRTs = new HashMap<String, Double>();
						
						StringBuilder sb = new StringBuilder();
						
						for (String s : demands.keySet()) {
							double avgDemand = 0;
							int count = 0;
							long maxTimestamp = Long.MAX_VALUE;
							boolean goOn = true;
							
							for (int i = iDemands.get(s); i < maxForecastedDemands && goOn; ++i) {
								Datum d = demands.get(s).get(i);
								avgDemand += d.value * 1000;
								count++;
								if (count == Math.round(Math.ceil((double)avgWindow / window))) {
									goOn = false;
									avgDemand /= count;
									sb.append(String.format("%s,", doubleFormatter.format(avgDemand)));
									iDemands.put(s, i+1);
									maxTimestamp = d.timestamp;
								}
							}
							avgDemands.put(s, avgDemand);
							
							keepConsideringData &= (iDemands.get(s) + Math.round(Math.ceil((double)avgWindow / window)) <= maxForecastedDemands);
							
							double avgThroughput = 0;
							count = 0;
							goOn = true;
							
							for (int i = iWorkloads.get(s); i < workloads.get(s).size() && goOn; ++i) {
								Datum w = workloads.get(s).get(i);
								if (w.timestamp > maxTimestamp) {
									goOn = false;
									avgThroughput = (avgThroughput / count) / (window * cores);
									sb.append(String.format("%s,", doubleFormatter.format(avgThroughput)));
									iWorkloads.put(s, i);
								} else {
									avgThroughput += w.value;
									count++;
								}
							}
							
							u += avgDemand * avgThroughput;
							
							double avgRT = 0;
							count = 0;
							goOn = true;
							
							for (int i = iRTs.get(s); i < rts.get(s).size() && goOn; ++i) {
								Datum rt = rts.get(s).get(i);
								if (rt.timestamp > maxTimestamp) {
									goOn = false;
									avgRT /= count;
									sumMonitoredRTs.put(s, sumMonitoredRTs.get(s) + avgRT);
									avgRTs.put(s, avgRT);
									sb.append(String.format("%s,", doubleFormatter.format(avgRT)));
									iRTs.put(s, i);
								} else {
									avgRT += rt.value;
									count++;
								}
							}
						}
						
						u /= 1000;
						
						for (String s : demands.keySet()) {
							double avgDemand = avgDemands.get(s);
							double r = avgDemand / (1 - u); // / 1000;
							sumComputedRTs.put(s, sumComputedRTs.get(s) + r);
							double avgRT = avgRTs.get(s);
							
							sb.append(String.format("%s,%s,%s%%,", doubleFormatter.format(r), doubleFormatter.format(r / avgRT), doubleFormatter.format(((avgRT - r)/ avgRT) * 100)));
						}
						countR++;
						
						sb.append(String.format("%s", doubleFormatter.format(u)));
						
						out.println(sb.toString());
					}
					
					for (String s : demands.keySet()) {
						double RTactual = sumComputedRTs.get(s) / countR;
						double RTmeasured = sumMonitoredRTs.get(s) / countR;
						
						out.printf("%1$s,%1$s,%2$s,%3$s,%4$s,", EMPTY,
								doubleFormatter.format(RTmeasured),
								doubleFormatter.format(RTactual),
								doubleFormatter.format(RTactual / RTmeasured)
								);
					}
					out.printf("%s\n", EMPTY);
					
					out.flush();
				}
			}
		} catch (Exception e) {
			logger.error("Error while getting the data or writing to the result files.", e);
		}
	}
	
	public static final String EMPTY = "--------";
	
}
