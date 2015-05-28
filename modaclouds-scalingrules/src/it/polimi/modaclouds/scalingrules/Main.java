package it.polimi.modaclouds.scalingrules;

import it.cloud.amazon.ec2.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {
	
	private static final Logger logger = LoggerFactory.getLogger(Main.class);
	
	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;
	
	@Parameter(names = "-monitoringPlatformIp", description = "The IP of the monitoring platform if it's already running")
	private String monitoringPlatformIp = null;
	
	@Parameter(names = "-monitoringPlatformPort", description = "The port of the monitoring platform")
	private int monitoringPlatformPort = it.polimi.modaclouds.scalingrules.Configuration.MONITORING_PLATFORM_PORT;
	
	@Parameter(names = "-cloudMLIp", description = "The IP of CloudML")
	private String cloudMLIp = it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_IP;
	
	@Parameter(names = "-cloudMLPort", description = "The port of CloudML")
	private int cloudMLPort = it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_PORT;
	
	@Parameter(names = "-useOnDemand", description = "Use OnDemand instances instead of OnSpot")
	private boolean useOnDemand = false;
	
	@Parameter(names = "-reuseInstances", description = "Reuse running instances")
	private boolean reuseInstances = false;
	
	@Parameter(names = "-leaveInstancesOn", description = "Leave the instances running")
	private boolean leaveInstancesOn = false;
	
	@Parameter(names = "-clients", description = "The number of JMeter clients to be used (default: 1)")
	private int clients = 1;
	
	@Parameter(names = "-data", description = "The file with the workload")
	private String data = null;
	
	@Parameter(names = "-batch", description = "Go in batch mode, reading a file describing a number of tests as parameter")
	private String batch = null;
	
	@Parameter(names = "-baseJmx", description = "The JMX that will be configured for the run")
	private String baseJmx = "jmeterTestTemplate.jmx";
	
	@Parameter(names = "-background", description = "Run in background", hidden = true)
	private boolean background = false;
	
	@Parameter(names = "-onlyStartMachines", description = "Starts the machines and quit", hidden = true)
	private boolean onlyStartMachines = false;
	
	@Parameter(names = "-wait", description = "Stalls the execution of the given milliseconds without starting any test at all (used only in batch)", hidden = true)
	private int wait = -1;
	
	@Parameter(names = "-loadBalancer", description = "The name of the load balancer that will be used", required = true)
	private String loadBalancer = null;
	
	public static final String APP_TITLE = "\nScaling Rules Test\n";
	
	public static void main(String[] args) {
//		args = new String[] { "-clients", "1", "-data", "tests.txt", "-useOnDemand", "-reuseInstances", "-leaveInstancesOn", "-monitoringPlatformIp", "specclient2.dei.polimi.it" };
		
		Main m = new Main();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		it.polimi.modaclouds.scalingrules.Configuration.MONITORING_PLATFORM_PORT = m.monitoringPlatformPort;
		it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_IP = m.cloudMLIp;
		it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_PORT = m.cloudMLPort;
		
		List<String> errors = Configuration.checkValidity();
		if (errors.size() > 0) {
			if (errors.size() == 1) {
				logger.error("There is 1 configuration error:\n- " + errors.get(0));
			} else {
				StringBuilder sb = new StringBuilder();
				sb.append("There are " + errors.size() + " configuration errors:");
				for (String error : errors)
					sb.append("\n- " + error);
				logger.error(sb.toString());
			}
			
			System.exit(-1);
		}
		
		if (m.batch == null && m.data == null) {
			logger.error("You need to provide a data or batch file!");
			System.exit(-1);
		} else if (m.batch == null) {
			doTest(m.cloudMLIp, m.cloudMLPort, m.monitoringPlatformIp, m.monitoringPlatformPort, m.clients, Paths.get(m.baseJmx), m.data, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.loadBalancer);
		} else {
			ArrayList<Thread> threads = new ArrayList<Thread>(); 
			
			try (Scanner sc = new Scanner(Configuration.getInputStream(m.batch))) {
				while (sc.hasNextLine()) {
					String line = sc.nextLine();
					if (line.startsWith("#") || line.trim().length() == 0)
						continue;
					
					String[] fields = line.split(" ");
					jc = new JCommander(m, fields);
					
					if (m.help)
						continue;
					
					if (m.wait > 0) {
						logger.info("Waiting {} ms...", m.wait);
						try {
							Thread.sleep(m.wait);
						} catch (Exception e) { }
						m.wait = -1;
						continue;
					}
					
					if (m.background)
						threads.add(doTestInBackground(m.cloudMLIp, m.cloudMLPort, m.monitoringPlatformIp, m.monitoringPlatformPort, m.clients, Paths.get(m.baseJmx), m.data, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.loadBalancer));
					else
						doTest(m.cloudMLIp, m.cloudMLPort, m.monitoringPlatformIp, m.monitoringPlatformPort, m.clients, Paths.get(m.baseJmx), m.data, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.loadBalancer);
				}
				
				for (Thread t : threads)
					t.join();
				
				logger.error("Jobs ended!");
			} catch (Exception e) {
				logger.error("Error while reading the file!", e);
				System.exit(-1);
			}
		}
		
		System.exit(0);
	}
	
	public static void doTest(String cloudMLIp, int cloudMLPort, String monitoringPlatformIp, int monitoringPlatformPort, int clients, Path baseJmx, String data, boolean useOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String loadBalancer) {
		logger.info("Preparing the system and running the test...");
		
		boolean res = Test.performTest(cloudMLIp, cloudMLPort, monitoringPlatformIp, monitoringPlatformPort, clients, baseJmx, data, useOnDemand, reuseInstances, leaveInstancesOn, onlyStartMachines, loadBalancer);
		
		if (res)
			logger.info("The test run correctly!");
		else
			logger.error("There were some problems during the test! :(");
	}
	
	public static Thread doTestInBackground(String cloudMLIp, int cloudMLPort, String monitoringPlatformIp, int monitoringPlatformPort, int clients, Path baseJmx, String data, boolean useOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String loadBalancer) {
		final String fcloudMLIp = cloudMLIp;
		final int fcloudMLPort = cloudMLPort;
		final String fmonitoringPlatformIp = monitoringPlatformIp;
		final int fmonitoringPlatformPort = monitoringPlatformPort;
		final int fclients = clients;
		final Path fbaseJmx = baseJmx;
		final String fdata = data;
		final boolean fuseOnDemand = useOnDemand;
		final boolean freuseInstances = reuseInstances;
		final boolean fleaveInstancesOn = leaveInstancesOn;
		final boolean fonlyStartMachines = onlyStartMachines;
		final String floadBalancer = loadBalancer;
		
		Thread t = new Thread() {
			public void run() {
				doTest(fcloudMLIp, fcloudMLPort, fmonitoringPlatformIp, fmonitoringPlatformPort, fclients, fbaseJmx, fdata, fuseOnDemand, freuseInstances, fleaveInstancesOn, fonlyStartMachines, floadBalancer);
			}
		};
		
		t.start();
		return t;
	}

}
