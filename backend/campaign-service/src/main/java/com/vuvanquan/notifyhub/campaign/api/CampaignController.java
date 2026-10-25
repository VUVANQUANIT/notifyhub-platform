package com.vuvanquan.notifyhub.campaign.api;

import com.vuvanquan.notifyhub.campaign.application.*;
import com.vuvanquan.notifyhub.campaign.domain.CampaignStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import static com.vuvanquan.notifyhub.campaign.application.CampaignModels.*;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {
    private final CampaignApplicationService service;
    private final ActorResolver actors;
    public CampaignController(CampaignApplicationService service, ActorResolver actors) {
        this.service = service; this.actors = actors;
    }

    @PostMapping
    public ResponseEntity<CampaignView> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateCampaign body, HttpServletRequest request, Authentication auth) {
        var result = service.create(actors.resolve(request, auth), key, body);
        return ResponseEntity.created(URI.create("/api/campaigns/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public CampaignView get(@PathVariable UUID id, HttpServletRequest request, Authentication auth) {
        return service.get(actors.resolve(request, auth), id);
    }

    @GetMapping
    public PageView<CampaignView> list(@RequestParam(required=false) CampaignStatus status,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            HttpServletRequest request, Authentication auth) {
        return service.list(actors.resolve(request, auth), status, page, size);
    }

    @PostMapping(value="/{id}/imports", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportView> importCsv(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
            HttpServletRequest request, Authentication auth) throws IOException {
        if (file.getSize() > RecipientCsvReader.MAX_BYTES) {
            throw new IllegalArgumentException("CSV exceeds 2 MiB");
        }
        var result = service.importCsv(actors.resolve(request, auth), id, file.getBytes());
        return ResponseEntity.created(URI.create("/api/campaigns/" + id + "/imports/" + result.id())).body(result);
    }

    @GetMapping("/{id}/imports/{importId}")
    public ImportView getImport(@PathVariable UUID id, @PathVariable UUID importId,
            HttpServletRequest request, Authentication auth) {
        return service.getImport(actors.resolve(request, auth), id, importId);
    }

    @GetMapping("/{id}/recipients")
    public PageView<RecipientView> recipients(@PathVariable UUID id,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
            HttpServletRequest request, Authentication auth) {
        return service.recipients(actors.resolve(request, auth), id, page, size);
    }

    @PostMapping("/{id}/start")
    public CampaignView start(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request, Authentication auth) {
        return service.start(actors.resolve(request, auth), id, key);
    }
}
