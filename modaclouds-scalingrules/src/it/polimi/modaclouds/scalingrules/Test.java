package it.polimi.modaclouds.scalingrules;

import it.cloud.amazon.Configuration;
import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.amazon.elb.ElasticLoadBalancing;
import it.cloud.amazon.elb.ElasticLoadBalancing.Listener;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.cloud.utils.Ssh;
import it.polimi.modaclouds.scalingrules.utils.CloudML;
import it.polimi.modaclouds.scalingrules.utils.MonitoringPlatform;
import it.polimi.tower4clouds.rules.MonitoringRule;
import it.polimi.tower4clouds.rules.MonitoringRules;

import java.io.File;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

import com.amazonaws.services.cloudwatch.model.Statistic;

public class Test {

	private static final Logger logger = LoggerFactory.getLogger(Test.class);

	private MonitoringPlatform monitoringPlatform;
	private CloudML cloudML;
	
	public static enum App {
		MIC("mic", "MPloadModel-MiC", "jmeterTestTemplate-MiC.jmx", "MIC", "cloudml-MiC.json"),
		HTTPAGENT("httpagent", "MPloadModel-HTTPAgent", "jmeterTestTemplate-HTTPAgent.jmx", "HTTPAgent", "cloudml-HTTPAgent.json");
		
		public String name;
		public String fileModel;
		public String baseJmx;
		public String tierName;
		public String cloudMl;
		
		private App(String name, String fileModel, String baseJmx, String tierName, String cloudMl) {
			this.name = name;
			this.fileModel = fileModel;
			this.baseJmx = baseJmx;
			this.tierName = tierName;
			this.cloudMl = cloudMl;
		}
		
		public Path getBaseJmxPath() {
			return Configuration.getPathToFile(baseJmx);
		}
		
		public static App getFromName(String name) {
			for (App a : values())
				if (a.name.equalsIgnoreCase(name))
					return a;
			
			return DEFAULT_APP;
		}
	}
	
	public static final App DEFAULT_APP = App.MIC;

	public Test(int clients, String size, String appName) throws Exception {
		mpl = VirtualMachine.getVM("mpl", size, 1);
		app = VirtualMachine.getVM(appName, size, 1);
		this.clients = VirtualMachine.getVM("client", size, clients);

		running = false;
		initialized = false;

		loadBalancer = null;
	}
	
	public static String getActualFile(String ip, VirtualMachine vm, String filePath, String folder) throws Exception {
		Path p = it.polimi.modaclouds.scalingrules.Configuration.getAsFile(filePath).toPath();
		
		if (!ip.equals("localhost") && !ip.equals("127.0.0.1")) {
			String remotePath = vm.getParameter("REMOTE_PATH") + "/" + folder;
			String newFile = remotePath + "/" + p.toFile().getName();
			Ssh.exec(ip, vm, "mkdir -p " + remotePath);
			Ssh.sendFile(ip, vm, p.toString(), newFile);
			return newFile;
		}
		
		return p.toString();
	}
	
	public static String getActualKey(String ip, VirtualMachine vm, String filePath, String folder) throws Exception {
		String newFile = getActualFile(ip, vm, filePath, folder);
		
		if (!ip.equals("localhost") && !ip.equals("127.0.0.1"))
			Ssh.exec(ip, vm, "chmod 400 " + newFile);
		
		return newFile;
	}
	
	public Path getActualDeploymentModel(App app, boolean remotePathIfNecessary) throws Exception {
		String ipMpl = mpl.getInstances().get(0).getIp();
		return getActualDeploymentModel(ipMpl, mpl, this.app, app.cloudMl, loadBalancer, remotePathIfNecessary);
	}
	
	public static Path getActualDeploymentModel(String ipMpl, VirtualMachine mpl, VirtualMachine app, String cloudMl, String loadBalancer, boolean remotePathIfNecessary) throws Exception {
		String body = FileUtils.readFileToString(it.polimi.modaclouds.scalingrules.Configuration.getAsFile(cloudMl));
		
		JSONObject jsonObject = new JSONObject(body);
		
		Date date = new Date();
		String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM",	date);
		
		if (jsonObject.has("providers")) {
			JSONArray array = jsonObject.getJSONArray("providers");
			for (int i = 0; i < array.length(); ++i) {
				JSONObject provider = array.getJSONObject(i);
				if (provider.has("credentials")) {
					String credentials = provider.getString("credentials");
					String s = null;
					if (remotePathIfNecessary)
						s = Test.getActualFile(ipMpl, mpl, credentials, now);
					else
						s = it.polimi.modaclouds.scalingrules.Configuration.getAsFile(credentials).toString();
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
					String s = null;
					if (remotePathIfNecessary)
						s = Test.getActualKey(ipMpl, mpl, privateKey, now);
					else
						s = it.polimi.modaclouds.scalingrules.Configuration.getAsFile(privateKey).toString();
					vmo.put("privateKey", s);
					
					body = body.replaceAll(privateKey, s);
				}
			}
		}
		
		body = String.format(body,
				RandomStringUtils.randomNumeric(3),
				app.getImageId(),
				it.cloud.amazon.Configuration.REGION,
				String.format(
						app.getParameter("STARTER").replaceAll("&&", " ; "),
						ipMpl),
				String.format(
						app.getParameter("ADD_TO_LOAD_BALANCER").replaceAll("&&", " ; "),
						it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSAccessKeyId(),
						it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSSecretKey(),
						it.cloud.amazon.Configuration.REGION,
						loadBalancer),
				String.format(
						app.getParameter("DEL_FROM_LOAD_BALANCER").replaceAll("&&", " ; "),
						it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSAccessKeyId(),
						it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSSecretKey(),
						it.cloud.amazon.Configuration.REGION,
						loadBalancer),
				app.getSize()
				);
		
		Path p = Files.createTempFile("model", ".json");
		FileUtils.writeStringToFile(p.toFile(), body);
		
		return p;
	}
	
