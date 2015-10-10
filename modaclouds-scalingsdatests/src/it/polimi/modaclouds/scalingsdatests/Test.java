package it.polimi.modaclouds.scalingsdatests;

import it.cloud.Configuration;
import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.Instance;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.elb.ElasticLoadBalancing;
import it.cloud.amazon.elb.ElasticLoadBalancing.Listener;
import it.cloud.utils.CloudException;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.cloud.utils.Local;
import it.cloud.utils.Ssh;
import it.polimi.modaclouds.scalingsdatests.utils.CloudMLCall;
import it.polimi.modaclouds.scalingsdatests.utils.CloudMLCall.CloudML;
import it.polimi.modaclouds.scalingsdatests.utils.MonitoringPlatform;
import it.polimi.modaclouds.scalingsdatests.validator.sda.Validator;
import it.polimi.tower4clouds.rules.MonitoringRule;
import it.polimi.tower4clouds.rules.MonitoringRules;

import java.io.File;
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

public class Test {

	private static final Logger logger = LoggerFactory.getLogger(Test.class);

	private VirtualMachine mpl;
	private VirtualMachine app;
	private VirtualMachine clients;
	private VirtualMachine lb;
	
	private boolean useCloudML;
	private boolean useSDA;
	private boolean useOwnLoadBalancer;
	private boolean useAutoscalingReasoner;

	private boolean running;
	private boolean initialized;
	private boolean cloudMlInitialized;

