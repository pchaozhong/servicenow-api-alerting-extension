/*
 * Copyright 2014. AppDynamics LLC and its affiliates.
 * All Rights Reserved.
 * This is unpublished proprietary source code of AppDynamics LLC and its affiliates.
 * The copyright notice above does not evidence any actual or intended publication of such source code.
 */

package com.appdynamics.extensions.servicenow;


import com.appdynamics.extensions.alerts.customevents.EvaluationEntity;
import com.appdynamics.extensions.alerts.customevents.Event;
import com.appdynamics.extensions.alerts.customevents.EventBuilder;
import com.appdynamics.extensions.alerts.customevents.HealthRuleViolationEvent;
import com.appdynamics.extensions.alerts.customevents.TriggerCondition;
import com.appdynamics.extensions.servicenow.api.Alert;
import com.appdynamics.extensions.servicenow.common.Configuration;
import com.appdynamics.extensions.servicenow.common.Field;
import com.appdynamics.extensions.servicenow.common.FileSystemStore;
import com.appdynamics.extensions.servicenow.common.HttpHandler;
import com.appdynamics.extensions.yml.YmlReader;
import com.google.common.base.Strings;
import org.apache.log4j.Logger;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class ServiceNowAlertExtension {

    private static Logger logger = Logger.getLogger(ServiceNowAlertExtension.class);
    private static final String CONFIG_FILENAME = "." + File.separator + "conf" + File.separator + "config.yaml";
    private Configuration config;
    private static final String NEW_LINE = "\n";
    private static final String SPACE = " ";

    public static final String POLICY_CLOSE = "POLICY_CLOSE";
    public static final String POLICY_CANCELED = "POLICY_CANCELED";

    final EventBuilder eventBuilder = new EventBuilder();

    public static void main(String[] args) {

        try {

            if (logger.isDebugEnabled()) {
                logger.debug("Args passed are " + Arrays.toString(args));
            }

            Configuration config = YmlReader.readFromFile(CONFIG_FILENAME, Configuration.class);
            ServiceNowAlertExtension serviceNowAlert = new ServiceNowAlertExtension(config);
            boolean status = serviceNowAlert.processAnEvent(args);
            if (status) {
                logger.info("ServiceNow Extension completed successfully.");
                return;
            }
        } catch (Exception e) {
            logger.error("Error processing an event", e);
        }
        logger.error("ServiceNow Extension completed with errors");
    }

    public ServiceNowAlertExtension(Configuration config) {
        String msg = "ServiceNowAlert Version [" + getImplementationTitle() + "]";
        logger.info(msg);
        System.out.println(msg);
        this.config = config;
    }


    private boolean processAnEvent(String[] args) {
        Event event = eventBuilder.build(args);
        if (event != null) {
            if (event instanceof HealthRuleViolationEvent) {
                HealthRuleViolationEvent violationEvent = (HealthRuleViolationEvent) event;
                Alert alert = buildAlert(violationEvent);

                String incidentID = violationEvent.getIncidentID();
                String snowSysId = FileSystemStore.INSTANCE.getFromStore(incidentID);

                HttpHandler handler = new HttpHandler(config);

                if (Strings.isNullOrEmpty(snowSysId)) {
                    return handler.postAlert(alert, incidentID);
                } else {
                    String eventType = violationEvent.getEventType();
                    boolean closeEvent = shouldResolveEvent(eventType);
                    return handler.updateAlert(alert, incidentID, snowSysId, closeEvent);
                }
            } else {
                logger.error("This extension only works with Health Rule Violation Event. Skipping this event as this is not Health Rule Violation Event");
            }
        }
        return false;
    }

    private Alert buildAlert(HealthRuleViolationEvent violationEvent) {

        String comments = buildSummery(violationEvent);
        String shortDescription = buildShortDescription(violationEvent);

        Alert alert = new Alert();
        alert.setImpact(getImpact(violationEvent));
        alert.setPriority(violationEvent.getPriority());
        alert.setShortDescription(shortDescription);
        alert.setComments(comments);

        List<Field> fields = config.getFields();

        if (fields != null) {
            for (Field field : fields) {
                if (field.getValue() != null && field.getValue().length() > 0) {
                    alert.addDynamicProperties(field.getName(), field.getValue());
                }
            }
        }

        return alert;
    }

    private String buildShortDescription(HealthRuleViolationEvent violationEvent) {
        StringBuilder sb = new StringBuilder("Policy");
        sb.append(SPACE).append(violationEvent.getHealthRuleName()).append(SPACE).append("for")
                .append(SPACE).append(violationEvent.getAffectedEntityName()).append(SPACE).append("violated");
        return sb.toString();
    }

    private String getImpact(HealthRuleViolationEvent violationEvent) {
        String severity = violationEvent.getSeverity();
        String severityInt = null;
        if ("ERROR".equals(severity)) {
            severityInt = "1";
        } else if ("WARN".equals(severity)) {
            severityInt = "2";
        } else {
            severityInt = "3";
        }
        return severityInt;
    }

    private String buildSummery(HealthRuleViolationEvent violationEvent) {
        StringBuilder summery = new StringBuilder();
        summery.append("Application Name:").append(violationEvent.getAppName()).append(NEW_LINE);
        summery.append("Policy Violation Alert Time:").append(violationEvent.getPvnAlertTime()).append(NEW_LINE);
        summery.append("Severity:").append(violationEvent.getSeverity()).append(NEW_LINE);
        summery.append("Name of Violated Policy:").append(violationEvent.getHealthRuleName()).append(NEW_LINE);
        summery.append("Affected Entity Type:").append(violationEvent.getAffectedEntityType()).append(NEW_LINE);
        summery.append("Name of Affected Entity:").append(violationEvent.getAffectedEntityName()).append(NEW_LINE);

        List<EvaluationEntity> evaluationEntities = violationEvent.getEvaluationEntity();
        for (int i = 0; i < evaluationEntities.size(); i++) {
            EvaluationEntity evaluationEntity = evaluationEntities.get(i);
            summery.append("EVALUATION ENTITY #").append(i + 1).append(":").append(NEW_LINE);
            summery.append("Evaluation Entity:").append(evaluationEntity.getType()).append(NEW_LINE);
            summery.append("Evaluation Entity Name:").append(evaluationEntity.getName()).append(NEW_LINE);

            List<TriggerCondition> triggeredConditions = evaluationEntity.getTriggeredConditions();
            for (int j = 0; j < triggeredConditions.size(); j++) {
                TriggerCondition triggerCondition = triggeredConditions.get(j);
                summery.append("Triggered Condition #").append(j + 1).append(":").append(NEW_LINE).append(NEW_LINE);
                summery.append("Scope Type:").append(triggerCondition.getScopeType()).append(NEW_LINE);
                summery.append("Scope Name:").append(triggerCondition.getScopeName()).append(NEW_LINE);

                if (triggerCondition.getConditionUnitType() != null && triggerCondition.getConditionUnitType().toUpperCase().startsWith("BASELINE")) {
                    summery.append("Is Default Baseline?").append(triggerCondition.isUseDefaultBaseline() ? "true" : "false").append(NEW_LINE);
                    if (!triggerCondition.isUseDefaultBaseline()) {
                        summery.append("Baseline Name:").append(triggerCondition.getBaselineName()).append(NEW_LINE);
                    }
                }
                summery.append(triggerCondition.getConditionName()).append(triggerCondition.getOperator()).append(triggerCondition.getThresholdValue()).append(NEW_LINE);
                summery.append("Violation Value:").append(triggerCondition.getObservedValue()).append(NEW_LINE).append(NEW_LINE);
                summery.append("Incident URL:").append(violationEvent.getIncidentUrl());
            }
        }
        return summery.toString();
    }

    private boolean shouldResolveEvent(String eventType) {
        return eventType != null && (eventType.startsWith(POLICY_CLOSE) || eventType.startsWith(POLICY_CANCELED));
    }

    private String getImplementationTitle() {
        return this.getClass().getPackage().getImplementationTitle();
    }
}