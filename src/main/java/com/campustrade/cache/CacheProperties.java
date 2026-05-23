package com.campustrade.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private long itemDetailTtlSeconds = 1800;
    private long nullItemTtlSeconds = 120;
    private long randomJitterSeconds = 300;
    private String hotRankKey = "item:hot:rank";

    public long getItemDetailTtlSeconds() {
        return itemDetailTtlSeconds;
    }

    public void setItemDetailTtlSeconds(long itemDetailTtlSeconds) {
        this.itemDetailTtlSeconds = itemDetailTtlSeconds;
    }

    public long getNullItemTtlSeconds() {
        return nullItemTtlSeconds;
    }

    public void setNullItemTtlSeconds(long nullItemTtlSeconds) {
        this.nullItemTtlSeconds = nullItemTtlSeconds;
    }

    public long getRandomJitterSeconds() {
        return randomJitterSeconds;
    }

    public void setRandomJitterSeconds(long randomJitterSeconds) {
        this.randomJitterSeconds = randomJitterSeconds;
    }

    public String getHotRankKey() {
        return hotRankKey;
    }

    public void setHotRankKey(String hotRankKey) {
        this.hotRankKey = hotRankKey;
    }
}
