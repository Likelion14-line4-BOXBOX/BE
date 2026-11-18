package com.boxbox.race;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SessionNamesTest {
    @Test
    void translatesKnownTypesAndPracticeNumbers() {
        for (int number = 1; number <= 3; number++) {
            assertEquals("프리 프랙티스 " + number, SessionNames.display("PRACTICE", number, "Practice " + number));
        }
        assertEquals("예선", SessionNames.display("QUALIFYING", 1, "Qualifying"));
        assertEquals("스프린트 예선", SessionNames.display("SPRINT_QUALIFYING", 1, "Sprint Qualifying"));
        assertEquals("스프린트", SessionNames.display("SPRINT", 1, "Sprint"));
        assertEquals("본선", SessionNames.display("RACE", 1, "Race"));
    }

    @Test
    void keepsCustomKoreanAndUnknownOrInvalidSessions() {
        assertEquals("특별 예선", SessionNames.display("QUALIFYING", 1, "특별 예선"));
        assertEquals("Other", SessionNames.display("OTHER", 1, "Other"));
        assertEquals("Practice 4", SessionNames.display("PRACTICE", 4, "Practice 4"));
        assertEquals("Race 2", SessionNames.display("RACE", 2, "Race 2"));
        assertEquals("Unknown", SessionNames.display(null, 1, "Unknown"));
        assertNull(SessionNames.display("OTHER", 1, null));
    }
}
