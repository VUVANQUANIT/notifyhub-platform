package com.vuvanquan.notifyhub.auth.control;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressTest {
    @Test void untrusted_clients_cannot_spoof_forwarding_headers() {
        var resolver = resolver(List.of()); var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.3"); request.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.3");
    }
    @Test void traverses_only_explicitly_trusted_proxies_from_the_right() {
        var resolver = resolver(List.of("10.0.0.2", "10.0.0.3")); var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.3"); request.addHeader("X-Forwarded-For", "9.9.9.9, 1.2.3.4, 10.0.0.2");
        assertThat(resolver.resolve(request)).isEqualTo("1.2.3.4");
    }
    @Test void invalid_forwarding_values_fall_back_to_socket_peer_without_dns() {
        var resolver = resolver(List.of("127.0.0.1")); var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1"); request.addHeader("X-Forwarded-For", "attacker.example.com");
        assertThat(resolver.resolve(request)).isEqualTo("127.0.0.1");
        assertThatThrownBy(() -> ClientAddress.literal("999.1.1.1")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ClientAddress.literal("::1")).contains(":");
    }
    private ClientAddress resolver(List<String> proxies) { return new ClientAddress(new ControlProperties(null,null,null,null,null,null,proxies)); }
}
