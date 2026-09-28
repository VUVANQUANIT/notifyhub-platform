package com.vuvanquan.notifyhub.campaign.application;

public class CampaignNotFound extends RuntimeException {
    public CampaignNotFound() {
        super("Campaign or import not found");
    }
}
