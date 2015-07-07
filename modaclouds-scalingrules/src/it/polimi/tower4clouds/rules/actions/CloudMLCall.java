/**
 * Copyright (C) 2014 Politecnico di Milano (marco.miglierina@polimi.it)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.polimi.tower4clouds.rules.actions;

import it.polimi.modaclouds.qos_models.EnumErrorType;
import it.polimi.modaclouds.qos_models.Problem;
import it.polimi.tower4clouds.manager.api.ManagerAPI;
import it.polimi.tower4clouds.rules.AbstractAction;
import it.polimi.tower4clouds.rules.MonitoringRule;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.NotYetConnectedException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import org.apache.commons.lang.RandomStringUtils;
import org.java_websocket.WebSocket.READYSTATE;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_17;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

public class CloudMLCall extends AbstractAction {

	public static final String IP = "ip";
	public static final String PORT = "port";
	public static final String COMMAND = "command";
	public static final String TIER = "tier";
	public static final String N = "n";
	public static final String COOLDOWN = "cooldown";
	
	public static final String DEFAULT_MANAGER_IP = "127.0.0.1";
	public static final String DEFAULT_MANAGER_PORT = "8170";

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

	private final Set<String> requiredParameters;

	private Map<String, CloudMLCall.CloudML> connectedClients;
	
	public CloudMLCall() {
		requiredParameters = new HashSet<String>();
		connectedClients = new HashMap<String, CloudMLCall.CloudML>();

		requiredParameters.add(IP);
		requiredParameters.add(PORT);
		requiredParameters.add(COMMAND);
		requiredParameters.add(TIER);
		requiredParameters.add(N);
		requiredParameters.add(COOLDOWN);
	}

	@Override
	public void execute(String resourceId, String value, String timestamp) {
		Map<String, String> parameters = getParameters();
		Command c = Command.getByName(parameters.get(COMMAND));

		String ip = parameters.get(IP);
		String port = parameters.get(PORT);
		String id = parameters.get(TIER);
		String n = parameters.get(N);
		String cooldown = parameters.get(COOLDOWN);
		int intCooldown = -1;
		try {
			intCooldown = Integer.parseInt(cooldown);
		} catch (Exception e) { }

		try {
			perform(ip, port, c, intCooldown, id, n);
		} catch (Exception e) {
			getLogger()
					.error("Error while performing the command: some variable was null.");
		}
	}

	@Override
	protected Set<String> getMyRequiredPars() {
		return requiredParameters;
	}

	@Override
	protected Collection<? extends Problem> validate(MonitoringRule rule,
			List<MonitoringRule> otherRules) {
		Set<Problem> problems = new HashSet<Problem>();
		Map<String, String> parameters = getParameters();

		Command c = Command.getByName(parameters.get(COMMAND));
		if (c == null)
			problems.add(new Problem(rule.getId(),
					EnumErrorType.INVALID_ACTION, COMMAND,
					"Command not recognized; it must be one among these: "
							+ Command.getList()));
		else {
			String id = parameters.get(TIER);
			String n = parameters.get(N);

			if (id == null)
				problems.add(new Problem(rule.getId(),
						EnumErrorType.INVALID_ACTION, TIER,
						"The tier isn't valid for the given action"));

			if (n == null)
				problems.add(new Problem(rule.getId(),
						EnumErrorType.INVALID_ACTION, N,
						"The number of instances isn't valid for the given action"));
		}

		String ip = parameters.get(IP);
		String port = parameters.get(PORT);

		if (ip == null)
			problems.add(new Problem(rule.getId(),
					EnumErrorType.INVALID_ACTION, IP, "The ip is null"));
		if (port == null)
			problems.add(new Problem(rule.getId(),
					EnumErrorType.INVALID_ACTION, PORT, "The port is null"));

		if (ip != null && port != null && !isServerAvailable(ip, port))
			problems.add(new Problem(rule.getId(),
					EnumErrorType.INVALID_ACTION, IP,
					"Server not found at the given ip/port."));

		return problems;
	}

	private boolean isServerAvailable(String ip, String port) {
		return (getConnection(ip, port) != null);
	}

	private CloudML getConnection(String ip, String port) {
		String serverURI = String.format("ws://%s:%s", ip, port);

		CloudML client = connectedClients.get(serverURI);

		if (client == null) {
			try {
				client = new CloudML(ip, Integer.parseInt(port));
			} catch (Exception e) {
				getLogger().error("Error while using the web socket.", e);
				return null;
			}
		}
		if (client != null)
			connectedClients.put(serverURI, client);
		return client;
	}

	private boolean perform(String ip, String port, Command c, int cooldown,
			Object... parameters) {
		if (ip == null || port == null || c == null)
			throw new RuntimeException("Error with the parameters!");

		if (!c.actualCommand) {
			switch (c) {
			case SCALE:
				if (parameters.length >= 2)
					return scale(ip, port, (String) parameters[0],
							Integer.parseInt((String) parameters[1]));
				else
					throw new RuntimeException(
							"You didn't pass the id of the vms and the number of instances.");
			default:
				getLogger().debug("Command {} not handled at the moment.",
						c.name);
				return true;
			}
		}

		String command = String.format(c.command, parameters);

		CloudML client = getConnection(ip, port);
		if (client == null)
			return false;
		
		disableRule(DEFAULT_MANAGER_IP, DEFAULT_MANAGER_PORT, getRuleId(), cooldown);

		if (!c.blocking)
			client.send(command);
		else
			client.sendBlocking(command, c);

		return true;
	}
	
	private void disableRule(String ip, String port, String ruleId, int cooldown) {
		if (cooldown < 0)
			return;
		
		final String fip = ip;
		final int fport = Integer.parseInt(port);
		final int fcooldown = cooldown;
		final String fruleId = ruleId;
		
		new Thread() {
			public void run() {
				ManagerAPI manager = new ManagerAPI(fip, fport);
				
				try {
					manager.disableRule(fruleId);
					
					Thread.sleep(fcooldown * 1000);
					
					manager.enableRule(fruleId);
				} catch (Exception e) {
					getLogger().error("Error while disabling temporarely the rule.", e);
				}
			}
		}.start();
	}

	private boolean scale(String ip, String port, String id, int n) {
		if (n == 0)
			return true;
		
		CloudML client = getConnection(ip, port);
		if (client == null)
			return false;
		
		client.scale(id, n);

		return true;
	}
	
	public class CloudML implements PropertyChangeListener {
		
		private WSClient wsClient;
		
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
		
		private void printStatus() {
			if (instancesPerTier.size() == 0)
				getLogger().info("No instances found!");
			
			for (String tier : instancesPerTier.keySet()) {
				Instances i = instancesPerTier.get(tier);
				getLogger().info(i.toString());
			}
		}
		
		private void waitUntilDone(Command cmd, long timeout) {
			if (timeout <= 0)
				timeout = Long.MAX_VALUE;
			
			long init = System.currentTimeMillis();
			long end = init;
			
			Integer wait = waitingPerCommand.get(cmd);
			if (wait == null)
				return;
			
			while (wait > 0 && (end - init) <= timeout) {
				try {
					Thread.sleep(1000);
				} catch (Exception e) { }
				end = System.currentTimeMillis();
				wait = waitingPerCommand.get(cmd);
			}
			
			if ((end - init) >= timeout)
				signalCompleted(cmd, "Timeout");
		}

		public CloudML(String ip, int port) throws Exception {
			String serverURI = String.format("ws://%s:%d", ip, port);
			
			commandParam = new HashMap<CloudMLCall.Command, String>();
			instancesPerTier = new HashMap<String, Instances>();
			
			wsClient = new WSClient(serverURI);
			wsClient.addPropertyChangeListener(this);
			boolean connected = wsClient.connectBlocking();
				
			if (!connected)
				throw new Exception("CloudML server not found at the given URI (" + serverURI + ").");

		}
		
		public void terminate() {
//			wsClient.send("!extended { name : Terminate }");
		}
		
		private void pushDeploymentModel(File orig, Object... substitutions) {
			String[] commands = Command.LOAD_DEPLOYMENT.command.split("\n");
			if (commands.length < 2)
				return;
			
			wsClient.send(commands[0]);
			
			wsClient.send(String.format(commands[1], getDeploymentModelFromFile(orig, substitutions)));
		}
		
		protected String getDeploymentModelFromFile(File orig, Object... substitutions) {
			StringBuilder body = new StringBuilder();
			
			try (Scanner sc = new Scanner(orig)) {
				while (sc.hasNextLine())
					body.append(" " + sc.nextLine().trim());
			} catch (Exception e) {
				getLogger().error("Error while reading the file.", e);
				return null;
			}
			
			Object[] newSubstitutions = new Object[substitutions.length + 1];
			newSubstitutions[0] = RandomStringUtils.randomNumeric(3);
			for (int i = 0; i < substitutions.length; ++i)
				newSubstitutions[i+1] = substitutions[i];
			
			String model = String.format(body.toString(), newSubstitutions);
			
			return model;
		}
		
		public void send(String command) {
			wsClient.send(command);
		}
		
		public void sendBlocking(String command, Command cmd) {
			wsClient.sendBlocking(command, cmd);
		}
		
		public void deploy(File orig, Object... substitutions) {
			pushDeploymentModel(orig, substitutions);
			
			wsClient.sendBlocking(Command.DEPLOY.command, -1, Command.DEPLOY);
		}
		
		private void scaleOut(String vmId, int times) {
			getLogger().info("Scaling out " + times + " instances");
			
			wsClient.sendBlocking(String.format(Command.SCALE_OUT.command, vmId, Integer.valueOf(times).toString()), Command.SCALE_OUT);
		}

		public void getDeploymentModel() {
			getLogger().info("Asking for the deployment model...");
			
			wsClient.sendBlocking(Command.GET_STATUS.command, Command.GET_STATUS);
		}

		private void getInstanceInfo(String id) {
			if (id == null)
				return;
			
			wsClient.send( //Blocking(
					String.format(Command.GET_INSTANCE_STATUS.command, id)
					); //, Command.GET_INSTANCE_STATUS);
		}

		private void stopInstances(List<String> instances) {
			if (instances.size() == 0)
				return;
			
			for (String instanceId : instances)
				getLogger().info("Stopping the instance with id " + instanceId);
			
			String toSend = "";
			for (String instance : instances) {
				if (toSend.equals("")) {
					toSend += instance;
				} else {
					toSend += "," + instance;
				}
			}

			wsClient.sendBlocking(String.format(Command.STOP_INSTANCE.command, toSend), Command.STOP_INSTANCE);
		}
		
		public void terminateAllInstances() {
			for (String tier : instancesPerTier.keySet()) {
				Instances instances = instancesPerTier.get(tier);
				stopInstances(instances.running);
			}
		}

		private void startInstances(List<String> instances) {
			if (instances.size() == 0)
				return;
			
			for (String instanceId : instances)
				getLogger().info("Restarting the instance with id " + instanceId);
			
			String toSend = "";
			for (String instance : instances) {
				if (toSend.equals("")) {
					toSend += instance;
				} else {
					toSend += "," + instance;
				}
			}

			wsClient.sendBlocking(String.format(Command.START_INSTANCE.command, toSend), Command.START_INSTANCE);
		}
		
		private boolean isReachable(String ip) {
			try {
				return InetAddress.getByName(ip).isReachable(30000);
			} catch (Exception e) {
				return false;
			}
		}
		
		private Map<Command, Integer> waitingPerCommand = new HashMap<Command, Integer>();
		
		private void signalClearWaiting() {
			waitingPerCommand = new HashMap<Command, Integer>();
		}
		
		public class WSClient extends WebSocketClient {
			public WSClient(String serverURI) throws InterruptedException,
				URISyntaxException {
				super(new URI(serverURI), new Draft_17());
				signalClearWaiting();
			}
			
			private void parseRuntimeDeploymentModel(String body) throws Exception {
				JSONObject jsonObject = new JSONObject(body.substring( body.indexOf('{') ));
				JSONArray instances = jsonObject.getJSONArray("vmInstances");
				
				instancesPerTier = new HashMap<String, CloudML.Instances>();
				
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
							
							ins.ipPerId.put(id, ip);
							
							getInstanceInfo(id);
						}
					} catch (Exception e) { }
				}
			}
			
			private void parseInstanceInformation(String body) throws Exception {
				JSONObject jsonObject = new JSONObject(body.substring(body.indexOf('{'), body.lastIndexOf('}')+1).replaceAll("/", "<>"));
				
				String tier = jsonObject.has("tier") ? jsonObject.getString("tier") : null;
				String vm = jsonObject.has("vm") ? jsonObject.getString("vm") : null;
				String status = jsonObject.has("status") && !jsonObject.isNull("status") ? jsonObject.getString("status") : null;
				String ip = jsonObject.has("ip") ? jsonObject.getString("ip") : null;
				String id = jsonObject.has("id") ? jsonObject.getString("id").replaceAll("<>", "/") : null;
				
				if (tier.indexOf("fromImage") > -1)
					tier = tier.substring(0, tier.indexOf('('));
				
				Instances inst = instancesPerTier.get(tier);
				if (inst != null) {
					
					getLogger().trace("{} is {}", id, status);
					
					if ((status != null && status.indexOf("RUNNING") >= 0) || (status == null && isReachable(ip))) {
						if (inst.stopped.contains(id))
							inst.stopped.remove(id);
						if (!inst.running.contains(id))
							inst.running.add(id);
					} else {
						if (inst.running.contains(id))
							inst.running.remove(id);
						if (!inst.stopped.contains(id))
							inst.stopped.add(id);
					}
					
					inst.ipPerId.put(id, ip);
					inst.idPerName.put(vm, id);
					inst.statusPerId.put(id, status);
						
				}
			}
			
			private boolean parseUpdate(String body) throws Exception {
				body = body.replaceAll("''", "<>");
				JSONObject jsonObject = new JSONObject(body.substring(body.indexOf('{'), body.lastIndexOf('}')+1));
				
				String newValue = jsonObject.has("newValue") ? jsonObject.getString("newValue") : null;
				String parent = jsonObject.has("parent") ? jsonObject.getString("parent") : null;
				String property = jsonObject.has("property") ? jsonObject.getString("property") : null;
				
				if (parent == null || !property.equalsIgnoreCase("status"))
					return false;
				
				parent = parent.substring(parent.indexOf("<>")+2, parent.lastIndexOf("<>"));
				
				getLogger().trace("Vm: {}, Property: {}, NewValue: {}", parent, property, newValue);
				
				printStatus();
				
				String id = null;
				Instances inst = null;
				
				for (String tier : instancesPerTier.keySet()) {
					Map<String, String> names = instancesPerTier.get(tier).idPerName;
					if (names.containsKey(parent)) {
						id = names.get(parent);
						inst = instancesPerTier.get(tier);
					}
				}
				
				if (id == null)
					return false;
				
				getLogger().trace("{} is {}", id, newValue);
				
				if (newValue.indexOf("RUNNING") >= 0) {
					if (inst.stopped.contains(id))
						inst.stopped.remove(id);
					if (!inst.running.contains(id))
						inst.running.add(id);
					
					signalCompleted(Command.START_INSTANCE);
				} else if (newValue.indexOf("STOPPED") >= 0) {
					if (inst.running.contains(id))
						inst.running.remove(id);
					if (!inst.stopped.contains(id))
						inst.stopped.add(id);
					
					signalCompleted(Command.STOP_INSTANCE);
				}
				
				return true;
				
			}
			
			@Override
			public void send(String command) throws NotYetConnectedException {
				getLogger().trace(">>> {}", command);
				super.send("!listenToAny");
				
				try {
					Thread.sleep(800);
				} catch (Exception e) { }
				
				super.send(command);
			}
			
			public void sendBlocking(String command, Command cmd) throws NotYetConnectedException {
				sendBlocking(command, 300000, cmd); // 600000
			}
			
			public void sendBlocking(String command, long timeout, Command cmd) throws NotYetConnectedException {
				send(command);
				
				signalWaiting(cmd);
				waitUntilDone(cmd, timeout);
			}

			@Override
			public void onOpen(ServerHandshake serverHandshake) {
				getLogger().debug("Connected to the CloudML server");
				pcs.firePropertyChange("Connection", false, true);
			}

			private final PropertyChangeSupport pcs = new PropertyChangeSupport(
					this);

			public void addPropertyChangeListener(PropertyChangeListener listener) {
				this.pcs.addPropertyChangeListener(listener);
			}

			public void removePropertyChangeListener(PropertyChangeListener listener) {
				this.pcs.removePropertyChangeListener(listener);
			}
			
			private static final String RESULT_SNAPSHOT = "###return of GetSnapshot###";

			@Override
			public void onMessage(String s) {
				if (s.trim().length() == 0)
					return;
				
				getLogger().trace("<<< {}", s);

				if (s.contains("ack") && s.contains("ScaleOut")) {
					getLogger().info("Scale out completed.");
					
					pcs.firePropertyChange(Command.SCALE_OUT.name, false, true);
				} else if (s.contains(RESULT_SNAPSHOT)
						&& !s.contains("!snapshot")) {

					try {
						getLogger().info("Updating the runtime environment...");
						parseRuntimeDeploymentModel(s);
					} catch (Exception e) {
						getLogger().error("Error while updating the runtime environment.", e);
					}
					
//					pcs.firePropertyChange(Command.GET_STATUS.name, false, true);
				} else if (s.contains(RESULT_SNAPSHOT)
						&& s.contains("!snapshot")) {

					try {
						getLogger().info("Received instance information");
						parseInstanceInformation(s);
					} catch (Exception e) {
						getLogger().error("Error while updating the instance information.", e);
					}
					
					pcs.firePropertyChange(Command.GET_INSTANCE_STATUS.name, false, true);
					
					for (String tier : instancesPerTier.keySet()) {
						boolean end = true;
						Instances ins = instancesPerTier.get(tier);
						end &= ins.count() == ins.ipPerId.size();
						
						getLogger().trace("Received the information about {} instances out of {} for the tier {}.", ins.count(), ins.ipPerId.size(), tier);
						printStatus();
						
						if (end)
							pcs.firePropertyChange(Command.GET_STATUS.name, false, true);
					}
					
				} else if (s.contains("ack") && s.contains("Deploy")) {
					getLogger().info("Deploy completed.");
				
					pcs.firePropertyChange("Deploy", false, true);
					
					send("!getSnapshot { path : / }");
					
//					getDeploymentModel();
				} else if (s.contains("ack")) {
					
					getLogger().trace("Ack received: {}", s);
					pcs.firePropertyChange("OtherCommand", false, true);
				} else if (s.contains("!updated")) {
					
					boolean res = false;
					
					try {
						res = parseUpdate(s);
					} catch (Exception e) {
						getLogger().error("Error while updating the saved informations.", e);
					}
					if (res)
						pcs.firePropertyChange("Update", false, true);
				}
			}

			@Override
			public void onClose(int i, String s, boolean b) {
				getLogger().info("Disconnected from the CloudML server " + s);
				super.close();
				
				pcs.firePropertyChange("Connection", true, false);
				signalClearWaiting();
			}

			@Override
			public void onError(Exception e) {
				if (e instanceof ConnectException
						&& e.getMessage().equals("Connection refused"))
					getLogger().debug("The server isn't running, start if first.");
				else
					getLogger().error("Error met.", e);
				
				pcs.firePropertyChange("Error", false, true);
			}

			public boolean isConnected() {
				return super.getReadyState() == READYSTATE.OPEN;
			}
			
			public boolean disconnect() {
				try {
					closeBlocking();
					return true;
				} catch (Exception e) {
					getLogger().error("Error while disconnecting.", e);
					return false;
				}
			}
		}
		
		public void disconnect() {
			if (wsClient != null)
				wsClient.disconnect();
			
			wsClient = null;
		}
		
		private Map<Command, String> commandParam;
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
		
		public boolean scale(String id, int n) {
			if (n == 0)
				return true;
			
//			signalWaiting(Command.SCALE);
			
			commandParam.put(Command.GET_STATUS, String.format("%s;%s;%s;%d", "ip", "port", id, n));
//			getDeploymentModel();
			
			wsClient.sendBlocking("!getSnapshot { path : / }", Command.SCALE);

			return true;
		}
		
		private void signalWaiting(Command what) {
			Integer wait = waitingPerCommand.get(what);
			if (wait == null)
				wait = 0;
			
			wait++;
			getLogger().trace("New operation [{}] started, {} waiting in total.", what.name, wait);
			
			waitingPerCommand.put(what, wait);
		}
		
		private void signalCompleted(Command what) {
			signalCompleted(what, null);
		}
		
		private void signalCompleted(Command what, String reason) {
			Integer wait = waitingPerCommand.get(what);
			if (wait == null)
				wait = 0;
			
			if (wait > 0)
				wait--;
			if (reason == null)
				getLogger().trace("Operation [{}] completed, {} waiting in total.", what.name, wait);
			else
				getLogger().trace("Operation [{}] completed with reason '{}', {} waiting in total.", what.name, reason, wait);
			
			waitingPerCommand.put(what, wait);
		}

		@Override
		public void propertyChange(PropertyChangeEvent evt) {
			getLogger().debug("Property changed: " + evt.getPropertyName());
			
			if (evt.getPropertyName().equals(Command.SCALE_OUT.name)) {
				signalCompleted(Command.SCALE_OUT);
			} else if (evt.getPropertyName().equals(Command.GET_STATUS.name)) {
				signalCompleted(Command.GET_STATUS);
				signalCompleted(Command.DEPLOY);
				
				String params = commandParam.remove(Command.GET_STATUS);
				if (params == null)
					return;
				
				String[] paramsArray = params.split(";");
				
//				String ip = paramsArray[0];
//				String port = paramsArray[1];
				String tier = paramsArray[2];
				int n = Integer.parseInt(paramsArray[3]);
				
				if (!instancesPerTier.containsKey(tier)) {
					signalCompleted(Command.SCALE, "Scaling an unknown tier");
					
					return;
				}
				
				Instances instances = instancesPerTier.get(tier).clone();
				
				if (n < 0 && instances.running.size() > 0) {
					int toBeShuttedDown = -n;
					if (instances.running.size() -1 < toBeShuttedDown)
						toBeShuttedDown = instances.running.size() - 1;
					
					ArrayList<String> ids = new ArrayList<String>();
					for (int i = 0; i < toBeShuttedDown; ++i)
						ids.add(instances.running.get(i));
					
					stopInstances(ids);
					
				} else if (n > 0) {
					int toBeStarted = n;
					int toBeCreated = 0;
					if (instances.stopped.size() < toBeStarted) {
						toBeStarted = instances.stopped.size();
						toBeCreated = n - toBeStarted;
					}
					
					ArrayList<String> ids = new ArrayList<String>();
					for (int i = 0; i < toBeStarted; ++i)
						ids.add(instances.stopped.get(i));
					
					startInstances(ids);
					
					if (toBeCreated > 0)
						scaleOut(instances.idPerName.get(instances.vm), toBeCreated);
				}
				
				signalCompleted(Command.SCALE);
			}
			
		}
		
	}

}
