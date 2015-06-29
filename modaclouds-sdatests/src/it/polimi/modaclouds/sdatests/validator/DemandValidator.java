package it.polimi.modaclouds.sdatests.validator;

import it.polimi.modaclouds.sdatests.validator.util.Datum;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DemandValidator {

	private static final Logger logger = LoggerFactory
			.getLogger(DemandValidator.class);

	private static List<Float> cpu = new ArrayList<Float>();
	
	private static Map<String, List<Float>> RTs = new HashMap<String, List<Float>>();
	private static Map<String, Float> Dems = new HashMap<String, Float>();
	
	public static void main(String[] args) {
		perform(Paths.get("."), Validator.METHODS, Validator.FIRST_INSTANCES_TO_SKIP);
	}

	public static final String RESULT = "demandAnalysis.csv";
	
	public static final String FORECASTED_DEMAND = "demand.out";
	public static final String MONITORED_RESPONSETIME = "monitored_responseTime.out";
	public static final String MONITORED_CPU = "cpu.out";

	public static void perform(Path parent, String[] methods, int firstInstancesToSkip) {
		try (PrintWriter writer = new PrintWriter(Paths.get(
						parent.toString(), RESULT).toFile(), "UTF-8")) {
			
			writer.write("AvarageCPUUtil");
			for (String s : methods)
				writer.write(String.format(",AvarageEstimatedDemand_%1$s,AvarageRealResponseTime_%1$s,AvarageEstimatedResponseTime_%1$s,GAP_%1$s", s));
			writer.write("\n");
			
			Map<String, List<Datum>> forecastedDemands = new HashMap<String, List<Datum>>();
			int maxForecastedDemands = Integer.MAX_VALUE;
			for (int i = 0; i < methods.length; ++i) {
				List<Datum> forecastedDemand = Datum.getAllData(Paths.get(parent.toString(), "method" + (i+1), FORECASTED_DEMAND));
				forecastedDemands.put(methods[i], forecastedDemand);
				
				if (maxForecastedDemands > forecastedDemand.size())
					maxForecastedDemands = forecastedDemand.size();
			}
			
			Map<String, List<Datum>> monitoredRTs = new HashMap<String, List<Datum>>();
			for (int i = 0; i < methods.length; ++i) {
				List<Datum> monitoredRT = Datum.getAllData(Paths.get(parent.toString(), "method" + (i+1), MONITORED_RESPONSETIME));
				monitoredRTs.put(methods[i], monitoredRT);
			}
			
			List<Datum> monitoredCpu = Datum.getAllData(Paths.get(parent.toString(), MONITORED_CPU));
			
			Map<String, Integer> iRTs = new HashMap<String, Integer>();
			for (String s : methods)
				iRTs.put(s, 0);
			
			int iCpu = 0;
			
			for (int i = 0; i < maxForecastedDemands; ++i) {
				long maxTimestamp = Long.MAX_VALUE;
				
				for (String s : methods) {
					Datum d = forecastedDemands.get(s).get(i);
					
					Dems.put(s, d.value.floatValue());
					
					if (maxTimestamp > d.timestamp)
						maxTimestamp = d.timestamp;
				}
				
				for (String s : methods) {
					int iRT = iRTs.get(s);
					List<Datum> monitoredRT = monitoredRTs.get(s);
					List<Float> RT = RTs.get(s);
					if (RT == null) {
						RT = new ArrayList<Float>();
						RTs.put(s, RT);
					}
					
					boolean goOn = true;
					for (; iRT < monitoredRT.size() && goOn; ++iRT) {
						Datum rt = monitoredRT.get(iRT);
						if (rt.timestamp > maxTimestamp) {
							goOn = false;
							iRTs.put(s, iRT);
							continue;
						}
						RT.add(rt.value.floatValue());
					}
				}
				
				boolean goOn = true;
				for (; iCpu < monitoredCpu.size() && goOn; ++iCpu) {
					Datum util = monitoredCpu.get(iCpu);
					if (util.timestamp > maxTimestamp) {
						goOn = false;
						iCpu--;
						continue;
					}
					cpu.add(util.value.floatValue());
				}
				
				if (hasEnoughValues())
					writer.write(validate(methods) + "\n");
				flushLists();
			}
			
			writer.flush();
		} catch (Exception e) {
			logger.error("Error while getting the data from the result files.", e);
		}
	}

	private static String validate(String[] methods) {
		// System.out.println("VALIDATION");

		float temp = 0;
		for (Float f : cpu)
			temp = temp + f.floatValue();

		/*
		 * if(Float.isNaN(temp)){ for(Float f: cpu){
		 * if(Float.isNaN(f.floatValue())){ System.out.println(f.floatValue());
		 * } } System.out.println("####################"); }
		 */
		float U = temp / cpu.size();

		StringBuilder res = new StringBuilder();
		
		res.append(U);
		
		for (String s : methods) {
			float[] result = validate(RTs.get(s), Dems.get(s), U);
			for (float f : result)
				res.append("," + f);
		}

		return res.toString();
	}
	
	private static float[] validate(List<Float> responseTimes, Float estimatedDemand, float utilization) {
		float[] toReturn = new float[4];

		float temp = 0;
		for (Float f : responseTimes)
			temp += f;

		float avgResponseTime = temp / responseTimes.size();

		float estimatedResponseTime = estimatedDemand / (1 - utilization);

		float GAP = Math.abs((estimatedResponseTime - avgResponseTime) / avgResponseTime) * 100;

		toReturn[0] = estimatedDemand;
		toReturn[1] = avgResponseTime;
		toReturn[2] = estimatedResponseTime;
		toReturn[3] = GAP;

		return toReturn;
	}

	private static void flushLists() {
		cpu.clear();
		
		for (String s : RTs.keySet()) {
			RTs.put(s, new ArrayList<Float>());
			Dems.put(s, null);
		}
	}
	
	private static boolean hasEnoughValues() {
		boolean res = true;
		
		res &= (cpu.size() > 0);
		for (String s : RTs.keySet()) {
			res &= (RTs.get(s).size() > 0);
			res &= (Dems.get(s) != null);
		}
		
		return res;
	}
}
