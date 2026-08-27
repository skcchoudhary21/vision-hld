package com.visionbank.banking.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Stub: logs instead of sending an email/SMS, per the assignment's explicit
// allowance to mock notifications. Real delivery is out of scope here.
@Component
public class LoggingNotificationClient implements NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationClient.class);

    @Override
    public void notifyMaker(String makerId, String transferId, String message) {
        log.info("[NOTIFY] maker={} transfer={} : {}", makerId, transferId, message);
    }
}
