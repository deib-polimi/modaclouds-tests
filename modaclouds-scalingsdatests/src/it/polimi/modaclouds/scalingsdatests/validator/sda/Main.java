package it.polimi.modaclouds.scalingsdatests.validator.sda;

import it.polimi.modaclouds.scalingsdatests.Test;

import java.nio.file.Paths;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {

	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;

	@Parameter(names = "-parent", description = "The parent folder", required = true)
	private String parent = null;

	@Parameter(names = "-cores", description = "The number of cores of the machines used", required = true)
	private int cores = 2;

	@Parameter(names = "-skip", description = "The number of inital instances that will be skipped")
	private int firstInstancesToSkip = Validator.FIRST_INSTANCES_TO_SKIP;

	@Parameter(names = "-app", description = "The name of the app that is going to be used for the test")
	private String app = it.polimi.modaclouds.scalingsdatests.Test.App.DEFAULT.name;

	@Parameter(names = "-window", description = "The size in seconds of the window of the monitoring rules")
	private int window = 300;

	public static final String APP_TITLE = "\nSDA Validator\n";

	public static void main(String[] args) {
//		args = "-parent /Users/ft/Lavoro/tmp/scalingsdatests-0.0.1/tests/1507151224-m3.large-800x2-mic-ERPS/mpl1/home/ubuntu -cores 2 -skip 12 -app mic -window 10".split(" ");

		Main m = new Main();
		JCommander jc = new JCommander(m, args);

		System.out.println(APP_TITLE);

		if (m.help) {
			jc.usage();
			System.exit(0);
		}

		Test.App app = Test.App.getFromName(m.app);

		Validator.perform(Paths.get(m.parent), m.cores, m.firstInstancesToSkip, app, m.window);
	}

}
