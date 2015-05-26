package it.polimi.modaclouds.scalingrules;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Configuration {

	private static final Logger logger = LoggerFactory.getLogger(Configuration.class);
	
	public static final String CONFIGURATION = "configuration.properties";
	
	public static final int DEFAULT_MONITORING_PLATFORM_PORT = 8170;
	public static final int DEFAULT_CLOUDML_PORT = 9000;
	public static final String DEFAULT_CLOUDML_IP = "127.0.0.1";
	
	public static int MONITORING_PLATFORM_PORT = DEFAULT_MONITORING_PLATFORM_PORT;
	public static String CLOUDML_IP = DEFAULT_CLOUDML_IP;
	public static int CLOUDML_PORT = DEFAULT_CLOUDML_PORT;
	
	public static final String MONITORING_RULE_CPU_ABOVE_FILE = "monitoringrules/cpuRuleAbove.txt";
	public static final String MONITORING_RULE_CPU_UNDER_FILE = "monitoringrules/cpuRuleUnder.txt";
	public static final String MONITORING_PLATFORM_MODEL = "models/monitoringplatform.json";
	public static final String CLOUDML_DEPLOYMENT_MODEL = "models/cloudml.json";
	
	static {
		try {
			loadConfiguration(CONFIGURATION);
		} catch (Exception e) {
			logger.error("Error while configuring the system.", e);
		}
	}
	
	public static File getAsFile(String filePath) {
		File f = new File(filePath);
		if (f.exists())
			return f;
		
		URL url = Configuration.class.getResource(filePath);
		if (url == null)
			url = Configuration.class.getResource("/" + filePath);
		if (url == null)
			return null;
		return new File(url.getFile());
	}
	
//	public static void main(String[] args) {
//		System.out.println(getAsFile(CLOUDML_DEPLOYMENT_MODEL).getAbsolutePath());
//	}
	
	public static InputStream getInputStream(String filePath) {
		File f = new File(filePath);
		if (f.exists())
			try {
				return new FileInputStream(f);
			} catch (Exception e) { }
		
		InputStream is = Configuration.class.getResourceAsStream(filePath);
		if (is == null)
			is = Configuration.class.getResourceAsStream("/" + filePath);
		return is;
	}
	
	public static String readFile(String filePath) throws Exception {
		StringBuilder ret = new StringBuilder();

		try (Scanner sc = new Scanner(Configuration.getInputStream(filePath))) {
			while (sc.hasNextLine())
				ret.append(sc.nextLine() + "\n");
		}

		return ret.toString();
	}
	
	public static void saveConfiguration(String filePath) throws IOException {
		FileOutputStream fos = new FileOutputStream(filePath);
		Properties prop = new Properties();
		
		prop.put("MONITORING_PLATFORM_PORT", Integer.valueOf(MONITORING_PLATFORM_PORT).toString());
		prop.put("CLOUDML_IP", CLOUDML_IP);
		prop.put("CLOUDML_PORT", Integer.valueOf(CLOUDML_PORT).toString());
		
		prop.store(fos, "ScalingRule configuration properties");
		fos.flush();
	}
	
	public static void loadConfiguration(String filePath) throws IOException {
		Properties prop = new Properties();
		InputStream is = getInputStream(filePath);
		prop.load(is);
		
		CLOUDML_IP = prop.getProperty("CLOUDML_IP", CLOUDML_IP);
		MONITORING_PLATFORM_PORT = getProperty(prop, "MONITORING_PLATFORM_PORT", MONITORING_PLATFORM_PORT, DEFAULT_MONITORING_PLATFORM_PORT);
		CLOUDML_PORT = getProperty(prop, "CLOUDML_PORT", CLOUDML_PORT, DEFAULT_CLOUDML_PORT);
	}
	
	private static int getProperty(Properties prop, String propertyName, int oldValue, int defaultValue) {
		int res;
		try {
			res = Integer.parseInt(prop.getProperty(propertyName, Integer.valueOf(oldValue).toString()));
		} catch (Exception e) {
			res = defaultValue;
		}
		return res;
	}
	
	public static List<String> checkValidity() {
		ArrayList<String> errors = new ArrayList<String>();
		
		if (CLOUDML_IP == null || CLOUDML_IP.trim().length() == 0)
			errors.add("The CloudML IP isn't valid");
		if (MONITORING_PLATFORM_PORT < 0 || MONITORING_PLATFORM_PORT > 65536)
			errors.add("The monitoring platform port isn't valid");
		if (!isLocal(CLOUDML_IP) && (CLOUDML_PORT < 0 || CLOUDML_PORT > 65536))
			errors.add("The CloudML port isn't valid");
		
		return errors;
	}
	
	private static boolean isLocal(String ip) {
		return ip.equals("127.0.0.1") || ip.equals("localhost");
	}
	
}
