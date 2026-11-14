package com.boxbox.radio.chat;

import com.boxbox.common.api.ApiException;

/** TR19와 공유하는 방별 영속 이벤트 위치. 접속 수/heartbeat는 이 위치를 변경하지 않는다. */
record ChatEventCursor(long roomId, long sequence) {
    static long parse(String value, long roomId, long latest) {
        if (value == null) return latest;
        if (value.length() > 64 || !value.matches("evt_[1-9][0-9]*_(0|[1-9][0-9]*)")) throw invalid();
        try {
            String[] parts = value.split("_");
            long room = Long.parseLong(parts[1]);
            long sequence = Long.parseLong(parts[2]);
            if (room != roomId || sequence > latest) throw invalid();
            return sequence;
        } catch (NumberFormatException exception) { throw invalid(); }
    }

    @Override public String toString() { return "evt_" + roomId + "_" + sequence; }
    private static ApiException invalid() { return ApiException.invalidRequest("Last-Event-ID를 확인해 주세요."); }
}
