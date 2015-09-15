package it.polimi.modaclouds.cloudapp.httpagenthelper.servlet;

import it.polimi.tower4clouds.java_app_dc.Property;
import it.polimi.tower4clouds.java_app_dc.Registry;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class InitDataCollectorServlet implements ServletContextListener {

//	private static final Logger logger = LoggerFactory.getLogger(InitDataCollectorServlet.class);

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
//		AppDataCollectorFactory.getInstance().stopSyncingWithKB();
	}
	
	private static String MONITORING_PLATFORM_IP = "localhost";
	private static int MONITORING_PLATFORM_PORT = 8170;
	private static String MONITORING_PLATFORM_PROVIDER = "amazon";
	
	public static final String MONITORING_PLATFORM_IP_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_MANAGER_IP";
	public static final String MONITORING_PLATFORM_PORT_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_MANAGER_PORT";
	public static final String MONITORING_PLATFORM_PROVIDER_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_ID";
	
	public static void loadFromEnrivonmentVariables() {
		String mpIp = System.getenv(MONITORING_PLATFORM_IP_PROPERTY);
		String mpPort = System.getenv(MONITORING_PLATFORM_PORT_PROPERTY);
		String mpProvider = System.getenv(MONITORING_PLATFORM_PROVIDER_PROPERTY);
		
		if (mpIp != null)
			MONITORING_PLATFORM_IP = mpIp;
		if (mpPort != null)
			try {
				MONITORING_PLATFORM_PORT = Integer.parseInt(mpPort);
			} catch (Exception e) { }
		if (mpProvider != null)
			MONITORING_PLATFORM_PROVIDER = mpProvider;
	}
	
	public static void loadFromSystemProperties() {
		String mpIp = System.getProperty(MONITORING_PLATFORM_IP_PROPERTY);
		String mpPort = System.getProperty(MONITORING_PLATFORM_PORT_PROPERTY);
		String mpProvider = System.getProperty(MONITORING_PLATFORM_PROVIDER_PROPERTY);
		
		if (mpIp != null)
			MONITORING_PLATFORM_IP = mpIp;
		if (mpPort != null)
			try {
				MONITORING_PLATFORM_PORT = Integer.parseInt(mpPort);
			} catch (Exception e) { }
		if (mpProvider != null)
			MONITORING_PLATFORM_PROVIDER = mpProvider;
	}
	
	static {
		loadFromSystemProperties();
		loadFromEnrivonmentVariables();
	}

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		Map<Property, String> applicationProperties = new HashMap<Property, String>();
		
		applicationProperties.put(Property.ID, "http-agent-helper");
		applicationProperties.put(Property.TYPE, "HTTPAgentHelper");
		applicationProperties.put(Property.CLOUD_PROVIDER_ID, MONITORING_PLATFORM_PROVIDER);
		applicationProperties.put(Property.CLOUD_PROVIDER_TYPE, "IaaS");
		applicationProperties.put(Property.VM_ID, "HTTPAgentHelper1");
		applicationProperties.put(Property.VM_TYPE, "Frontend");
		
		Registry.initialize(MONITORING_PLATFORM_IP, MONITORING_PLATFORM_PORT,
				applicationProperties, getClass().getPackage().getName());
		Registry.startMonitoring();
	}

}
