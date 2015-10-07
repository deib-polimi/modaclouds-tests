package it.polimi.modaclouds.scalingsdatests.validator.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONObject;

public class Datum {
	public static enum Type { RDF_JSON, TOWER_JSON, GRAPHITE, INFLUXDB, CSV, JMETER_CSV, TOMCAT_LOCALHOST_ACCESS_LOG };
	
	public String resourceId;
	public String metric;
	public Double value;
	public Long timestamp;
	
	public Datum(JSONObject obj, Type type) throws Exception {
		setFromJSONObject(obj, type);
	}
	
	private void setFromJSONObject(JSONObject obj, Type type) throws Exception {
		if (type != Type.TOWER_JSON)
			throw new Exception("The given datum type " + type.toString() + " is not supported at the moment.");
		resourceId = obj.getString("resourceId");
		metric = obj.getString("metric");
		value = obj.getDouble("value");
		timestamp = obj.getLong("timestamp");
	}
	
	public Datum(String line) throws Exception {
		this(line, Type.CSV);
	}
	
	public Datum(String line, Type type) throws Exception {
		setFromLine(line, type);
	}
	
	private void setFromLine(String line, Type type) throws Exception {
		switch (type) {
		case CSV: {
			String[] splitted = line.split(",");
			if (splitted.length != 5)
				throw new Exception("The given string is not a correct CSV with 5 comma-separated values.");
			resourceId = splitted[1];
			metric = splitted[2];
			value = Double.parseDouble(splitted[3]);
			timestamp = Long.parseLong(splitted[0]);
			break;
		}
		case JMETER_CSV: {
			String[] splitted = line.split(",");
			if (splitted.length != 12)
				throw new Exception("The given string is not a correct JMeter-CSV with 12 comma-separated values.");
			resourceId = splitted[2];
			metric = "Latency";
			value = Double.parseDouble(splitted[11]);
			timestamp = Long.parseLong(splitted[0]);
			break;
		}
		case TOWER_JSON: {
			JSONArray array = new JSONArray(line);
			JSONObject obj = array.getJSONObject(0);
			setFromJSONObject(obj, Type.TOWER_JSON);
			break;
		}
		case TOMCAT_LOCALHOST_ACCESS_LOG: {
			String[] splitted = line.split(" ");
			if (splitted.length != 11)
				throw new Exception("The given string is not a correct Tomcat-localhost_access_log with 11 space-separated values.");
			
			DateTimeFormatter formatter = DateTimeFormat.forPattern("[dd/MMM/yyyy:HH:mm:ss Z]").withLocale(Locale.ENGLISH);
		    timestamp = formatter.parseMillis(splitted[3] + " " + splitted[4]);
			
			resourceId = splitted[6];
			if (resourceId.indexOf('?') > -1)
				resourceId = resourceId.substring(0, resourceId.indexOf('?'));
			resourceId = resourceId.substring(resourceId.lastIndexOf('/') + 1);
			
			metric = "Latency";
			value = Double.parseDouble(splitted[10]);
			break;
		}
		default:
			break;
		}
	}
	
	public Datum(String resourceId, String metric, Double value, Long timestamp) {
		this.resourceId = resourceId;
		this.metric = metric;
		this.value = value;
		this.timestamp = timestamp;
	}
	
	public String getActualResourceId() {
		if (resourceId.lastIndexOf("_") > -1)
			return resourceId.substring(0, resourceId.lastIndexOf("_"));
		return resourceId;
	}
	
	public static String getActualResourceId(String resourceId) {
		if (resourceId.lastIndexOf("_") > -1)
			return resourceId.substring(0, resourceId.lastIndexOf("_"));
		return resourceId;
	}
	
	@Override
	public String toString() {
		return this.getClass().getName() + "[resourceId: " + resourceId + ", metric: " + metric + ", value: " + value + ", timestamp: " + timestamp + "]";
	}
	
	public static String getCSVHeader() {
		return "ObserverTimestamp,ResourceId,Metric,Value,Timestamp";
	}
	
