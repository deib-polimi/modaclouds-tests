package it.polimi.modaclouds.scalingrules;

import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.Configuration;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.utils.CloudException;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.polimi.tower4clouds.rules.MonitoringRule;
import it.polimi.tower4clouds.rules.MonitoringRules;
import it.polimi.modaclouds.scalingrules.utils.CloudML;
import it.polimi.modaclouds.scalingrules.utils.MonitoringPlatform;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.Date;
import java.util.Scanner;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Test {
	
	private static final Logger logger = LoggerFactory.getLogger(Test.class);

	private MonitoringPlatform monitoringPlatform;
	private CloudML cloudML;
	
	private String cloudMLIp;
	private int cloudMLPort;
	private int monitoringPlatformPort;
	
	public Test(String cloudMLIp, int cloudMLPort, int monitoringPlatformPort, int clients) throws CloudException {
		cloudML = new CloudML(cloudMLIp, cloudMLPort);
		
		this.monitoringPlatformPort = monitoringPlatformPort;
		this.cloudMLIp = cloudMLIp;
		this.cloudMLPort = cloudMLPort;
		
		mpl = VirtualMachine.getVM("mpl", null, 1);
		this.clients = VirtualMachine.getVM("client", null, clients);
		
		running = false;
		initialized = false;
	}
	
	public static boolean performTest(String cloudMLIp, int cloudMLPort, int monitoringPlatformPort, int clients, Path baseJmx, String data, boolean useOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines) {
		try {
			Test t = new Test(cloudMLIp, cloudMLPort, monitoringPlatformPort, clients);
			
			if (reuseInstances)
				t.considerRunningMachines();
			t.startMachines(useOnDemand);
			t.initSystem();
			
			t.addCPUUtilizationMonitoringRules();
			
			if (onlyStartMachines)
				return true;
			
			String status = t.getTierStatus("MIC");
			
			if (status == null || status.equals("null"))
				throw new Exception("CloudML isn't working (the statuses are null).");
			
			t.runTest(baseJmx, data, t.getTierIp("MIC"));
			if (!leaveInstancesOn)
				t.stopMachines();
			
			return true;
		} catch (Exception e) {
			logger.error("There were errors while running the test.", e);
			return false;
		}
	}
	
	private String getTierStatus(String tier) {
		cloudML.getDeploymentModel();
		return cloudML.getTierStatus(tier);
	}
	
	private String getTierIp(String tier) {
		cloudML.getDeploymentModel();
		return cloudML.getTierIp(tier);
	}
	
	private VirtualMachine mpl;
	private VirtualMachine clients;
	
	static {
		VirtualMachine.PRICE_MARGIN = 0.35;
	}
	
	private boolean running = false;
	private boolean initialized = false;
	
	public void startMachines(boolean startAsOnDemand) {
		if (running)
			throw new RuntimeException("The system is already running!");
		
		logger.info("Starting all the machines...");
		
		if (!startAsOnDemand) {
			mpl.spotRequest();
			clients.spotRequest();
		} else {
			mpl.onDemandRequest();
			clients.onDemandRequest();
		}
		
		mpl.waitUntilRunning();
		clients.waitUntilRunning();
		
		mpl.setNameToInstances("MPL");
		clients.setNameToInstances("JMeter");
		
		mpl.getInstances().get(0).waitUntilSshAvailable();
		clients.getInstances().get(0).waitUntilSshAvailable();
		
		logger.info("All the machines are now running!");
		
		running = true;
	}
	
	public void considerRunningMachines() {
		if (running)
			throw new RuntimeException("The system is already running!");
		
		logger.info("Searching for running machines...");
		
		AmazonEC2 ec2 = new AmazonEC2();
		
		ec2.addRunningInstances(mpl);
		ec2.addRunningInstances(clients);
		
		mpl.reboot();
		clients.reboot();
		
		logger.info("Added running instances!");
	}
	
	public void stopMachines() {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		logger.info("Stopping all the machines...");
		
		mpl.terminate();
		clients.terminate();
		
		logger.info("All the machines have been shutted down!");
		
		running = false;
		initialized = false;
	}
	
	public void initSystem() throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		if (initialized)
			throw new RuntimeException("The system is already initialized!");
		
		logger.info("Deleting the files set for removal before the test...");
		
		mpl.deleteFiles();
		clients.deleteFiles();
		
		logger.info("Initializing the system...");
		
		Instance impl = mpl.getInstances().get(0); 
		
		impl.waitUntilSshAvailable();
		
		impl.exec(mpl.getParameter("STARTER"));
		
		monitoringPlatform = new MonitoringPlatform(impl.getIp(), monitoringPlatformPort);
		monitoringPlatform.loadModel();
		
		cloudML.deploy();
		
		logger.info("System initialized!");
		
		initialized = true;
	}
	
	public void stopInfrastructure() {
		if (!running)
			throw new RuntimeException("The infrastructure isn't running yet!");
		
		cloudML.terminate();
		monitoringPlatform = null;
		mpl.terminate();
		
		logger.info("Infrastructure stopped.");
		
		running = false;
	}
	
	private static MonitoringRule getMonitoringRuleFromFile(String fileName, Object... substitutions) throws Exception {
		String tmp = it.polimi.modaclouds.scalingrules.Configuration.readFile(fileName);
		if (substitutions.length > 0)
			tmp = String.format(tmp, substitutions);
		
		JAXBContext context = JAXBContext.newInstance(MonitoringRule.class);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		return (MonitoringRule) unmarshaller.unmarshal(new StringReader(tmp));
	}
	
	public void addCPUUtilizationMonitoringRules(double aboveValue, double underValue) throws Exception {
		MonitoringRules rules = new MonitoringRules();
		rules.getMonitoringRules().add(getMonitoringRuleFromFile(it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_ABOVE_FILE, aboveValue, cloudMLIp, cloudMLPort));
		rules.getMonitoringRules().add(getMonitoringRuleFromFile(it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_UNDER_FILE, underValue, cloudMLIp, cloudMLPort));
		
		monitoringPlatform.installRules(rules);
	}
	
	public void addCPUUtilizationMonitoringRules() throws Exception {
		addCPUUtilizationMonitoringRules(0.6, 0.2);
	}
	
	public static int getPeakFromData(String data) {
		int peak = 0;
		try (Scanner sc = new Scanner(Configuration.getInputStream(data))) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				int value;
				try {
					value = Integer.parseInt(line.split(" ")[0]);
				} catch (Exception e) {
					continue;
				}
				if (value > peak)
					peak = value;
			}
		} catch (Exception e) { }
		return peak;
	}
	
	public void runTest(Path baseJmx, String data, String server) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		if (!initialized)
			throw new RuntimeException("The system isn't initialized yet!");
		
		Date date = new Date();
		String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM-%2$s-%3$dx%4$d", date, clients.getSize(), getPeakFromData(data) / clients.getInstancesRunning(), clients.getInstancesRunning());
		
		String localPath = "tests" + File.separator + now;
		if (!new File(localPath).exists()) {
			File f = new File(localPath);
			f.mkdirs();
		}
		
		String remotePath = clients.getParameter("REMOTE_PATH") + "/" + now;
		
		String protocol = "http";
		
		String port = "8080";
		
		JMeterTest test = new JMeterTest(clients.getParameter("AMI"), clients.getInstancesRunning(), localPath, remotePath, clients.getParameter("JMETER_PATH"), data,
				server,
				protocol,
				port);
		
		RunInstance run = test.createModifiedFile(baseJmx);
		
		logger.info("Test starting...");
		
		test.performTest(clients, run);
		
		logger.info("Test ended!");
		
		logger.info("Retrieving the files from the instances...");
		
		mpl.retrieveFiles(localPath, "/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath, "/home/" + clients.getParameter("SSH_USER"));
		
		logger.info("Done!");
	}

}
