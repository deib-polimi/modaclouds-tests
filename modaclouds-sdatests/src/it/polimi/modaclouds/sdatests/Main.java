package it.polimi.modaclouds.sdatests;

import it.cloud.Configuration;

import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

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
	
	@Parameter(names = "-noSDA", description = "Don't use the SDAs for the test", hidden = true)
	private boolean noSDA = false;
	
	@Parameter(names = "-healthCheck", description = "Just do a health check", hidden = true)
	private boolean healthCheck = false;
	
	@Parameter(names = "-loadModelFile", description = "The path to the load model file")
	private String loadModelFile = null;
	
	public static final String APP_TITLE = "\nSDA Test\n";

	public static void main(String[] args) {
//		args = new String[] { "-clients", "1", "-size", "m3.large", "-data", "tests.txt", "-useOnDemand" }; //, "-healthCheck" , "-leaveInstancesOn" }; //"-reuseInstances" };
		
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
			doTest(m.size, m.clients, m.servers, m.baseJmx, m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.noSDA, m.healthCheck, m.loadModelFile);
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
						threads.add(doTestInBackground(m.size, m.clients, m.servers, m.baseJmx, m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.noSDA, m.healthCheck, m.loadModelFile));
					else
						doTest(m.size, m.clients, m.servers, m.baseJmx, m.data, m.useDatabase, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.noSDA, m.healthCheck, m.loadModelFile);
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
	
	public static void doTest(String size, int clients, int servers, String baseJmx, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, boolean noSDA, boolean healthCheck, String loadModelFile) {
		logger.info("Preparing the system and running the test...");
		
		try {
			long duration = Test.performTest(size, clients, servers, Configuration.getPathToFile(baseJmx).toString(), Configuration.getPathToFile(data).toString(), useDatabase, startAsOnDemand, reuseInstances, leaveInstancesOn, onlyStartMachines, noSDA, healthCheck, loadModelFile != null ? Configuration.getPathToFile(loadModelFile).toString() : null);
			
			if (duration > -1)
				logger.info("The test run correctly in {}!", durationToString(duration));
			else
				logger.info("The test run correctly!");
		} catch (Exception e) {
			logger.error("There were some problems during the test! :(", e);
		}
		
	}
	
	public static Thread doTestInBackground(String size, int clients, int servers, String baseJmx, String data, boolean useDatabase, boolean startAsOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, boolean noSDA, boolean healthCheck, String loadModelFile) {
		final String fsize = size;
		final int fclients = clients;
		final String fbaseJmx = baseJmx;
		final String fdata = data;
		final boolean fuseDatabase = useDatabase;
		final boolean fstartAsOnDemand = startAsOnDemand;
		final boolean freuseInstances = reuseInstances;
		final boolean fleaveInstancesOn = leaveInstancesOn;
		final boolean fonlyStartMachines = onlyStartMachines;
		final boolean fnoSDA = noSDA;
		final int fservers = servers;
		final boolean fhealthCheck = healthCheck;
		final String floadModelFile = loadModelFile;
		
		Thread t = new Thread() {
			public void run() {
				doTest(fsize, fclients, fservers, fbaseJmx, fdata, fuseDatabase, fstartAsOnDemand, freuseInstances, fleaveInstancesOn, fonlyStartMachines, fnoSDA, fhealthCheck, floadModelFile);
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
