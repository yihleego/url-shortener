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
import java.util.Base64;
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
            // TODO Base62 is better
            String s = Base64.getUrlEncoder().withoutPadding().encodeToString(hashCode.asBytes());
            Boolean b = redisTemplate.execute(script, List.of(s), url, /* timeout */ "86400");
            if (b != null && b) {
                return s;
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

}
