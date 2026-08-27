package com.visionbank.banking.notification;

// Stubbed per the assignment's allowance for mocking notifications (SMS/email).
// One seam, called wherever a maker-facing outcome needs to reach them —
// swap this implementation for a real email/SMS/push gateway later without
// touching any caller.
public interface NotificationClient {
    void notifyMaker(String makerId, String transferId, String message);
}
