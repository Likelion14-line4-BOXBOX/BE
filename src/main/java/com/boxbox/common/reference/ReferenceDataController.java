package com.boxbox.common.reference;

import com.boxbox.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
class ReferenceDataController {
    private final ReferenceDataService service;

    @GetMapping("/api/reference-data")
    ApiResponse<ReferenceData> getReferenceData() {
        return ApiResponse.success(service.getReferenceData());
    }
}
