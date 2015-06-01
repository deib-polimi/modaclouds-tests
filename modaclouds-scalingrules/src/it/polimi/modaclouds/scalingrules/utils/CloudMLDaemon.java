package it.polimi.modaclouds.scalingrules.utils;

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
		
		start(it.polimi.modaclouds.scalingrules.Configuration.DEFAULT_CLOUDML_PORT);
	}

	public static void start(int port) {
		org.cloudml.websocket.Daemon.main(new String[] { Integer.valueOf(port).toString() });
	}

}
