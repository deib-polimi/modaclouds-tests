package it.polimi.modaclouds.scalingrules.model;

import it.polimi.modaclouds.scalingrules.exceptions.TierNotFoudException;
import it.polimi.modaclouds.scalingrules.schemas.adaptationRuntime.ApplicationTier;
import it.polimi.modaclouds.scalingrules.schemas.adaptationRuntime.Container;
import it.polimi.modaclouds.scalingrules.schemas.adaptationRuntime.Containers;
import it.polimi.modaclouds.scalingrules.schemas.adaptationRuntime.Functionality;
import it.polimi.modaclouds.scalingrules.schemas.adaptationRuntime.ObjectFactory;
import it.polimi.modaclouds.scalingrules.schemas.adaptationRuntime.ResponseTimeThreshold;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class ModelManager {

	private static final Logger logger = LoggerFactory.getLogger(ModelManager.class);

	private static Containers model;
	private static List<ApplicationTierAtRuntime> runtimeEnv;

	private static int optimizationWindow;
	private static int timestepDuration;

	private static Map<String, TierTempRuntimeData> tempMonitoringData;

	public static void setOptimizationWindow(int horizon) {
		optimizationWindow = horizon;
	}

	public static int getOptimizationWindow() {
		return optimizationWindow;
	}

	public static Containers getModel() {
		if (model != null) {
			return model;
		}

		return null;
	}

	public static void loadModel(String pathToSourceModel, String window,
			String timestep) {

		logger.info("loading model");

		tempMonitoringData = new HashMap<String, TierTempRuntimeData>();

		ObjectFactory factory = new ObjectFactory();
		model = factory.createContainers();
		GenericXMLHelper xmlHelper = new GenericXMLHelper(pathToSourceModel);
		runtimeEnv = new ArrayList<ApplicationTierAtRuntime>();

		optimizationWindow = Integer.parseInt(window);
		timestepDuration = Integer.parseInt(timestep);

		for (Element c : xmlHelper.getElements("container")) {
			Container toAdd = factory.createContainer();
			toAdd.setCapacity(Float.parseFloat(c.getAttribute("capacity")));
			toAdd.setMaxReserved(Integer.parseInt(c.getAttribute("maxReserved")));
			toAdd.setOnDemandCost(Float.parseFloat(c
					.getAttribute("onDemandCost")));
			toAdd.setReservedCost(Float.parseFloat(c
					.getAttribute("reservedCost")));
			toAdd.setId(UUID.randomUUID().toString() + "_capacity="
					+ toAdd.getCapacity());

			ApplicationTier tier;
			ApplicationTierAtRuntime runtimeTier;

			List<Element> tiers = xmlHelper.getElements(c, "applicationTier");
			int index = 1;

			for (Element t : tiers) {
				runtimeTier = new ApplicationTierAtRuntime();

				tier = factory.createApplicationTier();
				tier.setId(t.getAttribute("id"));
				tier.setInitialNumberOfVMs(Integer.parseInt(t
						.getAttribute("initialNumberOfVMs")));

				Functionality tempFunc;
				for (Element f : xmlHelper.getElements(t, "functionality")) {
					tempFunc = new Functionality();
					tempFunc.setId(f.getAttribute("id"));

					tier.getFunctionality().add(tempFunc);
				}

				ResponseTimeThreshold tempThreshold;

				for (Element rtt : xmlHelper.getElements(t,
						"responseTimeThreshold")) {
					tempThreshold = new ResponseTimeThreshold();

					tempThreshold.setHour(Integer.parseInt(rtt
							.getAttribute("hour")));
					tempThreshold.setValue(Float.parseFloat(rtt
							.getAttribute("value")));
					tier.getResponseTimeThreshold().add(tempThreshold);
				}

				toAdd.getApplicationTier().add(tier);

				tempMonitoringData.put(tier.getId(), new TierTempRuntimeData(
						tier.getFunctionality(), optimizationWindow));

				runtimeTier.setTierId(tier.getId());
				runtimeTier.setAlgorithmIndex(index);
				index++;
				runtimeEnv.add(runtimeTier);
			}

			model.getContainer().add(toAdd);

		}

	}

	public static void flushTemporaryMonitoringData(Container c) {
		for (ApplicationTier t : c.getApplicationTier()) {
			TierTempRuntimeData temp = tempMonitoringData.get(t.getId());
			temp.refreshBuffers(t.getFunctionality(), optimizationWindow);
		}

	}

	public static void printModel() {

		logger.info("actual model...");

		for (Container c : model.getContainer()) {
			logger.info("Container:" + c.getId());
			for (ApplicationTier t : c.getApplicationTier()) {
				ApplicationTierAtRuntime rt;
				try {
					rt = getApplicationTierAtRuntime(t.getId());
					logger.info("Tier: " + t.getId());
					logger.info("Actual instance pointed for scale out: "
									+ rt.getInstanceToScale());
					logger.info("Tier index in the optimization problem: "
									+ rt.getAlgorithmIndex());

					logger.info("running tier instances...");

					for (String instance : rt.getInstancesStartTimes().keySet()) {
						if (rt.getInstancesStartTimes().get(instance) != null) {
							logger.info(instance + "started at "
									+ rt.getInstancesStartTimes().get(instance));
						} else {
							logger.info(instance
									+ "actually stopped");
						}
					}
					logger.info("hosted functionalities");

					for (Functionality f : t.getFunctionality()) {
						logger.info(f.getId());
					}
				} catch (TierNotFoudException e) {
					e.printStackTrace();
				}

			}
		}
	}

	public static void updateDemand(String monitoredResource,
			Float monitoredValue, String functionality) {

		System.out.println("Updating demand for resource " + monitoredResource
				+ " with value: " + monitoredValue
				+ "relative to functionality " + functionality);

		for (String key : tempMonitoringData.keySet()) {
			if (key.equals(monitoredResource)) {
				System.out.println("updating...");
				tempMonitoringData.get(key).addDemandValue(monitoredValue,
						functionality);
			}
		}

	}

	public static void updateWorkloadPrediction(String monitoredResource,
			String monitoredMetric, Float monitoredValue, String functionality) {

		System.out.println("updating workload forecast for resource "
				+ monitoredResource + " and for time step ahead "
				+ monitoredMetric + " with value: " + monitoredValue
				+ "relative to functionality " + functionality);
		for (String key : tempMonitoringData.keySet()) {

			if (key.equals(monitoredResource)) {

				for (int i = 1; i <= optimizationWindow; i++) {
					if (monitoredMetric.contains(Integer.toString(i))) {
						System.out.println("updating...");
						tempMonitoringData.get(key).addWorkloadForecastValue(
								monitoredValue, i, functionality);
					}
				}
			}

		}

	}

	public static String getLastInstanceCreated(String tierId)
			throws TierNotFoudException {
		String toReturn = null;
		Date maxDate = null;

		ApplicationTierAtRuntime tier = getApplicationTierAtRuntime(tierId);
		Map<String, Date> instances = tier.getInstancesStartTimes();

		for (String instance : instances.keySet()) {
			if (maxDate == null && instances.get(instance) != null) {
				maxDate = instances.get(instance);
				toReturn = instance;
			} else {
				if (instances.get(instance) != null) {
					if (instances.get(instance).after(maxDate)) {
						maxDate = instances.get(instance);
						toReturn = instance;
					}
				}
			}

		}

		return toReturn;

	}

	public static void updateRuntimeEnv(JSONArray instances)
			throws JSONException, TierNotFoudException {
		ApplicationTierAtRuntime temp;
		System.out.println("updating runtime environment");

		for (int i = 0; i < instances.length(); i++) {
			JSONObject instance = instances.getJSONObject(i);
			if (instance.get("id") != null) {
				System.out.println("checking instance "
						+ instance.get("id")
						+ " on tier "
						+ instance
								.get("type")
								.toString()
								.substring(
										4,
										instance.get("type").toString()
												.length() - 1));
				temp = getApplicationTierAtRuntime(instance
						.get("type")
						.toString()
						.substring(4,
								instance.get("type").toString().length() - 1));
				if (!containInstance(temp, instance.get("id").toString())) {
					temp.addNewInstance(instance.get("id").toString());

					if (temp.getInstanceToScale() == null) {
						// if(instance.getString("id").toString().equals("eu-west-1/i-6c307c8b")){
						temp.setInstanceToScale(instance.getString("id")
								.toString());
					}
				}
			}
		}
	}

	public ApplicationTier getTierById(String id) {
		for (Container c : model.getContainer()) {
			for (ApplicationTier t : c.getApplicationTier()) {
				if (t.getId().equals(id)) {
					return t;
				}
			}
		}

		return null;
	}

	public static Container getContainerByTierId(String tierId) {
		for (Container c : model.getContainer()) {
			for (ApplicationTier t : c.getApplicationTier()) {
				if (t.getId().equals(tierId)) {
					return c;
				}
			}
		}

		return null;
	}

	public Container getContainerById(String containerId) {
		for (Container c : model.getContainer()) {
			if (c.getId().equals(containerId)) {
				return c;
			}
		}

		return null;
	}

	public static List<ApplicationTierAtRuntime> getRuntimeEnv() {
		return runtimeEnv;
	}

	public static void setRuntimeEnv(List<ApplicationTierAtRuntime> runtimeModel) {
		runtimeEnv = runtimeModel;
	}

	public static ApplicationTierAtRuntime getApplicationTierAtRuntime(
			String tierId) throws TierNotFoudException {
		for (ApplicationTierAtRuntime t : runtimeEnv) {
			if (t.getTierId().equals(tierId) | tierId.startsWith(t.getTierId())) {
				return t;
			}
		}

		throw new TierNotFoudException(
				"in the runtime model there is no tier with the specified id");
	}

	public static List<String> getExpiringInstances(ApplicationTier toCheck,
			int lookAhead) {
		List<String> toReturn = new ArrayList<String>();
		Calendar cal = Calendar.getInstance();

		System.out.println("START LOOKING FOR EXPIRING INSTANCES");

		ApplicationTierAtRuntime tier;
		try {
			tier = getApplicationTierAtRuntime(toCheck.getId());
			Map<String, Date> instancesStartTimes = tier
					.getInstancesStartTimes();
			Date actual = cal.getTime();

			cal.add(Calendar.MINUTE, -(60 - timestepDuration * lookAhead));
			Date oneHourBack = cal.getTime();

			for (String instance : instancesStartTimes.keySet()) {
				// if the 'instance' is not stopped
				if (instancesStartTimes.get(instance) != null) {
					// and if the instance will be automatically recharged
					// within the next 'lookAhead' timesteps
					if (instancesStartTimes.get(instance).before(oneHourBack)) {
						System.out.println("expiring instance found within "
								+ lookAhead + " timesteps! id: " + instance
								+ " instanceStartTime: "
								+ instancesStartTimes.get(instance).toString()
								+ "" + " one hour bofere it is: "
								+ oneHourBack.toString()
								+ "actual checking time: " + actual);
						toReturn.add(instance);
					}
				}
			}
		} catch (TierNotFoudException e) {
			e.printStackTrace();
		}

		return toReturn;
	}

	public static List<String> getAvailableInstances(ApplicationTier toCheck,
			int lookAhead) {
		List<String> toReturn = new ArrayList<String>();
		Calendar cal = Calendar.getInstance();

		System.out.println("START LOOKING FOR AVAILABLE INSTANCES");

		ApplicationTierAtRuntime tier;
		try {
			tier = getApplicationTierAtRuntime(toCheck.getId());
			Map<String, Date> instancesStartTimes = tier
					.getInstancesStartTimes();
			Date actual = cal.getTime();
			cal.add(Calendar.MINUTE, -(60 - timestepDuration * lookAhead));
			Date oneHourBack = cal.getTime();

			for (String instance : instancesStartTimes.keySet()) {
				// if the 'instance' is not stopped
				if (instancesStartTimes.get(instance) != null) {
					// and if the instance doesn't need to be recharged within
					// the next 'lookAhead' timesteps
					if (instancesStartTimes.get(instance).after(oneHourBack)) {
						System.out.println("available instance found within "
								+ lookAhead + " timesteps! id: " + instance
								+ " instanceStartTime: "
								+ instancesStartTimes.get(instance).toString()
								+ "" + " one hour bofere it is: "
								+ oneHourBack.toString()
								+ "actual checking time: " + actual);
						toReturn.add(instance);
					}
				}
			}
		} catch (TierNotFoudException e) {
			e.printStackTrace();
		}

		return toReturn;
	}

	public static List<String> getStoppedInstances(ApplicationTier toCheck) {
		List<String> toReturn = new ArrayList<String>();

		System.out.println("START LOOKING FOR STOPPED INSTANCES");

		ApplicationTierAtRuntime tier;
		try {
			tier = getApplicationTierAtRuntime(toCheck.getId());
			Map<String, Date> instancesStartTimes = tier
					.getInstancesStartTimes();

			for (String instance : instancesStartTimes.keySet()) {
				// if the 'instance' is stopped
				if (instancesStartTimes.get(instance) == null) {
					System.out.println("stopped instance found: " + instance);
					toReturn.add(instance);
				}
			}
		} catch (TierNotFoudException e) {
			e.printStackTrace();
		}

		return toReturn;
	}

	public static int getTimestepDuration() {
		return timestepDuration;
	}

	public static void setTimestepDuration(int timestepDuration) {
		ModelManager.timestepDuration = timestepDuration;
	}

	private static boolean containInstance(ApplicationTierAtRuntime toCheck,
			String toFind) {

		for (String instance : toCheck.getInstancesStartTimes().keySet()) {
			if (instance.equals(toFind)) {
				return true;
			}
		}

		return false;
	}

	public static void stopInstance(String instanceId, String tierId)
			throws TierNotFoudException {
		ApplicationTierAtRuntime toUpdate = getApplicationTierAtRuntime(tierId);
		toUpdate.deleteInstance(instanceId);
	}

	public static void addInstance(String instanceId, String tierId)
			throws TierNotFoudException {
		ApplicationTierAtRuntime toUpdate = getApplicationTierAtRuntime(tierId);
		toUpdate.addNewInstance(instanceId);
	}

	public static String getTierIdByInstanceId(String instanceId) {

		for (ApplicationTierAtRuntime tier : runtimeEnv) {
			for (String instance : tier.getInstancesStartTimes().keySet()) {
				if (instance.equals(instanceId)) {
					return tier.getTierId();
				}
			}
		}

		return null;

	}
	
	public static class GenericXMLHelper {
		// XML document
		protected Document maindoc;

		// is true, if document was loaded
		private boolean loadrez = false;

		// root element of loaded document
		protected Element root;

		public GenericXMLHelper(String Filepath) {
			loadModel(Filepath);
		}

		private boolean loadModel(String Filepath) {
			try {
				File newfile = new File(Filepath);
				DocumentBuilderFactory docFactory = DocumentBuilderFactory
						.newInstance();
				DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
				this.maindoc = docBuilder.parse(newfile);
				this.root = maindoc.getDocumentElement();
				this.loadrez = true;
			} catch (Exception e) {
				// e.getMessage();
				e.printStackTrace();
				loadrez = false;
			}
			return loadrez;
		}

		public List<Element> getElements(String ThisType) {
			List<Element> res = new ArrayList<Element>();
			if (!loadrez)
				return res;
			NodeList list = this.root.getElementsByTagName(ThisType);
			for (int i = 0; i < list.getLength(); i++)
				if (list.item(i).getNodeType() == Node.ELEMENT_NODE)
					res.add((Element) list.item(i));
			return res;
		}

		public List<Element> getElements(Element element, String ThisType) {
			List<Element> res = new ArrayList<Element>();
			if (!loadrez)
				return res;
			NodeList list = element.getElementsByTagName(ThisType);
			for (int i = 0; i < list.getLength(); i++)
				if (list.item(i).getNodeType() == Node.ELEMENT_NODE)
					res.add((Element) list.item(i));
			return res;
		}
	}

}
