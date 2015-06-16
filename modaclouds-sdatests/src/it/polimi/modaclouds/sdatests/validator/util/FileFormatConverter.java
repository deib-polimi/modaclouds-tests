package it.polimi.modaclouds.sdatests.validator.util;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class FileFormatConverter {
	
	private static final Logger logger = LoggerFactory.getLogger(FileFormatConverter.class);
	
	public static enum DataType { RDF_JSON, TOWER_JSON, GRAPHITE, INFLUXDB };
	
	public static class Element {
		public String resourceId;
		public String metric;
		public Double value;
		public Long timestamp;
		
		public Element(JSONObject obj) throws JSONException {
			resourceId = obj.getString("resourceId");
			metric = obj.getString("metric");
			value = obj.getDouble("value");
			timestamp = obj.getLong("timestamp");
		}
		
		public String getActualResourceId() {
			return resourceId.substring(0, resourceId.lastIndexOf("_"));
		}
		
		public String toString() {
			return this.getClass().getName() + "[resourceId: " + resourceId + ", metric: " + metric + ", value: " + value + ", timestamp: " + timestamp + "]";
		}
		
		public String toCSV() {
			return timestamp + "," + resourceId + "," + metric + "," + value + "," + timestamp;
		}
	}
	
	public static void rewriteJsonAsCsv(Path file, DataType origDataType) {
		if (!file.toFile().exists())
			throw new RuntimeException("The file doesn't exists!");
		
		try {
			Path p = Files.createTempFile("file", ".out");
			jsonToCsv(file, p, origDataType);
			Files.move(file, Paths.get(file.toFile().getParent(), file.toFile().getName() + "-bak"), StandardCopyOption.REPLACE_EXISTING);
			Files.move(p, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			logger.error("Error while rewrite the file JSON as a CSV.", e);
		}
		
	}
	
	public static void jsonToCsv(Path file, Path newFile, DataType origDataType) {
		try (Scanner sc = new Scanner(file);
				PrintWriter out = new PrintWriter(newFile.toFile())) {
			
			out.println("ObserverTimestamp,ResourceId,Metric,Value,Timestamp");
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				
				try {
					switch (origDataType) {
					case TOWER_JSON:
						JSONArray array = new JSONArray(line);
						for (int i = 0; i < array.length(); ++i) {
							JSONObject obj = array.getJSONObject(i);
							Element el = new Element(obj);
							
							out.println(el.toCSV());
						}
						break;
					default:
						break;
					}
				} catch (Exception e) { }
			}
		} catch (Exception e) {
			logger.error("Error while converting the file.", e);
		}
	}
	
	public static String jsonAsCsvString(Path file, DataType origDataType) {
		StringBuilder out = new StringBuilder();
		
		try (Scanner sc = new Scanner(file)) {
			
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				
				try {
					switch (origDataType) {
					case TOWER_JSON:
						JSONArray array = new JSONArray(line);
						for (int i = 0; i < array.length(); ++i) {
							JSONObject obj = array.getJSONObject(i);
							Element el = new Element(obj);
							
							out.append(el.toCSV() + "\n");
						}
						break;
					default:
						break;
					}
				} catch (Exception e) { }
			}
		} catch (Exception e) {
			logger.error("Error while converting the file.", e);
		}
		
		return out.toString();
	}

}
