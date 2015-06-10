package it.polimi.modaclouds.sdatests;

import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.Configuration;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.amazon.elb.ElasticLoadBalancing;
import it.cloud.amazon.elb.ElasticLoadBalancing.Listener;
import it.cloud.utils.CloudException;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.cloud.utils.Ssh;

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

public class Test {
	
	private static final Logger logger = LoggerFactory.getLogger(Test.class);
	
	private VirtualMachine mpl;
	private VirtualMachine mic;
	private VirtualMachine clients;
	private VirtualMachine database;
	
	private boolean useDatabase;
	private boolean noSDA;
	private boolean healthCheck;
	
	private boolean running;
	private boolean initialized;
	
	static {
		VirtualMachine.PRICE_MARGIN = 0.35;
	}
	
	public static long performTest(String size, int clients, int servers, Path baseJmx, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, boolean noSDA, boolean healthCheck, String validator) throws Exception {
		long init = System.currentTimeMillis();
		
		Test t = new Test(size, clients, servers, useDatabase, noSDA, healthCheck);
		
		if (reuseInstances)
			t.considerRunningMachines();
		t.startMachines(startAsOnDemand);
		
		t.createLoadBalancer();
		
		t.initSystem();
		
		if (onlyStartMachines)
			return -1;
		
		Path path = t.runTest(baseJmx, data);
		
		t.destroyLoadBalancer();
		
		if (!leaveInstancesOn)
			t.stopMachines();
		
		if (!noSDA)
			t.execValidator(validator, path);
		else
			Data2StdoutParser.perform(path);
		
		return System.currentTimeMillis() - init;
	}

	public Test(String size, int clients, int servers, boolean useDatabase, boolean noSDA, boolean healthCheck) throws CloudException {
		if (clients <= 0)
			throw new RuntimeException("You need at least 1 client!");
		
		if (servers <= 0)
			throw new RuntimeException("You need at least 1 server!");
		
		if (healthCheck)
			noSDA = true;
		this.noSDA = noSDA;
		this.healthCheck = healthCheck;
		
		mpl = VirtualMachine.getVM("mpl", size, 1);
		mic = VirtualMachine.getVM("mic", size, servers);
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
		if (mic.getInstancesNeeded() <= 1)
			return;
		
		if (loadBalancer != null)
			throw new RuntimeException(
					"You've already created a load balancer!");

		loadBalancer = "SDATests" + RandomStringUtils.randomNumeric(3);

		ElasticLoadBalancing.createNewLoadBalancer(loadBalancer, new Listener("HTTP", Integer.parseInt(mic.getParameter("PORT"))));
	}

	private void destroyLoadBalancer() {
		if (mic.getInstancesNeeded() <= 1)
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
			mic.spotRequest();
			clients.spotRequest();
			if (useDatabase)
				database.spotRequest();
		} else {
			mpl.onDemandRequest();
			mic.onDemandRequest();
			clients.onDemandRequest();
			if (useDatabase)
				database.onDemandRequest();
		}
		
		mpl.waitUntilRunning();
		mic.waitUntilRunning();
		clients.waitUntilRunning();
		if (useDatabase)
			database.waitUntilRunning();
		
		mpl.setNameToInstances("MPL");
		mic.setNameToInstances("MiC");
		clients.setNameToInstances("JMeter");
		
		mpl.getInstances().get(0).waitUntilSshAvailable();
		mic.getInstances().get(0).waitUntilSshAvailable();
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
		ec2.addRunningInstances(mic);
		ec2.addRunningInstances(clients);
		if (useDatabase)
			ec2.addRunningInstances(database);
		
		mpl.reboot();
		mic.reboot();
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
		mic.terminate();
		clients.terminate();
		if (useDatabase)
			database.terminate();
		
		logger.info("All the machines have been shutted down!");
		
