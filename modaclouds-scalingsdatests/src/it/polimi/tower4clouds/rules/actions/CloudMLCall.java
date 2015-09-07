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
import it.polimi.tower4clouds.common.net.DefaultRestClient;
import it.polimi.tower4clouds.common.net.RestMethod;
import it.polimi.tower4clouds.rules.AbstractAction;
import it.polimi.tower4clouds.rules.MonitoringRule;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

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
    
    public static final String DEFAULT_CLOUDML_IP = "127.0.0.1";
    public static final String DEFAULT_CLOUDML_PORT = "9030";
    
    public static final String DEFAULT_N = "1";
    public static final String DEFAULT_COOLDOWN = "600";

    private final Set<String> requiredParameters;

    private Map<String, CloudMLCall.CloudML> connectedClients;
    
    public CloudMLCall() {
        requiredParameters = new HashSet<String>();
        connectedClients = new HashMap<String, CloudMLCall.CloudML>();
        
        requiredParameters.add(COMMAND);
        requiredParameters.add(TIER);
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
        
        if (ip == null)
        	ip = DEFAULT_CLOUDML_IP;
        if (port == null)
        	port = DEFAULT_CLOUDML_PORT;
        if (n == null)
        	n = DEFAULT_N;
        if (cooldown == null)
        	cooldown = DEFAULT_COOLDOWN;
        
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

            if (id == null)
                problems.add(new Problem(rule.getId(),
                        EnumErrorType.INVALID_ACTION, TIER,
                        "The tier isn't valid for the given action"));
        }

        String ip = parameters.get(IP);
        String port = parameters.get(PORT);

        if (ip == null)
        	ip = DEFAULT_CLOUDML_IP;
        if (port == null)
        	port = DEFAULT_CLOUDML_PORT;

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
        
        disableRule(DEFAULT_MANAGER_IP, Integer.toString(getMmPort()), getRuleId(), cooldown);

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
            	DefaultRestClient client = new DefaultRestClient();
        		String managerUrl = "http://" + fip + ":" + fport + "/v1";
        		
        		try {
        			client.execute(RestMethod.GET, managerUrl + "/monitoring-rules"
        					+ "/" + fruleId + "?enabled=false", null, 204, 5000);
        			
        			Thread.sleep(fcooldown * 1000);
        			
        			client.execute(RestMethod.GET, managerUrl + "/monitoring-rules"
        					+ "/" + fruleId + "?enabled=true", null, 204, 5000);
        		} catch (Exception e) {
        			getLogger().error("Error while disabling temporarely the rule.", e);
        		}
            	
