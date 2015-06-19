package it.polimi.modaclouds.sdatests.validator.util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONObject;

public class Datum {
	public static enum Type { RDF_JSON, TOWER_JSON, GRAPHITE, INFLUXDB, CSV };
	
	public String resourceId;
	public String metric;
	public Double value;
	public Long timestamp;
	
	public Datum(JSONObject obj) throws Exception {
		this(obj, Type.TOWER_JSON);
	}
	
	public Datum(JSONObject obj, Type type) throws Exception {
		if (type != Type.TOWER_JSON)
			throw new Exception("The given datum type " + type.toString() + " is not supported at the moment.");
		resourceId = obj.getString("resourceId");
		metric = obj.getString("metric");
		value = obj.getDouble("value");
		timestamp = obj.getLong("timestamp");
	}
	
	public Datum(String csv) throws Exception {
		String[] splitted = csv.split(",");
		if (splitted.length != 5)
			throw new Exception("The given string is not a correct CSV with 5 comma-separated values.");
		resourceId = splitted[1];
		metric = splitted[2];
		value = Double.parseDouble(splitted[3]);
		timestamp = Long.parseLong(splitted[0]);
	}
	
	public Datum(String resourceId, String metric, Double value, Long timestamp) {
		this.resourceId = resourceId;
		this.metric = metric;
		this.value = value;
		this.timestamp = timestamp;
	}
	
	public String getActualResourceId() {
		return resourceId.substring(0, resourceId.lastIndexOf("_"));
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
			new Datum(obj, Type.TOWER_JSON);
			return Type.TOWER_JSON;
		} catch (Exception e) { }
		
		try {
			JSONArray array = new JSONArray(line);
			JSONObject obj = array.getJSONObject(0);
			new Datum(obj, Type.RDF_JSON);
			return Type.RDF_JSON;
		} catch (Exception e) { }
		
		try {
			new Datum(line);
			return Type.CSV;
		} catch (Exception e) { }
		
		return null;
	}
	
	public static List<Datum> getAllData(Path p) throws Exception {
		return getAllData(p, getTypeFromFile(p));
	}
	
	public static List<Datum> getAllData(Path p, Type origDataType) throws Exception {
		if (p == null || !p.toFile().exists())
			throw new RuntimeException("File null or not found.");
		
		ArrayList<Datum> res = new ArrayList<Datum>();
		
		try (Scanner sc = new Scanner(p)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				
				try {
					switch (origDataType) {
					case TOWER_JSON:
						JSONArray array = new JSONArray(line);
						for (int i = 0; i < array.length(); ++i) {
							JSONObject obj = array.getJSONObject(i);
							Datum el = new Datum(obj, origDataType);
							res.add(el);
						}
						break;
					case CSV:
						Datum el = new Datum(line);
						res.add(el);
					default:
						break;
					}
				} catch (Exception e) { }
			}
		}
		
		return res;
	}
}