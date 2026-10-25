package com.vuvanquan.notifyhub.auth.control;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ClientAddress {
    private final Set<String> trusted;
    public ClientAddress(ControlProperties properties) {
        var addresses = new HashSet<String>();
        if (properties.trustedProxyAddresses() != null) {
            for (String address : properties.trustedProxyAddresses()) if (!address.isBlank()) addresses.add(literal(address.strip()));
        }
        trusted = Set.copyOf(addresses);
    }
    public String resolve(HttpServletRequest request) {
        String peer = literal(request.getRemoteAddr());
        if (!trusted.contains(peer)) return peer;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 512) return peer;
        String[] chain = forwarded.split(",", -1);
        if (chain.length > 10) return peer;
        String originalPeer = peer;
        try {
            for (int i=chain.length-1; i>=0 && trusted.contains(peer); i--) peer = literal(chain[i].strip());
            return peer;
        } catch (IllegalArgumentException malformed) { return originalPeer; }
    }
    static String literal(String value) {
        if (value == null || !value.matches("[0-9a-fA-F:.]+")) throw new IllegalArgumentException("A literal proxy IP address is required");
        if (!value.contains(":")) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
            for (String part : parts) if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) throw new IllegalArgumentException("Invalid IPv4 address");
        }
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (java.net.UnknownHostException malformed) { throw new IllegalArgumentException("Invalid proxy IP address", malformed); }
    }
}
