package it.polimi.modaclouds.scalingrules.utils;

import it.cloud.amazon.ec2.VirtualMachine;
import it.polimi.modaclouds.scalingrules.Configuration;
import it.polimi.modaclouds.scalingrules.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudMLNew implements EventHandler {
	
	protected static final Logger logger = LoggerFactory.getLogger(CloudMLNew.class);
	protected static final Logger loggerCml = LoggerFactory.getLogger("org.cloudml.facade.CloudML");
	
	private org.cloudml.facade.CloudML cml;
	private CommandFactory fcommand;
	
	private String ip;
	private int port;
	private boolean isConnected;
	
	public static void main(String[] args) throws Exception {
		String mplIp = "54.77.91.67";
		String cloudMLIp = mplIp;
		VirtualMachine mpl = VirtualMachine.getVM("mpl", "m3.large", 1);
		VirtualMachine mic = VirtualMachine.getVM("mic", "m3.large", 1);
		String loadBalancer = "ScalingRules545";
		
		CloudMLNew cml = new CloudMLNew(cloudMLIp, Configuration.DEFAULT_CLOUDML_PORT);
		
		logger.info("Deploy the system...");
		
		cml.deploy(Test.getActualDeploymentModel(cloudMLIp, mpl, mic, loadBalancer, true).toFile());
		
		logger.info("Starting the test...");
		
		cml.scale("MIC", 1);
		
		cml.scale("MIC", oneAmong(-1, 1));
		
		cml.scale("MIC", oneAmong(-1, 1));
		
		cml.terminateAllInstances();
		
		logger.info("Test ended!");
		
	}
	
	private static final Random RND = new Random();
	
	public static int oneAmong(int... vals) {
		if (vals == null || vals.length == 0)
			return 0;
		
		int i = RND.nextInt(vals.length);
		return vals[i];
	}
	
	public String getTierStatus(String tier) {
		Instances ins = instancesPerTier.get(tier);
		if (ins == null)
			return null;
		return ins.getTierStatus();
	}
	
	public String getTierIp(String tier) {
		Instances ins = instancesPerTier.get(tier);
		if (ins == null)
			return null;
		return ins.getTierIp();
	}
	
	@SuppressWarnings("unused")
	private void printStatus() {
		if (instancesPerTier.size() == 0)
			logger.info("No instances found!");
		
		for (String tier : instancesPerTier.keySet()) {
			Instances i = instancesPerTier.get(tier);
			logger.info(i.toString());
		}
	}

	public CloudMLNew(String ip, int port) throws Exception {
		this.ip = ip;
		this.port = port;
		
		instancesPerTier = new HashMap<String, Instances>();
		
		init();
	}
	
	private void init() {
		if (isConnected)
			return;
		
		logger.debug("Initiating the connection to {}:{}...", ip, port);
		
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
	
	private void pushDeploymentModel(File orig) {
		if (!isConnected)
			return;
		
		String model = getDeploymentModelFromFile(orig);
		
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
	
	protected String getDeploymentModelFromFile(File orig, Object... substitutions) {
		StringBuilder body = new StringBuilder();
		
		try (Scanner sc = new Scanner(orig)) {
			while (sc.hasNextLine())
				body.append(" " + sc.nextLine().trim());
		} catch (Exception e) {
			logger.error("Error while reading the file.", e);
			return null;
		}
		
		Object[] newSubstitutions = new Object[substitutions.length + 1];
		newSubstitutions[0] = RandomStringUtils.randomNumeric(3);
		for (int i = 0; i < substitutions.length; ++i)
			newSubstitutions[i+1] = substitutions[i];
		
		String model = String.format(body.toString(), newSubstitutions);
		
		return model;
	}
	
	public void deploy(File orig) {
		if (!isConnected)
			return;
		
		pushDeploymentModel(orig);
		
		logger.debug("Deploy...");
		
		CloudMlCommand cmd = fcommand.deploy();
		cml.fireAndWait(cmd);

		logger.debug("...done!");
		
		getDeploymentModel();
	}
	
	private void scaleOut(String vmId, int times) {
		if (!isConnected)
			return;
		
		logger.debug("Scaling out {} instances", times);
		
		CloudMlCommand cmd = fcommand.scaleOut(vmId, times);
		cml.fireAndWait(cmd);

		logger.debug("...done!");
		
		getDeploymentModel();
	}

	public void getDeploymentModel() {
		if (!isConnected)
			return;
		
		logger.info("Asking for the deployment model...");
		
		CloudMlCommand cmd = fcommand.getDeployment();
		cml.fireAndWait(cmd);

		logger.debug("...done!");
	}

	@SuppressWarnings("unused")
	private void getInstanceInfo(String id) {
		if (id == null || !isConnected)
			return;
		
		logger.debug("Retrieving the information about the instance {}...", id);
		
		CloudMlCommand cmd = fcommand.snapshot("/componentInstances[id='" + id
				+ "']");
		cml.fireAndWait(cmd);

		logger.debug("...done!");
	}

	private void stopInstances(List<String> instances) {
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
	
	public void terminateAllInstances() {
		for (String tier : instancesPerTier.keySet()) {
			Instances instances = instancesPerTier.get(tier);
			stopInstances(instances.running);
		}
	}

	private void startInstances(List<String> instances) {
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
	
	public static enum Command {
		SCALE("SCALE", null, false, true),
		SCALE_OUT("SCALE_OUT",
				"!extended { name: ScaleOut, params: [ %1$s , %2$s ] }", true, true),
		START_INSTANCE("START_INSTANCE",
				"!extended { name: StartComponent, params: [ %1$s ] }", true, true),
		STOP_INSTANCE("STOP_INSTANCE",
				"!extended { name: StopComponent, params: [ %1$s ] }", true, true),
		GET_STATUS("GET_STATUS",
				"!getSnapshot { path : / }", true, true),
		GET_INSTANCE_STATUS("GET_INSTANCE_STATUS",
				"!getSnapshot\n" +
						"path : /componentInstances[id='%1$s']\n" +
						"multimaps : { vm : name, tier : type/name, id : id, status : status, ip : publicAddress }", true, false),
		DEPLOY("DEPLOY", "!extended { name : Deploy }", false, true),
		LOAD_DEPLOYMENT("LOAD_DEPLOYMENT",
				"!extended { name : LoadDeployment }\n" +
				"!additional json-string: %s", true, false);

		public String name;
		public String command;
		public boolean actualCommand;
		public boolean blocking;

		private Command(String name, String command, boolean actualCommand, boolean blocking) {
			this.name = name;
			this.command = command;
			this.actualCommand = actualCommand;
			this.blocking = blocking;
		}

		public static Command getByName(String name) {
			for (Command c : values())
				if (c.name.equalsIgnoreCase(name))
					return c;
			return null;
		}

		public static String getList() {
			StringBuilder sb = new StringBuilder();
			for (Command c : values())
				sb.append(c.name + ", ");
			return sb.substring(0, sb.lastIndexOf(","));
		}
	}

	private Map<String, Instances> instancesPerTier;
	
	private class Instances {
		String vm = null;
		String tier = null;
		List<String> running = new ArrayList<String>();
		List<String> stopped = new ArrayList<String>();
		Map<String, String> ipPerId = new HashMap<String, String>();
		Map<String, String> idPerName = new HashMap<String, String>();
		Map<String, String> statusPerId = new HashMap<String, String>();
		
		public Instances clone() {
			Instances ret = new Instances();
			ret.vm = vm;
			ret.tier = tier;
			ret.running.addAll(running);
			ret.stopped.addAll(stopped);
			ret.ipPerId.putAll(ipPerId);
			ret.idPerName.putAll(idPerName);
			ret.statusPerId.putAll(statusPerId);
			
			return ret;
		}
		
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
		
		@SuppressWarnings("unused")
		public int count() {
			return running.size() + stopped.size();
		}
		
		public String getTierStatus() {
			return statusPerId.get(idPerName.get(vm));
		}
		
		public String getTierIp() {
			return ipPerId.get(idPerName.get(vm));
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
	
	public static class Message {
		public static enum Type {
			Ack("!ack"), Updated("!updated");
			
			String actual;
			private Type(String actual) {
				this.actual = actual;
			}
			public static Type getFromValue(String actual) {
				for (Type t : values())
					if (t.actual.equals(actual))
						return t;
				return null;
			}
		}
		
		public Type type;
		public Map<String, String> body;
		
		public Message(String body) {
			this.body = new HashMap<String, String>();
			
			String type = body.substring(0, body.indexOf(' '));
			this.type = Type.getFromValue(type);
			if (this.type != null) {
				String actualBody = body.substring(body.indexOf('{')+1, body.lastIndexOf('}'));
				String[] splitted = actualBody.split(", ");
				for (String s : splitted) {
					String[] values = s.split(": ");
					this.body.put(values[0], values[1]);
				}
			}
		}
		
		public String toString() {
			StringBuilder sb = new StringBuilder();
			
			sb.append(this.getClass().getName() + "[");
			
			sb.append("Type: " + type);
			for (String key : body.keySet())
				sb.append(", " + key + ": " + body.get(key));
			
			sb.append("]");
			
			return sb.toString();
		}
	}

	@Override
	public void handle(org.cloudml.facade.events.Message m) {
		String body = m.getBody();
		
		Message msg = new Message(body);
		if (msg.type == Message.Type.Ack && msg.body.get("status").equals("completed")) {
			if (msg.body.get("fromPeer").contains("Deploy"))
				logger.info("Deployment completed!");
		}
		
		loggerCml.debug(body);
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
