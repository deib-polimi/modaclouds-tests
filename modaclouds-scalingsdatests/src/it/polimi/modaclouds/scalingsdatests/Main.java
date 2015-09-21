package it.polimi.modaclouds.scalingsdatests;

import it.cloud.Configuration;
import it.polimi.modaclouds.scalingsdatests.sdavalidator.Validator;

import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {

	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	@Parameter(names = { "-h", "--help", "-help" }, help = true, description = "Shows this help")
	private boolean help = false;

	@Parameter(names = "-size", description = "The size that will be used for all the machines")
	private String size = null;

	@Parameter(names = "-clients", description = "The number of JMeter clients to be used")
	private int clients = 1;

	@Parameter(names = "-servers", description = "The number of servers to be used")
	private int servers = 1;

	@Parameter(names = "-data", description = "The file with the workload")
	private String data = null;

	@Parameter(names = "-batch", description = "Go in batch mode, reading a file describing a number of tests as parameter")
	private String batch = null;

	@Parameter(names = "-useDatabase", description = "Either if we need to use an external database or not")
	private boolean useDatabase = false;

	@Parameter(names = "-useOnDemand", description = "Use OnDemand instances instead of OnSpot")
	private boolean useOnDemand = false;

	@Parameter(names = "-reuseInstances", description = "Reuse running instances")
	private boolean reuseInstances = false;

	@Parameter(names = "-leaveInstancesOn", description = "Leave the instances running")
	private boolean leaveInstancesOn = false;

	@Parameter(names = "-background", description = "Run in background", hidden = true)
	private boolean background = false;

	@Parameter(names = "-onlyStartMachines", description = "Starts the machines and quit", hidden = true)
	private boolean onlyStartMachines = false;

	@Parameter(names = "-wait", description = "Stalls the execution of the given milliseconds without starting any test at all (used only in batch)", hidden = true)
	private int wait = -1;

	@Parameter(names = "-loadModelFile", description = "The path to the load model file")
	private String loadModelFile = null;

	@Parameter(names = "-skip", description = "The number of inital instances that will be skipped")
	private int firstInstancesToSkip = 0;

	@Parameter(names = "-app", description = "The name of the app that is going to be used for the test")
	private String app = Test.App.DEFAULT.name;

	@Parameter(names = "-demandEstimator", description = "The name of the method that will estimate the demands")
	private String demandEstimator = Test.DemandEstimator.DEFAULT.name;

	@Parameter(names = "-window", description = "The size in seconds of the window of the monitoring rules")
	private int window = Validator.DEFAULT_WINDOW;
	
	public static final double DEFAULT_HIGH_CPU = 0.6;
	public static final double DEFAULT_LOW_CPU = 0.1;

	@Parameter(names = "-highCpu", description = "The upper bound for the CPU utilization")
	private double highCpu = DEFAULT_HIGH_CPU;

	@Parameter(names = "-lowCpu", description = "The lower bound for the CPU utilization")
	private double lowCpu = DEFAULT_LOW_CPU;

	@Parameter(names = "-cooldown", description = "The cooldown period that the monitoring rule will stay off once it's triggered")
	private int cooldown = 600;

	@Parameter(names = "-useSDA", description = "Use the SDA")
	private boolean useSDA = false;

	@Parameter(names = "-useCloudML", description = "Use CloudML and the auto scaling rules")
	private boolean useCloudML = false;
	
	@Parameter(names = "-loadBalancerIp", description = "The IP of the load balancer that will be used")
	private String loadBalancerIp = null;
	
	@Parameter(names = "-useAutoscalingReasoner", description = "Use the AutoscalingReasoner")
	private boolean useAutoscalingReasoner = false;
	
	@Parameter(names = "-sshHost", description = "The host where there's AMPL")
	private String sshHost = null;
	
	@Parameter(names = "-sshUsername", description = "The username for the host where there's AMPL")
	private String sshUsername = null;
	
	@Parameter(names = "-sshPassword", description = "The password for the user on the host where there's AMPL")
	private String sshPassword = null;

	public static final String APP_TITLE = "\nScaling SDA Test\n";

	static {
		// Optionally remove existing handlers attached to j.u.l root logger
		SLF4JBridgeHandler.removeHandlersForRootLogger();  // (since SLF4J 1.6.5)

		// add SLF4JBridgeHandler to j.u.l's root logger, should be done once during
		// the initialization phase of your application
		SLF4JBridgeHandler.install();
	}

	public static void main(String[] args) {
//		args = "-clients 1 -size m3.large -data tests.txt -useOnDemand".split(" ");
//		args = "-clients 2 -size m3.large -data /Users/ft/Lavoro/tmp/tests/ramp2h.txt -app httpagent -demandEstimator ERPS".split(" ");
//		args = "-clients 2 -size m3.large -data /Users/ft/Lavoro/tmp/tests/ramp3h.txt -app httpagent -window 180 -useSDA -useOnDemand -reuseInstances -demandEstimator ERPS".split(" ");

		Main m = new Main();
		JCommander jc = new JCommander(m, args);

		System.out.println(APP_TITLE);

		if (m.help) {
			jc.usage();
			System.exit(0);
		}

		if (m.batch == null && m.data == null) {
			logger.error("You need to provide a data or batch file!");
			System.exit(-1);
		} else if (m.batch == null) {
			Test.App a = Test.App.getFromName(m.app);
			Test.DemandEstimator d = Test.DemandEstimator.getFromName(m.demandEstimator);

			doTest(m.size, m.clients, m.servers, a, m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.loadModelFile != null ? m.loadModelFile : a.fileModel, m.firstInstancesToSkip, d.name, m.window, m.useSDA, m.useCloudML, m.highCpu, m.lowCpu, m.cooldown, m.loadBalancerIp, m.useAutoscalingReasoner, m.sshHost, m.sshUsername, m.sshPassword);
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

					Test.App a = Test.App.getFromName(m.app);
					Test.DemandEstimator d = Test.DemandEstimator.getFromName(m.demandEstimator);

					if (m.background)
						threads.add(doTestInBackground(m.size, m.clients, m.servers, a, m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.loadModelFile != null ? m.loadModelFile : a.fileModel, m.firstInstancesToSkip, d.name, m.window, m.useSDA, m.useCloudML, m.highCpu, m.lowCpu, m.cooldown, m.loadBalancerIp, m.useAutoscalingReasoner, m.sshHost, m.sshUsername, m.sshPassword));
					else
						doTest(m.size, m.clients, m.servers, a, m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.loadModelFile != null ? m.loadModelFile : a.fileModel, m.firstInstancesToSkip, d.name, m.window, m.useSDA, m.useCloudML, m.highCpu, m.lowCpu, m.cooldown, m.loadBalancerIp, m.useAutoscalingReasoner, m.sshHost, m.sshUsername, m.sshPassword);
				}

				for (Thread t : threads)
					t.join();

				logger.error("Jobs ended!");
			} catch (Exception e) {
				logger.error("Error while reading the file!", e);
				System.exit(-1);
			}
		}

		logger.info("Goodbye!");
		System.exit(0);

	}

	public static void doTest(String size, int clients, int servers, Test.App app, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String loadModelFile, int firstInstancesToSkip, String demandEstimator, int window,
			boolean useSDA, boolean useCloudML, double highCpu, double lowCpu, int cooldown, String loadBalancerIp, boolean useAutoscalingReasoner, String sshHost, String sshUsername, String sshPassword) {
		logger.info("Preparing the system and running the test...");

		try {
			long duration = Test.performTest(size, clients, servers, app, Configuration.getPathToFile(data).toString(), useDatabase, startAsOnDemand, reuseInstances, leaveInstancesOn, onlyStartMachines, loadModelFile != null ? Configuration.getPathToFile(loadModelFile).toString() : null, firstInstancesToSkip, demandEstimator, window, useSDA, useCloudML, highCpu, lowCpu, cooldown, loadBalancerIp, useAutoscalingReasoner, sshHost, sshUsername, sshPassword);

			if (duration == Test.ERROR_STATUS_NULL)
				logger.error("There was the status == null problem...");
			else if (duration > -1)
				logger.info("The test run correctly in {}!", durationToString(duration));
			else
				logger.info("The test run correctly!");
		} catch (Exception e) {
			logger.error("There were some problems during the test! :(", e);
		}

	}

	public static Thread doTestInBackground(String size, int clients, int servers, Test.App app, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String loadModelFile, int firstInstancesToSkip, String demandEstimator, int window,
			boolean useSDA, boolean useCloudML, double highCpu, double lowCpu, int cooldown, String loadBalancerIp, boolean useAutoscalingReasoner, String sshHost, String sshUsername, String sshPassword) {
		final String fsize = size;
		final int fclients = clients;
		final String fdata = data;
		final boolean fuseDatabase = useDatabase;
		final boolean fstartAsOnDemand = startAsOnDemand;
		final boolean freuseInstances = reuseInstances;
		final boolean fleaveInstancesOn = leaveInstancesOn;
		final boolean fonlyStartMachines = onlyStartMachines;
		final int fservers = servers;
		final String floadModelFile = loadModelFile;
		final int ffirstInstancesToSkip = firstInstancesToSkip;
		final Test.App fapp = app;
		final String fdemandEstimator = demandEstimator;
		final int fwindow = window;
		final boolean fuseSDA = useSDA;
		final boolean fuseCloudML = useCloudML;
		final double fhighCpu = highCpu;
		final double flowCpu = lowCpu;
		final int fcooldown = cooldown;
		final String floadBalancerIp = loadBalancerIp;
		final boolean fuseAutoscalingReasoner = useAutoscalingReasoner;
		final String fsshHost = sshHost;
		final String fsshUsername = sshUsername;
		final String fsshPassword = sshPassword;

		Thread t = new Thread() {
			public void run() {
				doTest(fsize, fclients, fservers, fapp, fdata, fuseDatabase, fstartAsOnDemand, freuseInstances, fleaveInstancesOn, fonlyStartMachines, floadModelFile, ffirstInstancesToSkip, fdemandEstimator, fwindow, fuseSDA, fuseCloudML, fhighCpu, flowCpu, fcooldown, floadBalancerIp, fuseAutoscalingReasoner, fsshHost, fsshUsername, fsshPassword);
			}
		};

		t.start();
		return t;
	}

	public static String durationToString(long duration) {
		String actualDuration = "";
		{
			int res = (int) TimeUnit.MILLISECONDS.toSeconds(duration);
			if (res > 60 * 60) {
				actualDuration += (res / (60 * 60)) + " h ";
				res = res % (60 * 60);
			}
			if (res > 60) {
				actualDuration += (res / 60) + " m ";
				res = res % 60;
			}
			actualDuration += res + " s";
		}


		return actualDuration;
	}

}
