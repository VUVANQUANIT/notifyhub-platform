package com.vuvanquan.notifyhub.campaign.persistence;

import com.vuvanquan.notifyhub.campaign.domain.CampaignStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.*;

public interface CampaignRepository extends JpaRepository<CampaignEntity, UUID> {
    Optional<CampaignEntity> findByTenantIdAndId(UUID tenantId, UUID id);
    Optional<CampaignEntity> findByTenantIdAndCreateKey(UUID tenantId, String createKey);
    Page<CampaignEntity> findByTenantId(UUID tenantId, Pageable pageable);
    Page<CampaignEntity> findByTenantIdAndStatus(UUID tenantId, CampaignStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CampaignEntity c where c.tenantId = :tenantId and c.id = :id")
    Optional<CampaignEntity> lock(UUID tenantId, UUID id);

    List<CampaignEntity> findTop100ByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            CampaignStatus status, Instant now);
}
