package com.cardprocess.portador.infrastructure.messaging;

import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
public class SqsIssuanceSender {

    private static final Map<String, MessageAttributeValue> JSON_CONTENT_TYPE = Map.of(
            "contentType", MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("application/json")
                    .build());

    private final SqsAsyncClient sqsClient;
    private final String queueName;
    private volatile String queueUrl;

    public SqsIssuanceSender(SqsAsyncClient sqsClient,
                             @Value("${cardprocess.issuance.queue}") String queueName) {
        this.sqsClient = sqsClient;
        this.queueName = queueName;
    }

    public void send(String payload) {
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(resolveQueueUrl())
                    .messageBody(payload)
                    .messageAttributes(JSON_CONTENT_TYPE)
                    .build()).join();
        } catch (CompletionException failure) {
            queueUrl = null;
            throw unwrap(failure);
        }
    }

    private String resolveQueueUrl() {
        String cached = queueUrl;
        if (cached != null) {
            return cached;
        }
        String resolved = sqsClient.getQueueUrl(request -> request.queueName(queueName)).join().queueUrl();
        queueUrl = resolved;
        return resolved;
    }

    private RuntimeException unwrap(CompletionException failure) {
        return failure.getCause() instanceof RuntimeException cause ? cause : failure;
    }
}
