package it.polimi.modaclouds.sdatests.validator;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {
	
	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;
	
	@Parameter(names = "-parent", description = "The parent folder", required = true)
	private String parent = null;
	
	public static final String APP_TITLE = "\nSDA Validator\n";

	public static void main(String[] args) {
		args = new String[] { "-parent", "/Users/ft/Lavoro/tmp/sdatests-0.0.11/tests/1806151551-m3.large-625x2/mpl1/home/ubuntu" };
		
		Main m = new Main();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		Path parent = Paths.get(m.parent);
		
		Validator.perform(parent);
	}

}
