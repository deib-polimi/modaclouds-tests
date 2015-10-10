package it.polimi.modaclouds.scalingsdatests.validator.ar;

import java.nio.file.Paths;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {

	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;

	@Parameter(names = "-parent", description = "The parent folder", required = true)
	private String parent = null;
	
	@Parameter(names = "-window", description = "The size in seconds of the window of the monitoring rules")
	private int window = 10;
	
	@Parameter(names = "-createFromSingleLog", description = "If we should read the data from the single data2stdout.log file instead from the old multiple files")
	private boolean createFromSingleLog = false;
	
	@Parameter(names = "-cpuMarker", description = "The marker for the CPU utilization")
	private double cpuMarker = 0.5;
	
	@Parameter(names = "-rtMarker", description = "The marker for the response times")
	private double rtMarker = 1500;
	
	@Parameter(names = "-rtMaximum", description = "The maximum value for the response times to be considered")
	private double rtMaximum = 8000;

	public static final String APP_TITLE = "\nAR Validator\n";

	public static void main(String[] args) {
//		args = "-parent /Users/ft/Desktop/tmp/trash/mpl1/home/ubuntu -window 10 -createFromSingleLog -rtMaximum 1500 -rtMarker 560".split(" ");
//		args = "-parent /Users/ft/Desktop/tmp/trash/0610151235-m3.large-500x2-httpagent-CloudML-CPU/mpl1/home/ubuntu -window 180".split(" ");

		Main m = new Main();
		JCommander jc = new JCommander(m, args);

		System.out.println(APP_TITLE);

		if (m.help) {
			jc.usage();
			System.exit(0);
		}

		Validator.perform(Paths.get(m.parent), m.window, m.createFromSingleLog, m.cpuMarker, m.rtMarker, m.rtMaximum);
	}

}
