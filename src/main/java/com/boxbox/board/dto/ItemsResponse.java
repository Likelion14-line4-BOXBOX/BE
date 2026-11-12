package com.boxbox.board.dto;

import java.util.List;

public record ItemsResponse<T>(List<T> items) {
}
