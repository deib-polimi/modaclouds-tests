package it.polimi.modaclouds.sdatests;

import it.cloud.amazon.ec2.Configuration;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {
	
	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	@Parameter(names = { "-h", "--help" }, help = true)
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
	
	@Parameter(names = "-baseJmx", description = "The JMX that will be configured for the run")
	private String baseJmx = "jmeterTestTemplate.jmx";
	
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
	
	@Parameter(names = "-validator", description = "The path to the validator that will be called in the end")
	private String validator = null;
	
	@Parameter(names = "-noSDA", description = "Don't use the SDAs for the test", hidden = true)
	private boolean noSDA = false;
	
	public static final String APP_TITLE = "\nSDA Test\n";

	public static void main(String[] args) {
//		args = new String[] { "-clients", "1", "-size", "m3.large", "-data", "tests.txt", "-useOnDemand", "-noSDA"  }; //, "-leaveInstancesOn" }; //"-reuseInstances" };
		
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
			doTest(m.size, m.clients, m.servers, Paths.get(m.baseJmx), m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.validator, m.noSDA);
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
					
					if (m.background)
						threads.add(doTestInBackground(m.size, m.clients, m.servers, Paths.get(m.baseJmx), m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.validator, m.noSDA));
					else
						doTest(m.size, m.clients, m.servers, Paths.get(m.baseJmx), m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.validator, m.noSDA);
				}
				
				for (Thread t : threads)
					t.join();
				
				logger.error("Jobs ended!");
			} catch (Exception e) {
				logger.error("Error while reading the file!", e);
				System.exit(-1);
			}
		}
		
		System.exit(0);
		
	}
	
	public static void doTest(String size, int clients, int servers, Path baseJmx, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String validator, boolean noSDA) {
		logger.info("Preparing the system and running the test...");
		
		try {
			Path path = Test.performTest(size, clients, servers, baseJmx, data, useDatabase, startAsOnDemand, reuseInstances, leaveInstancesOn, onlyStartMachines, noSDA);
			
			logger.info("The test run correctly!");
			
			if (path != null && validator != null && new File(validator).exists()) {
				logger.info("Launching the validator...");
				exec(String.format(EXEC_VALIDATOR, validator, path.toString(), clients));
			}
		} catch (Exception e) {
			logger.error("There were some problems during the test! :(", e);
		}
		
	}
	
	public static Thread doTestInBackground(String size, int clients, int servers, Path baseJmx, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String validator, boolean noSDA) {
		final String fsize = size;
		final int fclients = clients;
		final Path fbaseJmx = baseJmx;
		final String fdata = data;
		final boolean fuseDatabase = useDatabase;
		final boolean fstartAsOnDemand = startAsOnDemand;
		final boolean freuseInstances = reuseInstances;
		final boolean fleaveInstancesOn = leaveInstancesOn;
		final boolean fonlyStartMachines = onlyStartMachines;
		final String fvalidator = validator;
		final boolean fnoSDA = noSDA;
		final int fservers = servers;
		
		Thread t = new Thread() {
			public void run() {
				doTest(fsize, fclients, fservers, fbaseJmx, fdata, fuseDatabase, fstartAsOnDemand, freuseInstances, fleaveInstancesOn, fonlyStartMachines, fvalidator, fnoSDA);
			}
		};
		
		t.start();
		return t;
	}
	
	public static final String EXEC_VALIDATOR = "bash %s -parent %s -clients %d";
	
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

}
