/**
 * Copyright 2018-2020 Dynatrace LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dynatrace.openkit.protocol;

import com.dynatrace.openkit.api.Logger;
import com.dynatrace.openkit.core.caching.BeaconCache;
import com.dynatrace.openkit.core.caching.BeaconKey;
import com.dynatrace.openkit.core.configuration.BeaconConfiguration;
import com.dynatrace.openkit.core.configuration.OpenKitConfiguration;
import com.dynatrace.openkit.core.configuration.PrivacyConfiguration;
import com.dynatrace.openkit.core.configuration.ServerConfiguration;
import com.dynatrace.openkit.core.configuration.ServerConfigurationUpdateCallback;
import com.dynatrace.openkit.core.objects.BaseActionImpl;
import com.dynatrace.openkit.core.objects.SessionImpl;
import com.dynatrace.openkit.core.objects.WebRequestTracerBaseImpl;
import com.dynatrace.openkit.core.util.InetAddressValidator;
import com.dynatrace.openkit.core.util.PercentEncoder;
import com.dynatrace.openkit.providers.HTTPClientProvider;
import com.dynatrace.openkit.providers.RandomNumberGenerator;
import com.dynatrace.openkit.providers.ThreadIDProvider;
import com.dynatrace.openkit.providers.TimingProvider;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Beacon class holds all the beacon data and the beacon protocol implementation.
 */
public class Beacon {

    // basic data constants
    private static final String BEACON_KEY_PROTOCOL_VERSION = "vv";
    private static final String BEACON_KEY_OPENKIT_VERSION = "va";
    private static final String BEACON_KEY_APPLICATION_ID = "ap";
    private static final String BEACON_KEY_APPLICATION_NAME = "an";
    private static final String BEACON_KEY_APPLICATION_VERSION = "vn";
    private static final String BEACON_KEY_PLATFORM_TYPE = "pt";
    private static final String BEACON_KEY_AGENT_TECHNOLOGY_TYPE = "tt";
    private static final String BEACON_KEY_VISITOR_ID = "vi";
    private static final String BEACON_KEY_SESSION_NUMBER = "sn";
    private static final String BEACON_KEY_SESSION_SEQUENCE = "ss";
    private static final String BEACON_KEY_CLIENT_IP_ADDRESS = "ip";
    private static final String BEACON_KEY_MULTIPLICITY = "mp";
    private static final String BEACON_KEY_DATA_COLLECTION_LEVEL = "dl";
    private static final String BEACON_KEY_CRASH_REPORTING_LEVEL = "cl";
    private static final String BEACON_KEY_VISIT_STORE_VERSION = "vs";

    // device data constants
    private static final String BEACON_KEY_DEVICE_OS = "os";
    private static final String BEACON_KEY_DEVICE_MANUFACTURER = "mf";
    private static final String BEACON_KEY_DEVICE_MODEL = "md";

    // timestamp constants
    private static final String BEACON_KEY_SESSION_START_TIME = "tv";
    private static final String BEACON_KEY_TRANSMISSION_TIME = "tx";

    // Action related constants
    private static final String BEACON_KEY_EVENT_TYPE = "et";
    private static final String BEACON_KEY_NAME = "na";
    private static final String BEACON_KEY_THREAD_ID = "it";
    private static final String BEACON_KEY_ACTION_ID = "ca";
    private static final String BEACON_KEY_PARENT_ACTION_ID = "pa";
    private static final String BEACON_KEY_START_SEQUENCE_NUMBER = "s0";
    private static final String BEACON_KEY_TIME_0 = "t0";
    private static final String BEACON_KEY_END_SEQUENCE_NUMBER = "s1";
    private static final String BEACON_KEY_TIME_1 = "t1";

    // data, error & crash capture constants
    private static final String BEACON_KEY_VALUE = "vl";
    private static final String BEACON_KEY_ERROR_CODE = "ev";
    private static final String BEACON_KEY_ERROR_REASON = "rs";
    private static final String BEACON_KEY_ERROR_STACKTRACE = "st";
    private static final String BEACON_KEY_ERROR_TECHNOLOGY_TYPE = "tt";

    // web request constants
    private static final String BEACON_KEY_WEBREQUEST_RESPONSECODE = "rc";
    private static final String BEACON_KEY_WEBREQUEST_BYTES_SENT = "bs";
    private static final String BEACON_KEY_WEBREQUEST_BYTES_RECEIVED = "br";

    // in Java 6 there is no constant for "UTF-8" in the JDK yet, so we define it ourselves
    static final String CHARSET = "UTF-8";

    // max name length
    private static final int MAX_NAME_LEN = 250;

    // web request tag prefix constant
    private static final String TAG_PREFIX = "MT";

    // web request tag reserved characters
    private static final char[] RESERVED_CHARACTERS = {'_'};

    private static final char BEACON_DATA_DELIMITER = '&';

