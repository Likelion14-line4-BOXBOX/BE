package com.boxbox.radio.board;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface BoardTagRepository extends Repository<BoardTag, Long> {
    @Query(value = """
            SELECT t.id AS id,COALESCE(
            """ + RadioNameSql.TAG_NAME_KO + """
                   ,t.name) AS name,t.tag_group AS tagGroup,
                   t.is_active AS active,t.display_order AS displayOrder
            FROM board_tags t WHERE t.board_id=:boardId ORDER BY t.display_order,t.id
            """, nativeQuery = true)
    List<Summary> findSummaries(@Param("boardId") Long boardId);

    interface Summary {
        Long getId();
        String getName();
        String getTagGroup();
        Boolean getActive();
        Integer getDisplayOrder();
    }
}