		running = false;
		initialized = false;
	}
	
	public static final String LOAD_MODEL_FILE = "MPloadModel";
	
	public static String LOAD_MODEL_COMMAND = "bash " + Configuration.getPathToFile(LOAD_MODEL_FILE) + " %s %s";
	public static String LOAD_MODEL_COMMAND_NOSDA = "bash " + Configuration.getPathToFile(LOAD_MODEL_FILE + "-noSDA") + " %s";
	public static String LOAD_MODEL_COMMAND_HEALTHCHECK = "bash " + Configuration.getPathToFile(LOAD_MODEL_FILE + "-healthCheck") + " %s";
	
	public static final String SDA_CONFIG = "sdaconfig.properties";
	
	private List<Thread> otherThreads;
	
	public void initSystem() throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		if (initialized)
			throw new RuntimeException("The system is already initialized!");
		
		logger.info("Deleting the files set for removal before the test...");
		
		mic.deleteFiles();
		mpl.deleteFiles();
		clients.deleteFiles();
		if (useDatabase)
			database.deleteFiles();
		
		logger.info("Initializing the system...");
		
		Instance impl = mpl.getInstances().get(0);
		
		impl.exec(String.format(mpl.getParameter("STARTER"), impl.getIp()));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		if (!noSDA) {
			exec(String.format(
					LOAD_MODEL_COMMAND,
					impl.getIp(),
					impl.getIp()));
		} else {
			Ssh.execInBackground(impl, 
					mpl.getParameter("DATA2STDOUT_STARTER"));
			
			try { Thread.sleep(5000); } catch (Exception e) { }
			
			if (!healthCheck)
				exec(String.format(
						LOAD_MODEL_COMMAND_NOSDA,
						impl.getIp()));
			else
				exec(String.format(
						LOAD_MODEL_COMMAND_HEALTHCHECK,
						impl.getIp()));
		}
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		if (!noSDA) {
			otherThreads.add(Ssh.execInBackground(impl, String.format(
					mpl.getParameter("SDA_STARTER"),
					impl.getIp())));
			
			try { Thread.sleep(10000); } catch (Exception e) { }
		}
		
		for (Instance imic : mic.getInstances()) {
			imic.exec(String.format(
					mic.getParameter("STARTER0"),
					useDatabase ? database.getIps().get(0) : "127.0.0.1",
					impl.getIp()
					));
			
			try { Thread.sleep(10000); } catch (Exception e) { }
			
			imic.exec(mic.getParameter("STARTER1"));
			
			try { Thread.sleep(10000); } catch (Exception e) { }
			
			if (mic.getInstancesNeeded() > 1 && loadBalancer != null) {
				imic.exec(String.format(mic.getParameter("ADD_TO_LOAD_BALANCER"), loadBalancer));
				
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
	
	public Path runTest(Path baseJmx, String data) throws Exception {
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
		
		String protocol = mic.getParameter("PROTOCOL");
		if (protocol == null)
			protocol = "http";
		
		String port = mic.getParameter("PORT");
		if (port == null)
			port = "8080";
		
		String server = mic.getInstancesNeeded() > 1 && loadBalancer != null ? ElasticLoadBalancing.getLoadBalancerDNS(loadBalancer) : mic.getIps().get(0); 
		
		JMeterTest test = new JMeterTest(clients.getParameter("AMI"), clients.getInstancesRunning(), localPath, remotePath, clients.getParameter("JMETER_PATH"), data,
				server,
				protocol,
				port);
		
		RunInstance run = test.createModifiedFile(baseJmx);
		
		logger.info("Test starting...");
		
		test.performTest(clients, run);
		
		logger.info("Test ended!");
		
		logger.info("Retrieving the files from the instances...");
		
		mic.retrieveFiles(localPath, "/home/" + mic.getParameter("SSH_USER"));
		mpl.retrieveFiles(localPath, "/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath, "/home/" + clients.getParameter("SSH_USER"));
		if (useDatabase)
			database.retrieveFiles(localPath, "/home/" + database.getParameter("SSH_USER"));
		
		logger.info("Done!");
		
		return Paths.get(localPath, "mpl1", "home", mpl.getParameter("SSH_USER"));
	}
	
	public static final String EXEC_VALIDATOR = "bash %s -parent %s";
	
	public void execValidator(String validator, Path path) throws Exception {
		if (path != null && validator != null && new File(validator).exists()) {
			logger.info("Launching the validator...");
			exec(String.format(EXEC_VALIDATOR, validator, path.toString()));
		}
	}
	
	public int getTotalCountResponseTime(Path path) {
		try {
			Data2StdoutParser parser = new Data2StdoutParser(path, Data2StdoutParser.DataType.TOWER_JSON);
			return (int)parser.getTotalPerMetric("CountResponseTime");
		} catch (Exception e) {
			logger.error("Error while parsing the output.", e);
		}
		
		return 0;
	}
	
	public static void main(String[] args) throws Exception {
		VirtualMachine tmp = VirtualMachine.getVM("mpl", "m3.large", 1);
		
		Ssh.exec("54.154.106.241", tmp, "cd /home/ubuntu/modaclouds-sda && source ~/.bashrc && sudo -E bash run_main.sh /usr/local/MATLAB/MATLAB_Compiler_Runtime/v81 tower4clouds > /home/ubuntu/sda.out 2>&1 &");
	}

}
