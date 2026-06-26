package com.cardprocess.portador.infrastructure.messaging;

import com.cardprocess.portador.application.IssuancePublisher;
import com.cardprocess.shared.messaging.IssuanceMessage;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SqsIssuancePublisher implements IssuancePublisher {

    private final SqsTemplate sqsTemplate;
    private final String queueName;

    public SqsIssuancePublisher(SqsTemplate sqsTemplate,
                                @Value("${cardprocess.issuance.queue}") String queueName) {
        this.sqsTemplate = sqsTemplate;
        this.queueName = queueName;
    }

    @Override
    public void publish(IssuanceMessage message) {
        sqsTemplate.send(to -> to.queue(queueName).payload(message));
    }
}
