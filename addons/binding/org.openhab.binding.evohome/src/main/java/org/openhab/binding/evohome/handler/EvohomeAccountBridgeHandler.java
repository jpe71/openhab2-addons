/**
v * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.evohome.handler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseBridgeHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.evohome.configuration.EvohomeAccountConfiguration;
import org.openhab.binding.evohome.internal.api.EvohomeApiClient;
import org.openhab.binding.evohome.internal.api.EvohomeApiClientV2;
import org.openhab.binding.evohome.internal.api.models.v2.response.Gateway;
import org.openhab.binding.evohome.internal.api.models.v2.response.GatewayStatus;
import org.openhab.binding.evohome.internal.api.models.v2.response.Location;
import org.openhab.binding.evohome.internal.api.models.v2.response.LocationStatus;
import org.openhab.binding.evohome.internal.api.models.v2.response.Locations;
import org.openhab.binding.evohome.internal.api.models.v2.response.LocationsStatus;
import org.openhab.binding.evohome.internal.api.models.v2.response.TemperatureControlSystem;
import org.openhab.binding.evohome.internal.api.models.v2.response.TemperatureControlSystemStatus;
import org.openhab.binding.evohome.internal.api.models.v2.response.Zone;
import org.openhab.binding.evohome.internal.api.models.v2.response.ZoneStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the bridge for this binding. Controls the authentication sequence.
 * Manages the scheduler for getting updates from the API and updates the Things it contains.
 *
 * @author Jasper van Zuijlen - Initial contribution
 *
 */
