package com.boxbox.board;

import java.util.List;
import org.springframework.data.repository.Repository;

public interface BoardTagRepository extends Repository<BoardTag, Long> {
    List<BoardTag> findByBoardIdOrderByDisplayOrderAscIdAsc(Long boardId);
}
