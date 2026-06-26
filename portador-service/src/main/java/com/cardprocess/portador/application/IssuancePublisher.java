package com.cardprocess.portador.application;

import com.cardprocess.shared.messaging.IssuanceMessage;

public interface IssuancePublisher {

    void publish(IssuanceMessage message);
}
