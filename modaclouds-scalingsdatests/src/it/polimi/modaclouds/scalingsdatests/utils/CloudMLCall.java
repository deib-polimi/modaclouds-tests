package it.polimi.modaclouds.scalingsdatests.utils;

import it.cloud.amazon.ec2.AmazonEC2;
import it.cloud.amazon.ec2.VirtualMachine;
import it.cloud.amazon.ec2.VirtualMachine.Instance;
import it.cloud.utils.Ssh;
import it.polimi.modaclouds.scalingsdatests.Test;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudMLCall {

	protected static final Logger logger = LoggerFactory.getLogger(CloudMLCall.class);

	public static void main(String[] args) throws Exception {
		boolean machineAlreadyPrepared = true;
		boolean restartCloudML = false;
		boolean useLocalCloudML = false;
		boolean useExternalLoadBalancer = true;
		boolean rebootMachine = false;

		Test.App usedApp = Test.App.HTTPAGENT;

		VirtualMachine mpl = VirtualMachine.getVM("mpl", "m3.medium", 1);
		VirtualMachine app = VirtualMachine.getVM(usedApp.name, "m3.medium", 1);
		VirtualMachine lb = VirtualMachine.getVM("lb");
		String loadBalancer;
		if (useExternalLoadBalancer)
			loadBalancer = "109.231.126.56";
		else
			loadBalancer = "ScalingSDATests090";
		String cloudMLIp = null;
		int cloudMLPort = Integer.parseInt(mpl.getParameter("CLOUDML_PORT"));

		if (!useLocalCloudML) {
			AmazonEC2 ec2 = new AmazonEC2();
			
			do {
				ec2.addRunningInstances(mpl);
				if (mpl.getInstances().size() == 0) {
					logger.info("No machines found, retrying in 10 seconds...");
					try {
						Thread.sleep(10000);
					} catch (Exception e) { }
				}
			} while (mpl.getInstances().size() == 0);

			Instance impl = mpl.getInstances().get(0);
			impl.setName("MPLSDA");

			impl.waitUntilRunning();
			impl.waitUntilSshAvailable();

			String mplIp = impl.getIp();
			cloudMLIp = mplIp;
			
			if (rebootMachine) {
				impl.reboot();
				Thread.sleep(10000);
				impl.waitUntilRunning();
				impl.waitUntilSshAvailable();
			}

			if (!machineAlreadyPrepared) {
				impl.exec(mpl.getParameter("UPDATER"));
			}
			
			if (!machineAlreadyPrepared || rebootMachine) {
				impl.exec(String.format(mpl.getParameter("STARTER"), mplIp));
				Thread.sleep(10000);
			}
			
			if (!machineAlreadyPrepared || rebootMachine || restartCloudML) {
				if (useExternalLoadBalancer) {
					Ssh.exec(loadBalancer, lb, lb.getParameter("STOPPER"));
					Ssh.exec(loadBalancer, lb, lb.getParameter("STARTER"));
				}
				
				impl.exec(String.format(mpl.getParameter("CLOUDML_STOPPER"), Integer.toString(cloudMLPort)));
				impl.exec(String.format(mpl.getParameter("CLOUDML_STARTER"), Integer.toString(cloudMLPort)));
				Thread.sleep(10000);
			}
		} else {
			cloudMLIp = "localhost";

			if (restartCloudML) {
				if (useExternalLoadBalancer) {
					Ssh.exec(loadBalancer, lb, lb.getParameter("STOPPER"));
					Ssh.exec(loadBalancer, lb, lb.getParameter("STARTER"));
				}
				
				CloudMLDaemon.stop(cloudMLPort);
				CloudMLDaemon.start(cloudMLPort);
				Thread.sleep(10000);
			}
		}

		CloudML cml = null;
		while (cml == null) {
			try {
				cml = new CloudML(cloudMLIp, cloudMLPort);
			} catch (Exception e) {
				logger.error("Error", e);
				cml = null;
				logger.info("No CloudML found, retrying in 10 seconds...");
				
				if (!useLocalCloudML) {
					mpl.getInstances().get(0).exec(String.format(mpl.getParameter("CLOUDML_STOPPER"), Integer.toString(cloudMLPort)));
					mpl.getInstances().get(0).exec(String.format(mpl.getParameter("CLOUDML_STARTER"), Integer.toString(cloudMLPort)));
				} else {
					CloudMLDaemon.stop(cloudMLPort);
					CloudMLDaemon.start(cloudMLPort);
				}
				
				try {
					Thread.sleep(10000);
				} catch (Exception e1) { }
			}
		}

		logger.info("Deploy the system...");

		if (restartCloudML || !machineAlreadyPrepared || rebootMachine)
			cml.deploy(Test.getActualDeploymentModel(cloudMLIp, mpl, app, usedApp.cloudMl, usedApp.cloudMlLoadBalancer, loadBalancer, true, false, null, useExternalLoadBalancer).toFile());

		logger.info("Starting the test...");

		cml.scale(usedApp.tierName + "Flexiant", 1);
		
//		cml.scale(usedApp.tierName + "Flexiant", 1);
//
//		cml.burst(usedApp.tierName + "Flexiant");
//
//		cml.terminateAllInstances();

		logger.info("Test ended!");
	}
	
	public static class CloudML implements PropertyChangeListener {
	
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
				logger.info("No instances found!");
	
			for (String tier : instancesPerTier.keySet()) {
				Instances i = instancesPerTier.get(tier);
				logger.info(i.toString());
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
				logger.error("Error while reading the file.", e);
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
				logger.info("Scaling out of {} skipped because {} <= 0.", vmId, times);
				return;
			}
			logger.info("Scaling out {} instances.", times);
	
			if (blocking)
				wsClient.sendBlocking(String.format(Command.SCALE_OUT.command, vmId, Integer.toString(times)), Command.SCALE_OUT);
			else
				wsClient.send(String.format(Command.SCALE_OUT.command, vmId, Integer.toString(times)));
		}
	
		public void updateStatus() {
			logger.info("Asking for the deployment model...");
	
	//		wsClient.sendBlocking(Command.GET_STATUS.command, Command.GET_STATUS);
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
				logger.info("Stopping the instance with id {}...", instance);
				
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
				logger.info("Restarting the instance with id {}...", instance);
				
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
		
		@ClientEndpoint
		public class WSClient implements AutoCloseable {
			private String serverURI;
			
			public WSClient(String serverURI) throws Exception {
				this.serverURI = serverURI;
				
				open();
			}
			
			public void open() throws Exception {
				if (session != null)
					logger.info("You're already connected!");
				
				WebSocketContainer container = ContainerProvider.getWebSocketContainer();
		        container.connectToServer(this, new URI(serverURI));
			}
	
			private void parseRuntimeDeploymentModel(String body) throws Exception {
				JSONObject jsonObject = new JSONObject(body.substring( body.indexOf('{') ));
				JSONArray instances = jsonObject.getJSONArray("vmInstances");
	
				instancesPerTier = new HashMap<String, CloudMLCall.Instances>();
	
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
	
				if (tier.indexOf("fromImage") > -1)
					tier = tier.substring(0, tier.indexOf('('));
	
				Instances inst = instancesPerTier.get(tier);
				if (inst != null) {
	
					logger.trace("{} is {}", id, status);
	
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
	
			private boolean parseUpdate(String body) throws Exception {
				body = body.replaceAll("''", "<>");
				JSONObject jsonObject = new JSONObject(body.substring(body.indexOf('{'), body.lastIndexOf('}')+1));
	
				String newValue = jsonObject.has("newValue") ? jsonObject.getString("newValue") : null;
				String parent = jsonObject.has("parent") ? jsonObject.getString("parent") : null;
				String property = jsonObject.has("property") ? jsonObject.getString("property") : null;
	
				if (parent == null || !property.equalsIgnoreCase("status"))
					return false;
	
				parent = parent.substring(parent.indexOf("<>")+2, parent.lastIndexOf("<>"));
	
				logger.trace("Vm: {}, Property: {}, NewValue: {}", parent, property, newValue);
	
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
	
				logger.trace("{} is {}", id, newValue);
	
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
				
				logger.trace(">>> {}", command);
				
				try {
					session.getBasicRemote().sendText("!listenToAny");
					
					Thread.sleep(800);
					
					session.getBasicRemote().sendText(command);
				} catch (Exception e) {
					logger.error("Error while sending the command.", e);
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
	
				logger.trace("<<< {}", s);
	
				if (s.contains("ack") && s.contains("ScaleOut")) {
					if (somebodyWaiting(Command.SCALE_OUT)) {
						logger.info("Scale out completed.");
		
						pcs.firePropertyChange(Command.SCALE_OUT.name, false, true);
					}
				} else if (s.contains(RESULT_SNAPSHOT)
						&& !s.contains("!snapshot")) {
	
					try {
						logger.info("Updating the runtime environment...");
						parseRuntimeDeploymentModel(s);
					} catch (Exception e) {
						logger.error("Error while updating the runtime environment.", e);
					}
	
	//				pcs.firePropertyChange(Command.GET_STATUS.name, false, true);
				} else if (s.contains(RESULT_SNAPSHOT)
						&& s.contains("!snapshot")) {
	
					try {
						logger.info("Received instance information.");
						parseInstanceInformation(s);
					} catch (Exception e) {
						logger.error("Error while updating the instance information.", e);
					}
	
					pcs.firePropertyChange(Command.GET_INSTANCE_STATUS.name, false, true);
	
					for (String tier : instancesPerTier.keySet()) {
						boolean end = true;
						Instances ins = instancesPerTier.get(tier);
						end &= ins.count() == ins.ipPerId.size();
	
						logger.trace("Received the information about {} instances out of {} for the tier {}.", ins.count(), ins.ipPerId.size(), tier);
						printStatus();
	
						if (end)
							pcs.firePropertyChange(Command.GET_STATUS.name, false, true);
					}
	
				} else if (s.contains("ack") && s.contains("Deploy") && !s.contains("MaxVMsReached")) {
					logger.info("Deploy completed.");
	
					pcs.firePropertyChange(Command.DEPLOY.name, false, true);
	
					try {
						Thread.sleep(10000);
					} catch (Exception e) { }
	
					updateStatus();
				} else if (s.contains("ack") && s.contains("Burst")) {
					logger.info("Burst completed.");
	
					pcs.firePropertyChange(Command.BURST.name, false, true);
	
					try {
						Thread.sleep(10000);
					} catch (Exception e) { }
	
					updateStatus();
				} else if (s.contains("ack") && s.contains("MaxVMsReached")) {
					logger.info("It was impossible to perform the scale out because you reached the max VM constraint.");
	
					pcs.firePropertyChange(Command.SCALE_OUT.name, false, true);
					
					String params = commandParam.remove(Command.SCALE_OUT);
					if (params != null) {
						logger.info("Trying the burst instead...");
						String[] paramsArray = params.split(";");
						String id = paramsArray[0];
						burst(id);
					}
				} else if (s.contains("ack")) {
	
					logger.trace("Ack received: {}", s);
					pcs.firePropertyChange("OtherCommand", false, true);
				} else if (s.contains("!updated")) {
	
					boolean res = false;
	
					try {
						res = parseUpdate(s);
					} catch (Exception e) {
						logger.error("Error while updating the saved informations.", e);
					}
					if (res)
						pcs.firePropertyChange("Update", false, true);
				}
			}
			
			@OnOpen
			public void onOpen(Session session) {
				logger.info("Connected to the CloudML server {}.", serverURI);
				this.session = session;
				
				pcs.firePropertyChange("Connection", false, true);
				signalClearWaiting();
			}
	
			@OnClose
			public void onClose(CloseReason closeReason) {
				logger.info("Disconnected from the CloudML server {} ({}: {}).", serverURI, closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
				session = null;
				
				pcs.firePropertyChange("Connection", true, false);
				signalClearWaiting();
			}
			
			@OnError
			public void onError(Throwable e) {
				logger.error("Error met.", e);
	
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
					logger.error("Error while disconnecting.", e);
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
			commandParam.put(Command.GET_STATUS, String.format("%s;1;%s", id, Command.BURST.name));
	
			wsClient.sendBlocking(Command.GET_STATUS.command, Command.BURST);
	
			return true;
		}
	
		private boolean burst(String id, String provider) {
	//		wsClient.sendBlocking(String.format(Command.BURST.command, id, provider), Command.BURST);
			wsClient.send(String.format(Command.BURST.command, id, provider));
	
			return true;
		}
	
		private void signalWaiting(Command what) {
			Integer wait = waitingPerCommand.get(what);
			if (wait == null)
				wait = 0;
	
			wait++;
			logger.trace("New operation [{}] started, {} waiting in total.", what.name, wait);
	
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
				logger.trace("Operation [{}] completed, {} waiting in total.", what.name, wait);
			else
				logger.trace("Operation [{}] completed with reason '{}', {} waiting in total.", what.name, reason, wait);
	
			waitingPerCommand.put(what, wait);
		}
	
		@Override
		public void propertyChange(PropertyChangeEvent evt) {
			logger.debug("Property changed: {}", evt.getPropertyName());
	
			if (evt.getPropertyName().equals(Command.SCALE_OUT.name)) {
				signalCompleted(Command.SCALE_OUT);
				signalCompleted(Command.SCALE);
			} else if (evt.getPropertyName().equals(Command.BURST.name)) {
				signalCompleted(Command.BURST); 
			} else if (evt.getPropertyName().equals(Command.GET_STATUS.name)) {
				signalCompleted(Command.GET_STATUS);
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
						if (instances.running.size() -1 < toBeShuttedDown)
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
							String id = instances.idPerName.get(instances.vm);
							
							commandParam.put(Command.SCALE_OUT, String.format("%s;%d;%s", id, toBeCreated, Command.SCALE_OUT.name));
							scaleOut(id, toBeCreated, false);
						}
					}
	
	//				signalCompleted(Command.SCALE);
				} else if (cmd == Command.BURST) {
					if (!instancesPerTier.containsKey(tier)) {
						signalCompleted(Command.BURST, "Bursting an unknown tier");
	
						return;
					}
	
					Instances instances = instancesPerTier.get(tier).clone();
					if (instances.running.size() == 0) {
						signalCompleted(Command.BURST, "Bursting a non running tier");
	
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
	
						return;
					}
	
					burst(instances.idPerName.get(instances.vm), provider);
	
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

		public List<String> getUsedProviders() {
			List<String> res = new ArrayList<String>();
			for (String key : providerPerId.keySet()) {
				String provider = providerPerId.get(key);
				if (!res.contains(provider))
					res.add(provider);
			}
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
	}

}