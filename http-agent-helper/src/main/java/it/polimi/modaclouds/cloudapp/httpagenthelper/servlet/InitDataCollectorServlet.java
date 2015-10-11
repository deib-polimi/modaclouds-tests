package it.polimi.modaclouds.cloudapp.httpagenthelper.servlet;

import it.polimi.tower4clouds.java_app_dc.Property;
import it.polimi.tower4clouds.java_app_dc.Registry;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitDataCollectorServlet implements ServletContextListener {

	private static final Logger logger = LoggerFactory.getLogger(InitDataCollectorServlet.class);

	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
//		AppDataCollectorFactory.getInstance().stopSyncingWithKB();
	}
	
	private static String MONITORING_PLATFORM_IP = "localhost";
	private static int MONITORING_PLATFORM_PORT = 8170;
	private static String CLOUD_PROVIDER_ID = "amazon";
	private static String CLOUD_PROVIDER_TYPE = "IaaS";
	private static String INTERNAL_COMPONENT_ID = "http-agent-helper";
	private static String INTERNAL_COMPONENT_TYPE = "HTTPAgentHelper";
	private static String VM_ID = "HTTPAgentHelper1";
	private static String VM_TYPE = "HTTPAgent";
	
	
	public static final String MONITORING_PLATFORM_IP_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_MANAGER_IP";
	public static final String MONITORING_PLATFORM_PORT_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_MANAGER_PORT";
	public static final String CLOUD_PROVIDER_ID_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_ID";
	public static final String CLOUD_PROVIDER_TYPE_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_CLOUD_PROVIDER_TYPE";
	public static final String INTERNAL_COMPONENT_ID_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_ID";
	public static final String INTERNAL_COMPONENT_TYPE_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_INTERNAL_COMPONENT_TYPE";
	public static final String VM_ID_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_VM_ID";
	public static final String VM_TYPE_PROPERTY = "MODACLOUDS_TOWER4CLOUDS_VM_TYPE";
	
	public static void load(
			String mpIp, String mpPort,
			String cloudProviderId, String cloudProviderType,
			String internalComponentId, String internalComponentType,
			String vmId, String vmType
			) {
		if (mpIp != null)
			MONITORING_PLATFORM_IP = mpIp;
		if (mpPort != null)
			try {
				MONITORING_PLATFORM_PORT = Integer.parseInt(mpPort);
			} catch (Exception e) { }
		
		if (cloudProviderId != null)
			CLOUD_PROVIDER_ID = cloudProviderId;
		if (cloudProviderType != null)
			CLOUD_PROVIDER_TYPE = cloudProviderType;
		
		if (internalComponentId != null)
			INTERNAL_COMPONENT_ID = internalComponentId;
		if (internalComponentType != null)
			INTERNAL_COMPONENT_TYPE = internalComponentType;
		
		if (vmId != null)
			VM_ID = vmId;
		if (vmType != null)
			VM_TYPE = vmType;
	}
	
	public static void loadFromEnrivonmentVariables() {
		load(
				System.getenv(MONITORING_PLATFORM_IP_PROPERTY),
				System.getenv(MONITORING_PLATFORM_PORT_PROPERTY),
				System.getenv(CLOUD_PROVIDER_ID_PROPERTY),
				System.getenv(CLOUD_PROVIDER_TYPE_PROPERTY),
				System.getenv(INTERNAL_COMPONENT_ID_PROPERTY),
				System.getenv(INTERNAL_COMPONENT_TYPE_PROPERTY),
				System.getenv(VM_ID_PROPERTY),
				System.getenv(VM_TYPE_PROPERTY)
				);
	}
	
	public static void loadFromSystemProperties() {
		load(
				System.getProperty(MONITORING_PLATFORM_IP_PROPERTY),
				System.getProperty(MONITORING_PLATFORM_PORT_PROPERTY),
				System.getProperty(CLOUD_PROVIDER_ID_PROPERTY),
				System.getProperty(CLOUD_PROVIDER_TYPE_PROPERTY),
				System.getProperty(INTERNAL_COMPONENT_ID_PROPERTY),
				System.getProperty(INTERNAL_COMPONENT_TYPE_PROPERTY),
				System.getProperty(VM_ID_PROPERTY),
				System.getProperty(VM_TYPE_PROPERTY)
				);
	}
	
	static {
		loadFromSystemProperties();
		loadFromEnrivonmentVariables();
	}
	
	public static void printConfig(){
		try {
			Field[] fs = InitDataCollectorServlet.class.getFields();
			for (Field f : fs) {
				if (Modifier.isFinal(f.getModifiers()))
					continue;
				logger.debug("{} = {}", f.getName(), f.get(null));
			}
		} catch (Exception e) {
			logger.error("Error while getting the value of the properties.", e);
		}
	}

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		Map<Property, String> applicationProperties = new HashMap<Property, String>();
		
		applicationProperties.put(Property.ID, INTERNAL_COMPONENT_ID);
		applicationProperties.put(Property.TYPE, INTERNAL_COMPONENT_TYPE);
		applicationProperties.put(Property.CLOUD_PROVIDER_ID, CLOUD_PROVIDER_ID);
		applicationProperties.put(Property.CLOUD_PROVIDER_TYPE, CLOUD_PROVIDER_TYPE);
		applicationProperties.put(Property.VM_ID, VM_ID);
		applicationProperties.put(Property.VM_TYPE, VM_TYPE);
		
		printConfig();
		
		Registry.initialize(MONITORING_PLATFORM_IP, MONITORING_PLATFORM_PORT,
				applicationProperties, getClass().getPackage().getName());
		Registry.startMonitoring();
	}

}