	private MonitoringPlatform monitoringPlatform;
	private CloudML cloudML;

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
				"cloudmlrules.txt",
				"MICInitialModel.xml"),
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
				"cloudmlrules.txt",
				"httpAgentInitialModel.xml");

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
		public String cloudMlRules;
		public String pathToModel;

		private App(String name, String fileModel, String baseJmx, String startContainerMonitoringFile, String stopContainerMonitoringFile, String grepMethodResult, String[] methods, String tierName, String cloudMl, String cloudMlLoadBalancer, String cloudMlRules, String pathToModel) {
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
			this.cloudMlRules = cloudMlRules;
			this.pathToModel = pathToModel;
		}

		public Path getBaseJmxPath() {
			return Configuration.getPathToFile(baseJmx);
		}

		public static App getFromName(String name) {
			for (App a : values())
				if (a.name.equalsIgnoreCase(name))
					return a;

			return DEFAULT;
		}
		
		public static App DEFAULT = HTTPAGENT;
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

			return DEFAULT;
		}
		
		public static DemandEstimator DEFAULT = ERPS;
	}

	public static final long ERROR_STATUS_NULL = -1234;
	public static final int MAX_ATTEMPTS = 5;

	public static long performTest(String size, int clients, int servers, App app, String data, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String loadModelFile, int firstInstancesToSkip, String demandEstimator, int window,
			boolean useSDA, boolean useCloudML, String highMetric, String lowMetric, int cooldown, String loadBalancerIp, boolean useAutoscalingReasoner,
			String sshHost, String sshUsername, String sshPassword, boolean alreadyUpdated) throws Exception {
		String baseJmx = app.getBaseJmxPath().toString();

		if (baseJmx == null || !Configuration.getPathToFile(baseJmx).toFile().exists())
			throw new RuntimeException("The provided base JMX file (" + baseJmx.toString() + ") doesn't exist!");
		if (data == null || !Configuration.getPathToFile(data).toFile().exists())
			throw new RuntimeException("The provided data file (" + data + ") doesn't exist!");
		if (loadModelFile != null && !Configuration.getPathToFile(loadModelFile).toFile().exists())
			loadModelFile = null;

		if (window <= 0)
			window = Validator.DEFAULT_WINDOW;

		long init = System.currentTimeMillis();

		Test t = new Test(size, clients, servers, app.name, useSDA, useCloudML, loadBalancerIp != null, useAutoscalingReasoner);

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
			t.initSystem(loadModelFile, demandEstimator, window, alreadyUpdated);

			if (onlyStartMachines)
				return -1;

			boolean performTheTest = false;
			if (useCloudML || useAutoscalingReasoner) {
				String status;
				int attempt = 0;
				do {
					t.initCloudML(app);
					status = t.getTierStatus(app.tierName);
					attempt++;
				} while ((status == null || status.equals("null")) && attempt < MAX_ATTEMPTS);

				if (status != null && !status.equals("null")) {
					if (useCloudML)
						t.addCloudMLRules(app.cloudMlRules, app.tierName, highMetric, lowMetric, window, cooldown);
					if (useAutoscalingReasoner)
						t.startAutoscalingReasoner(app.pathToModel, sshHost, sshUsername, sshPassword);
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
		
		if (thrown != null) {
			try {
				Date date = new Date();
				String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM-%2$s-%3$dx%4$d-%5$s%6$s-broken", date, size, getPeakFromData(data) / clients, clients, app.name, (useSDA ? "-" + demandEstimator : "") + (useCloudML ? "-CloudML" : "") + (useAutoscalingReasoner ? "-AR" : ""));
				
				t.localPath = "tests" + File.separator + now;
				t.retrieveFiles(app);
			} catch (Exception e) {
				logger.error("Error while trying to retrieve the files after an error.", e);
			}
		}

		if (!leaveInstancesOn) {
			if (useCloudML || useAutoscalingReasoner) {
				t.stopCloudMLInstances();
				t.terminateCloudMLDaemon();
			}
			
			if (loadBalancerIp == null)
				t.destroyLoadBalancer();
			
			t.stopMachines();
		}

		if (useSDA && !useAutoscalingReasoner)
			Validator.perform(path, t.getCores(), firstInstancesToSkip, app, window);
		if (useAutoscalingReasoner)
			it.polimi.modaclouds.scalingsdatests.validator.ar.Validator.perform(path, 10, true, 0.5, 1500, 10000);

		if (thrown != null)
			throw thrown;

		return System.currentTimeMillis() - init;
	}

	public Test(String size, int clients, int servers, String app, boolean useSDA, boolean useCloudML, boolean useOwnLoadBalancer, boolean useAutoscalingReasoner) throws CloudException {
		if (clients <= 0)
			throw new RuntimeException("You need at least 1 client!");

		if (servers <= 0)
			throw new RuntimeException("You need at least 1 server!");

		mpl = VirtualMachine.getVM("mpl", size, 1);
		this.app = VirtualMachine.getVM(app, size, servers);
		this.clients = VirtualMachine.getVM("client", size, clients);

		running = false;
		initialized = false;
		cloudMlInitialized = false;

		this.useCloudML = useCloudML;
		this.useSDA = useSDA;
		this.useOwnLoadBalancer = useOwnLoadBalancer;
		if (useOwnLoadBalancer)
			lb = VirtualMachine.getVM("lb");

		otherThreads = new ArrayList<Thread>();
		
		this.useAutoscalingReasoner = useAutoscalingReasoner;
	}

	private String loadBalancer;
	public static final String LB_BASENAME = "ScalingSDATests";

	private void createLoadBalancer() {
		if ((app.getInstancesNeeded() <= 1 && !useCloudML && !useAutoscalingReasoner) || useOwnLoadBalancer)
			return;

		if (loadBalancer != null)
			throw new RuntimeException(
					"You've already created a load balancer!");

		loadBalancer = LB_BASENAME + RandomStringUtils.randomNumeric(3);

		ElasticLoadBalancing.createNewLoadBalancer(loadBalancer, new Listener("HTTP", Integer.parseInt(app.getParameter("PORT"))));
	}

	private void destroyLoadBalancer() {
		if ((app.getInstancesNeeded() <= 1 && !useCloudML && !useAutoscalingReasoner) || useOwnLoadBalancer)
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
			if (!useCloudML && !useAutoscalingReasoner)
				app.spotRequest();
			clients.spotRequest();
		} else {
			mpl.onDemandRequest();
			if (!useCloudML && !useAutoscalingReasoner)
				app.onDemandRequest();
			clients.onDemandRequest();
		}

		mpl.waitUntilRunning();
		if (!useCloudML && !useAutoscalingReasoner)
			app.waitUntilRunning();
		clients.waitUntilRunning();

		mpl.setNameToInstances(mpl.getParameter("NAME"));
		if (!useCloudML && !useAutoscalingReasoner)
			app.setNameToInstances(app.getParameter("NAME"));
		clients.setNameToInstances(clients.getParameter("NAME"));

		mpl.getInstances().get(0).waitUntilSshAvailable();
		if (!useCloudML && !useAutoscalingReasoner)
			app.getInstances().get(0).waitUntilSshAvailable();

		logger.info("All the machines are now running!");

		running = true;
	}
	
	public void considerRunningMachines() {
		considerRunningMachines(true);
	}

	public void considerRunningMachines(boolean rebootMachines) {
		if (running)
			throw new RuntimeException("The system is already running!");

		logger.info("Searching for running machines...");

		AmazonEC2 ec2 = new AmazonEC2();

		ec2.addRunningInstances(mpl);
		if (!useCloudML && !useAutoscalingReasoner)
			ec2.addRunningInstances(app);
		ec2.addRunningInstances(clients);

		if (rebootMachines) {
			mpl.reboot();
			if (!useCloudML && !useAutoscalingReasoner)
				app.reboot();
			clients.reboot();
		}

		logger.info("Added running instances!");
	}

	public void stopMachines() {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		logger.info("Stopping all the machines...");

		if (!Boolean.parseBoolean(mpl.getParameter("LEAVE_INSTANCES_ON")))
			mpl.terminate();
		if (!useCloudML && !useAutoscalingReasoner && !Boolean.parseBoolean(app.getParameter("LEAVE_INSTANCES_ON")))
			app.terminate();
		if (!Boolean.parseBoolean(clients.getParameter("LEAVE_INSTANCES_ON")))
			clients.terminate();

		logger.info("All the machines have been shutted down!");

		running = false;
		initialized = false;
		localPath = null;
	}

	public void stopCloudMLInstances() {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (Boolean.parseBoolean(app.getParameter("LEAVE_INSTANCES_ON"))) {
			logger.info("Leaving the CLoudML instances on as requested...");
			return;
		}
		
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

	public void initSystem(String loadModelFile, String demandEstimator, int window, boolean alreadyUpdated) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (initialized)
			throw new RuntimeException("The system is already initialized!");

		logger.info("Deleting the files set for removal before the test and upgrading the scripts...");

		if (!useCloudML && !useAutoscalingReasoner) {
			app.deleteFiles();
			app.execDownloader();
		}
		
		mpl.deleteFiles();
		Instance impl = (Instance)mpl.getInstances().get(0);
		impl.execDownloader();
		
		clients.deleteFiles();
		clients.execDownloader();
		if (!alreadyUpdated && !Boolean.parseBoolean(clients.getParameter("ALREADY_UPDATED")))
			clients.execUpdater();
		
		if (useOwnLoadBalancer) {
			logger.info("Updating and starting the load balancer...");
			
			Ssh.exec(loadBalancer, lb, lb.getParameter("DOWNLOADER"));
			
			Ssh.exec(loadBalancer, lb, lb.getParameter("STOPPER"));
			VirtualMachine.deleteFiles(loadBalancer, lb);
			
			if (!alreadyUpdated && !Boolean.parseBoolean(lb.getParameter("ALREADY_UPDATED"))) {
				Ssh.exec(loadBalancer, lb, lb.getParameter("UPDATER"));
				
				try { Thread.sleep(10000); } catch (Exception e) { }
			}
			
			Ssh.exec(loadBalancer, lb, lb.getParameter("STARTER"));
		}

		logger.info("Initializing the system...");

		String mplIp = impl.getIp();
		int mplPort = Integer.parseInt(mpl.getParameter("MP_PORT"));

		if (!alreadyUpdated && !Boolean.parseBoolean(mpl.getParameter("ALREADY_UPDATED")))
			impl.execUpdater();
		impl.exec(String.format(mpl.getParameter("STARTER"), mplIp));

		try { Thread.sleep(10000); } catch (Exception e) { }

		monitoringPlatform = new MonitoringPlatform(mplIp, mplPort);

		int cores = getCores();

		if (!useAutoscalingReasoner) {
			Local.exec(String.format("bash %s %s %s %d %s %d %b",
						loadModelFile,
						impl.getIp(),
						impl.getIp(),
						cores,
						demandEstimator,
						window,
						useSDA));
			
			try { Thread.sleep(10000); } catch (Exception e) { }
		}

		if (useSDA) {
			otherThreads.add(Ssh.execInBackground(impl, String.format(
					mpl.getParameter("SDA_STARTER"),
					mplIp)));

			try { Thread.sleep(10000); } catch (Exception e) { }
		}

		if (!useCloudML && !useAutoscalingReasoner)
			for (it.cloud.Instance iapp : app.getInstances()) {
				if (!alreadyUpdated && !Boolean.parseBoolean(app.getParameter("ALREADY_UPDATED")))
					iapp.execUpdater();
				iapp.exec(String.format(
						app.getParameter("STARTER"),
						"127.0.0.1",
						mplIp,
						mpl.getParameter("MP_PORT")
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
	
	public void startAutoscalingReasoner(String pathToModel, String sshHost, String sshUsername, String sshPassword) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (!initialized)
			throw new RuntimeException("The system isn't initialized yet!");
		
		if (!cloudMlInitialized)
			throw new RuntimeException("CloudML isn't initialized yet!");
		
		Instance impl = (Instance) mpl.getInstances().get(0);
		
		String remotePathToModel = getActualFile(impl.getIp(), mpl, pathToModel, "autoscalingReasoner");
		
		impl.exec(String.format(mpl.getParameter("AR_STARTER"),
				impl.getIp(),
				mpl.getParameter("AR_PORT"),
				remotePathToModel,
				sshHost,
				sshUsername,
				sshPassword,
				impl.getIp(),
				mpl.getParameter("CLOUDML_PORT"),
				mpl.getParameter("OBSERVER_PORT")));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
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

		Instance impl = (Instance)mpl.getInstances().get(0);
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

	public void addCloudMLRules(String file, String tierName, String highMetric,
			String lowMetric, int window, int cooldown) throws Exception {
		
		String cloudMLIp = mpl.getInstances().get(0).getIp();
		int cloudMLPort = Integer.parseInt(mpl.getParameter("CLOUDML_PORT"));

		MonitoringRules rules = new MonitoringRules();
		
		String metric = null;
		double value = 0.0;
		String metricClass = null;
		String metricType = null;
		
		if (highMetric != null)
			try {
				String[] ss = highMetric.split(":");
				if (ss.length == 1) {
					metric = "CPUUtilization";
					value = Double.parseDouble(highMetric);
				} else {
					metric = ss[0];
					value = Double.parseDouble(ss[1]);
					if (ss.length > 2) {
						metricClass = ss[2];
						metricType = ss[3];
					} else {
						metricClass = "VM";
						metricType = tierName;
					}
				}
				
				rules.getMonitoringRules()
				.addAll(getMonitoringRulesFromFile(
						file,
						metric, "&gt;=", doubleFormatter.format(value), cloudMLIp, cloudMLPort, tierName, window, cooldown,
						RandomStringUtils.randomNumeric(3), metricClass, metricType));
			} catch (Exception e) { }
		
		if (lowMetric != null)
			try {
				String[] ss = lowMetric.split(":");
				if (ss.length == 1) {
					metric = "CPUUtilization";
					value = Double.parseDouble(lowMetric);
				} else {
					metric = ss[0];
					value = Double.parseDouble(ss[1]);
					if (ss.length > 2) {
						metricClass = ss[2];
						metricType = ss[3];
					} else {
						metricClass = "VM";
						metricType = tierName;
					}
				}
				
				rules.getMonitoringRules()
				.addAll(getMonitoringRulesFromFile(
						file,
						metric, "&lt;=", doubleFormatter.format(value), cloudMLIp, cloudMLPort, tierName, window, cooldown,
						RandomStringUtils.randomNumeric(3), metricClass, metricType));
			} catch (Exception e) { }

		monitoringPlatform.installRules(rules);
		
		attachAllTheObservers();
	}
	
	public void attachAllTheObservers() throws Exception {
		String mmIp = mpl.getInstances().get(0).getIp();
		String port = mpl.getParameter("OBSERVER_PORT");
		if (port == null || port.length() == 0)
			port = "8001";
		
		monitoringPlatform.attachObserver("FrontendCPUUtilization", mmIp, port);
		monitoringPlatform.attachObserver("AvarageEffectiveResponseTime", mmIp, port);
		monitoringPlatform.attachObserver("Workload", mmIp, port);
		
		if (useSDA) {
			monitoringPlatform.attachObserver("EstimatedDemand", mmIp, port);
			monitoringPlatform.attachObserver("ForecastedWorkload1", mmIp, port);
			monitoringPlatform.attachObserver("ForecastedWorkload2", mmIp, port);
			monitoringPlatform.attachObserver("ForecastedWorkload3", mmIp, port);
			monitoringPlatform.attachObserver("ForecastedWorkload4", mmIp, port);
			monitoringPlatform.attachObserver("ForecastedWorkload5", mmIp, port);
		}
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
		return getActualDeploymentModel(ipMpl, mpl, this.app, app.cloudMl, app.cloudMlLoadBalancer, loadBalancer, remotePathIfNecessary, useOwnLoadBalancer);
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
	
	private static String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM",	new Date());

	public static Path getActualDeploymentModel(String ipMpl, VirtualMachine mpl, VirtualMachine app, String cloudMl, String cloudMlLoadBalancer, String loadBalancer, boolean remotePathIfNecessary, boolean useOwnLoadBalancer) throws Exception {
		String body = null;
		if (useOwnLoadBalancer)
			body = FileUtils.readFileToString(Configuration.getPathToFile(cloudMlLoadBalancer).toFile());
		else
			body = FileUtils.readFileToString(Configuration.getPathToFile(cloudMl).toFile());
		
		body = removeCommentedLines(body);

		JSONObject jsonObject = new JSONObject(body);

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
							"127.0.0.1",
							ipMpl,
							mpl.getParameter("MP_PORT")),
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
							"127.0.0.1",
							ipMpl,
							mpl.getParameter("MP_PORT")),
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
	
	private void printSystemStatus() {
		if (!running || !initialized || (!cloudMlInitialized && (useCloudML || useAutoscalingReasoner)))
			return;
		
		logger.info(mpl.toString());
		for (it.cloud.Instance i : mpl.getInstances())
			logger.info("- {}", i.getIp());
		logger.info(clients.toString());
		for (it.cloud.Instance i : clients.getInstances())
			logger.info("- {}", i.getIp());
		if (!useCloudML && !useAutoscalingReasoner) {
			logger.info(app.toString());
			for (it.cloud.Instance i : app.getInstances())
				logger.info("- {}", i.getIp());
		} else {
			logger.info(app.toString());
			for (String id : cloudML.getRunningInstancesIds(app.getParameter("NAME")))
				logger.info("- {}", Instance.getIp(id));
		}
	}
	
	private String localPath;

	public Path runTest(App app, String data, String method) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");

		if (!initialized)
			throw new RuntimeException("The system isn't initialized yet!");

		if (!cloudMlInitialized && (useCloudML || useAutoscalingReasoner))
			throw new RuntimeException("CloudML isn't initialized yet!");
		
		printSystemStatus();

		Date date = new Date();
		String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM-%2$s-%3$dx%4$d-%5$s%6$s", date, clients.getSize(), getPeakFromData(data) / clients.getInstancesRunning(), clients.getInstancesRunning(), app.name, (useSDA ? "-" + method : "") + (useCloudML ? "-CloudML" : "") + (useAutoscalingReasoner ? "-AR" : ""));

		localPath = "tests" + File.separator + now;

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

		String server;
		if (useOwnLoadBalancer)
			server = loadBalancer;
		else
			server = loadBalancer != null ? ElasticLoadBalancing.getLoadBalancerDNS(loadBalancer) : this.app.getIps().get(0);

		JMeterTest test = new JMeterTest(clients.getParameter("AMI"), clients.getInstancesRunning(), localPath, remotePath, clients.getParameter("JMETER_PATH"), data,
				server,
				protocol,
				port);

		RunInstance run = test.createModifiedFile(app.getBaseJmxPath());

		String javaParameters = clients.getParameter("JAVA_PARAMETERS");
		if (javaParameters != null && (javaParameters.trim().length() == 0 || !javaParameters.startsWith("-")))
			javaParameters = null;
		JMeterTest.javaParameters = javaParameters;

		if (!useCloudML && !useAutoscalingReasoner)
			if (app.startContainerMonitoringFile != null)
				for (it.cloud.Instance iapp : this.app.getInstances())
					Local.exec(String.format("bash %s %s", Configuration.getPathToFile(app.startContainerMonitoringFile), iapp.getIp()));

		logger.info("Test starting...");

		test.performTest(clients, run);

		logger.info("Test ended!");

		logger.info("Retrieving the files from the instances...");

		retrieveFiles(app);

		logger.info("Retrieving the data from the metrics...");

		if (!useCloudML && !useAutoscalingReasoner)
			this.app.retrieveMetrics(localPath, date);
		else
			VirtualMachine.retrieveMetrics(cloudML.getRunningInstancesIds(app.tierName), this.app, localPath, date);
		mpl.retrieveMetrics(localPath, date);
		clients.retrieveMetrics(localPath, date);
		logger.info("Done!");

		return Paths.get(localPath, "mpl1", "home", mpl.getParameter("SSH_USER"));
	}
	
	private void retrieveFiles(App app) throws Exception {
		if (!running || !initialized || localPath == null)
			return;

		if (!useCloudML && !useAutoscalingReasoner) {
			if (app.stopContainerMonitoringFile != null) {
				int i = 1;
				for (it.cloud.Instance iapp : this.app.getInstances())
					Local.exec(String.format("bash %s %s %s %s", Configuration.getPathToFile(app.stopContainerMonitoringFile), iapp.getIp(), Paths.get(localPath, app.name + i++), Configuration.getPathToFile(this.app.getParameter("KEYPAIR_NAME") + ".pem")));
			}
			
			this.app.retrieveFiles(localPath, "/home/" + this.app.getParameter("SSH_USER"));
		} else {
			List<String> ids = cloudML.getRunningInstancesIds(app.tierName);
			
			for (int i = 1; i < ids.size(); ++i)
				Local.exec(String.format("bash %s %s %s %s", Configuration.getPathToFile(app.stopContainerMonitoringFile), Instance.getIp(ids.get(i)), Paths.get(localPath, app.name + i++), Configuration.getPathToFile(this.app.getParameter("KEYPAIR_NAME") + ".pem")));
			
			VirtualMachine.retrieveFiles(ids, this.app, localPath, "/home/" + this.app.getParameter("SSH_USER"));
		}
		mpl.retrieveFiles(localPath, "/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath, "/home/" + clients.getParameter("SSH_USER"));
		if (useOwnLoadBalancer)
			VirtualMachine.retrieveFiles(loadBalancer, lb, 1, localPath, "/home/" + lb.getParameter("SSH_USER"));
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
