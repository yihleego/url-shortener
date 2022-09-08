package io.leego.urlshortener.controller;

import com.google.common.cache.CacheBuilder;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

/**
 * @author Leego Yih
 */
@RestController
public class URLShortenerController {
    private final HashFunction[] hashes;
    private final ConcurrentMap<Object, Object> cache;

    public URLShortenerController() {
        // For hash collisions
        this.hashes = IntStream.iterate(0, n -> n + 1)
                .mapToObj(Hashing::murmur3_32_fixed)
                .limit(16)
                .toArray(HashFunction[]::new);
        this.cache = CacheBuilder.newBuilder()
                .concurrencyLevel(Runtime.getRuntime().availableProcessors())
                .initialCapacity(1 << 10)
                .maximumSize(1 << 14)
                .expireAfterAccess(Duration.ofDays(1))
                .build()
                .asMap();
    }

    @PostMapping("/")
    public String shorten(@RequestBody String url, HttpServletResponse response) {
        for (HashFunction hash : hashes) {
            HashCode hashCode = hash.hashString(url, StandardCharsets.UTF_8);
            // TODO Base62 is better
            String s = Base64.getUrlEncoder().withoutPadding().encodeToString(hashCode.asBytes());
            Object old = cache.putIfAbsent(s, url);
            if (old == null || Objects.equals(url, old)) {
                return s;
            }
        }
        response.setStatus(HttpStatus.CONFLICT.value());
        return null;
    }

    @GetMapping("/{hash}")
    public RedirectView redirect(@PathVariable String hash, HttpServletResponse response) {
        Object url = cache.get(hash);
        if (url != null) {
            RedirectView v = new RedirectView((String) url);
            // 301 is better than 302
            v.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
            return v;
        }
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return null;
    }

}
