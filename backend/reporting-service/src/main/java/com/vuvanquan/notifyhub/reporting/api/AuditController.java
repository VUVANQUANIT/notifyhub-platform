package com.vuvanquan.notifyhub.reporting.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuditController {
    private final JdbcTemplate db;private final TenantResolver tenants;
    public AuditController(JdbcTemplate db,TenantResolver tenants){this.db=db;this.tenants=tenants;}
    @GetMapping("/api/audit")
    public List<Map<String,Object>> list(HttpServletRequest request,Authentication auth,@RequestParam(defaultValue="0")int page){
        if(page<0 || page>1000000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        // No local header-based access to identity audit: an ADMIN bearer is always required.
        if(auth==null || auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("SCOPE_users:read")))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return db.queryForList("SELECT event_id,actor_id,subject_id,action,occurred_at FROM reporting.identity_audit WHERE tenant_id=? ORDER BY occurred_at DESC,event_id LIMIT 50 OFFSET ?",tenants.resolve(request,auth),(long)page*50);
    }
}
