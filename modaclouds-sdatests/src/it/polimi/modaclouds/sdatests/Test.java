package it.polimi.modaclouds.sdatests;

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
import it.polimi.modaclouds.sdatests.validator.Validator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.lang.RandomStringUtils;
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
	
	private boolean running;
	private boolean initialized;
	
	static {
		VirtualMachine.PRICE_MARGIN = 0.35;
	}
	
	public static enum App {
		MIC("mic", "MPloadModel-MiC", "jmeterTestTemplate-MiC.jmx", "startContainerMonitoring-MiC.sh", "stopContainerMonitoring-MiC.sh", "grep_methodResult-MiC", new String[] { "reg", "save", "answ" }),
		HTTPAGENT("httpagent", "MPloadModel-HTTPAgent", "jmeterTestTemplate-HTTPAgent.jmx", null, "stopContainerMonitoring-HTTPAgent.sh", "grep_methodResult-HTTPAgent", new String[] { "getPage" });
		
		public String name;
		public String fileModel;
		public String baseJmx;
		public String startContainerMonitoringFile;
		public String stopContainerMonitoringFile;
		public String grepMethodResult;
		public String[] methods;
		
		private App(String name, String fileModel, String baseJmx, String startContainerMonitoringFile, String stopContainerMonitoringFile, String grepMethodResult, String[] methods) {
			this.name = name;
			this.fileModel = fileModel;
			this.baseJmx = baseJmx;
			this.startContainerMonitoringFile = startContainerMonitoringFile;
			this.stopContainerMonitoringFile = stopContainerMonitoringFile;
			this.grepMethodResult = grepMethodResult;
			this.methods = methods;
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
	
	public static long performTest(String size, int clients, int servers, App app, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String loadModelFile, int firstInstancesToSkip, String demandEstimator, int sdaWindow) throws Exception {
		String baseJmx = app.getBaseJmxPath().toString();
		
		if (baseJmx == null || !new File(baseJmx).exists())
			throw new RuntimeException("The provided base JMX file (" + baseJmx.toString() + ") doesn't exist!");
		if (data == null || !new File(data).exists())
			throw new RuntimeException("The provided data file (" + data + ") doesn't exist!");
		if (loadModelFile != null && !new File(loadModelFile).exists())
			loadModelFile = null;
		
		if (sdaWindow <= 0)
			sdaWindow = 300;
		
		long init = System.currentTimeMillis();
		
		Test t = new Test(size, clients, servers, app.name, useDatabase);
		
		if (reuseInstances)
			t.considerRunningMachines();
		t.startMachines(startAsOnDemand);
		
		t.createLoadBalancer();
		
		t.initSystem(loadModelFile, demandEstimator, sdaWindow);
		
		if (onlyStartMachines)
			return -1;
		
		Path path = null;
		try {
			path = t.runTest(app, data, demandEstimator);
		} catch (Exception e) {
			logger.error("Error while performing the test.", e);
		}
		
		t.destroyLoadBalancer();
		
		if (!leaveInstancesOn)
			t.stopMachines();
		
		Validator.perform(path, t.getCores(), firstInstancesToSkip, app, sdaWindow);
		
		return System.currentTimeMillis() - init;
	}
	
	public Test(String size, int clients, int servers, String app, boolean useDatabase) throws CloudException {
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
		
		otherThreads = new ArrayList<Thread>();
	}
	
	private String loadBalancer;

	private void createLoadBalancer() {
		if (app.getInstancesNeeded() <= 1)
			return;
		
		if (loadBalancer != null)
			throw new RuntimeException(
					"You've already created a load balancer!");

		loadBalancer = "SDATests" + RandomStringUtils.randomNumeric(3);

		ElasticLoadBalancing.createNewLoadBalancer(loadBalancer, new Listener("HTTP", Integer.parseInt(app.getParameter("PORT"))));
	}

	private void destroyLoadBalancer() {
		if (app.getInstancesNeeded() <= 1)
			return;
		
		if (loadBalancer == null)
			throw new RuntimeException(
					"You've still to create a load balancer!");

		ElasticLoadBalancing.deleteLoadBalancer(loadBalancer);

		loadBalancer = null;
	}
	
	public void startMachines(boolean startAsOnDemand) {
		if (running)
			throw new RuntimeException("The system is already running!");
		
		logger.info("Starting all the machines...");
		
		if (!startAsOnDemand) {
			mpl.spotRequest();
			app.spotRequest();
			clients.spotRequest();
			if (useDatabase)
				database.spotRequest();
		} else {
			mpl.onDemandRequest();
			app.onDemandRequest();
			clients.onDemandRequest();
			if (useDatabase)
				database.onDemandRequest();
		}
		
		mpl.waitUntilRunning();
		app.waitUntilRunning();
		clients.waitUntilRunning();
		if (useDatabase)
			database.waitUntilRunning();
		
		mpl.setNameToInstances(mpl.getParameter("NAME"));
		app.setNameToInstances(app.getParameter("NAME"));
		clients.setNameToInstances(clients.getParameter("NAME"));
		if (useDatabase)
			database.setNameToInstances(database.getParameter("NAME"));
		
		mpl.getInstances().get(0).waitUntilSshAvailable();
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
		ec2.addRunningInstances(app);
		ec2.addRunningInstances(clients);
		if (useDatabase)
			ec2.addRunningInstances(database);
		
		mpl.reboot();
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
		app.terminate();
		clients.terminate();
		if (useDatabase)
			database.terminate();
		
		logger.info("All the machines have been shutted down!");
		
		running = false;
		initialized = false;
	}
	
	private List<Thread> otherThreads;
	
	public void initSystem(String loadModelFile, String demandEstimator, int sdaWindow) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		if (initialized)
			throw new RuntimeException("The system is already initialized!");
		
		logger.info("Deleting the files set for removal before the test...");
		
		app.deleteFiles();
		mpl.deleteFiles();
		clients.deleteFiles();
		if (useDatabase)
			database.deleteFiles();
		
		logger.info("Initializing the system...");
		
		Instance impl = mpl.getInstances().get(0);
		
		impl.exec(String.format(mpl.getParameter("STARTER"), impl.getIp()));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		int cores = getCores();
		
		exec(String.format("bash %s %s %s %d %s %d",
				loadModelFile,
				impl.getIp(),
				impl.getIp(),
				cores,
				demandEstimator,
				sdaWindow));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		otherThreads.add(Ssh.execInBackground(impl, String.format(
				mpl.getParameter("SDA_STARTER"),
				impl.getIp())));
			
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		for (Instance iapp : app.getInstances()) {
			iapp.exec(String.format(
					app.getParameter("STARTER0"),
					useDatabase ? database.getIps().get(0) : "127.0.0.1",
					impl.getIp()
					));
			
			try { Thread.sleep(10000); } catch (Exception e) { }
			
			iapp.exec(app.getParameter("STARTER1"));
			
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
	
	public Path runTest(App app, String data, String method) throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		if (!initialized)
			throw new RuntimeException("The system isn't initialized yet!");
		
		Date date = new Date();
		String now = String.format("%1$td%1$tm%1$ty%1$tH%1$tM-%2$s-%3$dx%4$d-%5$s-%6$s", date, clients.getSize(), getPeakFromData(data) / clients.getInstancesRunning(), clients.getInstancesRunning(), app.name, method);
		
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
		
		String server = this.app.getInstancesNeeded() > 1 && loadBalancer != null ? ElasticLoadBalancing.getLoadBalancerDNS(loadBalancer) : this.app.getIps().get(0); 
		
		JMeterTest test = new JMeterTest(clients.getParameter("AMI"), clients.getInstancesRunning(), localPath, remotePath, clients.getParameter("JMETER_PATH"), data,
				server,
				protocol,
				port);
		
		RunInstance run = test.createModifiedFile(app.getBaseJmxPath());
		
		String javaParameters = clients.getParameter("JAVA_PARAMETERS");
		if (javaParameters != null && (javaParameters.trim().length() == 0 || !javaParameters.startsWith("-")))
			javaParameters = null;
		JMeterTest.javaParameters = javaParameters;
		
		if (app.startContainerMonitoringFile != null)
			for (Instance iapp : this.app.getInstances())
				exec(String.format("bash " + Configuration.getPathToFile(app.startContainerMonitoringFile) + " %s", iapp.getIp()));
		
		logger.info("Test starting...");
		
		test.performTest(clients, run);
		
		logger.info("Test ended!");
		
		logger.info("Retrieving the files from the instances...");
		
		if (app.stopContainerMonitoringFile != null) {
			int i = 1;
			for (Instance iapp : this.app.getInstances())
				exec(String.format("bash " + Configuration.getPathToFile(app.stopContainerMonitoringFile) + " %s %s %s", iapp.getIp(), Paths.get(localPath, app.name + i++), Configuration.getPathToFile(this.app.getParameter("KEYPAIR_NAME") + ".pem")));
		}
		this.app.retrieveFiles(localPath, "/home/" + this.app.getParameter("SSH_USER"));
		mpl.retrieveFiles(localPath, "/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath, "/home/" + clients.getParameter("SSH_USER"));
		if (useDatabase)
			database.retrieveFiles(localPath, "/home/" + database.getParameter("SSH_USER"));
		
		logger.info("Retrieving the data from the metrics...");
		
		int period = getSuggestedPeriod(date);
		
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
