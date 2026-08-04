package com.campustrade.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.registration")
public class RegistrationProperties {

    private boolean inviteCodeRequired = false;

    public boolean isInviteCodeRequired() {
        return inviteCodeRequired;
    }

    public void setInviteCodeRequired(boolean inviteCodeRequired) {
        this.inviteCodeRequired = inviteCodeRequired;
    }
}
