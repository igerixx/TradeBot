package com.Bean;

import com.Controller.TeleBot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Component
public class BotStarter implements CommandLineRunner {

    @Autowired
    private TeleBot teleBot;

    @Autowired
    private BinanceWebSocket binanceWebSocket;

    public BotStarter(TeleBot teleBot) {
        this.teleBot = teleBot;
    }

    @Override
    public void run(String... args) throws Exception {
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        telegramBotsApi.registerBot(teleBot);
        System.out.println("Bot started");
        binanceWebSocket.connect();
    }
}
