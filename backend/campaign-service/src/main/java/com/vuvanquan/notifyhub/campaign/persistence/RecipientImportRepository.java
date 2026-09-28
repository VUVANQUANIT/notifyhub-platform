package com.vuvanquan.notifyhub.campaign.persistence;

import com.vuvanquan.notifyhub.campaign.domain.ImportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface RecipientImportRepository extends JpaRepository<RecipientImportEntity, UUID> {
    boolean existsByTenantIdAndCampaignIdAndStatus(UUID tenantId, UUID campaignId, ImportStatus status);
    Optional<RecipientImportEntity> findByTenantIdAndCampaignIdAndId(UUID tenantId, UUID campaignId, UUID id);
}
