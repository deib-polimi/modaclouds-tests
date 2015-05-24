package it.polimi.modaclouds.scalingrules.utils;

import it.polimi.modaclouds.scalingrules.Configuration;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.cloudml.facade.Factory;
import org.cloudml.facade.commands.CloudMlCommand;
import org.cloudml.facade.commands.CommandFactory;
import org.cloudml.facade.events.ComponentData;
import org.cloudml.facade.events.ComponentInstanceData;
import org.cloudml.facade.events.ComponentInstanceList;
import org.cloudml.facade.events.ComponentList;
import org.cloudml.facade.events.Data;
import org.cloudml.facade.events.Event;
import org.cloudml.facade.events.EventHandler;
import org.cloudml.facade.events.Message;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudMLNew implements EventHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(CloudML.class);
	private static final Logger loggerCml = LoggerFactory.getLogger("org.cloudml.facade.CloudML");
	
	private org.cloudml.facade.CloudML cml;
	private CommandFactory fcommand;
	
	private String ip;
	private int port;
	private boolean isConnected;

	public CloudMLNew(String ip, int port) {
		this.ip = ip;
		this.port = port;
		isConnected = false;
		
		instancesPerTier = new HashMap<String, CloudMLNew.Instances>();
		
		init();
	}
	
	public static void main(String[] args) {
		CloudMLNew cml = new CloudMLNew("127.0.0.1", Configuration.DEFAULT_CLOUDML_PORT);
		
		cml.deploy();
		
		cml.printStatus();
		
		cml.printStatus();
		
//		cml.scale("MIC", 1);
//		
//		cml.printStatus();
		
		cml.scale("MIC", -1);
		
		cml.printStatus();
	}
	
	public static void startDaemon(int port) {
		try {
			org.cloudml.websocket.Daemon.main(new String[] { Integer.valueOf(port).toString() });
		} catch (Exception e) { }
	}
	
	public void init() {
		if (isConnected)
			return;
		
		logger.debug("Initiating the connection to {}:{}...", ip, port);
		
		if (ip.equals("127.0.0.1") || ip.equals("localhost")) {
			startDaemon(port);
			
			try {
				Thread.sleep(1000); // give it time to start...
			} catch (Exception e2) { }
		}
		
		cml = Factory.getInstance().getCloudML(String.format("ws://%s:%d", ip, port));
		
		fcommand = new CommandFactory();
		
		cml.register(this);
		
		fcommand.reset();
		
		isConnected = true;
	}
	
	public void terminate() {
		if (!isConnected)
			return;
		
		logger.debug("Terminating the connection to {}:{}...", ip, port);
		
		fcommand.reset();
		cml.terminate();
		
		isConnected = false;
	}
	
	private void pushDeploymentModel() {
		if (!isConnected)
			return;
		
		logger.debug("Pushing the deployment model...");
		
		StringBuilder body = new StringBuilder();
		
		try (Scanner sc = new Scanner(Configuration.getInputStream(Configuration.CLOUDML_DEPLOYMENT_MODEL))) {
			while (sc.hasNextLine())
				body.append(" " + sc.nextLine().trim());
		}
		
		String model = String.format(body.toString(), RandomStringUtils.randomNumeric(3));
		Path tmp;
		try {
			tmp = Files.createTempFile("deploymentModel", "json");
			FileUtils.writeStringToFile(tmp.toFile(), model, false);
		} catch (Exception e) {
			throw new RuntimeException("Unable to write the temporary model file.", e);
		}
		
		CloudMlCommand load = fcommand.loadDeployment(tmp.toString());
		cml.fireAndWait(load);
		
		logger.debug("...done!");
	}
	
	public void deploy() {
		if (!isConnected)
			return;
		
		pushDeploymentModel();
		
		logger.debug("Deploy...");
		
		CloudMlCommand cmd = fcommand.deploy();
		cml.fireAndWait(cmd);

		logger.debug("...done!");
		
		getDeploymentModel();
	}
	
	public void scaleOut(String vmId, int times) {
		if (!isConnected)
			return;
		
		logger.debug("Scaling out the resource {} of {} instances...", vmId, times);
		
		CloudMlCommand cmd = fcommand.scaleOut(vmId, times);
		cml.fireAndWait(cmd);

		logger.debug("...done!");
		
		getDeploymentModel();
	}

	public void getDeploymentModel() {
		if (!isConnected)
			return;
		
		logger.debug("Retrieving the deployment model...");
		
		CloudMlCommand cmd = fcommand.snapshot("/");
		cml.fireAndWait(cmd);

		logger.debug("...done!");
	}

	public void getInstanceInfo(String id) {
		if (!isConnected)
			return;
		
		logger.debug("Retrieving the information about the instance {}...", id);
		
		CloudMlCommand cmd = fcommand.snapshot("/componentInstances[id='" + id
				+ "']");
		cml.fireAndWait(cmd);

		logger.debug("...done!");
	}
	
	public void stopInstance(String instance) {
		ArrayList<String> instances = new ArrayList<String>();
		instances.add(instance);
		stopInstances(instances);
	}

	public void stopInstances(List<String> instances) {
		if (!isConnected)
			return;
		
		if (instances.size() == 0) {
			logger.debug("No instance to stop.");
			return;
		}
		
		logger.debug("Stopping {} instances...", instances.size());
		
		CloudMlCommand cmd = fcommand.stopComponent(instances);
		cml.fireAndWait(cmd);

		logger.debug("...done!");
	}
	
	public void startInstance(String instance) {
		ArrayList<String> instances = new ArrayList<String>();
		instances.add(instance);
		startInstances(instances);
	}

	public void startInstances(List<String> instances) {
		if (!isConnected)
			return;
		
		if (instances.size() == 0) {
			logger.debug("No instance to start.");
			return;
		}
	
		logger.debug("Starting {} instances...", instances.size());
		
		CloudMlCommand cmd = fcommand.startComponent(instances);
		cml.fireAndWait(cmd);

		logger.debug("...done!");
	}
	
	private boolean isMachineUp(String ip) {
		try {
			return InetAddress.getByName(ip).isReachable(5000);
		} catch (Exception e) {
			return false;
		}
	}
	
	private void printStatus() {
		for (String tier : instancesPerTier.keySet()) {
			Instances i = instancesPerTier.get(tier);
			logger.info(i.toString());
		}
	}
	
	@SuppressWarnings("unused")
	private void parseJSONArrayOfInstances(JSONArray instances) {
		instancesPerTier = new HashMap<String, CloudMLNew.Instances>();
		
		for (int i = 0; i < instances.length(); i++) {
			try {
				JSONObject instance = instances.getJSONObject(i);
				if (instance.get("id") != null) {
					String id = instance.getString("id");
					String tier = instance.getString("type");
					tier = tier.substring(tier.indexOf('[')+1, tier.indexOf(']'));
					boolean scaledOut = false;
					if (tier.indexOf("fromImage") > -1) {
						scaledOut = true;
						tier = tier.substring(0, tier.indexOf('('));
					}
					String name = instance.getString("name");
					String ip = instance.getString("publicAddress");
					
					Instances ins = instancesPerTier.get(tier);
					if (ins == null) {
						ins = new Instances();
						ins.tier = tier;
						instancesPerTier.put(tier, ins);
					}
					if (!scaledOut)
						ins.vm = name;
					
					if (isMachineUp(ip))
						ins.running.add(id);
					else
						ins.stopped.add(id);
					
					ins.ips.put(id, ip);
				}
			} catch (Exception e) { }
		}
	}
	
	private Map<String, Instances> instancesPerTier;
	
	private class Instances {
		String vm = null;
		String tier = null;
		List<String> running = new ArrayList<String>();
		List<String> stopped = new ArrayList<String>();
		Map<String, String> ips = new HashMap<String, String>();
		
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Tier: %s, VM: %s", tier, vm));
			if (running.size() > 0) {
				sb.append(", Running: [ ");
				for (String s : running)
					sb.append(s + ", ");
				sb.deleteCharAt(sb.length() - 2);
				sb.append("]");
			}
			if (stopped.size() > 0) {
				sb.append(", Stopped: [ ");
				for (String s : stopped)
					sb.append(s + ", ");
				sb.deleteCharAt(sb.length() - 2);
				sb.append("]");
			}
			
			return sb.toString();
		}
	}
	
	public boolean scale(String tier, int n) {
		if (n == 0)
			return true;
		
		getDeploymentModel();
		
		Instances instances = instancesPerTier.get(tier);
		if (instances == null)
			return false;
		
		if (n < 0) {
			int toBeShuttedDown = n;
			if (instances.running.size() < n)
				toBeShuttedDown = instances.running.size();
			
			ArrayList<String> ids = new ArrayList<String>();
			for (int i = 0; i < toBeShuttedDown; ++i)
				ids.add(instances.running.get(i));
			
			stopInstances(ids);
			
		} else if (n > 0) {
			int toBeStarted = n;
			int toBeCreated = 0;
			if (instances.stopped.size() < n) {
				toBeStarted = instances.stopped.size();
				toBeCreated = n - toBeStarted;
			}
			
			ArrayList<String> ids = new ArrayList<String>();
			for (int i = 0; i < toBeStarted; ++i)
				ids.add(instances.stopped.get(i));
			
			startInstances(ids);
			
			if (toBeCreated > 0)
				scaleOut(instances.vm, toBeCreated);
		}

		return true;
	}

	@Override
	public void handle(Event e) {
		loggerCml.info("handle(Event arg0)");
	}

	@Override
	public void handle(Message m) {
		loggerCml.debug(m.getBody());
	}

	@Override
	public void handle(Data d) {
		loggerCml.info("handle(Data arg0)");
	}

	@Override
	public void handle(ComponentList cl) {
		loggerCml.info("handle(ComponentList arg0)");
	}

	@Override
	public void handle(ComponentData cd) {
		loggerCml.info("handle(ComponentData arg0)");
	}

	@Override
	public void handle(ComponentInstanceList cil) {
		loggerCml.info("handle(ComponentInstanceList arg0)");
	}

	@Override
	public void handle(ComponentInstanceData cid) {
		loggerCml.info("handle(ComponentInstanceData arg0)");
	}
	
}