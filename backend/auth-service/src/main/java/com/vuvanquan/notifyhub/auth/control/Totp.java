package com.vuvanquan.notifyhub.auth.control;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238: SHA-1, six digits, 30-second steps, +/- one step tolerance. */
public final class Totp {
    private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private Totp() {}
    public static String secret() {
        byte[] bytes=new byte[20]; new SecureRandom().nextBytes(bytes);
        var result=new StringBuilder(); int buffer=0,bits=0;
        for(byte b:bytes){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){bits-=5;result.append(ALPHABET.charAt((buffer>>bits)&31));}}
        return result.toString();
    }
    public static String code(String secret,long step) {
        if(!secret.matches("[A-Z2-7]{32}"))throw new IllegalArgumentException("Invalid TOTP secret");
        byte[] bytes=new byte[20];int buffer=0,bits=0,index=0;
        for(char c:secret.toCharArray()){buffer=(buffer<<5)|ALPHABET.indexOf(c);bits+=5;if(bits>=8){bits-=8;bytes[index++]=(byte)(buffer>>bits);}}
        try {
            var mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(bytes,"HmacSHA1"));
            byte[] digest=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=digest[digest.length-1]&15;
            int binary=ByteBuffer.wrap(digest,offset,4).getInt()&0x7fffffff;
            return String.format(Locale.ROOT,"%06d",binary%1_000_000);
        }catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}
    }
    public static long verify(String secret,String candidate,long step,long lastStep) {
        if(candidate==null || !candidate.matches("[0-9]{6}"))return -1;
        for(long current=step-1;current<=step+1;current++) {
            if(current>lastStep && MessageDigest.isEqual(code(secret,current).getBytes(StandardCharsets.US_ASCII),candidate.getBytes(StandardCharsets.US_ASCII)))return current;
        }
        return -1;
    }
}