//                ManagerAPI manager = new ManagerAPI(fip, fport);
//                
//                try {
//                    manager.disableRule(fruleId);
//                    
//                    Thread.sleep(fcooldown * 1000);
//                    
//                    manager.enableRule(fruleId);
//                } catch (Exception e) {
//                    getLogger().error("Error while disabling temporarely the rule.", e);
//                }
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
	
			try {
				wsClient = new WSClient(serverURI);
				wsClient.addPropertyChangeListener(this);
			} catch (Exception e) {
				throw new Exception("CloudML server not found at the given URI (" + serverURI + ").", e);
			}
		}
	
		private void pushDeploymentModel(File orig) {
			String[] commands = Command.LOAD_DEPLOYMENT.command.split("\n");
			if (commands.length < 2)
				return;
	
			wsClient.send(commands[0]);
	
			wsClient.send(String.format(commands[1], getDeploymentModelFromFile(orig)));
		}
	
		protected String getDeploymentModelFromFile(File orig) {
			StringBuilder body = new StringBuilder();
	
			try (Scanner sc = new Scanner(orig)) {
				while (sc.hasNextLine())
					body.append(sc.nextLine().trim().replaceAll("(\"[^\"]+\")[ \t]*:[ \t]*", "$1:"));
			} catch (Exception e) {
				getLogger().error("Error while reading the file.", e);
				return null;
			}
			
			return body.toString();
		}
	
		public void deploy(File orig) {
			pushDeploymentModel(orig);
	
			wsClient.sendBlocking(Command.DEPLOY.command, -1, Command.DEPLOY);
		}
	
		private void scaleOut(String vmId, int times, boolean blocking) {
			if (times <= 0) {
				getLogger().info("Scaling out of {} skipped because {} <= 0.", vmId, times);
				return;
			}
			getLogger().info("Scaling out {} instances.", times);
	
			if (blocking)
				wsClient.sendBlocking(String.format(Command.SCALE_OUT.command, vmId, Integer.toString(times)), Command.SCALE_OUT);
			else
				wsClient.send(String.format(Command.SCALE_OUT.command, vmId, Integer.toString(times)));
		}
		
		public void updateStatus() {
			updateStatus(true);
		}
	
		private void updateStatus(boolean blocking) {
			getLogger().info("Asking for the deployment model...");
	
			if (blocking)
				wsClient.sendBlocking(Command.GET_STATUS.command, Command.GET_STATUS);
			else
				wsClient.send(Command.GET_STATUS.command);
		}
	
		private void getInstanceInfo(String id) {
			if (id == null)
				return;
	
			wsClient.send( //Blocking(
					String.format(Command.GET_INSTANCE_STATUS.command, id)
					); //, Command.GET_INSTANCE_STATUS);
		}
	
		private void stopInstances(List<String> instances, boolean blocking) {
			if (instances.size() == 0)
				return;
	
			StringBuilder toSend = new StringBuilder();
			for (String instance : instances) {
				getLogger().info("Stopping the instance with id {}...", instance);
				
				if (toSend.length() == 0) {
					toSend.append(instance);
				} else {
					toSend.append(",");
					toSend.append(instance);
				}
			}
	
			if (blocking)
				wsClient.sendBlocking(String.format(Command.STOP_INSTANCE.command, toSend.toString()), Command.STOP_INSTANCE);
			else
				wsClient.send(String.format(Command.STOP_INSTANCE.command, toSend.toString()));
		}
	
		public void terminateAllInstances() {
			for (String tier : instancesPerTier.keySet()) {
				Instances instances = instancesPerTier.get(tier);
				stopInstances(instances.running, true);
			}
		}
	
		private void startInstances(List<String> instances, boolean blocking) {
			if (instances.size() == 0)
				return;
	
			StringBuilder toSend = new StringBuilder();
			for (String instance : instances) {
				getLogger().info("Restarting the instance with id {}...", instance);
				
				if (toSend.length() == 0) {
					toSend.append(instance);
				} else {
					toSend.append(",");
					toSend.append(instance);
				}
			}
	
			if (blocking)
				wsClient.sendBlocking(String.format(Command.START_INSTANCE.command, toSend.toString()), Command.START_INSTANCE);
			else
				wsClient.send(String.format(Command.START_INSTANCE.command, toSend.toString()));
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
		
		public void send(String command) {
			wsClient.send(command);
		}
		
		public void sendBlocking(String command, Command cmd) {
			wsClient.sendBlocking(command, cmd);
		}
		
		@ClientEndpoint
		public class WSClient implements AutoCloseable {
			private String serverURI;
			
			public WSClient(String serverURI) throws Exception {
				this.serverURI = serverURI;
				
				open();
			}
			
			public void open() throws Exception {
				if (session != null)
					getLogger().info("You're already connected!");
				
				WebSocketContainer container = ContainerProvider.getWebSocketContainer();
		        container.connectToServer(this, new URI(serverURI));
			}
	
			private void parseRuntimeDeploymentModel(String body) throws Exception {
				JSONObject jsonObject = new JSONObject(body.substring( body.indexOf('{') ));
				JSONArray instances = jsonObject.getJSONArray("vmInstances");
	
				instancesPerTier = new HashMap<String, Instances>();
	
				for (int i = 0; i < instances.length(); i++) {
					try {
						JSONObject instance = instances.getJSONObject(i);
						if (instance.get("id") != null) {
							String id = instance.getString("id");
							String tier = instance.getString("type");
							tier = tier.substring(tier.indexOf('[')+1, tier.indexOf(']'));
							boolean scaledOut = false;
							if (tier.indexOf("fromImage") > -1 || tier.indexOf("scaled") > -1) {
								scaledOut = true;
							}
							tier = Instances.sanitizeName(tier);
							String name = instance.getString("name");
							String ip = instance.getString("publicAddress");
	
							Instances ins = instancesPerTier.get(tier);
							if (ins == null) {
								ins = new Instances();
								ins.tier = tier;
								instancesPerTier.put(tier, ins);
							}
							if (ins.vm == null || !scaledOut)
								ins.vm = name;
	
							ins.ipPerId.put(id, ip);
	
							getInstanceInfo(id);
						}
					} catch (Exception e) { }
				}
	
				JSONArray providers = jsonObject.getJSONArray("providers");
	
				providersAvailable = new ArrayList<String>();
	
				for (int i = 0; i < providers.length(); i++) {
					try {
						JSONObject provider = providers.getJSONObject(i);
						if (provider.get("name") != null) {
							String name = provider.getString("name");
							providersAvailable.add(name);
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
				String provider = jsonObject.has("provider") ? jsonObject.getString("provider") : null;
	
				tier = Instances.sanitizeName(tier);
	
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
					inst.providerPerId.put(id, provider);
				}
			}
			
			private JSONObject getUpdateJSON(String body) {
				body = body.replaceAll("''", "<>");
				JSONObject jsonObject = new JSONObject(body.substring(body.indexOf('{'), body.lastIndexOf('}')+1));
				return jsonObject;
			}
	
			private boolean parseUpdate(String body) throws Exception {
				JSONObject jsonObject = getUpdateJSON(body);
	
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
			
			private Session session;
	
			public void send(String command) {
				if (!isConnected())
					throw new RuntimeException("You're not connected to any server!");
				
				getLogger().trace(">>> {}", command);
				
				try {
					session.getBasicRemote().sendText("!listenToAny");
					
					Thread.sleep(800);
					
					session.getBasicRemote().sendText(command);
				} catch (Exception e) {
					getLogger().error("Error while sending the command.", e);
				}
			}
	
			public static final int TIMEOUT = 600000;
	
			public void sendBlocking(String command, Command cmd) {
				sendBlocking(command, TIMEOUT, cmd);
			}
	
			public void sendBlocking(String command, long timeout, Command cmd) {
				send(command);
	
				signalWaiting(cmd);
				waitUntilDone(cmd, timeout);
			}
	
			private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
	
			public void addPropertyChangeListener(PropertyChangeListener listener) {
				this.pcs.addPropertyChangeListener(listener);
			}
	
			public void removePropertyChangeListener(PropertyChangeListener listener) {
				this.pcs.removePropertyChangeListener(listener);
			}
	
			private static final String RESULT_SNAPSHOT = "###return of GetSnapshot###";
	
			@OnMessage
			public void onMessage(String s) {
				if (s.trim().length() == 0)
					return;
	
				getLogger().trace("<<< {}", s);
	
				if (s.contains("ack") && s.contains("ScaleOut")) {
					if (somebodyWaiting(Command.SCALE_OUT)) {
						getLogger().info("Scale out completed.");
		
						pcs.firePropertyChange(Command.SCALE_OUT.name, false, true);
					}
				} else if (s.contains(RESULT_SNAPSHOT)
						&& !s.contains("!snapshot")) {
	
					try {
						getLogger().info("Updating the runtime environment...");
						parseRuntimeDeploymentModel(s);
					} catch (Exception e) {
						getLogger().error("Error while updating the runtime environment.", e);
					}
	
	//				pcs.firePropertyChange(Command.GET_STATUS.name, false, true);
				} else if (s.contains(RESULT_SNAPSHOT)
						&& s.contains("!snapshot")) {
	
					try {
						getLogger().info("Received instance information.");
						parseInstanceInformation(s);
					} catch (Exception e) {
						getLogger().error("Error while updating the instance information.", e);
					}
	
					pcs.firePropertyChange(Command.GET_INSTANCE_STATUS.name, false, true);
					
					boolean end = true;
	
					for (String tier : instancesPerTier.keySet()) {
						Instances ins = instancesPerTier.get(tier);
						end &= ins.count() == ins.ipPerId.size();
					}
					
					if (end) {
						printStatus();
						pcs.firePropertyChange(Command.GET_STATUS.name, false, true);
					}
	
				} else if (s.contains("ack") && s.contains("Deploy") && !s.contains("MaxVMsReached")) {
					getLogger().info("Deploy completed.");
	
					pcs.firePropertyChange(Command.DEPLOY.name, false, true);
	
					try {
						Thread.sleep(10000);
					} catch (Exception e) { }
	
					updateStatus(false);
				} else if (s.contains("ack") && s.contains("Burst")) {
					getLogger().info("Burst completed.");
	
					pcs.firePropertyChange(Command.BURST.name, false, true);
	
					try {
						Thread.sleep(10000);
					} catch (Exception e) { }
	
					updateStatus(false);
				} else if (s.contains("ack") && s.contains("MaxVMsReached") ||
						(s.contains("!updated") && getUpdateJSON(s).getString("newValue").equals("ERROR") && getUpdateJSON(s).getString("property").equals("status") && getUpdateJSON(s).getString("fromPeer").contains("CloudAppDeployer"))) {
					getLogger().info("It was impossible to perform the scale out because you reached the max VM constraint.");
	
//					pcs.firePropertyChange(Command.SCALE_OUT.name, false, true);
					
					String params = commandParam.remove(Command.SCALE_OUT);
					if (params != null) {
						String[] paramsArray = params.split(";");
						String tier = paramsArray[0];
						int toBeCreated = Integer.parseInt(paramsArray[1]);
						List<String> providers = new ArrayList<String>();
						for (int i = 3; i < paramsArray.length; ++i)
							providers.add(paramsArray[i]);
						
						Instances instances = instancesPerTier.get(tier);
						List<String> usedProviders = instances.getUsedProviders();
						if (usedProviders.size() > providers.size()) {
							String provider = null;
							for (int i = usedProviders.size() - 1; provider == null && i >= 0; --i) {
								if (!providers.contains(usedProviders.get(i)))
									provider = usedProviders.get(i);
							}
							
							String vmId = null;
							for (int i = 0; vmId == null && i < instances.running.size(); ++i) {
								if (instances.providerPerId.get(instances.running.get(i)).equals(provider))
									vmId = instances.running.get(i);
							}
							for (int i = 0; vmId == null && i < instances.stopped.size(); ++i) {
								if (instances.providerPerId.get(instances.stopped.get(i)).equals(provider))
									vmId = instances.stopped.get(i);
							}
							
							getLogger().info("Trying another provider...");
							commandParam.put(Command.SCALE_OUT, params + ";" + provider);
							scaleOut(vmId, toBeCreated, false);
						} else {
							getLogger().info("Trying the burst instead...");
							burst(tier, false);
						}
					}
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
			
			@OnOpen
			public void onOpen(Session session) {
				getLogger().info("Connected to the CloudML server {}.", serverURI);
				this.session = session;
				this.session.setMaxTextMessageBufferSize(10 * 1024 * 1024);
				
				pcs.firePropertyChange("Connection", false, true);
				signalClearWaiting();
			}
	
			@OnClose
			public void onClose(CloseReason closeReason) {
				getLogger().info("Disconnected from the CloudML server {} ({}: {}).", serverURI, closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
				session = null;
				
				pcs.firePropertyChange("Connection", true, false);
				signalClearWaiting();
				
				if (closeReason.getCloseCode().getCode() == 1006) {
					getLogger().info("The connection was killed for timeout. Reconnecting...");
					try {
						open();
					} catch (Exception e) {
						getLogger().error("Error while reconnecting to the server.", e);
					}
				}
			}
			
			@OnError
			public void onError(Throwable e) {
				getLogger().error("Error met.", e);
	
				pcs.firePropertyChange("Error", false, true);
			}
			
			public boolean isConnected() {
				return session != null && session.isOpen();
			}
	
			@Override
			public void close() {
				try {
					session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Requested explicitly"));
				} catch (Exception e) {
					getLogger().error("Error while disconnecting.", e);
				}
			}
		}
	
		public void close() {
			if (wsClient != null && wsClient.isConnected())
				wsClient.close();
	
			wsClient = null;
		}
	
		private Map<Command, String> commandParam;
		private Map<String, Instances> instancesPerTier;
		private List<String> providersAvailable;
	
		public boolean scale(String id, int n) {
			if (n == 0)
				return true;
	
			commandParam.put(Command.GET_STATUS, String.format("%s;%d;%s", id, n, Command.SCALE.name));
	
			wsClient.sendBlocking(Command.GET_STATUS.command, Command.SCALE);
	
			return true;
		}
		
		public boolean burst(String id) {
			return burst(id, true);
		}
	
		private boolean burst(String id, boolean blocking) {
			commandParam.put(Command.GET_STATUS, String.format("%s;1;%s", id, Command.BURST.name));
	
			if (blocking)
				wsClient.sendBlocking(Command.GET_STATUS.command, Command.BURST);
			else
				wsClient.send(Command.GET_STATUS.command);
	
			return true;
		}
	
		private boolean burst(String id, String provider, boolean blocking) {
			if (blocking)
				wsClient.sendBlocking(String.format(Command.BURST.command, id, provider), Command.BURST);
			else
				wsClient.send(String.format(Command.BURST.command, id, provider));
	
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
		
		private boolean somebodyWaiting(Command what) {
			Integer wait = waitingPerCommand.get(what);
			return (wait != null && wait > 0);
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
			getLogger().debug("Property changed: {}", evt.getPropertyName());
	
			if (evt.getPropertyName().equals(Command.SCALE_OUT.name)) {
				signalCompleted(Command.SCALE_OUT);
				if (somebodyWaiting(Command.SCALE))
					signalCompleted(Command.SCALE);
			} else if (evt.getPropertyName().equals(Command.BURST.name)) {
				signalCompleted(Command.BURST);
				if (somebodyWaiting(Command.SCALE))
					signalCompleted(Command.SCALE);
			} else if (evt.getPropertyName().equals(Command.GET_STATUS.name)) {
				signalCompleted(Command.GET_STATUS);
				if (somebodyWaiting(Command.DEPLOY))
					signalCompleted(Command.DEPLOY);
	
				String params = commandParam.remove(Command.GET_STATUS);
				if (params == null)
					return;
	
				String[] paramsArray = params.split(";");
	
				String tier = paramsArray[0];
				int n = Integer.parseInt(paramsArray[1]);
				Command cmd = Command.getByName(paramsArray[2]);
	
				if (cmd == null)
					return;
	
				if (cmd == Command.SCALE) {
					if (!instancesPerTier.containsKey(tier)) {
						signalCompleted(Command.SCALE, "Scaling an unknown tier");
	
						return;
					}
	
					Instances instances = instancesPerTier.get(tier).clone();
	
					if (n < 0 && instances.running.size() > 0) {
						int toBeShuttedDown = -n;
						if (instances.running.size() - 1 < toBeShuttedDown)
							toBeShuttedDown = instances.running.size() - 1;
	
						ArrayList<String> ids = new ArrayList<String>();
						for (int i = 0; i < toBeShuttedDown; ++i)
							ids.add(instances.running.get(i));
	
						stopInstances(ids, false);
	
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
	
						startInstances(ids, false);
	
						if (toBeCreated > 0) {
							List<String> providers = instances.getUsedProviders();
							if (providers.size() == 0)
								throw new RuntimeException("No provider found!");
							String provider = providers.get(providers.size() - 1);
							
							String id = instances.idPerName.get(instances.vm);
							if (!instances.providerPerId.get(id).equals(provider)) {
								id = null;
								for (int i = 0; id == null && i < instances.running.size(); ++i) {
								id = instances.running.get(i);
									if (!instances.providerPerId.get(id).equals(provider))
										id = null;
								}
								for (int i = 0; id == null && i < instances.stopped.size(); ++i) {
									id = instances.stopped.get(i);
									if (!instances.providerPerId.get(id).equals(provider))
										id = null;
								}
							}
							
							commandParam.put(Command.SCALE_OUT, String.format("%s;%d;%s;%s", instances.tier, toBeCreated, Command.SCALE_OUT.name, provider));
							scaleOut(id, toBeCreated, false);
						}
					}
	
	//				signalCompleted(Command.SCALE);
				} else if (cmd == Command.BURST) {
					if (!instancesPerTier.containsKey(tier)) {
						signalCompleted(Command.BURST, "Bursting an unknown tier");
						if (somebodyWaiting(Command.SCALE))
							signalCompleted(Command.SCALE);
	
						return;
					}
	
					Instances instances = instancesPerTier.get(tier).clone();
					if (instances.running.size() == 0) {
						signalCompleted(Command.BURST, "Bursting a non running tier");
						if (somebodyWaiting(Command.SCALE))
							signalCompleted(Command.SCALE);
	
						return;
					}
					List<String> usedProviders = instances.getUsedProviders();
					String provider = null;
					for (int i = 0; provider == null && i < providersAvailable.size(); ++i) {
						if (!usedProviders.contains(providersAvailable.get(i)))
							provider = providersAvailable.get(i);
					}
					if (provider == null) {
						signalCompleted(Command.BURST, "No available provider to burst to");
						if (somebodyWaiting(Command.SCALE))
							signalCompleted(Command.SCALE);
	
						return;
					}
	
					burst(instances.idPerName.get(instances.vm), provider, false);
	
	//				signalCompleted(Command.BURST);
				}
			}
		}
	}
	
	public static enum Command {
		SCALE("SCALE", null, false, true, true),
		SCALE_OUT("SCALE_OUT",
				"!extended { name: ScaleOut, params: [ %1$s , %2$s ] }", true, true, false),
		START_INSTANCE("START_INSTANCE",
				"!extended { name: StartComponent, params: [ %1$s ] }", true, true, false),
		STOP_INSTANCE("STOP_INSTANCE",
				"!extended { name: StopComponent, params: [ %1$s ] }", true, true, false),
		GET_STATUS("GET_STATUS",
				"!getSnapshot { path : / }", true, true, false),
		GET_INSTANCE_STATUS("GET_INSTANCE_STATUS",
				"!getSnapshot\n" +
						"path : /componentInstances[id='%1$s']\n" +
						"multimaps : { vm : name, tier : type/name, id : id, status : status, ip : publicAddress, provider : type/provider/name }", true, false, false),
		DEPLOY("DEPLOY", "!extended { name : Deploy }", false, true, false),
		LOAD_DEPLOYMENT("LOAD_DEPLOYMENT",
				"!extended { name : LoadDeployment }\n" +
				"!additional json-string:%s", true, false, false),
		BURST("BURST", "!extended { name: Burst, params: [ %1$s , %2$s ] }", true, true, false);

		public String name;
		public String command;
		public boolean actualCommand;
		public boolean blocking;
		public boolean publicCommand;

		private Command(String name, String command, boolean actualCommand, boolean blocking, boolean publicCommand) {
			this.name = name;
			this.command = command;
			this.actualCommand = actualCommand;
			this.blocking = blocking;
			this.publicCommand = publicCommand;
		}

		public static Command getByName(String name) {
			for (Command c : values())
				if (c.name.equalsIgnoreCase(name))
					return c;
			return null;
		}

		public static String getList() {
			StringBuilder sb = new StringBuilder();
			for (Command c : values()) {
				sb.append(c.name);
				sb.append(", ");
			}
			return sb.substring(0, sb.lastIndexOf(","));
		}
	}
	
	private static class Instances {
		String vm = null;
		String tier = null;
		List<String> running = new ArrayList<String>();
		List<String> stopped = new ArrayList<String>();
		Map<String, String> ipPerId = new HashMap<String, String>();
		Map<String, String> idPerName = new HashMap<String, String>();
		Map<String, String> statusPerId = new HashMap<String, String>();
		Map<String, String> providerPerId = new HashMap<String, String>();

		public Instances clone() {
			Instances ret = new Instances();
			ret.vm = vm;
			ret.tier = tier;
			ret.running.addAll(running);
			ret.stopped.addAll(stopped);
			ret.ipPerId.putAll(ipPerId);
			ret.idPerName.putAll(idPerName);
			ret.statusPerId.putAll(statusPerId);
			ret.providerPerId.putAll(providerPerId);

			return ret;
		}

		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append(String.format("Tier: %s, VM: %s", tier, vm));
			if (running.size() > 0) {
				sb.append(", Running: [ ");
				for (String s : running) {
					String provider = providerPerId.get(s);
					sb.append(String.format("%s%s, ", s, provider != null ? "@" + provider : ""));
				}
				sb.deleteCharAt(sb.length() - 2);
				sb.append("]");
			}
			if (stopped.size() > 0) {
				sb.append(", Stopped: [ ");
				for (String s : stopped) {
					String provider = providerPerId.get(s);
					sb.append(String.format("%s%s, ", s, provider != null ? "@" + provider : ""));
				}
				sb.deleteCharAt(sb.length() - 2);
				sb.append("]");
			}

			return sb.toString();
		}
		
		private String getNameFromId(String id) {
			for (String name : idPerName.keySet()) {
				String value = idPerName.get(name);
				if (value.equals(id))
					return name;
			}
			return null;
		}
		
		public List<String> getUsedProviders() {
			Map<Integer, String> tmp = new TreeMap<Integer, String>();
			
			String baseProvider = providerPerId.get(idPerName.get(vm));
			tmp.put(0, baseProvider);
			
			for (String id : providerPerId.keySet()) {
				String provider = providerPerId.get(id);
				if (!tmp.containsValue(provider)) {
					String vm = getNameFromId(id);
					int i = vm.indexOf("(no_");
					if (i > -1) {
						int j = vm.indexOf(")", i);
						int count = Integer.parseInt(vm.substring(i + 4, j));
						tmp.put(count, provider);
					}
				}
			}
			
			List<String> res = new ArrayList<String>();
			
			for (int i : tmp.keySet())
				res.add(tmp.get(i));
			
			return res;
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
		
		public static String sanitizeName(String name) {
			if (name.indexOf("fromImage") > -1)
				name = name.substring(0, name.indexOf('('));
			if (name.indexOf("(no_") > -1)
				name = name.substring(0, name.indexOf("(no_"));
			if (name.indexOf('@') > -1)
				name = name.substring(0, name.indexOf('@'));
			
			return name;
		}
	}

}