	public static final int MAX_ATTEMPTS = 5;

	public static long performTest(int clients, App app, String data, boolean useOnDemand,
			boolean reuseInstances, boolean leaveInstancesOn,
			boolean onlyStartMachines, String size, double highCpu, double lowCpu, int window, int cooldown) throws Exception {
		String baseJmx = app.getBaseJmxPath().toString();
		
		if (baseJmx == null || !new File(baseJmx).exists())
			throw new RuntimeException("The provided base JMX file (" + baseJmx.toString() + ") doesn't exist!");
		if (data == null || !new File(data).exists())
			throw new RuntimeException("The provided data file (" + data + ") doesn't exist!");
		
		long init = System.currentTimeMillis();
		
		Test t = new Test(clients, size, app.name);

		if (reuseInstances)
			t.considerRunningMachines();
		t.startMachines(useOnDemand);

		t.createLoadBalancer();

		t.initSystem();

		if (onlyStartMachines)
			return -1;

		String status;
		int attempt = 0;
		do {
			t.initCloudML(app);
			status = t.getTierStatus(app.tierName);
			attempt++;
		} while ((status == null || status.equals("null")) && attempt < MAX_ATTEMPTS);
		
		if (status != null && !status.equals("null")) {
			t.addCPUUtilizationMonitoringRules(app.tierName, highCpu, lowCpu, window, cooldown);
			try {
				t.runTest(Paths.get(baseJmx), data);
			} catch (Exception e) {
				logger.error("Error while performing the test.", e);
			}
		} else {
			logger.error("CloudML isn't working (the statuses are null).");
		}
		
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

		ElasticLoadBalancing.createNewLoadBalancer(loadBalancer, new Listener("HTTP", Integer.parseInt(app.getParameter("PORT"))));
	}

	private void destroyLoadBalancer() {
		if (loadBalancer == null)
			throw new RuntimeException(
					"You've still to create a load balancer!");

		ElasticLoadBalancing.deleteLoadBalancer(loadBalancer);

		loadBalancer = null;
	}

	private String getTierStatus(String tier) {
		cloudML.updateStatus();
		return cloudML.getTierStatus(tier);
	}

	private VirtualMachine mpl;
	private VirtualMachine app;
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

		mpl.setNameToInstances(mpl.getParameter("NAME"));
		clients.setNameToInstances(clients.getParameter("NAME"));

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
		
		String cloudMLIp = mpl.getInstances().get(0).getIp();
		String cloudMLPort = mpl.getParameter("CLOUDML_PORT");
		
		Ssh.execInBackground(cloudMLIp, mpl, String.format(mpl.getParameter("CLOUDML_STARTER"), cloudMLPort + " -stop"));
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
		String mplIp = impl.getIp();
		int mplPort = Integer.parseInt(mpl.getParameter("MP_PORT"));
		
		impl.exec(String.format(mpl.getParameter("STARTER"), mplIp));
		
		try { Thread.sleep(10000); } catch (Exception e) { }

		monitoringPlatform = new MonitoringPlatform(mplIp, mplPort);
		// monitoringPlatform.loadModel();

		logger.info("System initialized!");

