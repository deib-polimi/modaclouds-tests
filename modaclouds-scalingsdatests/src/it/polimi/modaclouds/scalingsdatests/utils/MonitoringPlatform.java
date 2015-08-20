package it.polimi.modaclouds.scalingsdatests.utils;

import it.polimi.modaclouds.scalingsdatests.schemas.adaptationRuntime.ApplicationTier;
import it.polimi.modaclouds.scalingsdatests.schemas.adaptationRuntime.Container;
import it.polimi.modaclouds.scalingsdatests.schemas.adaptationRuntime.Containers;
import it.polimi.tower4clouds.manager.api.ManagerAPI;
import it.polimi.tower4clouds.rules.Action;
import it.polimi.tower4clouds.rules.CollectedMetric;
import it.polimi.tower4clouds.rules.MonitoredTarget;
import it.polimi.tower4clouds.rules.MonitoringRule;
import it.polimi.tower4clouds.rules.MonitoringRules;
import it.polimi.tower4clouds.rules.ObjectFactory;
import it.polimi.tower4clouds.rules.Parameter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MonitoringPlatform {

	@SuppressWarnings("unused")
	private static final Logger logger = LoggerFactory
			.getLogger(MonitoringPlatform.class);

	private ObjectFactory factory = new ObjectFactory();

	private String ip;
	private int port;

	public MonitoringPlatform(String ip, int port) {
		this.ip = ip;
		this.port = port;
	}

	public void installRules(MonitoringRules toInstall) throws Exception {
		ManagerAPI manager = new ManagerAPI(ip, port);
		manager.installRules(toInstall);
	}

//	public void loadModel() throws Exception {
//		sendMessageFromFile("/v1/model/resources", Configuration.MONITORING_PLATFORM_MODEL, Method.PUT);
//	}

	public void attachObserver(String targetMetric, String observerIP,
							   String observerPort) throws Exception {
		attachObserver(targetMetric, observerIP, observerPort, "TOWER/JSON");
	}

	public void attachObserver(String targetMetric, String observerIP,
			String observerPort, String format) throws Exception {
		ManagerAPI manager = new ManagerAPI(ip, port);
		manager.registerHttpObserver(targetMetric, String.format("http://%s:%s/data", observerIP, observerPort), format);
	}

	public MonitoringRules buildDemandRule(Containers containers) {

		MonitoringRules toReturn = factory.createMonitoringRules();
		MonitoringRule rule;
		MonitoredTarget target;
		Action action;
		CollectedMetric collectedMetric;
		Parameter tempParam;

		rule = factory.createMonitoringRule();

		rule.setId("sdaHaproxy");
		rule.setTimeStep("10");
		rule.setTimeWindow("10");
		rule.setMonitoredTargets(factory.createMonitoredTargets());

		for (Container c : containers.getContainer()) {

			for (ApplicationTier t : c.getApplicationTier()) {

				target = factory.createMonitoredTarget();
				target.setClazz("VM");
				target.setType(t.getId());
				rule.getMonitoredTargets().getMonitoredTargets().add(target);

			}

		}

		collectedMetric = factory.createCollectedMetric();
		collectedMetric.setMetricName("EstimationUBR");

		tempParam = factory.createParameter();
		tempParam.setName("window");
		tempParam.setValue("60000");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("nCPU");
		tempParam.setValue("4");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("CPUUtilTarget");
		tempParam.setValue("MIC");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("CPUUtilMetric");
		tempParam.setValue("FrontendCPUUtilization");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("targetMetric");
		tempParam.setValue("AvarageResponseTime");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("samplingTime");
		tempParam.setValue("300");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("filePath");
		tempParam.setValue("/home/ubuntu/modaclouds-sda-1.2.2/");
		collectedMetric.getParameters().add(tempParam);

		rule.setCollectedMetric(collectedMetric);

		action = factory.createAction();
		action.setName("OutputMetric");

		tempParam = factory.createParameter();
		tempParam.setName("metric");
		tempParam.setValue("EstimatedDemand");
		action.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("value");
		tempParam.setValue("METRIC");
		action.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("resourceId");
		tempParam.setValue("ID");
		action.getParameters().add(tempParam);

		rule.setActions(factory.createActions());

		rule.getActions().getActions().add(action);

		toReturn.getMonitoringRules().add(rule);

		return toReturn;

	}

	public MonitoringRules buildWorkloadForecastRule(Containers containers,
			int timestepAhead) {
		MonitoringRules toReturn = factory.createMonitoringRules();
		MonitoringRule rule;
		MonitoredTarget target;
		Action action;
		CollectedMetric collectedMetric;
		Parameter tempParam;

		rule = factory.createMonitoringRule();

		rule.setId("sdaForecast" + timestepAhead);
		rule.setTimeStep("10");
		rule.setTimeWindow("10");
		rule.setMonitoredTargets(factory.createMonitoredTargets());

		for (Container c : containers.getContainer()) {

			for (ApplicationTier t : c.getApplicationTier()) {

				target = factory.createMonitoredTarget();
				target.setClazz("VM");
				target.setType(t.getId());
				rule.getMonitoredTargets().getMonitoredTargets().add(target);

			}

		}
		collectedMetric = factory.createCollectedMetric();
		collectedMetric.setMetricName("ForecastingTimeSeriesARIMA5Min");

		tempParam = factory.createParameter();
		tempParam.setName("targetMetric");
		tempParam.setValue("Workload");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("forecastPeriod");
		tempParam.setValue(Integer.toString(30 * timestepAhead));
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("autoregressive");
		tempParam.setValue("1");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("movingAverage");
		tempParam.setValue("1");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("integrated");
		tempParam.setValue("1");
		collectedMetric.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("samplingTime");
		tempParam.setValue("300");
		collectedMetric.getParameters().add(tempParam);

		rule.setCollectedMetric(collectedMetric);

		action = factory.createAction();
		action.setName("OutputMetric");

		tempParam = factory.createParameter();
		tempParam.setName("metric");
		tempParam.setValue("ForecastedWorkload" + timestepAhead);
		action.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("value");
		tempParam.setValue("METRIC");
		action.getParameters().add(tempParam);

		tempParam = factory.createParameter();
		tempParam.setName("resourceId");
		tempParam.setValue("ID");
		action.getParameters().add(tempParam);

		rule.setActions(factory.createActions());

		rule.getActions().getActions().add(action);

		toReturn.getMonitoringRules().add(rule);

		return toReturn;
	}

	public void getMonitoringRules() {

	}
}
