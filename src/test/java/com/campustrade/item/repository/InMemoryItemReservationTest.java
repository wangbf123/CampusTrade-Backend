package com.campustrade.item.repository;

import com.campustrade.item.model.Item;
import com.campustrade.item.model.ItemStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryItemReservationTest {

    @Test
    void concurrentReservationsHaveExactlyOneOwner() throws Exception {
        InMemoryItemRepository repository = new InMemoryItemRepository();
        Item item = onSaleItem(repository);
        long initialVersion = item.getVersion();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> attempts = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(16)) {
            for (long orderId = 1; orderId <= 32; orderId++) {
                long contender = orderId;
                attempts.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return repository.reserveIfOnSale(item.getId(), contender) ? contender : null;
                }));
            }
            start.countDown();
            List<Long> winners = new ArrayList<>();
            for (Future<Long> attempt : attempts) {
                Long result = attempt.get(5, TimeUnit.SECONDS);
                if (result != null) {
                    winners.add(result);
                }
            }

            assertEquals(1, winners.size());
            Item stored = repository.findById(item.getId()).orElseThrow();
            assertEquals(ItemStatus.RESERVED, stored.getStatus());
            assertEquals(winners.getFirst(), stored.getReservedOrderId());
            assertEquals(initialVersion + 1, stored.getVersion());
        }
    }

    @Test
    void aPreviousOwnerCannotReleaseOrSellANewReservation() {
        InMemoryItemRepository repository = new InMemoryItemRepository();
        Item item = onSaleItem(repository);
        assertTrue(repository.reserveIfOnSale(item.getId(), 10L));
        assertTrue(repository.releaseReservation(item.getId(), 10L));
        assertNull(repository.findById(item.getId()).orElseThrow().getReservedOrderId());
        assertTrue(repository.reserveIfOnSale(item.getId(), 20L));
        long version = item.getVersion();

        assertFalse(repository.releaseReservation(item.getId(), 10L));
        assertFalse(repository.sellReservation(item.getId(), 10L));

        Item stored = repository.findById(item.getId()).orElseThrow();
        assertEquals(ItemStatus.RESERVED, stored.getStatus());
        assertEquals(20L, stored.getReservedOrderId());
        assertEquals(version, stored.getVersion());
    }

    @Test
    void sellingRequiresTheOwnerAndClearsTheReservation() {
        InMemoryItemRepository repository = new InMemoryItemRepository();
        Item item = onSaleItem(repository);
        assertTrue(repository.reserveIfOnSale(item.getId(), 10L));

        assertTrue(repository.sellReservation(item.getId(), 10L));
        assertEquals(ItemStatus.SOLD, item.getStatus());
        assertNull(item.getReservedOrderId());
        assertFalse(repository.sellReservation(item.getId(), 10L));
        assertFalse(repository.releaseReservation(item.getId(), 10L));
        assertFalse(repository.reserveIfOnSale(item.getId(), 20L));
    }

    @Test
    void absentOrUnavailableItemsCannotBeReserved() {
        InMemoryItemRepository repository = new InMemoryItemRepository();
        assertFalse(repository.reserveIfOnSale(999L, 10L));
        assertFalse(repository.releaseReservation(999L, 10L));
        assertFalse(repository.sellReservation(999L, 10L));
        for (ItemStatus status : List.of(ItemStatus.OFF_SHELF, ItemStatus.SOLD, ItemStatus.RESERVED)) {
            Item item = new Item();
            item.setStatus(status);
            repository.save(item);
            assertFalse(repository.reserveIfOnSale(item.getId(), 10L));
            assertEquals(status, item.getStatus());
        }
    }

    private Item onSaleItem(InMemoryItemRepository repository) {
        Item item = new Item();
        item.setStatus(ItemStatus.ON_SALE);
        return repository.save(item);
    }
}
