package com.Bean;

import com.Entity.Frame;
import com.Repository.FrameRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.coyote.Request;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class BinanceWebSocket {
    private static final String WS_URL = "wss://stream.binance.com:9443/ws/btcusdt@trade";

    private final ObjectMapper mapper = new ObjectMapper();

    private boolean canBeAdded = true;
    private int count = 0;
    private final HttpClient httpClient;
    private final HttpRequest httpRequest;
    private BigDecimal OI;

    private final FrameRepository frameRepository;

    public BinanceWebSocket(FrameRepository frameRepository) {
        this.frameRepository = frameRepository;
        httpClient = HttpClient.newHttpClient();
        httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://fapi.binance.com/fapi/v1/openInterest?symbol=BTCUSDT"
                ))
                .GET()
                .build();
    }

    public void connect() throws Exception {
        WebSocketClient client = new WebSocketClient(new URI(WS_URL)) {
            @Override public void onMessage(String message) {
                try {
                    if (!canBeAdded) { return; }

                    if (count == 0) {
                        HttpResponse<String> response =
                                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                        JsonNode json = mapper.readTree(response.body());
                        OI = BigDecimal.valueOf(json.get("openInterest").asDouble());
                        count++;
                    } else if (count == 2) { count = 0; }

                    JsonNode json = mapper.readTree(message);
                    Frame frame = new Frame(
                            json.get("s").asText(),
                            BigDecimal.valueOf(json.get("p").asDouble()),
                            OI,
                            json.get("E").asLong());

                    frameRepository.save(frame);

                    canBeAdded = false;
                    new Thread(() -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        canBeAdded = true;
                    }).start();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onOpen(ServerHandshake h) {
                System.out.println("Connected to Binance");
            }

            @Override
            public void onClose(int c, String r, boolean remote) {
                System.out.println("Closed: " + r);
            }

            @Override
            public void onError(Exception ex) {
                ex.printStackTrace();
            }
        };

        client.connect();
    }
}