package com.vuvanquan.notifyhub.campaign.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-21T12:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-09-21T12:05:00Z");
    private static final CampaignReadiness READY = new CampaignReadiness(10, false);

    @Test
    void new_campaign_starts_as_draft() {
        Campaign campaign = immediateCampaign();

        assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaign.startedAt()).isEmpty();
    }

    @Test
    void scheduled_time_before_creation_is_rejected() {
        assertThatThrownBy(() -> campaign(Schedule.at(CREATED_AT.minusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Scheduled time must not be in the past");
    }

    @Test
    void content_must_match_email_channel() {
        assertThatThrownBy(() -> Campaign.create(
                campaignId(), tenantId(), new CampaignName("Welcome"), Channel.EMAIL,
                MessageContent.sms("Hello"), Schedule.immediate(), userId(), CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email content must have a subject");
    }

    @Test
    void content_must_match_sms_channel() {
        assertThatThrownBy(() -> Campaign.create(
                campaignId(), tenantId(), new CampaignName("Welcome"), Channel.SMS,
                MessageContent.email("Welcome", "Hello"), Schedule.immediate(), userId(), CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SMS content must not have a subject");
    }

    @Test
    void campaign_without_recipients_cannot_start() {
        Campaign campaign = immediateCampaign();

        assertThatThrownBy(() -> campaign.start(new CampaignReadiness(0, false), STARTED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Campaign must have at least one valid recipient");
        assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void campaign_with_import_in_progress_cannot_start() {
        Campaign campaign = immediateCampaign();

        assertThatThrownBy(() -> campaign.start(new CampaignReadiness(10, true), STARTED_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Campaign cannot start while a recipient import is in progress");
        assertThat(campaign.status()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void starting_an_immediate_campaign_moves_it_to_running() {
        Campaign campaign = immediateCampaign();

        campaign.start(READY, STARTED_AT);

        assertThat(campaign.status()).isEqualTo(CampaignStatus.RUNNING);
        assertThat(campaign.startedAt()).contains(STARTED_AT);
    }

    @Test
    void starting_with_a_future_schedule_moves_campaign_to_scheduled() {
        Instant scheduledAt = STARTED_AT.plusSeconds(60);
        Campaign campaign = campaign(Schedule.at(scheduledAt));

        campaign.start(READY, STARTED_AT);

        assertThat(campaign.status()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(campaign.startedAt()).isEmpty();
    }

    @Test
    void schedule_due_at_start_time_runs_immediately() {
        Campaign campaign = campaign(Schedule.at(STARTED_AT));

        campaign.start(READY, STARTED_AT);

        assertThat(campaign.status()).isEqualTo(CampaignStatus.RUNNING);
    }

    @Test
    void scheduled_campaign_cannot_activate_before_due_time() {
        Instant scheduledAt = STARTED_AT.plusSeconds(60);
        Campaign campaign = campaign(Schedule.at(scheduledAt));
        campaign.start(READY, STARTED_AT);

        assertThatThrownBy(() -> campaign.activateScheduled(scheduledAt.minusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Scheduled campaign is not due yet");
        assertThat(campaign.status()).isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    void due_scheduled_campaign_moves_to_running() {
        Instant scheduledAt = STARTED_AT.plusSeconds(60);
        Campaign campaign = campaign(Schedule.at(scheduledAt));
        campaign.start(READY, STARTED_AT);

        campaign.activateScheduled(scheduledAt);

        assertThat(campaign.status()).isEqualTo(CampaignStatus.RUNNING);
        assertThat(campaign.startedAt()).contains(scheduledAt);
    }

    @Test
    void campaign_cannot_start_twice() {
        Campaign campaign = immediateCampaign();
        campaign.start(READY, STARTED_AT);

        assertThatThrownBy(() -> campaign.start(READY, STARTED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Campaign can only be started from DRAFT");
    }

    @Test
    void draft_campaign_can_be_edited() {
        Campaign campaign = immediateCampaign();
        CampaignName newName = new CampaignName("Updated campaign");
        MessageContent newContent = MessageContent.email("Updated", "Updated body");

        campaign.rename(newName);
        campaign.changeContent(newContent);

        assertThat(campaign.name()).isEqualTo(newName);
        assertThat(campaign.content()).isEqualTo(newContent);
    }

    @Test
    void started_campaign_cannot_be_edited() {
        Campaign campaign = immediateCampaign();
        campaign.start(READY, STARTED_AT);

        assertThatThrownBy(() -> campaign.rename(new CampaignName("Updated")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a draft campaign can be renamed");
        assertThatThrownBy(() -> campaign.changeContent(MessageContent.email("Updated", "Body")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a draft campaign can change content");
    }

    @Test
    void running_campaign_can_complete() {
        Campaign campaign = runningCampaign();

        campaign.complete();

        assertThat(campaign.status()).isEqualTo(CampaignStatus.COMPLETED);
    }

    @Test
    void running_campaign_can_fail() {
        Campaign campaign = runningCampaign();

        campaign.fail();

        assertThat(campaign.status()).isEqualTo(CampaignStatus.FAILED);
    }

    @Test
    void draft_or_scheduled_campaign_cannot_complete() {
        Campaign draft = immediateCampaign();
        Campaign scheduled = campaign(Schedule.at(STARTED_AT.plusSeconds(60)));
        scheduled.start(READY, STARTED_AT);

        assertThatThrownBy(draft::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a running campaign can be completed");
        assertThatThrownBy(scheduled::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a running campaign can be completed");
    }

    @Test
    void terminal_campaign_cannot_transition_again() {
        Campaign completed = runningCampaign();
        completed.complete();
        Campaign failed = runningCampaign();
        failed.fail();

        assertThatThrownBy(completed::fail)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a running campaign can fail");
        assertThatThrownBy(failed::complete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a running campaign can be completed");
    }

    private Campaign runningCampaign() {
        Campaign campaign = immediateCampaign();
        campaign.start(READY, STARTED_AT);
        return campaign;
    }

    private Campaign immediateCampaign() {
        return campaign(Schedule.immediate());
    }

    private Campaign campaign(Schedule schedule) {
        return Campaign.create(
                campaignId(), tenantId(), new CampaignName("Welcome campaign"), Channel.EMAIL,
                MessageContent.email("Welcome", "Hello from NotifyHub"), schedule, userId(), CREATED_AT
        );
    }

    private CampaignId campaignId() {
        return new CampaignId(UUID.fromString("10000000-0000-0000-0000-000000000001"));
    }

    private TenantId tenantId() {
        return new TenantId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
    }

    private UserId userId() {
        return new UserId(UUID.fromString("30000000-0000-0000-0000-000000000001"));
    }
}