    // next ID and sequence number
    private final AtomicInteger nextID = new AtomicInteger(0);
    private final AtomicInteger nextSequenceNumber = new AtomicInteger(0);

    // session number & start time
    private final BeaconKey beaconKey;
    private final TimingProvider timingProvider;
    private final ThreadIDProvider threadIDProvider;
    private final long sessionStartTime;

    // unique device identifier
    private final long deviceID;

    // client IP address
    private final String clientIPAddress;

    // basic beacon data which does not change over time
    private final String immutableBasicBeaconData;

    // Configuration object required for this Beacon
    private final BeaconConfiguration configuration;

    private final Logger logger;

    private final BeaconCache beaconCache;

    /**
     * Creates a new beacon instance
     *
     * @param initializer           provider of relevant parameters to initialize / create the beacon
     * @param configuration         OpenKit related configuration.
     */
    public Beacon(BeaconInitializer initializer,
                  BeaconConfiguration configuration) {

        this.logger = initializer.getLogger();
        this.beaconCache = initializer.getBeaconCache();
        int sessionNumber  = initializer.getSessionIdProvider().getNextSessionID();
        int sessionSequenceNumber = initializer.getSessionSequenceNumber();
        this.beaconKey = new BeaconKey(sessionNumber, sessionSequenceNumber);
        this.timingProvider = initializer.getTimingProvider();

        this.configuration = configuration;
        this.threadIDProvider = initializer.getThreadIdProvider();
        this.sessionStartTime = timingProvider.provideTimestampInMilliseconds();

        this.deviceID = createDeviceID(initializer.getRandomNumberGenerator(), configuration);

        String ipAddress = initializer.getClientIpAddress();
        if (ipAddress == null) {
            // A client IP address, which is a null, is valid.
            // The real IP address is determined on the server side.
            this.clientIPAddress = "";
        } else if (InetAddressValidator.isValidIP(ipAddress)) {
            this.clientIPAddress = ipAddress;
        } else {
            if (logger.isWarnEnabled()) {
                logger.warning(getClass().getSimpleName() + ": Client IP address validation failed: " + ipAddress);
            }
            this.clientIPAddress = "";
        }

        immutableBasicBeaconData = createImmutableBasicBeaconData();
    }

    /**
     * Create a device ID.
     *
     * @param random        Pseudo random number generator.
     * @param configuration Configuration.
     * @return A device ID, which might either be the one set when building OpenKit or a randomly generated one.
     */
    private static long createDeviceID(RandomNumberGenerator random, BeaconConfiguration configuration) {

        if (configuration.getPrivacyConfiguration().isDeviceIDSendingAllowed()) {
            // configuration is valid and user allows data tracking
            return configuration.getOpenKitConfiguration().getDeviceID();
        }

        // no user tracking allowed
        return random.nextPositiveLong();
    }

    /**
     * Create a unique identifier.
     *
     * <p>
     * The identifier returned is only unique per Beacon.
     * Calling this method on two different Beacon instances, might give the same result.
     * </p>
     *
     * @return A unique identifier.
     */
    public int createID() {
        return nextID.incrementAndGet();
    }

    /**
     * Get the current timestamp in milliseconds by delegating to TimingProvider
     *
     * @return Current timestamp in milliseconds.
     */
    public long getCurrentTimestamp() {
        return timingProvider.provideTimestampInMilliseconds();
    }

    /**
     * Create a unique sequence number.
     *
     * <p>
     * The sequence number returned is only unique per Beacon.
     * Calling this method on two different Beacon instances, might give the same result.
     * </p>
     *
     * @return A unique sequence number.
     */
    public int createSequenceNumber() {
        return nextSequenceNumber.incrementAndGet();
    }

    /**
     * Returns the time when the session was started (in milliseconds).
     */
    public long getSessionStartTime() {
        return sessionStartTime;
    }

    /**
     * Create a web request tag.
     *
     * <p>
     * Web request tags can be attached as HTTP header for web request tracing.
     * If {@link PrivacyConfiguration#isWebRequestTracingAllowed()} yields {@code false} an empty tag is returned.
     * </p>
     *
     * @param parentActionID The ID of the {@link com.dynatrace.openkit.api.Action} for which to create a web request tag
     *                       or {@code 0} if no parent action exists.
     * @param tracerSeqNo    Sequence number of the {@link com.dynatrace.openkit.api.WebRequestTracer}.
     * @return A web request tracer tag.
     */
    public String createTag(int parentActionID, int tracerSeqNo) {
        if (!configuration.getPrivacyConfiguration().isWebRequestTracingAllowed()) {
            return "";
        }

        int serverId = configuration.getHTTPClientConfiguration().getServerID();
        StringBuilder builder = new StringBuilder(TAG_PREFIX);
        builder.append("_").append(ProtocolConstants.PROTOCOL_VERSION);
        builder.append("_").append(serverId);
        builder.append("_").append(getDeviceID());
        builder.append("_").append(getSessionNumber());
        if (getVisitStoreVersion() > 1) {
            builder.append("-").append(getSessionSequenceNumber());
        }
        builder.append("_").append(configuration.getOpenKitConfiguration().getPercentEncodedApplicationID());
        builder.append("_").append(parentActionID);
        builder.append("_").append(threadIDProvider.getThreadID());
        builder.append("_").append(tracerSeqNo);

        return builder.toString();
    }

