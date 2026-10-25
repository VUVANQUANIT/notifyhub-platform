package com.vuvanquan.notifyhub.auth.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class IdentityRulesTest {
    @Test void normalizes_email_and_slug_without_normalizing_passwords() {
        assertThat(IdentityRules.email(" QUAN@Example.com ")).isEqualTo("quan@example.com");
        assertThat(IdentityRules.slug(" My-Tenant ")).isEqualTo("my-tenant");
        assertThat(IdentityRules.password("  a long passphrase  ")).isEqualTo("  a long passphrase  ");
    }
    @Test void rejects_invalid_identity_and_short_or_excessive_passwords() {
        assertThatThrownBy(() -> IdentityRules.slug("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityRules.email("not-email")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityRules.password("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityRules.password("a".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void admin_changes_preserve_at_least_one_enabled_admin() {
        assertThatThrownBy(() -> IdentityRules.keepAdmin(Role.ADMIN, true, Role.VIEWER, true, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IdentityRules.keepAdmin(Role.ADMIN, true, Role.ADMIN, false, 1)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> IdentityRules.keepAdmin(Role.ADMIN, true, Role.VIEWER, true, 2)).doesNotThrowAnyException();
    }
    @Test void permissions_come_from_server_owned_roles() {
        assertThat(Role.ADMIN.scopes()).contains("users:write", "campaigns:write", "reports:read");
        assertThat(Role.OPERATOR.scopes()).contains("campaigns:write").doesNotContain("users:write");
        assertThat(Role.VIEWER.scopes()).contains("campaigns:read", "reports:read").doesNotContain("campaigns:write");
    }
}
