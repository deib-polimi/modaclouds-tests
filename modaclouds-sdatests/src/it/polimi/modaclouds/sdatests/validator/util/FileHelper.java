package it.polimi.modaclouds.sdatests.validator.util;

import it.polimi.modaclouds.sdatests.validator.util.Datum.Type;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class FileHelper {
	
	private static final Logger logger = LoggerFactory.getLogger(FileHelper.class);
	
	public static void rewriteJsonAsCsv(Path file) {
		if (!file.toFile().exists())
			throw new RuntimeException("The file doesn't exists!");
		
		try {
			Type origDataType = Datum.getTypeFromFile(file);
			if (origDataType == Type.CSV)
				return;
			
			Path p = Files.createTempFile("file", ".out");
			jsonToCsv(file, p, origDataType);
			Files.move(file, Paths.get(file.toFile().getParent(), file.toFile().getName() + "-bak"), StandardCopyOption.REPLACE_EXISTING);
			Files.move(p, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			logger.error("Error while rewrite the file JSON as a CSV.", e);
		}
	}
	
	public static void jsonToCsv(Path file, Path newFile, Datum.Type origDataType) {
		if (origDataType == Type.CSV)
			throw new RuntimeException("The file is already CSV!");
		
		try {
			List<Datum> data = Datum.getAllData(file, origDataType, true).get(Datum.MIXED);
			
			try (PrintWriter out = new PrintWriter(newFile.toFile())) {
				out.println(Datum.getCSVHeader());
				for (Datum d : data)
					out.println(d.toCSV());
			}
			
		} catch (Exception e) {
			logger.error("Error while converting the file in CSV.", e);
		}
	}
	
	public static void createGlassfishReportFromTomcatLog(Path file, Path basePath, String[] methods) {
		try {
			Map<String, List<Datum>> dataMap = Datum.getAllData(file, Datum.Type.TOMCAT_LOCALHOST_ACCESS_LOG, false);
			for (String method : methods) {
				List<Datum> data = dataMap.get(method);
				
				int count = data.size();
				double sum = 0.0;
				double max = Double.MIN_VALUE;
				long maxTimestamp = -1;
				
				for (Datum d : data) {
					if (d.value > max) {
						max = d.value;
						maxTimestamp = d.timestamp;
					}
					sum += d.value;
				}
				
				JSONObject root = new JSONObject();
				root.put("command", "Monitoring Data");
				root.put("exit_code", "SUCCESS");
				root.put("message", "");
				
				JSONObject extraProperties = new JSONObject();
				root.put("extraProperties", extraProperties);
				extraProperties.put("childResources", new JSONObject());
				
				JSONObject entity = new JSONObject();
				extraProperties.put("entity", entity);
				
				{
					JSONObject errorcount = new JSONObject();
					entity.put("errorcount", errorcount);
					errorcount.put("count", 0);
					errorcount.put("description", "Number of error responses (that is, responses with a status code greater than or equal to 400)");
					errorcount.put("lastsampletime", -1);
					errorcount.put("name", "ErrorCount");
					errorcount.put("starttime", data.get(0).timestamp);
					errorcount.put("unit", "count");
				}
				
				{
					JSONObject maxtime = new JSONObject();
					entity.put("maxtime", maxtime);
					maxtime.put("count", max);
					maxtime.put("description", "Maximum response time");
					maxtime.put("lastsampletime", maxTimestamp);
					maxtime.put("name", "MaxTime");
					maxtime.put("starttime", data.get(0).timestamp);
					maxtime.put("unit", "millisecond");
				}
				
				{
					JSONObject processingtime = new JSONObject();
					entity.put("processingtime", processingtime);
					processingtime.put("count", sum / count);
					processingtime.put("description", "Average response time");
					processingtime.put("lastsampletime", data.get(data.size() - 1).timestamp);
					processingtime.put("name", "ProcessingTime");
					processingtime.put("starttime", data.get(0).timestamp);
					processingtime.put("unit", "millisecond");
				}
				
				{
					JSONObject requestcount = new JSONObject();
					entity.put("requestcount", requestcount);
					requestcount.put("count", count);
					requestcount.put("description", "Number of requests processed");
					requestcount.put("lastsampletime", data.get(data.size() - 1).timestamp);
					requestcount.put("name", "RequestCount");
					requestcount.put("starttime", data.get(0).timestamp);
					requestcount.put("unit", "count");
				}
				
				{
					JSONObject servicetime = new JSONObject();
					entity.put("servicetime", servicetime);
					servicetime.put("count", sum);
					servicetime.put("description", "Aggregate response time");
					servicetime.put("lastsampletime", data.get(data.size() - 1).timestamp);
					servicetime.put("name", "ServiceTime");
					servicetime.put("starttime", data.get(0).timestamp);
					servicetime.put("unit", "millisecond");
				}
				
				try (PrintWriter out = new PrintWriter(Paths.get(basePath.toString(), method + ".json").toFile())) {
					out.println(root.toString());
				}
			}
			
		} catch (Exception e) {
			logger.error("Error while creating the fake Glassfish report.", e);
		}
	}

}
