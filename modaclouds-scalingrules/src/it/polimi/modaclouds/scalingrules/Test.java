package it.polimi.modaclouds.scalingrules;

import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.Configuration;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.amazon.elb.ElasticLoadBalancing;
import it.cloud.amazon.elb.ElasticLoadBalancing.Listener;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.cloud.utils.Ssh;
import it.polimi.modaclouds.scalingrules.utils.CloudML;
import it.polimi.modaclouds.scalingrules.utils.CloudMLDaemon;
import it.polimi.modaclouds.scalingrules.utils.MonitoringPlatform;
import it.polimi.tower4clouds.rules.MonitoringRule;
import it.polimi.tower4clouds.rules.MonitoringRules;

import java.io.File;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
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
			int monitoringPlatformPort, int clients, String size) throws Exception {
		if (monitoringPlatformIp != null
				&& !monitoringPlatformIp.equals("localhost")
				&& !monitoringPlatformIp.equals("127.0.0.1")
				&& !isReachable(monitoringPlatformIp))
			throw new RuntimeException(
					"The monitoring platform isn't reachable!");
		if (cloudMLIp != null && !cloudMLIp.equals("localhost")
				&& !cloudMLIp.equals("127.0.0.1") && !isReachable(cloudMLIp))
			throw new RuntimeException("The CloudML server isn't reachable!");
		
		if (cloudMLIp != null)
			try {
				cloudML = new CloudML(cloudMLIp, cloudMLPort);
			} catch (Exception e) {
				if (cloudMLIp.equals("127.0.0.1") || cloudMLIp.equals("localhost")) {
					CloudMLDaemon.start(cloudMLPort);
					try {
						Thread.sleep(3000);
					} catch (Exception e1) { }
					
					cloudML = new CloudML(cloudMLIp, cloudMLPort);
				} else {
					throw e;
				}
			}

		this.monitoringPlatformIp = monitoringPlatformIp;
		this.monitoringPlatformPort = monitoringPlatformPort;
		this.cloudMLIp = cloudMLIp;
		this.cloudMLPort = cloudMLPort;

		mpl = VirtualMachine.getVM("mpl", size, 1);
		this.clients = VirtualMachine.getVM("client", size, clients);

		running = false;
		initialized = false;

		loadBalancer = null;
	}
	
	public static String getActualCredentials(String ip, VirtualMachine vm, String filePath) throws Exception {
		String body = FileUtils.readFileToString(it.polimi.modaclouds.scalingrules.Configuration.getAsFile(filePath));
		
		Path p = Files.createTempFile("credentials", ".properties");
		FileUtils.writeStringToFile(p.toFile(), body);
		
		if (!ip.equals("localhost") && !ip.equals("127.0.0.1")) {
			String remotePath = vm.getParameter("REMOTE_PATH");
			String newFile = remotePath + "/" + p.toFile().getName();
			Ssh.exec(ip, vm, "mkdir -p " + remotePath);
			Ssh.sendFile(ip, vm, p.toString(), newFile);
			return newFile;
		}
		
		return p.toString();
	}
	
	public static String getActualKey(String ip, VirtualMachine vm, String filePath) throws Exception {
		Path p = it.polimi.modaclouds.scalingrules.Configuration.getAsFile(filePath).toPath();
		
		if (!ip.equals("localhost") && !ip.equals("127.0.0.1")) {
			String remotePath = vm.getParameter("REMOTE_PATH");
			String newFile = remotePath + "/" + p.toFile().getName();
			Ssh.exec(ip, vm, "mkdir -p " + remotePath);
			Ssh.sendFile(ip, vm, p.toString(), newFile);
			Ssh.exec(ip, vm, "chmod 400 " + newFile);
			return newFile;
		}
		
		return p.toString();
	}
	
	public static Path getActualDeploymentModel(String ip, VirtualMachine vm) throws Exception {
		String body = FileUtils.readFileToString(it.polimi.modaclouds.scalingrules.Configuration.getAsFile(it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_DEPLOYMENT_MODEL));
		
		JSONObject jsonObject = new JSONObject(body);
		
		if (jsonObject.has("providers")) {
			JSONArray array = jsonObject.getJSONArray("providers");
			for (int i = 0; i < array.length(); ++i) {
				JSONObject provider = array.getJSONObject(i);
				if (provider.has("credentials")) {
					String credentials = provider.getString("credentials");
					String s = Test.getActualCredentials(ip, vm, credentials);
					provider.put("credentials", s);
					
					body = body.replaceAll(credentials, s);
				}
			}
		}
		
		if (jsonObject.has("vms")) {
			JSONArray array = jsonObject.getJSONArray("vms");
			for (int i = 0; i < array.length(); ++i) {
				JSONObject vmo = array.getJSONObject(i);
				if (vmo.has("privateKey")) {
					String privateKey = vmo.getString("privateKey");
					String s = Test.getActualKey(ip, vm, privateKey);
					vmo.put("privateKey", s);
					
					body = body.replaceAll(privateKey, s);
				}
			}
		}
		
		Path p = Files.createTempFile("model", ".json");
		FileUtils.writeStringToFile(p.toFile(), body);
		
		System.out.println(body);
		
		return p;
	}

	public static long performTest(String cloudMLIp, int cloudMLPort,
			String monitoringPlatformIp, int monitoringPlatformPort,
			int clients, Path baseJmx, String data, boolean useOnDemand,
			boolean reuseInstances, boolean leaveInstancesOn,
			boolean onlyStartMachines, String size) throws Exception {
		long init = System.currentTimeMillis();
		
		Test t = new Test(cloudMLIp, cloudMLPort, monitoringPlatformIp,
				monitoringPlatformPort, clients, size);

		if (reuseInstances)
			t.considerRunningMachines();
		t.startMachines(useOnDemand);

		t.createLoadBalancer();

		t.initSystem();

		t.addCPUUtilizationMonitoringRules();

		if (onlyStartMachines)
			return -1;

		String status = t.getTierStatus(APP_NAME);

		if (status != null && !status.equals("null"))
			t.runTest(baseJmx, data);
		else
			logger.error("CloudML isn't working (the statuses are null).");
		
		t.stopCloudMLInstances();
		t.terminateCloudMLDaemon();
		
		t.destroyLoadBalancer();
	
		if (!leaveInstancesOn)
			t.stopMachines();

		return (status != null && !status.equals("null")) ? System.currentTimeMillis() - init : ERROR_STATUS_NULL;
	}
	
	public static final long ERROR_STATUS_NULL = -1234;

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
	
	public void terminateCloudMLDaemon() throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		logger.info("Stopping the CloudML daemon...");
		
		if (cloudMLIp.equals("localhost") || cloudMLIp.equals("127.0.0.1")) {
			CloudMLDaemon.port = cloudMLPort;
			CloudMLDaemon.stop();
		} else {
			Ssh.execInBackground(cloudMLIp, mpl, String.format(mpl.getParameter("CLOUDML_STARTER"), cloudMLPort + " -stop"));
		}
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
			mplIp = impl.getIp();
			
			impl.exec(String.format(mpl.getParameter("STARTER"), mplIp));
		} else {
			mplIp = monitoringPlatformIp;
		}
		
		if (cloudMLIp == null) {
			cloudMLIp = mplIp;
			
			try {
				cloudML = new CloudML(cloudMLIp, cloudMLPort);
			} catch (Exception e) {
				Ssh.execInBackground(cloudMLIp, mpl, String.format(mpl.getParameter("CLOUDML_STARTER"), Integer.valueOf(cloudMLPort).toString()));
				
				try {
					Thread.sleep(10000);
				} catch (Exception e1) { }
				
				cloudML = new CloudML(cloudMLIp, cloudMLPort);
				
			}
		}

		monitoringPlatform = new MonitoringPlatform(mplIp,
				monitoringPlatformPort);
		// monitoringPlatform.loadModel();

		cloudML.deploy(
				getActualDeploymentModel(cloudMLIp, mpl).toFile(),
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

	private static List<MonitoringRule> getMonitoringRulesFromFile(String fileName,
			Object... substitutions) throws Exception {
		String tmp = it.polimi.modaclouds.scalingrules.Configuration
				.readFile(fileName);
		if (substitutions.length > 0)
			tmp = String.format(tmp, substitutions);
		
		if (tmp.indexOf("<monitoringRules") == -1)
			tmp = "<monitoringRules xmlns=\"http://www.modaclouds.eu/xsd/1.0/monitoring_rules_schema\">" + tmp + "</monitoringRules>"; 

		JAXBContext context = JAXBContext.newInstance(MonitoringRules.class);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		MonitoringRules rules = (MonitoringRules) unmarshaller.unmarshal(new StringReader(tmp));
		return rules.getMonitoringRules();
	}
	
	public static final String APP_NAME = "MIC";
	
	private static DecimalFormat doubleFormatter = doubleFormatter();
	
	private static DecimalFormat doubleFormatter() {
		DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols(Locale.getDefault());
		otherSymbols.setDecimalSeparator('.');
		DecimalFormat myFormatter = new DecimalFormat("0.0#########", otherSymbols);
		return myFormatter;
	}

	public void addCPUUtilizationMonitoringRules(double aboveValue,
			double underValue) throws Exception {
		MonitoringRules rules = new MonitoringRules();
		rules.getMonitoringRules()
				.addAll(getMonitoringRulesFromFile(
						it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_ABOVE_FILE,
						doubleFormatter.format(aboveValue), cloudMLIp, cloudMLPort, APP_NAME));
		rules.getMonitoringRules()
				.addAll(getMonitoringRulesFromFile(
						it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_UNDER_FILE,
						doubleFormatter.format(underValue), cloudMLIp, cloudMLPort, APP_NAME));

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
