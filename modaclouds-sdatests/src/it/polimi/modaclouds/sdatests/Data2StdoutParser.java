package it.polimi.modaclouds.sdatests;

import java.io.IOException;
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

import com.amazonaws.util.json.JSONArray;
import com.amazonaws.util.json.JSONException;
import com.amazonaws.util.json.JSONObject;

public class Data2StdoutParser {
	
	private static final Logger logger = LoggerFactory.getLogger(Data2StdoutParser.class);
	
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
	}
	
	private Path file;
	private DataType type;
	private Map<String, Map<String, List<Element>>> data;

	public Data2StdoutParser(Path file, DataType type) throws IOException {
		this.file = file;
		this.type = type;
		data = null;
		
		parse();
	}
	
	private void parse() throws IOException {
		data = new HashMap<String, Map<String, List<Element>>>();
		
		try (Scanner sc = new Scanner(file)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				
				try {
					switch (type) {
					case TOWER_JSON:
						JSONArray array = new JSONArray(line);
						for (int i = 0; i < array.length(); ++i) {
							JSONObject obj = array.getJSONObject(i);
							Element el = new Element(obj);
							
							Map<String, List<Element>> map = data.get(el.metric);
							if (map == null) {
								map = new HashMap<String, List<Element>>();
								data.put(el.metric, map);
								List<Element> list = new ArrayList<Element>();
								map.put(el.getActualResourceId(), list);
							}
							List<Element> list = map.get(el.getActualResourceId());
							if (list == null) {
								list = new ArrayList<Element>();
								map.put(el.getActualResourceId(), list);
							}
							list.add(el);
						}
						break;
					default:
						break;
					}
				} catch (Exception e) { }
			}
		}
	}
	
	public Map<String, Map<String, List<Element>>> getData() {
		return data;
	}
	
	public double getTotalPerMetric(String metric) {
		return getTotalPerMetric(metric, null);
	}
	
	public double getTotalPerMetric(String metric, String resourceId) {
		double res = 0.0;
		
		Map<String, List<Element>> map = data.get(metric);
		if (map == null)
			return res;
		
		for (String key : map.keySet()) {
			if (resourceId == null || resourceId.equals(key)) {
				List<Element> list = map.get(key);
				for (Element el : list)
					res += el.value;
			}
		}
		
		return res;
	}
	
	public int getCountPerMetric(String metric) {
		return getCountPerMetric(metric, null);
	}
	
	public int getCountPerMetric(String metric, String resourceId) {
		int res = 0;
		
		Map<String, List<Element>> map = data.get(metric);
		if (map == null)
			return res;
		
		for (String key : map.keySet())
			if (resourceId == null || resourceId.equals(key))
				res += map.get(key).size();
		
		return res;
	}
	
	public Double getAvgPerMetric(String metric) {
		return getAvgPerMetric(metric, null);
	}
	
	public Double getAvgPerMetric(String metric, String resourceId) {
		double total = getTotalPerMetric(metric, resourceId);
		int count = getCountPerMetric(metric, resourceId);
		
		if (total == 0 || count == 0)
			return 0.0;
		
		return total / count;
	}
	
	public static final String JMETER_LOG = "test_aggregate.jtl";
	
	public static Map<String, Integer> getRequestsPerPage(Path parent, int clients) {
		if (clients <= 0)
			throw new RuntimeException("You should have used at least one client.");
		
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		
		for (int i = 1; i <= clients; ++i) {
			Path jmeterAggregate = Paths.get(parent.getParent().getParent().getParent().toString(), "client" + i, JMETER_LOG);
			
			Map<String, Integer> tmp = getRequestsPerPage(jmeterAggregate);
			for (String key : tmp.keySet()) {
				Integer val = res.get(key);
				if (val == null)
					val = 0;
				res.put(key, val += tmp.get(key));
			}
		}
		
		return res;
	}
	
	private static Map<String, Integer> getRequestsPerPage(Path f) {
		if (f == null || !f.toFile().exists())
			throw new RuntimeException("File not found or wrong path ("
					+ f.toString() + ")");
		
		HashMap<String, Integer> res = new HashMap<String, Integer>();
		
		try (Scanner sc = new Scanner(f)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				String[] values = line.split(",");
				
				String page = values[2];
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
	
	public static final String RESULT_REQS = "requests.csv";
	
	public static void perform(Path parent, int clients) {
		if (clients < 1)
			throw new RuntimeException("You need to specify at least 1 client!");
		
		Path log = null;
		if (parent == null || !(log = Paths.get(parent.toString(), "logs", "data2stdout.log")).toFile().exists())
			throw new RuntimeException("The log file doesn't exists!");
		
		try (PrintWriter out = new PrintWriter(Paths.get(parent.toString(), RESULT_REQS).toFile())) {
			out.print("TotalRequestsConsidered,");
			
			Map<String, Integer> requestsPerPage = getRequestsPerPage(parent, clients);
			for (String key : requestsPerPage.keySet())
				out.printf("ActualRequests_%s,", key);
			out.println("TotalActualRequests");
			
			Data2StdoutParser parser = new Data2StdoutParser(log, DataType.TOWER_JSON);
			int tot = (int)parser.getTotalPerMetric("CountResponseTime");
			
			out.print(tot + ",");
			
			tot = 0;
			
			for (String key : requestsPerPage.keySet()) {
				int methodTot = requestsPerPage.get(key);
				out.print(methodTot + ",");
				tot += methodTot;
			}
			out.println(tot);
			
			out.flush();
		} catch (Exception e) {
			logger.error("Error while dealing with the result file.", e);
		}
	}
	
	public static void main(String[] args) throws Exception {
		Data2StdoutParser.perform(Paths.get("/Users/ft/Lavoro/tmp/sdatests-0.0.7/tests/0906151602-m3.xlarge-3333x3/mpl1/home/ubuntu"), 3);
	}

}