    /**
     * Add {@link BaseActionImpl} to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param action The action to add.
     */
    public void addAction(BaseActionImpl action) {

        if (!configuration.getPrivacyConfiguration().isActionReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingDataAllowed()) {
            return;
        }

        StringBuilder actionBuilder = new StringBuilder();

        buildBasicEventData(actionBuilder, EventType.ACTION, action.getName());

        addKeyValuePair(actionBuilder, BEACON_KEY_ACTION_ID, action.getID());
        addKeyValuePair(actionBuilder, BEACON_KEY_PARENT_ACTION_ID, action.getParentID());
        addKeyValuePair(actionBuilder, BEACON_KEY_START_SEQUENCE_NUMBER, action.getStartSequenceNo());
        addKeyValuePair(actionBuilder, BEACON_KEY_TIME_0, getTimeSinceSessionStartTime(action.getStartTime()));
        addKeyValuePair(actionBuilder, BEACON_KEY_END_SEQUENCE_NUMBER, action.getEndSequenceNo());
        addKeyValuePair(actionBuilder, BEACON_KEY_TIME_1, action.getEndTime() - action.getStartTime());

        addActionData(action.getStartTime(), actionBuilder);
    }

    /**
     * Add start session event to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     */
    public void startSession() {

        if (!isDataCapturingEnabled()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        buildBasicEventData(eventBuilder, EventType.SESSION_START, null);

        addKeyValuePair(eventBuilder, BEACON_KEY_PARENT_ACTION_ID, 0);
        addKeyValuePair(eventBuilder, BEACON_KEY_START_SEQUENCE_NUMBER, createSequenceNumber());
        addKeyValuePair(eventBuilder, BEACON_KEY_TIME_0, 0L);

        addEventData(sessionStartTime, eventBuilder);
    }

    /**
     * Add {@link SessionImpl} to Beacon when session is ended.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     */
    public void endSession() {

        if (!configuration.getPrivacyConfiguration().isSessionReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingDataAllowed()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        buildBasicEventData(eventBuilder, EventType.SESSION_END, null);

        long sessionEndTime = getCurrentTimestamp();
        addKeyValuePair(eventBuilder, BEACON_KEY_PARENT_ACTION_ID, 0);
        addKeyValuePair(eventBuilder, BEACON_KEY_START_SEQUENCE_NUMBER, createSequenceNumber());
        addKeyValuePair(eventBuilder, BEACON_KEY_TIME_0, getTimeSinceSessionStartTime(sessionEndTime));

        addEventData(sessionEndTime, eventBuilder);
    }

    /**
     * Add key-value-pair to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param parentActionID The ID of the {@link com.dynatrace.openkit.api.Action} on which this value was reported.
     * @param valueName      Value's name.
     * @param value          Actual value to report.
     */
    public void reportValue(int parentActionID, String valueName, int value) {

        if (!configuration.getPrivacyConfiguration().isValueReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingDataAllowed()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        long eventTimestamp = buildEvent(eventBuilder, EventType.VALUE_INT, valueName, parentActionID);
        addKeyValuePair(eventBuilder, BEACON_KEY_VALUE, value);

        addEventData(eventTimestamp, eventBuilder);
    }

    /**
     * Add key-value-pair to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param parentActionID The ID of the {@link com.dynatrace.openkit.api.Action} on which this value was reported.
     * @param valueName      Value's name.
     * @param value          Actual value to report.
     */
    public void reportValue(int parentActionID, String valueName, double value) {

        if (!configuration.getPrivacyConfiguration().isValueReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingDataAllowed()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        long eventTimestamp = buildEvent(eventBuilder, EventType.VALUE_DOUBLE, valueName, parentActionID);
        addKeyValuePair(eventBuilder, BEACON_KEY_VALUE, value);

        addEventData(eventTimestamp, eventBuilder);
    }

    /**
     * Add key-value-pair to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param parentActionID The ID of the {@link com.dynatrace.openkit.api.Action} on which this value was reported.
     * @param valueName      Value's name.
     * @param value          Actual value to report.
     */
    public void reportValue(int parentActionID, String valueName, String value) {

        if (!configuration.getPrivacyConfiguration().isValueReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingDataAllowed()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        long eventTimestamp = buildEvent(eventBuilder, EventType.VALUE_STRING, valueName, parentActionID);
        if (value != null) {
            addKeyValuePair(eventBuilder, BEACON_KEY_VALUE, truncate(value));
        }

        addEventData(eventTimestamp, eventBuilder);
    }

    /**
     * Add event (aka. named event) to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param parentActionID The ID of the {@link com.dynatrace.openkit.api.Action} on which this event was reported.
     * @param eventName      Event's name.
     */
    public void reportEvent(int parentActionID, String eventName) {

        if (!configuration.getPrivacyConfiguration().isEventReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingDataAllowed()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        long eventTimestamp = buildEvent(eventBuilder, EventType.NAMED_EVENT, eventName, parentActionID);

        addEventData(eventTimestamp, eventBuilder);
    }

    /**
     * Add error to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param parentActionID The ID of the {@link com.dynatrace.openkit.api.Action} on which this error was reported.
     * @param errorName      Error's name.
     * @param errorCode      Some error code.
     * @param reason         Reason for that error.
     */
    public void reportError(int parentActionID, String errorName, int errorCode, String reason) {

        if (!configuration.getPrivacyConfiguration().isErrorReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingErrorsAllowed()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        buildBasicEventData(eventBuilder, EventType.ERROR, errorName);

        long timestamp = timingProvider.provideTimestampInMilliseconds();
        addKeyValuePair(eventBuilder, BEACON_KEY_PARENT_ACTION_ID, parentActionID);
        addKeyValuePair(eventBuilder, BEACON_KEY_START_SEQUENCE_NUMBER, createSequenceNumber());
        addKeyValuePair(eventBuilder, BEACON_KEY_TIME_0, getTimeSinceSessionStartTime(timestamp));
        addKeyValuePair(eventBuilder, BEACON_KEY_ERROR_CODE, errorCode);
        addKeyValuePairIfNotNull(eventBuilder, BEACON_KEY_ERROR_REASON, reason);
        addKeyValuePair(eventBuilder, BEACON_KEY_ERROR_TECHNOLOGY_TYPE, ProtocolConstants.ERROR_TECHNOLOGY_TYPE);

        addEventData(timestamp, eventBuilder);
    }

    /**
     * Add crash to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param errorName  Error's name.
     * @param reason     Reason for that error.
     * @param stacktrace Crash stacktrace.
     */
    public void reportCrash(String errorName, String reason, String stacktrace) {

        if (!configuration.getPrivacyConfiguration().isCrashReportingAllowed()) {
            return;
        }

        if (!configuration.getServerConfiguration().isSendingCrashesAllowed()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        buildBasicEventData(eventBuilder, EventType.CRASH, errorName);

        long timestamp = timingProvider.provideTimestampInMilliseconds();
        addKeyValuePair(eventBuilder, BEACON_KEY_PARENT_ACTION_ID, 0);                                  // no parent action
        addKeyValuePair(eventBuilder, BEACON_KEY_START_SEQUENCE_NUMBER, createSequenceNumber());
        addKeyValuePair(eventBuilder, BEACON_KEY_TIME_0, getTimeSinceSessionStartTime(timestamp));
        addKeyValuePairIfNotNull(eventBuilder, BEACON_KEY_ERROR_REASON, reason);
        addKeyValuePairIfNotNull(eventBuilder, BEACON_KEY_ERROR_STACKTRACE, stacktrace);
        addKeyValuePair(eventBuilder, BEACON_KEY_ERROR_TECHNOLOGY_TYPE, ProtocolConstants.ERROR_TECHNOLOGY_TYPE);

        addEventData(timestamp, eventBuilder);
    }

    /**
     * Add web request to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param parentActionID   The id of the parent {@link com.dynatrace.openkit.api.Action} on which this web request was reported.
     * @param webRequestTracer Web request tracer to serialize.
     */
    public void addWebRequest(int parentActionID, WebRequestTracerBaseImpl webRequestTracer) {

        if (!configuration.getPrivacyConfiguration().isWebRequestTracingAllowed()) {
            return;
        }

        if (!isDataCapturingEnabled()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        buildBasicEventData(eventBuilder, EventType.WEB_REQUEST, webRequestTracer.getURL());

        addKeyValuePair(eventBuilder, BEACON_KEY_PARENT_ACTION_ID, parentActionID);
        addKeyValuePair(eventBuilder, BEACON_KEY_START_SEQUENCE_NUMBER, webRequestTracer.getStartSequenceNo());
        addKeyValuePair(eventBuilder, BEACON_KEY_TIME_0, getTimeSinceSessionStartTime(webRequestTracer.getStartTime()));
        addKeyValuePair(eventBuilder, BEACON_KEY_END_SEQUENCE_NUMBER, webRequestTracer.getEndSequenceNo());
        addKeyValuePair(eventBuilder, BEACON_KEY_TIME_1, webRequestTracer.getEndTime() - webRequestTracer.getStartTime());

        addKeyValuePairIfNotNegative(eventBuilder, BEACON_KEY_WEBREQUEST_BYTES_SENT, webRequestTracer.getBytesSent());
        addKeyValuePairIfNotNegative(eventBuilder, BEACON_KEY_WEBREQUEST_BYTES_RECEIVED, webRequestTracer.getBytesReceived());
        addKeyValuePairIfNotNegative(eventBuilder, BEACON_KEY_WEBREQUEST_RESPONSECODE, webRequestTracer.getResponseCode());

        addEventData(webRequestTracer.getStartTime(), eventBuilder);
    }

    /**
     * Add user identification to Beacon.
     *
     * <p>
     * The serialized data is added to {@link com.dynatrace.openkit.core.caching.BeaconCache}.
     * </p>
     *
     * @param userTag User tag containing data to serialize.
     */
    public void identifyUser(String userTag) {

        if (!configuration.getPrivacyConfiguration().isUserIdentificationAllowed()) {
            return;
        }

        if (!isDataCapturingEnabled()) {
            return;
        }

        StringBuilder eventBuilder = new StringBuilder();

        buildBasicEventData(eventBuilder, EventType.IDENTIFY_USER, userTag);

        long timestamp = timingProvider.provideTimestampInMilliseconds();
        addKeyValuePair(eventBuilder, BEACON_KEY_PARENT_ACTION_ID, 0);
        addKeyValuePair(eventBuilder, BEACON_KEY_START_SEQUENCE_NUMBER, createSequenceNumber());
        addKeyValuePair(eventBuilder, BEACON_KEY_TIME_0, getTimeSinceSessionStartTime(timestamp));

        addEventData(timestamp, eventBuilder);
    }

    /**
     * Send current state of Beacon.
     *
     * <p>
     * This method tries to send all so far collected and serialized data.
     * </p>
     *
     * @param provider             Provider for getting an {@link HTTPClient} required to send the data.
     * @param additionalParameters additional parameters that will be send with the beacon request (can be {@code null}).
     * @return Returns the last status response retrieved from the server side, or {@code null} if an error occurred.
     */
    public StatusResponse send(HTTPClientProvider provider, AdditionalQueryParameters additionalParameters) {

        HTTPClient httpClient = provider.createClient(configuration.getHTTPClientConfiguration());
        StatusResponse response = null;

        while (true) {

            // prefix for this chunk - must be built up newly, due to changing timestamps
            String prefix = appendMutableBeaconData(immutableBasicBeaconData);
            // subtract 1024 to ensure that the chunk does not exceed the send size configured on server side?
            // i guess that was the original intention, but i'm not sure about this
            // TODO stefan.eberl - This is a quite uncool algorithm and should be improved, avoid subtracting some "magic" number
            String chunk = beaconCache.getNextBeaconChunk(beaconKey, prefix, configuration.getServerConfiguration().getBeaconSizeInBytes() - 1024, BEACON_DATA_DELIMITER);
            if (chunk == null || chunk.isEmpty()) {
                // no data added so far or no data to send
                return response;
            }

            byte[] encodedBeacon;
            try {
                encodedBeacon = encodeBeaconChunk(chunk);
            } catch (UnsupportedEncodingException e) {
                // must not happen, as UTF-8 should *really* be supported
                logger.error(getClass().getSimpleName() + ": Required charset \"" + CHARSET + "\" is not supported.", e);
                beaconCache.resetChunkedData(beaconKey);
                return response;
            }

            // send the request
            response = httpClient.sendBeaconRequest(clientIPAddress, encodedBeacon, additionalParameters);
            if (response == null || response.isErroneousResponse()) {
                // error happened - but don't know what exactly
                // reset the previously retrieved chunk (restore it in internal cache) & retry another time
                beaconCache.resetChunkedData(beaconKey);
                break;
            } else {
                // worked -> remove previously retrieved chunk from cache
                beaconCache.removeChunkedData(beaconKey);
            }
        }

        return response;
    }

    /**
     * Encodes the given chunk to an {@link #CHARSET} byte array.
     *
     * <p>
     * This method should only be used by tests.
     * </p>
     *
     * @param chunkToEncode the beacon chunk to encode
     * @return the encoded beacon chunk
     */
    protected byte[] encodeBeaconChunk(String chunkToEncode) throws UnsupportedEncodingException {
        return chunkToEncode.getBytes(CHARSET);
    }

    private String appendMutableBeaconData(String immutableBasicBeaconData) {

        StringBuilder mutableBeaconDataBuilder = new StringBuilder(immutableBasicBeaconData);
        addKeyValuePair(mutableBeaconDataBuilder, BEACON_KEY_VISIT_STORE_VERSION, getVisitStoreVersion());
        if (getVisitStoreVersion() > 1) {
            addKeyValuePair(mutableBeaconDataBuilder, BEACON_KEY_SESSION_SEQUENCE, getSessionSequenceNumber());
        }

        mutableBeaconDataBuilder.append(BEACON_DATA_DELIMITER);

        // append timestamp data
        mutableBeaconDataBuilder.append(createTimestampData());

        // append multiplicity
        mutableBeaconDataBuilder.append(BEACON_DATA_DELIMITER).append(createMultiplicityData());

        return mutableBeaconDataBuilder.toString();
    }

    /**
     * Add previously serialized action data to the beacon cache.
     *
     * @param timestamp     The timestamp when the action data occurred.
     * @param actionBuilder Contains the serialized action data.
     */
    private void addActionData(long timestamp, StringBuilder actionBuilder) {
        if (isDataCapturingEnabled()) {
            beaconCache.addActionData(beaconKey, timestamp, actionBuilder.toString());
        }
    }

    /**
     * Add previously serialized event data to the beacon cache.
     *
     * @param timestamp    The timestamp when the event data occurred.
     * @param eventBuilder Contains the serialized event data.
     */
    private void addEventData(long timestamp, StringBuilder eventBuilder) {
        if (isDataCapturingEnabled()) {
            beaconCache.addEventData(beaconKey, timestamp, eventBuilder.toString());
        }
    }

    /**
     * Clears all previously collected data for this Beacon.
     *
     * <p>
     * This only affects the so far serialized data, which gets removed from the cache.
     * </p>
     */
    public void clearData() {
        // remove all cached data for this Beacon from the cache
        beaconCache.deleteCacheEntry(beaconKey);
    }

    /**
     * Serialization helper for event data.
     *
     * @param builder        String builder storing the serialized data.
     * @param eventType      The event's type.
     * @param name           Event name
     * @param parentActionID The unique Action identifier on which this event was reported.
     * @return The timestamp associated with the event (timestamp since session start time).
     */
    private long buildEvent(StringBuilder builder, EventType eventType, String name, int parentActionID) {
        buildBasicEventData(builder, eventType, name);

        long eventTimestamp = timingProvider.provideTimestampInMilliseconds();

        addKeyValuePair(builder, BEACON_KEY_PARENT_ACTION_ID, parentActionID);
        addKeyValuePair(builder, BEACON_KEY_START_SEQUENCE_NUMBER, createSequenceNumber());
        addKeyValuePair(builder, BEACON_KEY_TIME_0, getTimeSinceSessionStartTime(eventTimestamp));

        return eventTimestamp;
    }

    /**
     * Serialization for building basic event data.
     *
     * @param builder   String builder storing serialized data.
     * @param eventType The event's type.
     * @param name      Event's name.
     */
    private void buildBasicEventData(StringBuilder builder, EventType eventType, String name) {
        addKeyValuePair(builder, BEACON_KEY_EVENT_TYPE, eventType.protocolValue());
        if (name != null) {
            addKeyValuePair(builder, BEACON_KEY_NAME, truncate(name));
        }
        addKeyValuePair(builder, BEACON_KEY_THREAD_ID, threadIDProvider.getThreadID());
    }

    /**
     * Serialization helper method for creating basic beacon protocol data.
     *
     * @return Serialized data.
     */
    private String createImmutableBasicBeaconData() {
        OpenKitConfiguration openKitConfiguration = configuration.getOpenKitConfiguration();
        StringBuilder basicBeaconBuilder = new StringBuilder();

        // version and application information
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_PROTOCOL_VERSION, ProtocolConstants.PROTOCOL_VERSION);
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_OPENKIT_VERSION, ProtocolConstants.OPENKIT_VERSION);
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_APPLICATION_ID, openKitConfiguration.getApplicationID());
        addKeyValuePairIfNotNull(basicBeaconBuilder, BEACON_KEY_APPLICATION_NAME, openKitConfiguration.getApplicationName());
        addKeyValuePairIfNotNull(basicBeaconBuilder, BEACON_KEY_APPLICATION_VERSION, openKitConfiguration.getApplicationVersion());
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_PLATFORM_TYPE, ProtocolConstants.PLATFORM_TYPE_OPENKIT);
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_AGENT_TECHNOLOGY_TYPE, ProtocolConstants.AGENT_TECHNOLOGY_TYPE);

        // device/visitor ID, session number and IP address
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_VISITOR_ID, getDeviceID());
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_SESSION_NUMBER, getSessionNumber());
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_CLIENT_IP_ADDRESS, clientIPAddress);

        // platform information
        addKeyValuePairIfNotNull(basicBeaconBuilder, BEACON_KEY_DEVICE_OS, openKitConfiguration.getOperatingSystem());
        addKeyValuePairIfNotNull(basicBeaconBuilder, BEACON_KEY_DEVICE_MANUFACTURER, openKitConfiguration.getManufacturer());
        addKeyValuePairIfNotNull(basicBeaconBuilder, BEACON_KEY_DEVICE_MODEL, openKitConfiguration.getModelID());

        PrivacyConfiguration privacyConfiguration = configuration.getPrivacyConfiguration();
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_DATA_COLLECTION_LEVEL, privacyConfiguration.getDataCollectionLevel());
        addKeyValuePair(basicBeaconBuilder, BEACON_KEY_CRASH_REPORTING_LEVEL, privacyConfiguration.getCrashReportingLevel());

        return basicBeaconBuilder.toString();
    }

