package it.polimi.modaclouds.cloudml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

public class Main {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory.getLogger(Main.class);

	@Parameter(names = { "-h", "--help", "-help" }, help = true)
	private boolean help;

	@Parameter(names = "-port", description = "The port of CloudML")
	private int cloudMLPort = CloudMLDaemon.DEFAULT_CLOUDML_PORT;

	@Parameter(names = "-stop", description = "Stops the server instead of starting it")
	private boolean stop = false;

	@Parameter(names = "-shell", description = "Starts the shell instead of the daemon")
	private boolean shell = false;

	public static final String APP_TITLE = "\nCloudML Daemon\n";

	static {
		System.setProperty("jsse.enableSNIExtension", "false");
		
		// Optionally remove existing handlers attached to j.u.l root logger
		SLF4JBridgeHandler.removeHandlersForRootLogger();  // (since SLF4J 1.6.5)

		// add SLF4JBridgeHandler to j.u.l's root logger, should be done once during
		// the initialization phase of your application
		SLF4JBridgeHandler.install();
	}

	public static void main(String[] args) {
//		args = "-shell".split(" ");

		Main m = new Main();
		JCommander jc = new JCommander(m, args);

		System.out.println(APP_TITLE);

		if (m.help) {
			jc.usage();
			System.exit(0);
		}

		if (m.shell) {
			CloudMLDaemon.shell();
		} else if (m.stop) {
			CloudMLDaemon.stop(m.cloudMLPort);
		} else {
			CloudMLDaemon.start(m.cloudMLPort);
		}
	}

}
