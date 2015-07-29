package it.polimi.modaclouds.scalingsdatests;

import it.cloud.Configuration;
import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.amazon.elb.ElasticLoadBalancing;
import it.cloud.amazon.elb.ElasticLoadBalancing.Listener;
import it.cloud.utils.CloudException;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.cloud.utils.Ssh;
import it.polimi.modaclouds.scalingsdatests.sdavalidator.Validator;
import it.polimi.modaclouds.scalingsdatests.utils.CloudMLCall;
import it.polimi.modaclouds.scalingsdatests.utils.CloudMLCall.CloudML;
import it.polimi.modaclouds.scalingsdatests.utils.MonitoringPlatform;
import it.polimi.tower4clouds.rules.MonitoringRule;
import it.polimi.tower4clouds.rules.MonitoringRules;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
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

	private VirtualMachine mpl;
	private VirtualMachine app;
	private VirtualMachine clients;
	private VirtualMachine database;

	private boolean useDatabase;
	private boolean useCloudML;
	private boolean useSDA;
	private boolean useOwnLoadBalancer;

	private boolean running;
	private boolean initialized;
	private boolean cloudMlInitialized;

	private MonitoringPlatform monitoringPlatform;
	private CloudML cloudML;

	static {
		VirtualMachine.PRICE_MARGIN = 0.35;
	}

	public static enum App {
		MIC(
				"mic",
				"MPloadModel-MiC",
				"jmeterTestTemplate-MiC.jmx",
				"startContainerMonitoring-MiC.sh",
				"stopContainerMonitoring-MiC.sh",
				"grep_methodResult-MiC",
				new String[] { "reg", "save", "answ" },
				"MIC",
				"cloudml.json",
				"cloudml-LB.json",
				"cloudmlrules.txt"),
		HTTPAGENT(
				"httpagent",
				"MPloadModel-HTTPAgent",
				"jmeterTestTemplate-HTTPAgent.jmx",
				null,
				"stopContainerMonitoring-HTTPAgent.sh",
				"grep_methodResult-HTTPAgent",
				new String[] { "getPage" },
				"HTTPAgent",
				"cloudml.json",
				"cloudml-LB.json",
				"cloudmlrules.txt"
				);

		public String name;
		public String fileModel;
		public String baseJmx;
		public String startContainerMonitoringFile;
		public String stopContainerMonitoringFile;
		public String grepMethodResult;
		public String[] methods;
		public String tierName;
		public String cloudMl;
		public String cloudMlLoadBalancer;
		public String cpuUtilizationRules;

		private App(String name, String fileModel, String baseJmx, String startContainerMonitoringFile, String stopContainerMonitoringFile, String grepMethodResult, String[] methods, String tierName, String cloudMl, String cloudMlLoadBalancer, String cpuUtilizationRules) {
			this.name = name;
			this.fileModel = fileModel;
			this.baseJmx = baseJmx;
			this.startContainerMonitoringFile = startContainerMonitoringFile;
			this.stopContainerMonitoringFile = stopContainerMonitoringFile;
			this.grepMethodResult = grepMethodResult;
			this.methods = methods;
			this.tierName = tierName;
			this.cloudMl = cloudMl;
			this.cloudMlLoadBalancer = cloudMlLoadBalancer;
			this.cpuUtilizationRules = cpuUtilizationRules;
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

	public static enum DemandEstimator {
		UBR("UBR"), ERPS("ERPS"), CI("CI");

		public String name;

		private DemandEstimator(String name) {
			this.name = name;
		}

		public static DemandEstimator getFromName(String name) {
			for (DemandEstimator d : values())
				if (d.name.equalsIgnoreCase(name))
					return d;

			return DEFAULT_DEMAND_ESTIMATOR;
		}
	}

	public static final App DEFAULT_APP = App.MIC;
	public static final DemandEstimator DEFAULT_DEMAND_ESTIMATOR = DemandEstimator.ERPS;
	public static final long ERROR_STATUS_NULL = -1234;
	public static final int MAX_ATTEMPTS = 5;

	public static long performTest(String size, int clients, int servers, App app, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String loadModelFile, int firstInstancesToSkip, String demandEstimator, int window,
			boolean useSDA, boolean useCloudML, double highCpu, double lowCpu, int cooldown, String loadBalancerIp) throws Exception {
		String baseJmx = app.getBaseJmxPath().toString();

		if (baseJmx == null || !new File(baseJmx).exists())
			throw new RuntimeException("The provided base JMX file (" + baseJmx.toString() + ") doesn't exist!");
		if (data == null || !new File(data).exists())
			throw new RuntimeException("The provided data file (" + data + ") doesn't exist!");
		if (loadModelFile != null && !new File(loadModelFile).exists())
			loadModelFile = null;

		if (window <= 0)
			window = Validator.DEFAULT_WINDOW;

		long init = System.currentTimeMillis();

		Test t = new Test(size, clients, servers, app.name, useDatabase, useSDA, useCloudML, loadBalancerIp != null);

		if (reuseInstances)
			t.considerRunningMachines();
		t.startMachines(startAsOnDemand);

		if (loadBalancerIp != null)
			t.loadBalancer = loadBalancerIp;
		else
			t.createLoadBalancer();

		Path path = null;
		Exception thrown = null;

		try {
			t.initSystem(loadModelFile, demandEstimator, window);

			if (onlyStartMachines)
				return -1;

			boolean performTheTest = false;
			if (useCloudML) {
				String status;
				int attempt = 0;
				do {
					t.initCloudML(app);
					status = t.getTierStatus(app.tierName);
					attempt++;
				} while ((status == null || status.equals("null")) && attempt < MAX_ATTEMPTS);

				if (status != null && !status.equals("null")) {
					t.addCPUUtilizationMonitoringRules(app.cpuUtilizationRules, app.tierName, highCpu, lowCpu, window, cooldown);
					performTheTest = true;
				} else {
					logger.error("CloudML isn't working (the statuses are null).");
					performTheTest = false;
				}
			} else {
				performTheTest = true;
			}

			if (performTheTest)
				path = t.runTest(app, data, demandEstimator);
		} catch (Exception e) {
			logger.error("Error while performing the test.", e);
			thrown = e;
		}

		if (useCloudML) {
			t.stopCloudMLInstances();
			t.terminateCloudMLDaemon();
		}

		if (loadBalancerIp == null)
			t.destroyLoadBalancer();

		if (!leaveInstancesOn)
			t.stopMachines();

		if (useSDA)
			Validator.perform(path, t.getCores(), firstInstancesToSkip, app, window);

		if (thrown != null)
			throw thrown;

		return System.currentTimeMillis() - init;
	}

	public Test(String size, int clients, int servers, String app, boolean useDatabase, boolean useSDA, boolean useCloudML, boolean useOwnLoadBalancer) throws CloudException {
		if (clients <= 0)
			throw new RuntimeException("You need at least 1 client!");

		if (servers <= 0)
			throw new RuntimeException("You need at least 1 server!");

		mpl = VirtualMachine.getVM("mpl", size, 1);
		this.app = VirtualMachine.getVM(app, size, servers);
		this.clients = VirtualMachine.getVM("client", size, clients);

		this.useDatabase = useDatabase;
		if (useDatabase)
			database = VirtualMachine.getVM("database", size, 1);

		running = false;
		initialized = false;
		cloudMlInitialized = false;

		this.useCloudML = useCloudML;
		this.useSDA = useSDA;
		this.useOwnLoadBalancer = useOwnLoadBalancer;

		otherThreads = new ArrayList<Thread>();
	}

	private String loadBalancer;
	public static final String LB_BASENAME = "ScalingSDATests";

	private void createLoadBalancer() {
		if (app.getInstancesNeeded() <= 1 && !useCloudML)
			return;

		if (loadBalancer != null)
			throw new RuntimeException(
					"You've already created a load balancer!");

		loadBalancer = LB_BASENAME + RandomStringUtils.randomNumeric(3);

		ElasticLoadBalancing.createNewLoadBalancer(loadBalancer, new Listener("HTTP", Integer.parseInt(app.getParameter("PORT"))));
	}

	private void destroyLoadBalancer() {
		if (app.getInstancesNeeded() <= 1 && !useCloudML)
			return;

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

	public void startMachines(boolean startAsOnDemand) {
		if (running)
			throw new RuntimeException("The system is already running!");

		logger.info("Starting all the machines...");

		if (!startAsOnDemand) {
			mpl.spotRequest();
			if (!useCloudML)
				app.spotRequest();
			clients.spotRequest();
			if (useDatabase)
				database.spotRequest();
		} else {
			mpl.onDemandRequest();
			if (!useCloudML)
				app.onDemandRequest();
			clients.onDemandRequest();
			if (useDatabase)
				database.onDemandRequest();
		}

		mpl.waitUntilRunning();
		if (!useCloudML)
			app.waitUntilRunning();
		clients.waitUntilRunning();
		if (useDatabase)
			database.waitUntilRunning();

		mpl.setNameToInstances(mpl.getParameter("NAME"));
		if (!useCloudML)
			app.setNameToInstances(app.getParameter("NAME"));
		clients.setNameToInstances(clients.getParameter("NAME"));
		if (useDatabase)
			database.setNameToInstances(database.getParameter("NAME"));

		mpl.getInstances().get(0).waitUntilSshAvailable();
		if (!useCloudML)
			app.getInstances().get(0).waitUntilSshAvailable();
		if (useDatabase)
			database.getInstances().get(0).waitUntilSshAvailable();

		logger.info("All the machines are now running!");

		running = true;
	}

	public void considerRunningMachines() {
		if (running)
			throw new RuntimeException("The system is already running!");

		logger.info("Searching for running machines...");

		AmazonEC2 ec2 = new AmazonEC2();

		ec2.addRunningInstances(mpl);
		if (!useCloudML)
			ec2.addRunningInstances(app);
		ec2.addRunningInstances(clients);
		if (useDatabase)
			ec2.addRunningInstances(database);

		mpl.reboot();
		if (!useCloudML)
			app.reboot();
		clients.reboot();
		if (useDatabase)
			database.reboot();

		logger.info("Added running instances!");
	}

	public void stopMachines() {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		logger.info("Stopping all the machines...");

		mpl.terminate();
		if (!useCloudML)
			app.terminate();
		clients.terminate();
		if (useDatabase)
			database.terminate();

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

	private List<Thread> otherThreads;

	public void initSystem(String loadModelFile, String demandEstimator, int window) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (initialized)
			throw new RuntimeException("The system is already initialized!");

		logger.info("Deleting the files set for removal before the test...");

		if (!useCloudML)
			app.deleteFiles();
		mpl.deleteFiles();
		clients.deleteFiles();
		if (useDatabase)
			database.deleteFiles();

		logger.info("Initializing the system...");

		Instance impl = mpl.getInstances().get(0);

		String mplIp = impl.getIp();
		int mplPort = Integer.parseInt(mpl.getParameter("MP_PORT"));

		impl.exec(mpl.getParameter("UPDATER"));
		impl.exec(String.format(mpl.getParameter("STARTER"), mplIp));

		try { Thread.sleep(10000); } catch (Exception e) { }

		{
			String starterBg = mpl.getParameter("STARTER_BG");
			if (starterBg != null && starterBg.trim().length() > 0) {
				Ssh.execInBackground(impl, starterBg);

				try { Thread.sleep(10000); } catch (Exception e) { }
			}
		}

		monitoringPlatform = new MonitoringPlatform(mplIp, mplPort);

		int cores = getCores();

		exec(String.format("bash %s %s %s %d %s %d %b",
				loadModelFile,
				impl.getIp(),
				impl.getIp(),
				cores,
				demandEstimator,
				window,
				useSDA));

		try { Thread.sleep(10000); } catch (Exception e) { }

		if (useSDA) {
			otherThreads.add(Ssh.execInBackground(impl, String.format(
					mpl.getParameter("SDA_STARTER"),
					mplIp)));

			try { Thread.sleep(10000); } catch (Exception e) { }
		}

		if (!useCloudML)
			for (Instance iapp : app.getInstances()) {
				iapp.exec(app.getParameter("UPDATER"));
				iapp.exec(String.format(
						app.getParameter("STARTER"),
						useDatabase ? database.getIps().get(0) : "127.0.0.1",
						mplIp
				));

				try { Thread.sleep(10000); } catch (Exception e) { }

				if (app.getInstancesNeeded() > 1 && loadBalancer != null) {
					iapp.exec(String.format(app.getParameter("ADD_TO_LOAD_BALANCER"), loadBalancer));

					try { Thread.sleep(10000); } catch (Exception e) { }
				}
			}

		logger.info("System initialized!");

		initialized = true;
	}

	public static List<String> exec(String command) throws IOException {
		List<String> res = new ArrayList<String>();
		ProcessBuilder pb = new ProcessBuilder(command.split(" "));
		pb.redirectErrorStream(true);

		Process p = pb.start();
		BufferedReader stream = new BufferedReader(new InputStreamReader(p.getInputStream()));
		String line = stream.readLine();
		while (line != null) {
			logger.trace(line);
			res.add(line);
			line = stream.readLine();
		}
		stream.close();

		return res;
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

		logger.info("Trying connecting to the CloudML daemon...");
		try {
			cloudML = CloudMLCall.getCloudML(cloudMLIp, cloudMLPort);
		} catch (Exception e) {
			logger.info("The daemon wasn't running, starting it...");
			Ssh.execInBackground(cloudMLIp, mpl, String.format(mpl.getParameter("CLOUDML_STARTER"), Integer.toString(cloudMLPort)));

			boolean goOn = true;
			while (goOn) {
				try {
					Thread.sleep(10000);
				} catch (Exception e1) { }

				try {
					cloudML = CloudMLCall.getCloudML(cloudMLIp, cloudMLPort);
					goOn = false;
				} catch (Exception e1) {
					logger.warn("Trying again to connect to the CloudML daemon in 10 seconds...");
				}
			}
		}
		logger.info("Connected to the CloudML daemon!");

		cloudML.deploy(getActualDeploymentModel(app, true).toFile());

		logger.info("CloudML initialized!");

		cloudMlInitialized = true;
	}

	private static List<MonitoringRule> getMonitoringRulesFromFile(String fileName,
			Object... substitutions) throws Exception {
		String tmp = FileUtils.readFileToString(Configuration.getPathToFile(fileName).toFile());
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

	public void addCPUUtilizationMonitoringRules(String file, String tierName, double aboveValue,
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
						file,
						doubleFormatter.format(aboveValue), doubleFormatter.format(belowValue), cloudMLIp, cloudMLPort, tierName, window, cooldown));

		monitoringPlatform.installRules(rules);
		monitoringPlatform.attachObserver("FrontendCPUUtilization", cloudMLIp, "8001");
		monitoringPlatform.attachObserver("AvarageEffectiveResponseTime", cloudMLIp, "8001");
		monitoringPlatform.attachObserver("Workload", cloudMLIp, "8001");
	}

	public static String getActualFile(String ip, VirtualMachine vm, String filePath, String folder) throws Exception {
		Path p = Configuration.getPathToFile(filePath);

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
		return getActualDeploymentModel(ipMpl, mpl, this.app, app.cloudMl, app.cloudMlLoadBalancer, loadBalancer, remotePathIfNecessary, useDatabase, database, useOwnLoadBalancer);
	}
	
	public static void main(String[] args) throws Exception {
//		String body = FileUtils.readFileToString(Configuration.getPathToFile(App.HTTPAGENT.cloudMl).toFile());
//		logger.info(body);
//		logger.info("#############################");
//		logger.info(removeCommentedLines(body));
//		logger.info("#############################");
//		JSONObject jsonObject = new JSONObject(removeCommentedLines(body));
//		logger.info(jsonObject.toString(0));
		
		String pattern = "(\"[^\"]+\")[ \t]*:[ \t]*";//"([\"][^\"]+[\"][ \t]*): ";
		String subst = "$1:";
		
		String s = "\"questa è una prova\": [";
		
		logger.info(">\n\n{}\n{}\n", s, s.replaceAll(pattern, subst));
		
		s = "\"questa è una prova\": {";
		
		logger.info(">\n\n{}\n{}\n", s, s.replaceAll(pattern, subst));
		
		s = "\"questa è una prova\": \"";
		
		logger.info(">\n\n{}\n{}\n", s, s.replaceAll(pattern, subst));
	}
	
	private static boolean isInString(String previousString) {
		int count = 0;
		int i = previousString.indexOf('"');
		while (i > -1) {
			count++;
			i = previousString.indexOf('"', i+1);
		}
		return count % 2 == 1;
	}
	
	public static String removeCommentedLines(String body) {
		StringBuilder sb = new StringBuilder();
		boolean inComment = false;
		try (Scanner sc = new Scanner(body)) {
			while (sc.hasNextLine()) {
				String line = sc.nextLine();
				int i = line.indexOf("//");
				int j = line.indexOf("/*");
				int k = line.indexOf("*/");
				
				if (inComment && (k == -1 || isInString(line.substring(0, k)))) {
					continue;
				} else if (inComment) {
					line = line.substring(k+2);
					inComment = false;
					if (line.trim().length() == 0)
						continue;
				} else if (j > -1 && !isInString(line.substring(0, j))) {
					if (k > -1) {
						line = line.substring(0, j).concat(line.substring(k+2));
					} else {
						line = line.substring(0, j);
						inComment = true;
					}
					if (line.trim().length() == 0)
						continue;
				} else if (i > -1 && !isInString(line.substring(0, i))) {
					line = line.substring(0, i);
					if (line.trim().length() == 0)
						continue;
				}
				sb.append(line + "\n");
			}
		}
		return sb.toString();
	}

	public static Path getActualDeploymentModel(String ipMpl, VirtualMachine mpl, VirtualMachine app, String cloudMl, String cloudMlLoadBalancer, String loadBalancer, boolean remotePathIfNecessary, boolean useDatabase, VirtualMachine database, boolean useOwnLoadBalancer) throws Exception {
		String body = null;
		if (useOwnLoadBalancer)
			body = FileUtils.readFileToString(Configuration.getPathToFile(cloudMlLoadBalancer).toFile());
		else
			body = FileUtils.readFileToString(Configuration.getPathToFile(cloudMl).toFile());
		
		body = removeCommentedLines(body);

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
						s = Configuration.getPathToFile(credentials).toString();
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
						s = Configuration.getPathToFile(privateKey).toString();
					vmo.put("privateKey", s);

					body = body.replaceAll(privateKey, s);
				}
			}
		}

		if (useOwnLoadBalancer)
			body = String.format(body,
					RandomStringUtils.randomNumeric(3),
					app.getImageId(),
					it.cloud.amazon.Configuration.REGION,
					app.getParameter("DOWNLOADER").replaceAll("&&", ";"),
					app.getParameter("INSTALLER").replaceAll("&&", ";"),
					String.format(
							app.getParameter("STARTER").replaceAll("&&", ";"),
							useDatabase ? database.getIps().get(0) : "127.0.0.1",
							ipMpl),
					"echo DONE",
					"echo DONE",
					app.getSize(),
					app.getParameter("NAME"),
					loadBalancer
					);
		else
			body = String.format(body,
					RandomStringUtils.randomNumeric(3),
					app.getImageId(),
					it.cloud.amazon.Configuration.REGION,
					app.getParameter("DOWNLOADER").replaceAll("&&", ";"),
					app.getParameter("INSTALLER").replaceAll("&&", ";"),
					String.format(
							app.getParameter("STARTER").replaceAll("&&", ";"),
							useDatabase ? database.getIps().get(0) : "127.0.0.1",
							ipMpl),
					String.format(
							app.getParameter("ADD_TO_LOAD_BALANCER").replaceAll("&&", ";"),
							it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSAccessKeyId(),
							it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSSecretKey(),
							it.cloud.amazon.Configuration.REGION,
							loadBalancer),
					String.format(
							app.getParameter("DEL_FROM_LOAD_BALANCER").replaceAll("&&", ";"),
							it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSAccessKeyId(),
							it.cloud.amazon.Configuration.AWS_CREDENTIALS.getAWSSecretKey(),
							it.cloud.amazon.Configuration.REGION,
							loadBalancer),
					app.getSize(),
					app.getParameter("NAME")
					);

		Path p = Files.createTempFile("model", ".json");
		FileUtils.writeStringToFile(p.toFile(), body);

		return p;
	}

	public Path runTest(App app, String data, String method) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (!initialized)
			throw new RuntimeException("The system isn't initialized yet!");

		if (!cloudMlInitialized && useCloudML)
			throw new RuntimeException("CloudML isn't initialized yet!");

		Date date = new Date();
		String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM-%2$s-%3$dx%4$d-%5$s%6$s", date, clients.getSize(), getPeakFromData(data) / clients.getInstancesRunning(), clients.getInstancesRunning(), app.name, (useSDA ? "-" + method : "") + (useCloudML ? "-CloudML" : ""));

		String localPath = "tests" + File.separator + now;

		File f = new File(localPath);
		for (int i = 2; f.exists(); ++i)
			f = new File(localPath + "-" + i);
		f.mkdirs();

		String remotePath = clients.getParameter("REMOTE_PATH") + "/" + now;

		String protocol = this.app.getParameter("PROTOCOL");
		if (protocol == null)
			protocol = "http";

		String port = this.app.getParameter("PORT");
		if (port == null)
			port = "8080";

		String server = loadBalancer != null ? ElasticLoadBalancing.getLoadBalancerDNS(loadBalancer) : this.app.getIps().get(0);

		JMeterTest test = new JMeterTest(clients.getParameter("AMI"), clients.getInstancesRunning(), localPath, remotePath, clients.getParameter("JMETER_PATH"), data,
				server,
				protocol,
				port);

		RunInstance run = test.createModifiedFile(app.getBaseJmxPath());

		String javaParameters = clients.getParameter("JAVA_PARAMETERS");
		if (javaParameters != null && (javaParameters.trim().length() == 0 || !javaParameters.startsWith("-")))
			javaParameters = null;
		JMeterTest.javaParameters = javaParameters;

		if (!useCloudML)
			if (app.startContainerMonitoringFile != null)
				for (Instance iapp : this.app.getInstances())
					exec(String.format("bash " + Configuration.getPathToFile(app.startContainerMonitoringFile) + " %s", iapp.getIp()));

		logger.info("Test starting...");

		test.performTest(clients, run);

		logger.info("Test ended!");

		logger.info("Retrieving the files from the instances...");

		if (!useCloudML)
			if (app.stopContainerMonitoringFile != null) {
				int i = 1;
				for (Instance iapp : this.app.getInstances())
					exec(String.format("bash " + Configuration.getPathToFile(app.stopContainerMonitoringFile) + " %s %s %s", iapp.getIp(), Paths.get(localPath, app.name + i++), Configuration.getPathToFile(this.app.getParameter("KEYPAIR_NAME") + ".pem")));
			}
		if (!useCloudML)
			this.app.retrieveFiles(localPath, "/home/" + this.app.getParameter("SSH_USER"));
		mpl.retrieveFiles(localPath, "/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath, "/home/" + clients.getParameter("SSH_USER"));
		if (useDatabase)
			database.retrieveFiles(localPath, "/home/" + database.getParameter("SSH_USER"));

		logger.info("Retrieving the data from the metrics...");

		int period = getSuggestedPeriod(date);

		if (!useCloudML)
			this.app.retrieveMetrics(localPath, date, period, Statistic.Average, null);
		mpl.retrieveMetrics(localPath, date, period, Statistic.Average, null);
		clients.retrieveMetrics(localPath, date, period, Statistic.Average, null);
		if (useDatabase)
			database.retrieveMetrics(localPath, date, period, Statistic.Average, null);

		logger.info("Done!");

		return Paths.get(localPath, "mpl1", "home", mpl.getParameter("SSH_USER"));
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

	public static int getCores(String resourceName) {
		switch (resourceName) {
		case "m3.medium":
			return 1;
		case "m3.large":
			return 2;
		case "m3.xlarge":
			return 4;
		case "m3.2xlarge":
			return 8;
		default:
			return 1;
		}
	}

	public int getCores() {
		return getCores(app.getSize());
	}

}