    /**
     * Get a visitor ID for the current data collection level
     *
     * in case of level 2 (USER_BEHAVIOR) the value from the configuration is used
     * in case of level 1 (PERFORMANCE) or 0 (OFF) a random number in the positive Long range is used
     *
     * @return The device identifier, which is truncated to 250 characters if level 2 (USER_BEHAVIOR) is used.
     */
    public long getDeviceID() {
        return deviceID;
    }

    /**
     * Get a session ID for the current data collection level
     *
     * <p>
     * If session number reporting is allowed (see also {@link PrivacyConfiguration#isSessionNumberReportingAllowed()},
     * then the real session number is returned, otherwise {@code 1} is returned.
     * </p>
     *
     * @return Pre calculated session number or {@code 1} if session number reporting is not allowed.
     */
    public int getSessionNumber() {
        PrivacyConfiguration privacyConfiguration = configuration.getPrivacyConfiguration();
        if (privacyConfiguration.isSessionNumberReportingAllowed()) {
            return beaconKey.beaconId;
        }
        return 1; //the visitor/device id is already random, it is fine to use 1 here
    }

    /**
     * Returns sequence number of the session.
     *
     * <p>
     * The session sequence number is a consecutive number which is increased when a session is split due to
     * exceeding the maximum number of allowed events. The split session will then have the same session number
     * but an increased session sequence number.
     * </p>
     */
    public int getSessionSequenceNumber() {
        return beaconKey.beaconSeqNo;
    }

