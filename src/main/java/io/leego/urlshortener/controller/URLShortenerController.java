package io.leego.urlshortener.controller;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

/**
 * @author Leego Yih
 */
@RestController
public class URLShortenerController {
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Boolean> script;
    private final HashFunction[] hashes;

    public URLShortenerController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>("""
                local o = redis.call('GET', KEYS[1])
                if not o then
                    redis.call('SET', KEYS[1], ARGV[1])
                    redis.call('EXPIRE', KEYS[1], ARGV[2])
                    return true
                end
                if o == ARGV[1] then
                    return true
                end
                return false""", Boolean.class);
        // For hash collisions
        this.hashes = IntStream.iterate(0, n -> n + 1)
                .mapToObj(Hashing::murmur3_32_fixed)
                .limit(16)
                .toArray(HashFunction[]::new);
    }

    @PostMapping("/")
    public String shorten(@RequestBody String url, HttpServletResponse response) {
        for (HashFunction hash : hashes) {
            HashCode hashCode = hash.hashString(url, StandardCharsets.UTF_8);
            // Base62 is better
            //String key = Base64.getUrlEncoder().withoutPadding().encodeToString(hashCode.asBytes());
            String key = toBase62(hashCode.asBytes());
            Boolean b = redisTemplate.execute(script, List.of(key), url, /* timeout */ "86400");
            if (b != null && b) {
                return key;
            }
        }
        response.setStatus(HttpStatus.CONFLICT.value());
        return null;
    }

    @GetMapping("/{hash}")
    public RedirectView redirect(@PathVariable String hash, HttpServletResponse response) {
        String url = redisTemplate.opsForValue().get(hash);
        if (url != null) {
            RedirectView v = new RedirectView(url);
            // 301 is better than 302
            v.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
            return v;
        }
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return null;
    }

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static String toBase62(byte[] src) {
        int rs = 0;
        int cs = (int) Math.ceil(Math.log(256) / Math.log(62) * src.length);
        byte[] dst = new byte[cs];
        for (byte b : src) {
            int c = 0;
            int v = b >= 0 ? b : 256 + b;
            for (int i = cs - 1; i >= 0 && (v != 0 || c < rs); i--) {
                v += (256 * dst[i]);
                dst[i] = (byte) (v % 62);
                v /= 62;
                c++;
            }
            rs = c;
        }
        for (int i = cs - rs; i < cs; i++) {
            dst[i] = (byte) ALPHABET.charAt(dst[i]);
        }
        return new String(dst, cs - rs, rs);
    }
}
