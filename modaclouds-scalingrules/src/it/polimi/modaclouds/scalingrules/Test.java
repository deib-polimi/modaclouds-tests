package it.polimi.modaclouds.scalingrules;

import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.Configuration;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.amazon.elb.ElasticLoadBalancing;
import it.cloud.amazon.elb.ElasticLoadBalancing.Listener;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.polimi.modaclouds.scalingrules.utils.CloudML;
import it.polimi.modaclouds.scalingrules.utils.MonitoringPlatform;
import it.polimi.tower4clouds.rules.MonitoringRule;
import it.polimi.tower4clouds.rules.MonitoringRules;

import java.io.File;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Date;
import java.util.Scanner;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.lang.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Test {

	private static final Logger logger = LoggerFactory.getLogger(Test.class);

	private MonitoringPlatform monitoringPlatform;
	private CloudML cloudML;

	private String cloudMLIp;
	private int cloudMLPort;
	private String monitoringPlatformIp;
	private int monitoringPlatformPort;

	private static boolean isReachable(String ip) {
		try {
			return InetAddress.getByName(ip).isReachable(30000);
		} catch (Exception e) {
			return false;
		}
	}

	public Test(String cloudMLIp, int cloudMLPort, String monitoringPlatformIp,
			int monitoringPlatformPort, int clients) throws Exception {
		cloudML = new CloudML(cloudMLIp, cloudMLPort);

		this.monitoringPlatformIp = monitoringPlatformIp;
		this.monitoringPlatformPort = monitoringPlatformPort;
		this.cloudMLIp = cloudMLIp;
		this.cloudMLPort = cloudMLPort;

		if (monitoringPlatformIp != null
				&& !monitoringPlatformIp.equals("localhost")
				&& !monitoringPlatformIp.equals("127.0.0.1")
				&& !isReachable(monitoringPlatformIp))
			throw new RuntimeException(
					"The monitoring platform isn't reachable!");
		if (cloudMLIp != null && !cloudMLIp.equals("localhost")
				&& !cloudMLIp.equals("127.0.0.1") && !isReachable(cloudMLIp))
			throw new RuntimeException("The CloudML server isn't reachable!");

		mpl = VirtualMachine.getVM("mpl", null, 1);
		this.clients = VirtualMachine.getVM("client", null, clients);

		running = false;
		initialized = false;

		loadBalancer = null;
	}

	public static boolean performTest(String cloudMLIp, int cloudMLPort,
			String monitoringPlatformIp, int monitoringPlatformPort,
			int clients, Path baseJmx, String data, boolean useOnDemand,
			boolean reuseInstances, boolean leaveInstancesOn,
			boolean onlyStartMachines) {
		try {
			Test t = new Test(cloudMLIp, cloudMLPort, monitoringPlatformIp,
					monitoringPlatformPort, clients);

			if (reuseInstances)
				t.considerRunningMachines();
			t.startMachines(useOnDemand);

			t.createLoadBalancer();

			t.initSystem();

			t.addCPUUtilizationMonitoringRules();

			if (onlyStartMachines)
				return true;

			String status = t.getTierStatus("MIC");

			if (status != null && !status.equals("null"))
				t.runTest(baseJmx, data);
			else
				logger.error("CloudML isn't working (the statuses are null).");
			
			t.stopCloudMLInstances();
			t.destroyLoadBalancer();
		
			if (!leaveInstancesOn)
				t.stopMachines();

			return (status != null && !status.equals("null"));
		} catch (Exception e) {
			logger.error("There were errors while running the test.", e);
			return false;
		}
	}

	private String loadBalancer;

	private void createLoadBalancer() {
		if (loadBalancer != null)
			throw new RuntimeException(
					"You've already created a load balancer!");

		loadBalancer = "ScalingRules" + RandomStringUtils.randomNumeric(3);

		ElasticLoadBalancing.createNewLoadBalancer(loadBalancer, new Listener("HTTP", Integer.parseInt(it.polimi.modaclouds.scalingrules.Configuration.MIC_PORT)));
	}

	private void destroyLoadBalancer() {
		if (loadBalancer == null)
			throw new RuntimeException(
					"You've still to create a load balancer!");

		ElasticLoadBalancing.deleteLoadBalancer(loadBalancer);

		loadBalancer = null;
	}

	private String getTierStatus(String tier) {
		cloudML.getDeploymentModel();
		return cloudML.getTierStatus(tier);
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
			if (monitoringPlatformIp == null)
				mpl.spotRequest();
			clients.spotRequest();
		} else {
			if (monitoringPlatformIp == null)
				mpl.onDemandRequest();
			clients.onDemandRequest();
		}

		if (monitoringPlatformIp == null)
			mpl.waitUntilRunning();
		clients.waitUntilRunning();

		if (monitoringPlatformIp == null)
			mpl.setNameToInstances("MPL");
		clients.setNameToInstances("JMeter");

		if (monitoringPlatformIp == null)
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

		if (monitoringPlatformIp == null)
			ec2.addRunningInstances(mpl);
		ec2.addRunningInstances(clients);

		if (monitoringPlatformIp == null)
			mpl.reboot();
		clients.reboot();

		logger.info("Added running instances!");
	}

	public void stopMachines() {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		logger.info("Stopping all the machines...");

		if (monitoringPlatformIp == null)
			mpl.terminate();
		clients.terminate();

		logger.info("All the machines have been shutted down!");

		running = false;
		initialized = false;
	}
	
	public void stopCloudMLInstances() {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		logger.info("Stopping CloudML instances...");
		
		cloudML.terminateAllInstances();
	}

	public void initSystem() throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (initialized)
			throw new RuntimeException("The system is already initialized!");

		logger.info("Deleting the files set for removal before the test...");

		if (monitoringPlatformIp == null)
			mpl.deleteFiles();
		clients.deleteFiles();

		logger.info("Initializing the system...");

		String mplIp;

		if (monitoringPlatformIp == null) {
			Instance impl = mpl.getInstances().get(0);

			impl.waitUntilSshAvailable();

			impl.exec(mpl.getParameter("STARTER"));

			mplIp = impl.getIp();
		} else {
			mplIp = monitoringPlatformIp;
		}

		monitoringPlatform = new MonitoringPlatform(mplIp,
				monitoringPlatformPort);
		// monitoringPlatform.loadModel();

		cloudML.deploy(
				it.polimi.modaclouds.scalingrules.Configuration.getAsFile(it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_DEPLOYMENT_MODEL),
				it.polimi.modaclouds.scalingrules.Configuration.MIC_AMI,
				Configuration.REGION,
				String.format(
						it.polimi.modaclouds.scalingrules.Configuration.MIC_STARTER.replaceAll("&&", " ; "),
						mplIp),
				String.format(
						it.polimi.modaclouds.scalingrules.Configuration.MIC_ADD_TO_LOAD_BALANCER.replaceAll("&&", " ; "),
						Configuration.AWS_CREDENTIALS.getAWSAccessKeyId(),
						Configuration.AWS_CREDENTIALS.getAWSSecretKey(),
						Configuration.REGION,
						loadBalancer),
				String.format(
						it.polimi.modaclouds.scalingrules.Configuration.MIC_DEL_FROM_LOAD_BALANCER.replaceAll("&&", " ; "),
						Configuration.AWS_CREDENTIALS.getAWSAccessKeyId(),
						Configuration.AWS_CREDENTIALS.getAWSSecretKey(),
						Configuration.REGION,
						loadBalancer));

		logger.info("System initialized!");

		initialized = true;
	}

	public void stopInfrastructure() {
		if (!running)
			throw new RuntimeException("The infrastructure isn't running yet!");

		cloudML.terminate();
		monitoringPlatform = null;
		if (monitoringPlatformIp == null)
			mpl.terminate();

		logger.info("Infrastructure stopped.");

		running = false;
	}

	private static MonitoringRule getMonitoringRuleFromFile(String fileName,
			Object... substitutions) throws Exception {
		String tmp = it.polimi.modaclouds.scalingrules.Configuration
				.readFile(fileName);
		if (substitutions.length > 0)
			tmp = String.format(tmp, substitutions);

		JAXBContext context = JAXBContext.newInstance(MonitoringRule.class);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		return (MonitoringRule) unmarshaller.unmarshal(new StringReader(tmp));
	}

	public void addCPUUtilizationMonitoringRules(double aboveValue,
			double underValue) throws Exception {
		MonitoringRules rules = new MonitoringRules();
		rules.getMonitoringRules()
				.add(getMonitoringRuleFromFile(
						it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_ABOVE_FILE,
						aboveValue, cloudMLIp, cloudMLPort));
		rules.getMonitoringRules()
				.add(getMonitoringRuleFromFile(
						it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_UNDER_FILE,
						underValue, cloudMLIp, cloudMLPort));

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
		} catch (Exception e) {
		}
		return peak;
	}

	public void runTest(Path baseJmx, String data) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (!initialized)
			throw new RuntimeException("The system isn't initialized yet!");

		String server = ElasticLoadBalancing.getLoadBalancerDNS(loadBalancer);

		Date date = new Date();
		String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM-%2$s-%3$dx%4$d",
				date, clients.getSize(),
				getPeakFromData(data) / clients.getInstancesRunning(),
				clients.getInstancesRunning());

		String localPath = "tests" + File.separator + now;
		if (!new File(localPath).exists()) {
			File f = new File(localPath);
			f.mkdirs();
		}

		String remotePath = clients.getParameter("REMOTE_PATH") + "/" + now;

		String protocol = "http";

		String port = it.polimi.modaclouds.scalingrules.Configuration.MIC_PORT;

		JMeterTest test = new JMeterTest(clients.getParameter("AMI"),
				clients.getInstancesRunning(), localPath, remotePath,
				clients.getParameter("JMETER_PATH"), data, server, protocol,
				port);

		RunInstance run = test.createModifiedFile(baseJmx);

		logger.info("Test starting...");

		test.performTest(clients, run);

		logger.info("Test ended!");

		logger.info("Retrieving the files from the instances...");

		if (monitoringPlatformIp == null)
			mpl.retrieveFiles(localPath,
					"/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath,
				"/home/" + clients.getParameter("SSH_USER"));

		logger.info("Done!");
	}

}