    /**
     * Returns the version of the visit store which this beacon uses.
     */
    private int getVisitStoreVersion() {
        return configuration.getServerConfiguration().getVisitStoreVersion();
    }

    /**
     * Serialization helper method for creating basic timestamp data.
     *
     * @return Serialized data.
     */
    private String createTimestampData() {
        StringBuilder timestampBuilder = new StringBuilder();

        // timestamp information
        addKeyValuePair(timestampBuilder, BEACON_KEY_TRANSMISSION_TIME, timingProvider.provideTimestampInMilliseconds());
        addKeyValuePair(timestampBuilder, BEACON_KEY_SESSION_START_TIME, sessionStartTime);

        return timestampBuilder.toString();
    }

    /**
     * Serialization helper method for creating multiplicity data.
     *
     * @return Serialized data.
     */
    private String createMultiplicityData() {

        StringBuilder multiplicityBuilder = new StringBuilder();

        int multiplicity = configuration.getServerConfiguration().getMultiplicity();
        addKeyValuePair(multiplicityBuilder, BEACON_KEY_MULTIPLICITY, multiplicity);

        return multiplicityBuilder.toString();
    }

    /**
     * Serialization helper method for adding key/value pairs with string values
     *
     * @param builder     The string builder storing serialized data.
     * @param key         The key to add.
     * @param stringValue The value to add.
     */
    private void addKeyValuePair(StringBuilder builder, String key, String stringValue) {
        String encodedValue = PercentEncoder.encode(stringValue, CHARSET, RESERVED_CHARACTERS);
        if (encodedValue == null) {
            // if encoding fails, skip this key/value pair
            logger.error(getClass().getSimpleName() + ": Skipped encoding of Key/Value: " + key + "/" + stringValue);
            return;
        }

        appendKey(builder, key);
        builder.append(encodedValue);
    }

