/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.hue.internal.handler;

import static org.openhab.binding.hue.internal.HueBindingConstants.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.IncreaseDecreaseType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingStatusInfo;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.UnDefType;
import org.openhab.binding.hue.internal.FullGroup;
import org.openhab.binding.hue.internal.State;
import org.openhab.binding.hue.internal.State.ColorMode;
import org.openhab.binding.hue.internal.StateUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link HueGroupHandler} is the handler for a hue group of lights. It uses the {@link HueClient} to execute the
 * actual command.
 *
 * @author Laurent Garnier - Initial contribution
 */
@NonNullByDefault
public class HueGroupHandler extends BaseThingHandler implements GroupStatusListener {
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_GROUP);

    private final Logger logger = LoggerFactory.getLogger(HueGroupHandler.class);

    private @NonNullByDefault({}) String groupId;

    private @Nullable Integer lastSentColorTemp;
    private @Nullable Integer lastSentBrightness;

    private long defaultFadeTime = 400;

    private @Nullable HueClient hueClient;

    private @Nullable ScheduledFuture<?> scheduledFuture;
    private @Nullable FullGroup lastFullGroup;

    public HueGroupHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        logger.debug("Initializing hue group handler.");
        Bridge bridge = getBridge();
        initializeThing((bridge == null) ? null : bridge.getStatus());
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        logger.debug("bridgeStatusChanged {}", bridgeStatusInfo);
        initializeThing(bridgeStatusInfo.getStatus());
    }

    private void initializeThing(@Nullable ThingStatus bridgeStatus) {
        logger.debug("initializeThing thing {} bridge status {}", getThing().getUID(), bridgeStatus);
        final String configGroupId = (String) getConfig().get(GROUP_ID);
        if (configGroupId != null) {
            BigDecimal time = (BigDecimal) getConfig().get(FADETIME);
            if (time != null) {
                defaultFadeTime = time.longValueExact();
            }

            groupId = configGroupId;
            // note: this call implicitly registers our handler as a listener on the bridge
            if (getHueClient() != null) {
                if (bridgeStatus == ThingStatus.ONLINE) {
                    updateStatus(ThingStatus.ONLINE);
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                }
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-no-group-id");
        }
    }

    @Override
    public void dispose() {
        logger.debug("Hue group handler disposes. Unregistering listener.");
        cancelScheduledFuture();
        if (groupId != null) {
            HueClient bridgeHandler = getHueClient();
            if (bridgeHandler != null) {
                bridgeHandler.unregisterGroupStatusListener(this);
                hueClient = null;
            }
            groupId = null;
        }
    }

    protected synchronized @Nullable HueClient getHueClient() {
        if (hueClient == null) {
            Bridge bridge = getBridge();
            if (bridge == null) {
                return null;
            }
            ThingHandler handler = bridge.getHandler();
            if (handler instanceof HueBridgeHandler) {
                hueClient = (HueClient) handler;
                hueClient.registerGroupStatusListener(this);
            } else {
                return null;
            }
        }
        return hueClient;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        handleCommand(channelUID.getId(), command, defaultFadeTime);
    }

    public void handleCommand(String channel, Command command, long fadeTime) {
        HueClient bridgeHandler = getHueClient();
        if (bridgeHandler == null) {
            logger.debug("hue bridge handler not found. Cannot handle command without bridge.");
            return;
        }

        FullGroup group = bridgeHandler.getGroupById(groupId);
        if (group == null) {
            logger.debug("hue group not known on bridge. Cannot handle command.");
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/offline.conf-error-wrong-group-id");
            return;
        }

        StateUpdate groupState = null;
        switch (channel) {
            case CHANNEL_COLOR:
                if (command instanceof HSBType) {
                    HSBType hsbCommand = (HSBType) command;
                    if (hsbCommand.getBrightness().intValue() == 0) {
                        groupState = LightStateConverter.toOnOffLightState(OnOffType.OFF);
                    } else {
                        groupState = LightStateConverter.toColorLightState(hsbCommand, group.getState());
                        groupState.setOn(true);
                        if (groupState != null) {
                            groupState.setTransitionTime(fadeTime);
                        }
                    }
                } else if (command instanceof PercentType) {
                    groupState = LightStateConverter.toBrightnessLightState((PercentType) command);
                    if (groupState != null) {
                        groupState.setTransitionTime(fadeTime);
                    }
                } else if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                } else if (command instanceof IncreaseDecreaseType) {
                    groupState = convertBrightnessChangeToStateUpdate((IncreaseDecreaseType) command, group);
                    if (groupState != null) {
                        groupState.setTransitionTime(fadeTime);
                    }
                }
                break;
            case CHANNEL_COLORTEMPERATURE:
                if (command instanceof PercentType) {
                    groupState = LightStateConverter.toColorTemperatureLightState((PercentType) command);
                    if (groupState != null) {
                        groupState.setTransitionTime(fadeTime);
                    }
                } else if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                } else if (command instanceof IncreaseDecreaseType) {
                    groupState = convertColorTempChangeToStateUpdate((IncreaseDecreaseType) command, group);
                    if (groupState != null) {
                        groupState.setTransitionTime(fadeTime);
                    }
                }
                break;
            case CHANNEL_BRIGHTNESS:
                if (command instanceof PercentType) {
                    groupState = LightStateConverter.toBrightnessLightState((PercentType) command);
                    if (groupState != null) {
                        groupState.setTransitionTime(fadeTime);
                    }
                } else if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                } else if (command instanceof IncreaseDecreaseType) {
                    groupState = convertBrightnessChangeToStateUpdate((IncreaseDecreaseType) command, group);
                    if (groupState != null) {
                        groupState.setTransitionTime(fadeTime);
                    }
                }
                if (groupState != null && lastSentColorTemp != null) {
                    // make sure that the light also has the latest color temp
                    // this might not have been yet set in the light, if it was off
                    groupState.setColorTemperature(lastSentColorTemp);
                    groupState.setTransitionTime(fadeTime);
                }
                break;
            case CHANNEL_SWITCH:
                if (command instanceof OnOffType) {
                    groupState = LightStateConverter.toOnOffLightState((OnOffType) command);
                }
                if (groupState != null && lastSentColorTemp != null) {
                    // make sure that the light also has the latest color temp
                    // this might not have been yet set in the light, if it was off
                    groupState.setColorTemperature(lastSentColorTemp);
                    groupState.setTransitionTime(fadeTime);
                }
                break;
            case CHANNEL_ALERT:
                if (command instanceof StringType) {
                    groupState = LightStateConverter.toAlertState((StringType) command);
                    if (groupState == null) {
                        // Unsupported StringType is passed. Log a warning
                        // message and return.
                        logger.warn("Unsupported String command: {}. Supported commands are: {}, {}, {} ", command,
                                LightStateConverter.ALERT_MODE_NONE, LightStateConverter.ALERT_MODE_SELECT,
                                LightStateConverter.ALERT_MODE_LONG_SELECT);
                        return;
                    } else {
                        scheduleAlertStateRestore(command);
                    }
                }
                break;
            default:
                break;
        }
        if (groupState != null) {
            // Cache values which we have sent
            Integer tmpBrightness = groupState.getBrightness();
            if (tmpBrightness != null) {
                lastSentBrightness = tmpBrightness;
            }
            Integer tmpColorTemp = groupState.getColorTemperature();
            if (tmpColorTemp != null) {
                lastSentColorTemp = tmpColorTemp;
            }
            bridgeHandler.updateGroupState(group, groupState, fadeTime);
        } else {
            logger.debug("Command sent to an unknown channel id: {}:{}", getThing().getUID(), channel);
        }
    }

    private @Nullable StateUpdate convertColorTempChangeToStateUpdate(IncreaseDecreaseType command, FullGroup group) {
        StateUpdate stateUpdate = null;
        Integer currentColorTemp = getCurrentColorTemp(group.getState());
        if (currentColorTemp != null) {
            int newColorTemp = LightStateConverter.toAdjustedColorTemp(command, currentColorTemp);
            stateUpdate = new StateUpdate().setColorTemperature(newColorTemp);
        }
        return stateUpdate;
    }

    private @Nullable Integer getCurrentColorTemp(@Nullable State groupState) {
        Integer colorTemp = lastSentColorTemp;
        if (colorTemp == null && groupState != null) {
            colorTemp = groupState.getColorTemperature();
        }
        return colorTemp;
    }

    private @Nullable StateUpdate convertBrightnessChangeToStateUpdate(IncreaseDecreaseType command, FullGroup group) {
        StateUpdate stateUpdate = null;
        Integer currentBrightness = getCurrentBrightness(group.getState());
        if (currentBrightness != null) {
            int newBrightness = LightStateConverter.toAdjustedBrightness(command, currentBrightness);
            stateUpdate = createBrightnessStateUpdate(currentBrightness, newBrightness);
        }
        return stateUpdate;
    }

    private @Nullable Integer getCurrentBrightness(@Nullable State groupState) {
        Integer brightness = lastSentBrightness;
        if (brightness == null && groupState != null) {
            if (!groupState.isOn()) {
                brightness = 0;
            } else {
                brightness = groupState.getBrightness();
            }
        }
        return brightness;
    }

    private StateUpdate createBrightnessStateUpdate(int currentBrightness, int newBrightness) {
        StateUpdate lightUpdate = new StateUpdate();
        if (newBrightness == 0) {
            lightUpdate.turnOff();
        } else {
            lightUpdate.setBrightness(newBrightness);
            if (currentBrightness == 0) {
                lightUpdate.turnOn();
            }
        }
        return lightUpdate;
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        HueClient handler = getHueClient();
        if (handler != null) {
            FullGroup group = handler.getGroupById(groupId);
            if (group != null) {
                onGroupStateChanged(group);
            }
        }
    }

    @Override
    public boolean onGroupStateChanged(FullGroup group) {
        logger.trace("onGroupStateChanged() was called for group {}", group.getId());

        State state = group.getState();

        final FullGroup lastState = lastFullGroup;
        if (lastState == null || !Objects.equals(lastState.getState(), state)) {
            lastFullGroup = group;
        } else {
            return true;
        }

        logger.trace("New state for group {}", groupId);

        lastSentColorTemp = null;
        lastSentBrightness = null;

        updateStatus(ThingStatus.ONLINE);

        logger.debug("onGroupStateChanged Group {}: on {} bri {} hue {} sat {} temp {} mode {} XY {}", group.getName(),
                state.isOn(), state.getBrightness(), state.getHue(), state.getSaturation(), state.getColorTemperature(),
                state.getColorMode(), state.getXY());

        HSBType hsbType = LightStateConverter.toHSBType(state);
        if (!state.isOn()) {
            hsbType = new HSBType(hsbType.getHue(), hsbType.getSaturation(), new PercentType(0));
        }
        updateState(CHANNEL_COLOR, hsbType);

        ColorMode colorMode = state.getColorMode();
        if (ColorMode.CT.equals(colorMode)) {
            PercentType colorTempPercentType = LightStateConverter.toColorTemperaturePercentType(state);
            updateState(CHANNEL_COLORTEMPERATURE, colorTempPercentType);
        } else {
            updateState(CHANNEL_COLORTEMPERATURE, UnDefType.NULL);
        }

        PercentType brightnessPercentType = LightStateConverter.toBrightnessPercentType(state);
        if (!state.isOn()) {
            brightnessPercentType = new PercentType(0);
        }
        updateState(CHANNEL_BRIGHTNESS, brightnessPercentType);

        updateState(CHANNEL_SWITCH, state.isOn() ? OnOffType.ON : OnOffType.OFF);

        return true;
    }

    @Override
    public void onGroupAdded(FullGroup group) {
        onGroupStateChanged(group);
    }

    @Override
    public void onGroupRemoved() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, "@text/offline.group-removed");
    }

    @Override
    public void onGroupGone() {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.GONE, "@text/offline.group-removed");
    }

    /**
     * Schedules restoration of the alert item state to {@link LightStateConverter#ALERT_MODE_NONE} after a given time.
     * <br>
     * Based on the initial command:
     * <ul>
     * <li>For {@link LightStateConverter#ALERT_MODE_SELECT} restoration will be triggered after <strong>2
     * seconds</strong>.
     * <li>For {@link LightStateConverter#ALERT_MODE_LONG_SELECT} restoration will be triggered after <strong>15
     * seconds</strong>.
     * </ul>
     * This method also cancels any previously scheduled restoration.
     *
     * @param command The {@link Command} sent to the item
     */
    private void scheduleAlertStateRestore(Command command) {
        cancelScheduledFuture();
        int delay = getAlertDuration(command);

        if (delay > 0) {
            scheduledFuture = scheduler.schedule(() -> {
                updateState(CHANNEL_ALERT, new StringType(LightStateConverter.ALERT_MODE_NONE));
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * This method will cancel previously scheduled alert item state
     * restoration.
     */
    private void cancelScheduledFuture() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
            scheduledFuture = null;
        }
    }

    /**
     * This method returns the time in <strong>milliseconds</strong> after
     * which, the state of the alert item has to be restored to {@link LightStateConverter#ALERT_MODE_NONE}.
     *
     * @param command The initial command sent to the alert item.
     * @return Based on the initial command will return:
     *         <ul>
     *         <li><strong>2000</strong> for {@link LightStateConverter#ALERT_MODE_SELECT}.
     *         <li><strong>15000</strong> for {@link LightStateConverter#ALERT_MODE_LONG_SELECT}.
     *         <li><strong>-1</strong> for any command different from the previous two.
     *         </ul>
     */
    private int getAlertDuration(Command command) {
        int delay;
        switch (command.toString()) {
            case LightStateConverter.ALERT_MODE_LONG_SELECT:
                delay = 15000;
                break;
            case LightStateConverter.ALERT_MODE_SELECT:
                delay = 2000;
                break;
            default:
                delay = -1;
                break;
        }

        return delay;
    }

    @Override
    public String getGroupId() {
        return groupId;
    }
}
