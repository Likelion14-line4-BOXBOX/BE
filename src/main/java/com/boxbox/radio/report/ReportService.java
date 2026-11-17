package com.boxbox.radio.report;

import com.boxbox.radio.report.dto.ReportResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED)
class ReportService {
    private final ReportRepository repository;

    ReportResponse create(String loginId, Map<String, Object> body) {
        long reporterId = repository.lockReporter(loginId);
        var input = ReportInput.from(body);
        // 기존 변경 API와 같은 회원 → 게시글 → 댓글 / 회원 → 방 → 메시지 잠금 순서다.
        repository.lockTarget(input);
        if (repository.alreadyReported(reporterId, input)) throw ReportRepository.duplicate();
        long id = repository.insert(reporterId, input, Instant.now().truncatedTo(ChronoUnit.MICROS));
        return new ReportResponse(Long.toString(id), "RECEIVED");
    }
}
