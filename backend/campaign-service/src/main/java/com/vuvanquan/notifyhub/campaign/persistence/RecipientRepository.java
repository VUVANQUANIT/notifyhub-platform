package com.vuvanquan.notifyhub.campaign.persistence;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface RecipientRepository extends JpaRepository<RecipientEntity, UUID> {
    long countByTenantIdAndCampaignId(UUID tenantId, UUID campaignId);
    Page<RecipientEntity> findByTenantIdAndCampaignId(UUID tenantId, UUID campaignId, Pageable pageable);

    @Query("select r.destination from RecipientEntity r where r.tenantId = :tenantId "
            + "and r.campaignId = :campaignId and r.destination in :destinations")
    List<String> existing(UUID tenantId, UUID campaignId, Collection<String> destinations);
}