	public String toCSV() {
		return timestamp + "," + resourceId + "," + metric + "," + value + "," + timestamp;
	}
	
	public static Type getTypeFromFile(Path p) throws Exception {
		if (p == null || !p.toFile().exists())
			throw new RuntimeException("File null or not found.");
		
		try (Scanner sc = new Scanner(p)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				
				Type t = getTypeFromString(line);
				if (t != null)
					return t;
			}
		}
		
		return null;
	}

	public static Type getTypeFromString(String line) {
		try {
			JSONArray array = new JSONArray(line);
			JSONObject obj = array.getJSONObject(0);
			try {
				new Datum(obj, Type.TOWER_JSON);
				return Type.TOWER_JSON;
			} catch (Exception e) { }
			try {
				new Datum(obj, Type.RDF_JSON);
				return Type.RDF_JSON;
			} catch (Exception e) { }
		} catch (Exception e) { }
		
		try {
			new Datum(line, Type.CSV);
			return Type.CSV;
		} catch (Exception e) { }
		
		try {
			new Datum(line, Type.JMETER_CSV);
			return Type.JMETER_CSV;
		} catch (Exception e) { }
		
		try {
			new Datum(line, Type.TOMCAT_LOCALHOST_ACCESS_LOG);
			return Type.TOMCAT_LOCALHOST_ACCESS_LOG;
		} catch (Exception e) { }
		
		return null;
	}
	
	public static Map<String, List<Datum>> getAllData(Path p) throws Exception {
		return getAllData(p, getTypeFromFile(p), false);
	}
	
	public static Map<String, List<Datum>> getAllData(Path p, boolean mixed) throws Exception {
		return getAllData(p, getTypeFromFile(p), mixed);
	}
	
	public static Map<String, List<Datum>> getAllData(Path p, Type origDataType) throws Exception {
		return getAllData(p, origDataType, false);
	}
	
	public static final String MIXED = "All";
	
	public static Map<String, List<Datum>> getAllData(Path p, Type origDataType, boolean mixed) throws Exception {
		if (p == null || !p.toFile().exists())
			throw new RuntimeException("File null or not found.");
		
		HashMap<String, List<Datum>> res = new HashMap<String, List<Datum>>();
		
		try (Scanner sc = new Scanner(p)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				
				try {
					switch (origDataType) {
					case TOWER_JSON: {
						JSONArray array = new JSONArray(line);
						for (int i = 0; i < array.length(); ++i) {
							JSONObject obj = array.getJSONObject(i);
							Datum el = new Datum(obj, origDataType);
							
							List<Datum> data = res.get(mixed ? MIXED : el.resourceId);
							if (data == null) {
								data = new ArrayList<Datum>();
								res.put(mixed ? MIXED : el.resourceId, data);
							}
							data.add(el);
						}
						break;
					}
					case CSV: {
						Datum el = new Datum(line, origDataType);
						List<Datum> data = res.get(mixed ? MIXED : el.resourceId);
						if (data == null) {
							data = new ArrayList<Datum>();
							res.put(mixed ? MIXED : el.resourceId, data);
						}
						data.add(el);
						break;
					}
					case JMETER_CSV: {
						if (!line.contains(",OK,"))
							continue;
						Datum el = new Datum(line, origDataType);
						List<Datum> data = res.get(mixed ? MIXED : el.resourceId);
						if (data == null) {
							data = new ArrayList<Datum>();
							res.put(mixed ? MIXED : el.resourceId, data);
						}
						data.add(el);
						break;
					}
					case TOMCAT_LOCALHOST_ACCESS_LOG: {
						if (!line.contains(" 200 "))
							continue;
						Datum el = new Datum(line, origDataType);
						List<Datum> data = res.get(mixed ? MIXED : el.resourceId);
						if (data == null) {
							data = new ArrayList<Datum>();
							res.put(mixed ? MIXED : el.resourceId, data);
						}
						data.add(el);
						break;
					}
					default:
						break;
					}
				} catch (Exception e) { }
			}
		}
		
		return res;
	}
}