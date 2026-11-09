package com.boxbox.openf1;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenF1Client {
    private final RestClient restClient;

    public OpenF1Client(RestClient.Builder builder) {
        builder.baseUrl("https://api.openf1.org");
        this.restClient = builder.build();
    }

    public String getMeetings(int year){
        return restClient.get().uri(uriBuilder -> uriBuilder
                .path("/v1/meetings")
                .queryParam("year", year)
                .build()).retrieve().body(String.class);
    }
}