public class EvohomeAccountBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(EvohomeAccountBridgeHandler.class);
    private EvohomeAccountConfiguration configuration;
    private EvohomeApiClient apiClient;
    private List<AccountStatusListener> listeners = new CopyOnWriteArrayList<AccountStatusListener>();

    protected ScheduledFuture<?> refreshTask;

    public EvohomeAccountBridgeHandler(Bridge thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        configuration = getConfigAs(EvohomeAccountConfiguration.class);

        if (checkConfig()) {
            disposeApiClient();
            apiClient = new EvohomeApiClientV2(configuration);

            // Initialization can take a while, so kick if off on a separate thread
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    if (apiClient.login()) {
                        if (checkInstallationInfo(apiClient.getInstallationInfo())) {
                            startRefreshTask();
                        } else {
                            updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                    "System Information Sanity Check failed");
                        }
                    } else {
                        updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "Authentication failed");
                    }
                }
            }, 0, TimeUnit.SECONDS);

        }
    }

    @Override
    public void dispose() {
        disposeRefreshTask();
        disposeApiClient();
        listeners.clear();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command == RefreshType.REFRESH) {
            String test = "";
        }
    }

    public Locations getEvohomeConfig() {
        return apiClient.getInstallationInfo();
    }

    public LocationsStatus getEvohomeStatus() {
        return apiClient.getInstallationStatus();
    }

    public void setTcsMode(String tcsId, String mode) {
        apiClient.setTcsMode(tcsId, mode);
    }

    public void addAccountStatusListener(AccountStatusListener listener) {
        listeners.add(listener);
        listener.accountStatusChanged(getThing().getStatus());
    }

    public void removeAccountStatusListener(AccountStatusListener listener) {
        listeners.remove(listener);
    }

    private boolean checkInstallationInfo(Locations locations) {
        boolean result = true;

        // Make sure that there are no duplicate IDs
        Set<String> ids = new HashSet<String>();

        for (Location location : locations) {
            result &= ids.add(location.locationInfo.locationId);
            for (Gateway gateway : location.gateways) {
                result &= ids.add(gateway.gatewayInfo.gatewayId);
                for (TemperatureControlSystem tcs : gateway.temperatureControlSystems) {
                    result &= ids.add(tcs.systemId);
                    for (Zone zone : tcs.zones) {
                        result &= ids.add(zone.zoneId);
                    }
                }
            }
        }
        return result;
    }

    private void disposeApiClient() {
        if (apiClient != null) {
            apiClient.logout();
        }
        apiClient = null;
    }

    private void disposeRefreshTask() {
        if (refreshTask != null) {
            refreshTask.cancel(true);
        }
    }

    private boolean checkConfig() {
        try {
            if (configuration == null) {
                updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "Configuration is missing or corrupted");
            } else if (StringUtils.isEmpty(configuration.username)) {
                updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "Username not configured");
            } else if (StringUtils.isEmpty(configuration.password)) {
                updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "Password not configured");
            } else if (StringUtils.isEmpty(configuration.applicationId)) {
                updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                        "Application Id not configured");
            } else {
                return true;
            }
        } catch (Exception e) {
            updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, e.getMessage());
        }

        return false;
    }

    private void startRefreshTask() {
        disposeRefreshTask();

        refreshTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                update();
            }
        }, 0, configuration.refreshInterval, TimeUnit.SECONDS);
    }

    private void update() {
        try {
            try {
                apiClient.update();
            } catch (Exception e) {
                updateAccountStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                return;
            }

            updateAccountStatus(ThingStatus.ONLINE);
            updateThings();
        } catch (Exception e) {
            logger.debug("update failed", e);
        }
    }

    private void updateAccountStatus(ThingStatus newStatus) {
        updateAccountStatus(newStatus, ThingStatusDetail.NONE, null);
    }

    private void updateAccountStatus(ThingStatus newStatus, ThingStatusDetail detail, String message) {
        // Prevent spamming the log file
        if (!newStatus.equals(getThing().getStatus())) {
            updateStatus(newStatus, detail, message);
            updateListeners(newStatus);
        }
    }

    private void updateListeners(ThingStatus status) {
        for (AccountStatusListener listener : listeners) {
            listener.accountStatusChanged(status);
        }
    }

    private void updateThings() {
        Map<String, TemperatureControlSystemStatus> idToTcsMap = new HashMap<>();
        Map<String, ZoneStatus> idToZoneMap = new HashMap<>();
        Map<String, GatewayStatus> tcsIdToGatewayMap = new HashMap<>();
        Map<String, String> zoneIdToTcsIdMap = new HashMap<>();
        Map<String, ThingStatus> idToTcsThingsStatusMap = new HashMap<>();

        // First, create a lookup table
        for (LocationStatus location : apiClient.getInstallationStatus()) {
            for (GatewayStatus gateway : location.gateways) {
                for (TemperatureControlSystemStatus tcs : gateway.temperatureControlSystems) {
                    idToTcsMap.put(tcs.systemId, tcs);
                    tcsIdToGatewayMap.put(tcs.systemId, gateway);
                    for (ZoneStatus zone : tcs.zones) {
                        idToZoneMap.put(zone.zoneId, zone);
                        zoneIdToTcsIdMap.put(zone.zoneId, tcs.systemId);
                    }
                }
            }
        }

        // Then update the things by type, with pre-filtered info
        for (Thing handler : getThing().getThings()) {
            ThingHandler thingHandler = handler.getHandler();

            if (thingHandler instanceof EvohomeTemperatureControlSystemHandler) {
                EvohomeTemperatureControlSystemHandler tcsHandler = (EvohomeTemperatureControlSystemHandler) thingHandler;
                tcsHandler.update(tcsIdToGatewayMap.get(tcsHandler.getId()), idToTcsMap.get(tcsHandler.getId()));
                idToTcsThingsStatusMap.put(tcsHandler.getId(), tcsHandler.getThing().getStatus());
            }
            if (thingHandler instanceof EvohomeHeatingZoneHandler) {
                EvohomeHeatingZoneHandler zoneHandler = (EvohomeHeatingZoneHandler) thingHandler;
                zoneHandler.update(idToTcsThingsStatusMap.get(zoneIdToTcsIdMap.get(zoneHandler.getId())),
                        idToZoneMap.get(zoneHandler.getId()));
            }
        }
    }

}
