package it.polimi.modaclouds.monitoring.sdaValidator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {
	
	private static final Logger logger = LoggerFactory.getLogger(Main.class);
	
	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;
	
	@Parameter(names = "-parent", description = "The parent folder", required = true)
	private String parent = null;
	
	public static final String APP_TITLE = "\nSDA Validator\n";

	public static void main(String[] args) {
		Main m = new Main();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		Path parent = Paths.get(m.parent);
		if (parent == null || !parent.toFile().exists())
			throw new RuntimeException("Parent folder not found! (" + parent.toString() + ")");
		
		try {
			logger.info("Launching the initializing script...");
			exec(String.format(INIT_COMMAND, createModifiedBash(parent).toString()));
			
			logger.info("Launching the DemandValidator class...");
			DemandValidator.perform(parent);
			
			logger.info("Launching the WorkloadCSVBuilder for method1...");
			WorkloadCSVBuilder.perform(Paths.get(parent.toString(), "method1"));
			
			logger.info("Launching the WorkloadCSVBuilder for method2...");
			WorkloadCSVBuilder.perform(Paths.get(parent.toString(), "method2"));
			
			logger.info("Launching the WorkloadCSVBuilder for method3...");
			WorkloadCSVBuilder.perform(Paths.get(parent.toString(), "method3"));
		} catch (Exception e) {
			logger.error("Error while running the script.", e);
		}
		
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
	
	public static final String BASH = "grep_methodResult";
	public static final String INIT_COMMAND = "bash %s";
	
	public static Path getPathToFile(String filePath) {
		File f = new File(filePath);
		if (f.exists())
			try {
				return f.toPath();
			} catch (Exception e) { }
		
		URL url = Main.class.getResource(filePath);
		if (url == null)
			url = Main.class.getResource("/" + filePath);
		if (url == null)
			return null;
		else
			return Paths.get(url.getPath());
	}
	
	private static Path createModifiedBash(Path parent) throws Exception {
		String file = FileUtils.readFileToString(getPathToFile(BASH).toFile());
		Path p = Files.createTempFile("bash", ".sh");
		FileUtils.writeStringToFile(p.toFile(), String.format(file, parent.toString()));
		return p;
	}

}
