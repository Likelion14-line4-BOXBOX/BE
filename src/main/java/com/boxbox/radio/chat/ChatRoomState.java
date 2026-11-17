package com.boxbox.radio.chat;

import java.time.Instant;

enum ChatRoomState {
    SCHEDULED, LIVE, ARCHIVED;

    static ChatRoomState at(String stored, Instant opensAt, Instant closesAt, Instant now) {
        if (ARCHIVED.name().equals(stored) || !now.isBefore(closesAt)) return ARCHIVED;
        return now.isBefore(opensAt) ? SCHEDULED : LIVE;
    }
}
