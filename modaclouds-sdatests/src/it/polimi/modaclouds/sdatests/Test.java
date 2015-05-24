package it.polimi.modaclouds.sdatests;

import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.Configuration;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.utils.CloudException;
import it.cloud.utils.JMeterTest;
import it.cloud.utils.JMeterTest.RunInstance;
import it.cloud.utils.Ssh;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Scanner;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Test {
	
	private static final Logger logger = LoggerFactory.getLogger(Test.class);
	
	private VirtualMachine mpl;
	private VirtualMachine sda;
	private VirtualMachine mic;
	private VirtualMachine clients;
	private VirtualMachine database;
	
	boolean useDatabase;
	
	private boolean running;
	private boolean initialized;
	
	static {
		VirtualMachine.PRICE_MARGIN = 0.35;
	}
	
	public static boolean performTest(String size, int clients, Path baseJmx, String data, String loadBalancer, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines) {
		try {
			Test t = new Test(size, clients, useDatabase);
			if (reuseInstances)
				t.considerRunningMachines();
			t.startMachines(startAsOnDemand);
			if (onlyStartMachines)
				return true;
			t.initSystem();
			t.runTest(baseJmx, data, loadBalancer);
			if (!leaveInstancesOn)
				t.stopMachines();
			return true;
		} catch (Exception e) {
			logger.error("There were errors while running the test.", e);
			return false;
		}
	}

	public Test(String size, int clients, boolean useDatabase) throws CloudException {
		if (clients <= 0)
			throw new RuntimeException("You need at least 1 client!");
		
		mpl = VirtualMachine.getVM("mpl", size, 1);
		sda = VirtualMachine.getVM("sda", size, 1);
		mic = VirtualMachine.getVM("mic", size, 1);
		this.clients = VirtualMachine.getVM("client", size, clients);
		
		this.useDatabase = useDatabase;
		if (useDatabase)
			database = VirtualMachine.getVM("database", size, 1);
		
		running = false;
		initialized = false;
		
		otherThreads = new ArrayList<Thread>();
	}
	
	public void startMachines(boolean startAsOnDemand) {
		if (running)
			throw new RuntimeException("The system is already running!");
		
		logger.info("Starting all the machines...");
		
		if (!startAsOnDemand) {
			mpl.spotRequest();
			sda.spotRequest();
			mic.spotRequest();
			clients.spotRequest();
			if (useDatabase)
				database.spotRequest();
		} else {
			mpl.onDemandRequest();
			sda.onDemandRequest();
			mic.onDemandRequest();
			clients.onDemandRequest();
			if (useDatabase)
				database.onDemandRequest();
		}
		
		mpl.waitUntilRunning();
		sda.waitUntilRunning();
		mic.waitUntilRunning();
		clients.waitUntilRunning();
		if (useDatabase)
			database.waitUntilRunning();
		
		mpl.setNameToInstances("MPL");
		sda.setNameToInstances("SDA");
		mic.setNameToInstances("MiC");
		clients.setNameToInstances("JMeter");
		
		mpl.getInstances().get(0).waitUntilSshAvailable();
		sda.getInstances().get(0).waitUntilSshAvailable();
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
		ec2.addRunningInstances(sda);
		ec2.addRunningInstances(mic);
		ec2.addRunningInstances(clients);
		if (useDatabase)
			ec2.addRunningInstances(database);
		
		mpl.reboot();
		sda.reboot();
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
		sda.terminate();
		mic.terminate();
		clients.terminate();
		if (useDatabase)
			database.terminate();
		
		logger.info("All the machines have been shutted down!");
		
		running = false;
		initialized = false;
	}
	
	public static final String LOAD_MODEL_FILE = "MPloadModel";
	public static final String OBSERVER_LAUNCH_FILE= "observer_remote_launcher";
	
	public static String LOAD_MODEL_COMMAND = "bash " + Configuration.getPathToFile(LOAD_MODEL_FILE) + " %s %s";
	public static String OBSERVER_LAUNCH_COMMAND = "bash " + Configuration.getPathToFile(OBSERVER_LAUNCH_FILE) + " %s %s %s";
	
	public static final String SDA_CONFIG = "sdaconfig.properties";
	
	private List<Thread> otherThreads;
	
	public void initSystem() throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		if (initialized)
			throw new RuntimeException("The system is already initialized!");
		
		logger.info("Deleting the files set for removal before the test...");
		
		sda.deleteFiles();
		mic.deleteFiles();
		mpl.deleteFiles();
		clients.deleteFiles();
		if (useDatabase)
			database.deleteFiles();
		
		logger.info("Initializing the system...");
		
		Instance impl = mpl.getInstances().get(0);
		Instance isda = sda.getInstances().get(0);
		Instance imic = mic.getInstances().get(0);
		
		impl.exec(mpl.getParameter("STARTER"));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		exec(String.format(
				LOAD_MODEL_COMMAND,
				impl.getIp(),
				isda.getIp()));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		exec(String.format(
				OBSERVER_LAUNCH_COMMAND,
				isda.getIp(),
				Configuration.getPathToFile(sda.getParameter("KEYPAIR_NAME") + ".pem").toString(),
				sda.getParameter("SSH_USER")));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		isda.sendFile(createModifiedSDAConfigFile().toString(), "/home/ubuntu/modaclouds-sda-1.2.2/config.properties");
		
		otherThreads.add(Ssh.execInBackground(isda, String.format(
				sda.getParameter("STARTER"),
				impl.getIp())));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		imic.exec(String.format(
				mic.getParameter("STARTER0"),
				useDatabase ? database.getIps().get(0) : "127.0.0.1",
				impl.getIp()
				));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
		imic.exec(mic.getParameter("STARTER1"));
		
		try { Thread.sleep(10000); } catch (Exception e) { }
		
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
	
	private Path createModifiedSDAConfigFile() throws Exception {
		if (!running)
			throw new RuntimeException("The system isn't running yet!");
		
		Instance impl = mpl.getInstances().get(0);
		
		String file = FileUtils.readFileToString(Configuration.getPathToFile(SDA_CONFIG).toFile());
		Path p = Files.createTempFile("config", ".properties");
		FileUtils.writeStringToFile(p.toFile(), String.format(file, impl.getIp()));
		return p;
	}
	
	public void runTest(Path baseJmx, String data, String loadBalancerDNS) throws Exception {
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
		
		
		JMeterTest test = new JMeterTest(clients.getParameter("AMI"), clients.getInstancesRunning(), localPath, remotePath, clients.getParameter("JMETER_PATH"), data,
				loadBalancerDNS != null ? loadBalancerDNS : mic.getIps().get(0),
				protocol,
				port);
		
		RunInstance run = test.createModifiedFile(baseJmx);
		
		logger.info("Test starting...");
		
		test.performTest(clients, run);
		
		logger.info("Test ended!");
		
		logger.info("Retrieving the files from the instances...");
		
		sda.retrieveFiles(localPath, "/home/" + sda.getParameter("SSH_USER"));
		mic.retrieveFiles(localPath, "/home/" + mic.getParameter("SSH_USER"));
		mpl.retrieveFiles(localPath, "/home/" + mpl.getParameter("SSH_USER"));
		clients.retrieveFiles(localPath, "/home/" + clients.getParameter("SSH_USER"));
		if (useDatabase)
			database.retrieveFiles(localPath, "/home/" + database.getParameter("SSH_USER"));
		
		logger.info("Done!");
	}

}
