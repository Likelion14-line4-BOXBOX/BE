package com.boxbox.radio.board;

/** 저장된 게시판/태그명은 보존하고 고유 관계로 한국어 표시명을 조회한다. b=boards, t=board_tags. */
public final class RadioNameSql {
    private RadioNameSql() { }

    public static final String BOARD_NAME_KO = """
            (CASE WHEN b.type='TEAM' THEN
                (SELECT CONCAT(TRIM(rt.name_ko),' 게시판') FROM teams rt WHERE rt.id=b.team_id AND NULLIF(TRIM(rt.name_ko),'') IS NOT NULL)
            ELSE NULL END)
            """;

    public static final String TAG_NAME_KO = """
            (CASE WHEN t.tag_group='DRIVER' THEN
                (SELECT NULLIF(TRIM(rd.name_ko),'') FROM drivers rd WHERE rd.id=t.driver_id)
            WHEN t.tag_group='TEAM' THEN
                (SELECT NULLIF(TRIM(rt.name_ko),'') FROM boards rb JOIN teams rt ON rt.id=rb.team_id WHERE rb.id=t.board_id)
            ELSE NULL END)
            """;
}
