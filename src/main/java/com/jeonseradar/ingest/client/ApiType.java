package com.jeonseradar.ingest.client;

public enum ApiType {

    TRADE("RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev"),
    RENT("RTMSDataSvcAptRent/getRTMSDataSvcAptRent");

    private final String path;

    ApiType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
