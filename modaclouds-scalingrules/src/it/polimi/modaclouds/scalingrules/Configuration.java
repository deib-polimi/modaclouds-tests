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
	
	public static final String MONITORING_RULE_CPU_ABOVE_FILE = "cpuRuleAbove.txt";
	public static final String MONITORING_RULE_CPU_UNDER_FILE = "cpuRuleUnder.txt";
	public static final String CLOUDML_DEPLOYMENT_MODEL = "cloudml.json";
	
	public static final int DEFAULT_CLOUDML_PORT = 9000;
	public static int CLOUDML_PORT = DEFAULT_CLOUDML_PORT;
	
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
		
		prop.put("CLOUDML_PORT", CLOUDML_PORT);
		
		prop.store(fos, "ScalingRule configuration properties");
		fos.flush();
	}
	
	public static void loadConfiguration(String filePath) throws IOException {
		Properties prop = new Properties();
		InputStream is = getInputStream(filePath);
		prop.load(is);
		
		CLOUDML_PORT = Integer.parseInt(prop.getProperty("CLOUDML_PORT", Integer.valueOf(CLOUDML_PORT).toString()));
	}
	
	public static List<String> checkValidity() {
		ArrayList<String> errors = new ArrayList<String>();
		
		return errors;
	}
	
}
