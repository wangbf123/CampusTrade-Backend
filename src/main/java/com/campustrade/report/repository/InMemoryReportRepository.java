package com.campustrade.report.repository;

import com.campustrade.report.model.Report;
import com.campustrade.report.model.ReportStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryReportRepository implements ReportRepository {

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, Report> reports = new ConcurrentHashMap<>();

    @Override
    public synchronized Report save(Report report) {
        LocalDateTime now = LocalDateTime.now();
        if (report.getId() == null) {
            report.setId(idGenerator.getAndIncrement());
            report.setCreatedAt(now);
        }
        report.setUpdatedAt(now);
        reports.put(report.getId(), report);
        return report;
    }

    @Override
    public Optional<Report> findById(Long id) {
        return Optional.ofNullable(reports.get(id));
    }

    @Override
    public List<Report> findByStatus(ReportStatus status, int page, int size) {
        int offset = (page - 1) * size;
        return reports.values().stream()
                .filter(report -> status == null || report.getStatus() == status)
                .sorted(Comparator.comparing(Report::getCreatedAt).thenComparing(Report::getId).reversed())
                .skip(offset)
                .limit(size)
                .toList();
    }
}
