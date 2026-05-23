package com.campustrade.cache;

import java.util.List;

public interface HotItemRankService {

    void recordView(Long itemId);

    List<Long> topItemIds(int limit);

    void remove(Long itemId);
}
