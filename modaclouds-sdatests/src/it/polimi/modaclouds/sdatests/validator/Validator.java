package it.polimi.modaclouds.sdatests.validator;

import it.polimi.modaclouds.sdatests.validator.util.FileFormatConverter;

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

public class Validator {
	
	private static final Logger logger = LoggerFactory.getLogger(Validator.class);

	public static void perform(Path parent) {
		if (parent == null || !parent.toFile().exists())
			throw new RuntimeException("Parent folder not found! (" + parent.toString() + ")");
		
		try {
			logger.info("Converting the results from JSON to CSV...");
			String[] files = new String[] {
					"cpu.out", "cpuSteal.out", "d.out", "rt.out", "thresholds.out", "wl.out",
					"wlforFifth.out", "wlforFirst.out", "wlforFourth.out", "wlforSecond.out", "wlforThird.out"
					};
			for (String f : files) {
				try {
					FileFormatConverter.rewriteJsonAsCsv(Paths.get(parent.toString(), f), FileFormatConverter.DataType.TOWER_JSON);
				} catch (Exception e) {
					logger.error("Error while converting the file.", e);
				}
			}
			
			logger.info("Launching the initializing script...");
			exec(String.format(INIT_COMMAND, createModifiedBash(parent).toString()));
			
//			TODO: fix the demand validator with the new sda.out format
//			logger.info("Launching the DemandValidator class...");
//			DemandValidator.perform(parent);
			
			String[] methods = new String[] { "reg", "save", "answ" };
			
			for (int i = 1; i <= methods.length; ++i) {
				logger.info("Launching the WorkloadCSVBuilder for {} method...", methods[i-1]);
				WorkloadCSVBuilder.perform(Paths.get(parent.toString(), "method" + i));
			}
			
			logger.info("Generating the full results file...");
			ResultsBuilder.perform(parent, methods);
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