    /**
     * Serialization helper method for adding key/value pairs with string values
     *
     * if the string value turns out to be null the key value pair is not added
     * to the string builder
     *
     * @param builder     The string builder storing serialized data.
     * @param key         The key to add.
     * @param stringValue The value to add.
     */
    private void addKeyValuePairIfNotNull(StringBuilder builder, String key, String stringValue) {
        if (stringValue != null) {
            addKeyValuePair(builder, key, stringValue);
        }
    }

    /**
     * Serialization helper method for adding key/value pairs with long values
     *
     * @param builder   The string builder storing serialized data.
     * @param key       The key to add.
     * @param longValue The value to add.
     */
    private void addKeyValuePair(StringBuilder builder, String key, long longValue) {
        appendKey(builder, key);
        builder.append(longValue);
    }

    /**
     * Serialization helper method for adding key/value pairs with int values
     *
     * @param builder  The string builder storing serialized data.
     * @param key      The key to add.
     * @param intValue The value to add.
     */
    private void addKeyValuePair(StringBuilder builder, String key, int intValue) {
        appendKey(builder, key);
        builder.append(intValue);
    }

    /**
     * Serialization helper method for adding key/value pairs with values implementing the
     * {@link SerializableBeaconValue} interface.
     *
     * @param builder the string builder storing the serialized data.
     * @param key     the key to add.
     * @param value   the value to add.
     */
    private void addKeyValuePair(StringBuilder builder, String key, SerializableBeaconValue value) {
        if (value == null) {
            return;
        }
        addKeyValuePair(builder, key, value.asBeaconValue());
    }

