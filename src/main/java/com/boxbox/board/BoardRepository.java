package com.boxbox.board;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface BoardRepository extends Repository<Board, Long> {
    boolean existsByIdAndActiveTrue(Long id);

    @Query(value = """
            SELECT b.id AS boardId, b.type AS type, b.name AS name,
                   b.team_id AS teamId, b.display_order AS displayOrder,
                   COUNT(p.id) AS postCount
            FROM boards b
            LEFT JOIN posts p ON p.board_id = b.id AND p.deleted_at IS NULL
            WHERE b.is_active = TRUE
              AND (:type IS NULL OR b.type = :type)
              AND (:teamId IS NULL OR b.team_id = :teamId)
            GROUP BY b.id, b.type, b.name, b.team_id, b.display_order
            ORDER BY b.display_order, b.id
            """, nativeQuery = true)
    List<BoardSummary> findSummaries(@Param("type") String type, @Param("teamId") Long teamId);

    // 인증 계층이 검증한 Principal의 name은 자체 로그인 아이디(loginId)로 연결한다.
    @Query(value = """
            SELECT id AS userId, favorite_team_id AS favoriteTeamId
            FROM users
            WHERE login_id = :loginId AND status = 'ACTIVE' AND deleted_at IS NULL
            """, nativeQuery = true)
    Optional<Viewer> findViewer(@Param("loginId") String loginId);

    interface BoardSummary {
        Long getBoardId();
        String getType();
        String getName();
        Long getTeamId();
        Integer getDisplayOrder();
        Long getPostCount();
    }

    interface Viewer {
        Long getUserId();
        Long getFavoriteTeamId();
    }
}
