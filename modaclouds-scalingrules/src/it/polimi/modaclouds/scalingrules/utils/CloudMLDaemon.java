package it.polimi.modaclouds.scalingrules.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class CloudMLDaemon {
	
	private static final Logger logger = LoggerFactory.getLogger(CloudMLDaemon.class);
	
	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;
	
	@Parameter(names = "-cloudMLPort", description = "The port of CloudML")
	private int cloudMLPort = it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_PORT;
	
	public static final String APP_TITLE = "\nCloudML Daemon\n";
	
	public static void main(String[] args) {
		CloudMLDaemon m = new CloudMLDaemon();
		JCommander jc = new JCommander(m, args);
		
		System.out.println(APP_TITLE);
		
		if (m.help) {
			jc.usage();
			System.exit(0);
		}
		
		it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_PORT = m.cloudMLPort;
		
		logger.info("Starting the CloudML daemon on port {}...", it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_PORT);
		
		start(it.polimi.modaclouds.scalingrules.Configuration.CLOUDML_PORT);
	}
	
	public static int port = -1;
	
	public static void start() {
		start(it.polimi.modaclouds.scalingrules.Configuration.DEFAULT_CLOUDML_PORT);
	}

	public static void start(int port) {
		CloudMLDaemon.port = port;
		org.cloudml.websocket.Daemon.main(new String[] { Integer.valueOf(port).toString() });
	}
	
	public static void stop() {
		if (port == -1)
			return;
		
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