		initialized = true;
	}
	
	private boolean cloudMlInitialized = false;
	
	public void initCloudML(App app) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (!initialized)
			throw new RuntimeException("The system isn't initialized yet!");
		
		if (cloudMlInitialized) {
			stopCloudMLInstances();
			terminateCloudMLDaemon();
			
			try {
				Thread.sleep(10000);
			} catch (Exception e) { }
			
			cloudMlInitialized = false;
		}

		logger.info("Initializing CloudML...");
		
		Instance impl = mpl.getInstances().get(0);
		String mplIp = impl.getIp();
		String cloudMLIp = mplIp;
		int cloudMLPort = Integer.parseInt(mpl.getParameter("CLOUDML_PORT"));
		
		try {
			cloudML = new CloudML(cloudMLIp, cloudMLPort);
		} catch (Exception e) {
			Ssh.execInBackground(cloudMLIp, mpl, String.format(mpl.getParameter("CLOUDML_STARTER"), Integer.valueOf(cloudMLPort).toString()));
			
			try {
				Thread.sleep(10000);
			} catch (Exception e1) { }
			
			cloudML = new CloudML(cloudMLIp, cloudMLPort);
			
		}
		
		cloudML.deploy(getActualDeploymentModel(app, true).toFile());
		
		logger.info("CloudML initialized!");

		cloudMlInitialized = true;
	}

	private static List<MonitoringRule> getMonitoringRulesFromFile(String fileName,
			Object... substitutions) throws Exception {
		String tmp = it.polimi.modaclouds.scalingrules.Configuration
				.readFile(fileName);
		if (substitutions.length > 0)
			tmp = String.format(tmp, substitutions);
		
		if (tmp.indexOf("<monitoringRules") == -1)
			tmp = "<monitoringRules xmlns=\"http://www.modaclouds.eu/xsd/1.0/monitoring_rules_schema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.modaclouds.eu/xsd/1.0/monitoring_rules_schema\">" + tmp + "</monitoringRules>"; 

		JAXBContext context = JAXBContext.newInstance(MonitoringRules.class);
		Unmarshaller unmarshaller = context.createUnmarshaller();
		MonitoringRules rules = (MonitoringRules) unmarshaller.unmarshal(new StringReader(tmp));
		return rules.getMonitoringRules();
	}
	
	private static DecimalFormat doubleFormatter = doubleFormatter();
	
	private static DecimalFormat doubleFormatter() {
		DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols(Locale.getDefault());
		otherSymbols.setDecimalSeparator('.');
		DecimalFormat myFormatter = new DecimalFormat("0.0#########", otherSymbols);
		return myFormatter;
	}

	public void addCPUUtilizationMonitoringRules(String tierName, double aboveValue,
			double belowValue, int window, int cooldown) throws Exception {
		if (aboveValue > 1.0)
			aboveValue = 1.0;
		if (aboveValue <= 0.0)
			aboveValue = 0.6;
		if (belowValue <= 0.0)
			belowValue = 0.0;
		if (belowValue >= 1.0)
			belowValue = 0.1;
		
		String cloudMLIp = mpl.getInstances().get(0).getIp();
		int cloudMLPort = Integer.parseInt(mpl.getParameter("CLOUDML_PORT"));
		
		MonitoringRules rules = new MonitoringRules();
		rules.getMonitoringRules()
				.addAll(getMonitoringRulesFromFile(
						it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_ABOVE_FILE,
						doubleFormatter.format(aboveValue), cloudMLIp, cloudMLPort, tierName, window, cooldown));
		rules.getMonitoringRules()
				.addAll(getMonitoringRulesFromFile(
						it.polimi.modaclouds.scalingrules.Configuration.MONITORING_RULE_CPU_UNDER_FILE,
						doubleFormatter.format(belowValue), cloudMLIp, cloudMLPort, tierName, window, cooldown));

		monitoringPlatform.installRules(rules);
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
		
		if (!cloudMlInitialized)
			throw new RuntimeException("CloudML isn't initialized yet!");

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

		String protocol = app.getParameter("PROTOCOL");

		String port = app.getParameter("PORT");

		JMeterTest test = new JMeterTest(clients.getParameter("AMI"),
				clients.getInstancesRunning(), localPath, remotePath,
				clients.getParameter("JMETER_PATH"), data, server, protocol,
				port);

		RunInstance run = test.createModifiedFile(baseJmx);
		
		String javaParameters = clients.getParameter("JAVA_PARAMETERS");
		if (javaParameters != null && (javaParameters.trim().length() == 0 || !javaParameters.startsWith("-")))
			javaParameters = null;
		JMeterTest.javaParameters = javaParameters;

		logger.info("Test starting...");

		test.performTest(clients, run);

		logger.info("Test ended!");

		logger.info("Retrieving the files from the instances...");

		mpl.retrieveFiles(localPath,
				"/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath,
				"/home/" + clients.getParameter("SSH_USER"));
		
		logger.info("Retrieving the data from the metrics...");
		
		int period = getSuggestedPeriod(date);
		
		mpl.retrieveMetrics(localPath, date, period, Statistic.Average, null);
		clients.retrieveMetrics(localPath, date, period, Statistic.Average, null);

		logger.info("Done!");
	}
	
	private static int getSuggestedPeriod(Date date) {
		Date now = new Date();
		double diff = now.getTime() - date.getTime();
		diff /= 1000 * 60;
		final int maxData = 1440;
		
		double res = diff / maxData;
		res = Math.ceil(res);
		
		if (res < 1)
			res = 1;
		
		return (int)res * 60;
	}

}
