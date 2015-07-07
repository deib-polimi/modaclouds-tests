package it.polimi.modaclouds.sdatests.validator;

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
	
	public static final String APP_TITLE = "\nSDA Validator\n";

	public static void main(String[] args) {
//		args = new String[] { "-parent", "/Users/ft/Lavoro/tmp/sdatests-0.0.15/tests/0307151705-m3.large-800x2-UBR/mpl1/home/ubuntu", "-cores", "2", "-skip", "12" };
		
		Main m = new Main();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		Validator.perform(Paths.get(m.parent), m.cores, m.firstInstancesToSkip);
	}

}
