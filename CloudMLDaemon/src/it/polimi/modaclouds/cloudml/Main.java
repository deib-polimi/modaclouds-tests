package it.polimi.modaclouds.cloudml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {
	
	private static final Logger logger = LoggerFactory.getLogger(Main.class);
	
	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;
	
	public static final int DEFAULT_CLOUDML_PORT = 9000;
	
	@Parameter(names = "-port", description = "The port of CloudML")
	private int cloudMLPort = DEFAULT_CLOUDML_PORT;
	
	@Parameter(names = "-stop", description = "Stops the server instead of starting it")
	private boolean stop = false;
	
	public static final String APP_TITLE = "\nCloudML Daemon\n";
	
	public static void main(String[] args) {
		Main m = new Main();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		if (m.stop) {
			port = m.cloudMLPort;
			stop();
		} else
			start(m.cloudMLPort);
	}
	
	public static int port = -1;
	
	public static void start() {
		start(DEFAULT_CLOUDML_PORT);
	}

	public static void start(int port) {
		logger.info("Starting the CloudML daemon on port {}...", port);
		
		Main.port = port;
		org.cloudml.websocket.Daemon.main(new String[] { Integer.valueOf(port).toString() });
	}
	
	public static void stop() {
		if (port == -1)
			return;
		
		logger.info("Stopping the CloudML daemon on port {}...", port);
		
		try {
			int pid = -1;
			String command = null;
			
			List<String> res = exec("lsof -i :" + port);
			
			for (String s : res) {
				String[] columns = s.split(" +");
				try {
					pid = Integer.parseInt(columns[1]);
					command = columns[0];
				} catch (Exception e) { }
			}
			
			if (pid > -1 && command.equals("java")) {
				logger.info("PID found: {}, killing the process...", pid);
				exec("kill -9 " + pid);
			}
		} catch (Exception e) {
			logger.error("Error while stopping the process.", e);
		}
		
		port = -1;
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

}
