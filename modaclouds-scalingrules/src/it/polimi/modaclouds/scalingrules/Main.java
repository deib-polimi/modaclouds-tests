package it.polimi.modaclouds.scalingrules;

import it.cloud.amazon.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {
	
	private static final Logger logger = LoggerFactory.getLogger(Main.class);
	
	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;
	
	@Parameter(names = "-useOnDemand", description = "Use OnDemand instances instead of OnSpot")
	private boolean useOnDemand = false;
	
	@Parameter(names = "-reuseInstances", description = "Reuse running instances")
	private boolean reuseInstances = false;
	
	@Parameter(names = "-leaveInstancesOn", description = "Leave the instances running")
	private boolean leaveInstancesOn = false;
	
	@Parameter(names = "-clients", description = "The number of JMeter clients to be used (default: 1)")
	private int clients = 1;
	
	@Parameter(names = "-data", description = "The file with the workload")
	private String data = null;
	
	@Parameter(names = "-batch", description = "Go in batch mode, reading a file describing a number of tests as parameter")
	private String batch = null;
	
	@Parameter(names = "-baseJmx", description = "The JMX that will be configured for the run")
	private String baseJmx = "jmeterTestTemplate.jmx";
	
	@Parameter(names = "-background", description = "Run in background", hidden = true)
	private boolean background = false;
	
	@Parameter(names = "-onlyStartMachines", description = "Starts the machines and quit", hidden = true)
	private boolean onlyStartMachines = false;
	
	@Parameter(names = "-wait", description = "Stalls the execution of the given milliseconds without starting any test at all (used only in batch)", hidden = true)
	private int wait = -1;
	
	@Parameter(names = "-size", description = "The size that will be used for all the machines")
	private String size = null;
	
	public static final String APP_TITLE = "\nScaling Rules Test\n";
	
	public static void main(String[] args) {
		args = new String[] { "-data", "tests.txt", "-useOnDemand", "-size", "m3.large", "-clients", "1", "-leaveInstancesOn" }; //, "-reuseInstances" };
		
		Main m = new Main();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		List<String> errors = Configuration.checkValidity();
		if (errors.size() > 0) {
			if (errors.size() == 1) {
				logger.error("There is 1 configuration error:\n- " + errors.get(0));
			} else {
				StringBuilder sb = new StringBuilder();
				sb.append("There are " + errors.size() + " configuration errors:");
				for (String error : errors)
					sb.append("\n- " + error);
				logger.error(sb.toString());
			}
			
			System.exit(-1);
		}
		
		if (m.batch == null && m.data == null) {
			logger.error("You need to provide a data or batch file!");
			System.exit(-1);
		} else if (m.batch == null) {
			doTest(m.clients, m.baseJmx, m.data, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.size);
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
						threads.add(doTestInBackground(m.clients, m.baseJmx, m.data, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.size));
					else
						doTest(m.clients, m.baseJmx, m.data, m.useOnDemand, m.reuseInstances, m.leaveInstancesOn, m.onlyStartMachines, m.size);
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
	
	public static void doTest(int clients, String baseJmx, String data, boolean useOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String size) {
		logger.info("Preparing the system and running the test...");
		
		try {
			long duration = Test.performTest(clients, Configuration.getPathToFile(baseJmx).toString(), Configuration.getPathToFile(data).toString(), useOnDemand, reuseInstances, leaveInstancesOn, onlyStartMachines, size);
		
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
	
	public static Thread doTestInBackground(int clients, String baseJmx, String data, boolean useOnDemand, boolean reuseInstances, boolean leaveInstancesOn, boolean onlyStartMachines, String size) {
		final int fclients = clients;
		final String fbaseJmx = baseJmx;
		final String fdata = data;
		final boolean fuseOnDemand = useOnDemand;
		final boolean freuseInstances = reuseInstances;
		final boolean fleaveInstancesOn = leaveInstancesOn;
		final boolean fonlyStartMachines = onlyStartMachines;
		final String fsize = size;
		
		Thread t = new Thread() {
			public void run() {
				doTest(fclients, fbaseJmx, fdata, fuseOnDemand, freuseInstances, fleaveInstancesOn, fonlyStartMachines, fsize);
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
