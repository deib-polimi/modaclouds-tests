package it.polimi.modaclouds.cloudml;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudMLDaemon {
	
	private static final Logger logger = LoggerFactory.getLogger(CloudMLDaemon.class);
	
	public static final int DEFAULT_CLOUDML_PORT = 9030;
	
	public static int port = -1;
	
	static {
		System.setProperty("jsse.enableSNIExtension", "false");
	}

	public static void start() {
		start(DEFAULT_CLOUDML_PORT);
	}

	public static void start(int port) {
		logger.info("Starting the CloudML daemon on port {}...", port);

		CloudMLDaemon.port = port;
		org.cloudml.websocket.Daemon.main(new String[]{Integer.valueOf(port).toString()});
	}

	public static void shell() {
		logger.info("Starting the CloudML shell...");

		org.cloudml.ui.shell.Main.main("-i".split(" "));
	}

	public static void stop() {
		stop(port);
	}

	public static void stop(int port) {
		if (port == -1)
			return;

		logger.info("Stopping the CloudML daemon on port {}...", port);

		try {
			int pid = -1;
			String command = null;

			List<String> res = exec(String.format("lsof -i :%d", port));

			for (String s : res) {
				String[] columns = s.split(" +");
				try {
					pid = Integer.parseInt(columns[1]);
					command = columns[0];
				} catch (Exception e) { }
			}

			if (pid > -1 && command.equals("java")) {
				logger.info("PID found: {}, killing the process...", pid);
				exec(String.format("kill -9 %d", pid));
			}
		} catch (Exception e) {
			logger.error("Error while stopping the process.", e);
		}

		port = -1;
	}
	
	public static List<String> exec(String command) throws Exception {
		final List<String> res = new ArrayList<String>();
		
		long init = System.currentTimeMillis();
		
		ProcessBuilder pb = new ProcessBuilder(new String[] { "bash", "-c", command });
		pb.redirectErrorStream(true);

		final Process p = pb.start();
		
		Thread in = new Thread() {
			public void run() {
				try (Scanner sc = new Scanner(p.getInputStream())) {
					while (sc.hasNextLine()) {
						String line = sc.nextLine();
						logger.trace(line);
						res.add(line);
					}
				}
			}
		};
		in.start();
		
		Thread err = new Thread() {
			public void run() {
				try (Scanner sc = new Scanner(p.getErrorStream())) {
					while (sc.hasNextLine()) {
						String line = sc.nextLine();
						logger.trace(line);
						res.add(line);
					}
				}
			}
		};
		err.start();

		in.join();
		err.join();
		
		res.add(String.format("exit-status: %d", p.waitFor()));
		
		long duration = System.currentTimeMillis() - init;
		logger.trace("Executed `{}` on {} in {}", command, "localhost", durationToString(duration));
		return res;
	}
	
	public static String durationToString(long duration) {
		StringBuilder sb = new StringBuilder();
		{
			int res = (int) TimeUnit.MILLISECONDS.toSeconds(duration);
			if (res > 60 * 60) {
				sb.append(res / (60 * 60));
				sb.append(" h ");
				res = res % (60 * 60);
			}
			if (res > 60) {
				sb.append(res / 60);
				sb.append(" m ");
				res = res % 60;
			}
			sb.append(res);
			sb.append(" s");
		}

		return sb.toString();
	}

}