    /**
     * Serialization helper method for adding key/value pairs with int values
     *
     * the key value pair is only added to the string builder when the int is not negative
     *
     * @param builder  The string builder storing serialized data.
     * @param key      The key to add.
     * @param intValue The value to add.
     */
    private void addKeyValuePairIfNotNegative(StringBuilder builder, String key, int intValue) {
        if (intValue >= 0) {
            addKeyValuePair(builder, key, intValue);
        }
    }


    /**
     * Serialization helper method for adding key/value pairs with double values
     *
     * @param builder     The string builder storing serialized data.
     * @param key         The key to add.
     * @param doubleValue The value to add.
     */
    private void addKeyValuePair(StringBuilder builder, String key, double doubleValue) {
        appendKey(builder, key);
        builder.append(doubleValue);
    }

    /**
     * Serialization helper method for appending a key.
     *
     * @param builder The string builder storing serialized data.
     * @param key     The key to add.
     */
    private void appendKey(StringBuilder builder, String key) {
        if (builder.length() > 0) {
            builder.append('&');
        }
        builder.append(key);
        builder.append('=');
    }

    /**
     * helper method for truncating name at max name size
     */
    private static String truncate(String name) {
        name = name.trim();
        if (name.length() > MAX_NAME_LEN) {
            name = name.substring(0, MAX_NAME_LEN);
        }
        return name;
    }

