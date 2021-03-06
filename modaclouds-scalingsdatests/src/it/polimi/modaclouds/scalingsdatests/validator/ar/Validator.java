package it.polimi.modaclouds.scalingsdatests.validator.ar;

import it.cloud.utils.Local;
import it.polimi.modaclouds.scalingsdatests.validator.util.Datum;
import it.polimi.modaclouds.scalingsdatests.validator.util.FileHelper;
import it.polimi.modaclouds.scalingsdatests.validator.util.GenericChart;

import java.io.File;
import java.io.FileFilter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Validator {
	
	private static final Logger logger = LoggerFactory.getLogger(Validator.class);

	public static final String DATA_FILE = "data2stdout.log";
	public static final String CLOUDML_FILE = "cloudMl-*.csv";
	
	public static final int DEFAULT_WINDOW = 10;

	public static void perform(Path parent, int window, boolean createFromSingleLog, double cpuMarker, double rtMarker, double rtMaximum) {
		if (parent == null || !parent.toFile().exists())
			throw new RuntimeException("Parent folder not found! (" + parent == null ? "null" : parent.toString() + ")");
		
		try {
			if (createFromSingleLog) {
				parent = Paths.get(parent.toString(), "logs");
				
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
			List<Datum> rt5min = new ArrayList<Datum>();
			int i = 0;
			double sum = 0.0;
			for (Datum d : rt) {
				sum += d.value;
				++i;
				if (i >= 30) {
					rt5min.add(new Datum(d.resourceId, d.metric + "_5Min", sum/i, d.timestamp));
					i = 0;
					sum = 0.0;
				}
			}
			
			Path p;
			if (createFromSingleLog)
				p = getFileInFolder(Paths.get(parent.getParent().toString(), "autoscalingReasoner"), CLOUDML_FILE);
			else
				p = getFileInFolder(Paths.get(parent.toString(), "autoscalingReasoner"), CLOUDML_FILE);
			if (p == null) {
				if (createFromSingleLog)
					p = getFileInFolder(Paths.get(parent.getParent().toString(), "tower4clouds/manager/manager-server/target/manager-server-0.4-SNAPSHOT"), CLOUDML_FILE);
				else
					p = getFileInFolder(Paths.get(parent.toString(), "tower4clouds/manager/manager-server/target/manager-server-0.4-SNAPSHOT"), CLOUDML_FILE);
			}
			
			List<Datum> machines = null;
			if (p != null)
				machines = Datum.getAllData(p, Datum.Type.CLOUDML_ACTION_CSV, true).get(Datum.MIXED);

			long minTimestamp = 0;
			if (workload != null)
				minTimestamp = workload.get(0).timestamp;
			if (cpu != null)
				if (minTimestamp != 0 && cpu.get(0).timestamp < minTimestamp)
					minTimestamp = cpu.get(0).timestamp;
			if (rt != null)
				if (minTimestamp != 0 && rt.get(0).timestamp < minTimestamp)
					minTimestamp = rt.get(0).timestamp;
			if (machines != null)
				if (minTimestamp != 0 && machines.get(0).timestamp < minTimestamp)
					minTimestamp = machines.get(0).timestamp;
			
			GenericChart.createGraphFromData(workloadPerS, Double.MAX_VALUE, false, minTimestamp).addDataAsVerticalMarkers(machines, minTimestamp).save2png(parent.toString(), "wlPerS.png");
			GenericChart.createGraphFromData(cpu, 1.0, true, minTimestamp, new GenericChart.Marker(cpuMarker)).addDataAsVerticalMarkers(machines, minTimestamp).save2png(parent.toString(), "cpu.png");
			GenericChart.createGraphFromData(rt, rtMaximum, false, minTimestamp, new GenericChart.Marker(rtMarker)).addDataAsVerticalMarkers(machines, minTimestamp).save2png(parent.toString(), "rt.png");
			
			rt5min.addAll(rt);
			
			GenericChart.createGraphFromData(rt5min, rtMaximum, false, minTimestamp, new GenericChart.Marker(rtMarker)).addDataAsVerticalMarkers(machines, minTimestamp).save2png(parent.toString(), "rt5min.png");
			
			GenericChart.createGraphFromData(machines, Double.MAX_VALUE, false, minTimestamp).save2png(parent.toString(), "machines.png");
			
			logger.info("Done!");
		} catch (Exception e) {
			logger.error("Error while running the script.", e);
		}
	}
	
	public static Path getFileInFolder(Path folder, String fileName) {
		final String ffileName = fileName.replaceAll("[*]", ".*");
		
		File[] fs = folder.toFile().listFiles(new FileFilter() {
			
			@Override
			public boolean accept(File pathname) {
				return (Pattern.matches(ffileName, pathname.getName()));
			}
		});
		
		if (fs.length > 0)
			return fs[0].toPath();
		else
			return null;
	}

}
