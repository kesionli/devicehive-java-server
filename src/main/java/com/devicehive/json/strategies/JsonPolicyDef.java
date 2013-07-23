package com.devicehive.json.strategies;


import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface JsonPolicyDef {

    Policy[] value();

    public static enum Policy {
        WEBSOCKET_SERVER_INFO,
        REST_SERVER_INFO,
        DEVICE_PUBLISHED,
        DEVICE_SUBMITTED,
        COMMAND_TO_CLIENT,
        COMMAND_TO_DEVICE,
        COMMAND_FROM_CLIENT,
        COMMAND_UPDATE_FROM_DEVICE,
        COMMAND_UPDATE_TO_CLEINT,
        NOTIFICATION_FROM_DEVICE,
        NOTIFICATION_TO_DEVICE,
        NOTIFICATION_TO_CLEINT,
        DEVICE_EQUIPMENT_SUBMITTED,
        EQUIPMENT_SUBMITTED,
        USER_PUBLISHED,
        USERS_LISTED
    }
}
