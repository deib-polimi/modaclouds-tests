package it.polimi.modaclouds.scalingsdatests.validator.ar;

import it.cloud.utils.Local;
import it.polimi.modaclouds.scalingsdatests.validator.util.Datum;
import it.polimi.modaclouds.scalingsdatests.validator.util.FileHelper;
import it.polimi.modaclouds.scalingsdatests.validator.util.GenericChart;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Validator {
	
	private static final Logger logger = LoggerFactory.getLogger(Validator.class);

	public static final String DATA_FILE = "data2stdout.log";
	public static final String CLOUDML_FILE = "cloudMl-9030.csv";
	
	public static final int DEFAULT_WINDOW = 10;

	public static void perform(Path parent, int window, boolean createFromSingleLog, double cpuMarker, double rtMarker) {
		if (parent == null || !parent.toFile().exists())
			throw new RuntimeException("Parent folder not found! (" + parent == null ? "null" : parent.toString() + ")");
		
		try {
			if (createFromSingleLog) {
				logger.info("Converting the results from JSON to CSV...");
				
				try {
					Path p = Paths.get(parent.toString(), DATA_FILE);
					
					FileHelper.rewriteJsonAsCsv(p);
					
					Local.exec(String.format("echo %3$s > %2$s && cat %1$s | grep \"Workload\" | grep -v \"Forecasted\" >> %2$s", p.toString(), Paths.get(p.getParent().toString(), "wl.out"), Datum.getCSVHeader()));
					Local.exec(String.format("echo %3$s > %2$s && cat %1$s | grep \"CPUUtilization\" >> %2$s", p.toString(), Paths.get(p.getParent().toString(), "cpu.out"), Datum.getCSVHeader()));
					Local.exec(String.format("echo %3$s > %2$s && cat %1$s | grep \"ResponseTime\" >> %2$s", p.toString(), Paths.get(p.getParent().toString(), "rt.out"), Datum.getCSVHeader()));
				} catch (Exception e) {
					logger.error("Error while converting the file.", e);
				}
			}
			
			List<Datum> workload = Datum.getAllData(Paths.get(parent.toString(), "wl.out"), true).get(Datum.MIXED);
			List<Datum> workloadPerS = new ArrayList<Datum>();
			for (Datum d : workload)
				workloadPerS.add(new Datum(d.resourceId, d.metric, d.value/window, d.timestamp));
			
			List<Datum> cpu = Datum.getAllData(Paths.get(parent.toString(), "cpu.out"), true).get(Datum.MIXED);
			List<Datum> rt = Datum.getAllData(Paths.get(parent.toString(), "rt.out"), true).get(Datum.MIXED);
			
			GenericChart.createGraphFromData(workloadPerS).save2png(parent.toString(), "wlPerS.png");
			GenericChart.createGraphFromData(cpu, new GenericChart.Marker(cpuMarker)).save2png(parent.toString(), "cpu.png");
			GenericChart.createGraphFromData(rt, new GenericChart.Marker(rtMarker)).save2png(parent.toString(), "rt.png");
			
			logger.info("Done!");
		} catch (Exception e) {
			logger.error("Error while running the script.", e);
		}
	}

}
