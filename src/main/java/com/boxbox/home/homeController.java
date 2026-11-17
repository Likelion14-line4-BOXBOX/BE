package com.boxbox.home;

import com.boxbox.openf1.OpenF1Client;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class homeController {
    private final OpenF1Client openF1Client;

    @GetMapping("/api/v1/races")
    public String home() {
        return openF1Client.getMeetings(2026);
    }
}
