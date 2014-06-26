/*
 * Copyright 2013 ZANOX AG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zanox.vertx.mods;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.*;
import com.amazonaws.retry.RetryPolicy;
import com.amazonaws.services.kinesis.AmazonKinesisAsyncClient;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.kinesis.model.PutRecordResult;
import com.zanox.vertx.mods.exception.KinesisException;
import org.vertx.java.busmods.BusModBase;
import org.vertx.java.core.Handler;
import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.zanox.vertx.mods.internal.EventProperties.PAYLOAD;
import static com.zanox.vertx.mods.internal.KinesisProperties.*;

/**
 * This verticle is responsible for processing messages.
 * It subscribes to Vert.x's specific EventBus address to handle messages published by other verticles
 * and sends messages to Kinesis.
 */
public class KinesisMessageProcessor extends BusModBase implements Handler<Message<JsonObject>> {

    private AmazonKinesisAsyncClient kinesisAsyncClient;
    private String streamName, partitionKey;

    @Override
    public void handle(Message<JsonObject> jsonObjectMessage) {
        try {
            sendMessageToKinesis(jsonObjectMessage);
        }

        catch (KinesisException exc) {
            logger.error(exc);
        }
    }

    @Override
    public void start() {
        super.start();

        kinesisAsyncClient = createClient();

        // Get the address of EventBus where the message was published
        final String address = getMandatoryStringConfig("address");

        vertx.eventBus().registerHandler(address, this);
    }

    @Override
    public void stop() {
        if (kinesisAsyncClient != null) {
            kinesisAsyncClient.shutdown();
        }
    }

    private AmazonKinesisAsyncClient createClient() {

        // Building Kinesis configuration
        int connectionTimeout = getOptionalIntConfig(CONNECTION_TIMEOUT, ClientConfiguration.DEFAULT_CONNECTION_TIMEOUT);
        int maxConnection = getOptionalIntConfig(MAX_CONNECTION, ClientConfiguration.DEFAULT_MAX_CONNECTIONS);

        // TODO: replace default retry policy
        RetryPolicy retryPolicy = ClientConfiguration.DEFAULT_RETRY_POLICY;
        int socketTimeout = getOptionalIntConfig(SOCKET_TIMEOUT, ClientConfiguration.DEFAULT_SOCKET_TIMEOUT);
        boolean useReaper = getOptionalBooleanConfig(USE_REAPER, ClientConfiguration.DEFAULT_USE_REAPER);
        String userAgent = getOptionalStringConfig(USER_AGENT, ClientConfiguration.DEFAULT_USER_AGENT);
        streamName = getMandatoryStringConfig(STREAM_NAME);
        partitionKey = getMandatoryStringConfig(PARTITION_KEY);

        ClientConfiguration clientConfiguration = new ClientConfiguration();
        clientConfiguration.setConnectionTimeout(connectionTimeout);
        clientConfiguration.setMaxConnections(maxConnection);
        clientConfiguration.setRetryPolicy(retryPolicy);
        clientConfiguration.setSocketTimeout(socketTimeout);
        clientConfiguration.setUseReaper(useReaper);
        clientConfiguration.setUserAgent(userAgent);

        // Reading credentials from ENV-variables
        AWSCredentialsProvider awsCredentialsProvider = new EnvironmentVariableCredentialsProvider();

        // Configuring Kinesis-client with configuration
        AmazonKinesisAsyncClient kinesisAsyncClient = new AmazonKinesisAsyncClient(awsCredentialsProvider, clientConfiguration);

        return kinesisAsyncClient;
    }

    protected void sendMessageToKinesis(Message<JsonObject> event) throws KinesisException {
        if (kinesisAsyncClient == null) {
            throw new KinesisException("AmazonKinesisAsyncClient is not initialized");
        }

        if(!isValid(event.body().getString(PAYLOAD))) {
            logger.error("Invalid message provided.");
            return;
        }

        byte [] payload = event.body().getBinary(PAYLOAD);

        PutRecordRequest putRecordRequest = new PutRecordRequest();
        putRecordRequest.setStreamName(streamName);
        putRecordRequest.setPartitionKey(partitionKey);

        logger.info("Writing to streamName " + streamName + " using partitionkey " + partitionKey);

        putRecordRequest.setData(ByteBuffer.wrap(payload));

        Future<PutRecordResult> futureResult = kinesisAsyncClient.putRecordAsync(putRecordRequest);
        try
        {
            PutRecordResult recordResult = futureResult.get();
            logger.info("Sent message to Kinesis: " + recordResult.toString());
            sendOK(event);
        }

        catch (InterruptedException iexc) {
            logger.error(iexc);
            sendError(event, "Failed sending message to Kinesis", iexc);
        }

        catch (ExecutionException eexc) {
            logger.error(eexc);
            sendError(event, "Failed sending message to Kinesis", eexc);
        }
    }

    private boolean isValid(String str) {
        return str != null && !str.isEmpty();
    }
}