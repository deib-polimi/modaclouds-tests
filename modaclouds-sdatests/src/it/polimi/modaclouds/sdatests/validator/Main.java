package it.polimi.modaclouds.sdatests.validator;

import it.polimi.modaclouds.sdatests.Test;

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
	private String app = it.polimi.modaclouds.sdatests.Test.DEFAULT_APP.name;
	
	@Parameter(names = "-sdaWindow", description = "The size in seconds of the window of the SDA")
	private int sdaWindow = 300;
	
	public static final String APP_TITLE = "\nSDA Validator\n";

	public static void main(String[] args) {
//		args = "-parent /Users/ft/Lavoro/tmp/sdatests-0.0.17/tests/0907150040-m3.large-250x2-httpagent-ERPS/mpl1/home/ubuntu -cores 2 -skip 12 -app httpagent -sdaWindow 10".split(" ");
		
		Main m = new Main();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		Test.App app = Test.App.getFromName(m.app);
		
		Validator.perform(Paths.get(m.parent), m.cores, m.firstInstancesToSkip, app, m.sdaWindow);
	}

}