    /**
     * Get a timestamp relative to the time this session (aka. beacon) was created.
     *
     * @param timestamp The absolute timestamp for which to get a relative one.
     * @return Relative timestamp.
     */
    private long getTimeSinceSessionStartTime(long timestamp) {
        return timestamp - sessionStartTime;
    }

    /**
     * Tests if the Beacon is empty.
     *
     * <p>
     * A beacon is considered to be empty, if it does not contain any action or event data.
     * </p>
     *
     * @return {@code true} if the beacon is empty, {@code false} otherwise.
     */
    public boolean isEmpty() {
        return beaconCache.isEmpty(beaconKey);
    }

    /**
     * Initializes the beacon with the given {@link ServerConfiguration}.
     * @param serverConfiguration the server configuration which will be used for initialization.
     */
    public void initializeServerConfiguration(ServerConfiguration serverConfiguration) {
        configuration.initializeServerConfiguration(serverConfiguration);
    }

    /**
     * Updates this beacon with the given {@link ServerConfiguration}
     *
     * @param serverConfiguration the server configuration which will be used to update this beacon.
     */
    public void updateServerConfiguration(ServerConfiguration serverConfiguration) {
        configuration.updateServerConfiguration(serverConfiguration);
    }

    /**
     * Indicates whether a server configuration is set on this beacon's configuration or not.
     */
    public boolean isServerConfigurationSet() {
        return configuration.isServerConfigurationSet();
    }

    /**
     * Sets the callback when a server configuration is updated.
     *
     * @param callback the callback to be notified when the server configuration is updated.
     */
    public void setServerConfigurationUpdateCallback(ServerConfigurationUpdateCallback callback) {
        configuration.setServerConfigurationUpdateCallback(callback);
    }

    /**
     * Indicates whether data capturing for this beacon is currently enabled or not.
     */
    public boolean isDataCapturingEnabled() {
        return configuration.getServerConfiguration().isSendingDataAllowed();
    }

    /**
     * Enables capturing for this beacon.
     *
     * <p>
     * This will implicitly cause {@link #isServerConfigurationSet()} to return {@code true}.
     * </p>
     */
    public void enableCapture() {
        configuration.enableCapture();
    }

    /**
     * Disables capturing for this session
     *
     * <p>
     * This will implicitly cause {@link #isServerConfigurationSet()} to return {@code true}.
     * </p>
     */
    public void disableCapture() {
        configuration.disableCapture();
    }

    /**
     * Get a boolean, indicating if action reporting is enabled by the privacy configuration
     *
     * @return {@code true} if action reporting is enabled by privacy configuration, {@code false} otherwise.
     */
    public boolean isActionReportingAllowedByPrivacySettings() {
        return configuration.getPrivacyConfiguration().isActionReportingAllowed();
    }
}
