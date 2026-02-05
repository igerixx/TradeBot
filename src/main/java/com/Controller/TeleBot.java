package com.Controller;

import com.Bean.BinanceWebSocket;
import com.Config.TeleProps;
import com.Entity.Frame;
import com.Entity.LimitOrder;
import com.Entity.User;
import com.Repository.FrameRepository;
import com.Repository.LimitOrderRepository;
import com.Repository.UserRepository;
import com.Service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatDescription;
import org.telegram.telegrambots.meta.api.methods.groupadministration.SetChatTitle;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@EnableScheduling
@Component
public class TeleBot extends TelegramLongPollingBot {
    private final String token;
    private final String username;
    private final UserService userService;

    @Autowired
    private BinanceWebSocket binanceWebSocket;
    @Autowired
    private FrameRepository frameRepository;
    @Autowired
    private LimitOrderRepository limitOrderRepository;
    @Autowired
    private UserRepository userRepository;

    private User user;
    private final float procent = 1.0F;
    private float limitProcent = 0.5F;
    private int procentCounter = 0;
    private List<Float> limitProcents;
    private Integer orderMessageId;
    private BigDecimal currentLimitOrderPrice;
    private Map<User, Boolean> limitOrders = new HashMap<>();
    private Map<User, String> orderNotifications = new HashMap<>();
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private ScheduledExecutorService orderScheduler = Executors.newScheduledThreadPool(2);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient priceClient, OIClient, priceClient24h;
    private final HttpRequest priceRequest, OIRequest, priceRequest24h;

    public TeleBot(TeleProps props, UserService userService) {
        super(props.getToken());
        this.userService = userService;
        this.token = props.getToken();
        this.username = props.getUsername();

        priceClient = HttpClient.newHttpClient();
        priceRequest = HttpRequest.newBuilder()
                .uri(URI.create(
                    "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1m&limit=61"
                ))
                .GET()
                .build();

        OIClient = HttpClient.newHttpClient();
        OIRequest = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://fapi.binance.com/futures/data/openInterestHist?symbol=BTCUSDT&period=5m&limit=13"
                ))
                .GET()
                .build();

        priceClient24h = HttpClient.newHttpClient();
        priceRequest24h = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT"
                ))
                .GET()
                .build();
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (!update.hasMessage()) {
            if (update.hasCallbackQuery())
                switch (update.getCallbackQuery().getData()) {
                    case "CANCEL":
                        deleteMSG(user.getChatId(), orderMessageId);
                        limitOrders.remove(user);
                        break;
                    case "BUY":
                        orderNotifications.put(user, "BUY");
                    case "SELL":
                        orderNotifications.put(user, "SELL");
                    default: {
                        if (update.getCallbackQuery().getData().charAt(0) == '&') {
                            LimitOrder limitOrder = limitOrderRepository.findById(Long.valueOf(update.getCallbackQuery().getData().replace("&", ""))).get();
                            sendMSG(user.getChatId(), "Удалён ордер " + limitOrder.getPrice());
                            limitOrderRepository.deleteById(Long.valueOf(update.getCallbackQuery().getData().replace("&", "")));

                            List<LimitOrder> orderList = limitOrderRepository.findAllByUserId(user.getId());
                            List<List<InlineKeyboardButton>> btns = new ArrayList<>();
                            int i = 0;
                            for (LimitOrder l : orderList) {
                                InlineKeyboardButton btn = new InlineKeyboardButton(String.format("№%d (%.2f)", ++i, l.getPrice()));
                                btn.setCallbackData(String.format("&%s", l.getId()));
                                btns.add(List.of(btn));
                            }

                            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                            markup.setKeyboard(btns);

                            editMSG(user.getChatId(), orderMessageId, markup);

                            break;
                        }

                        if (orderNotifications.get(user) != null
                                && limitOrders.get(user) != null
                                && currentLimitOrderPrice != null
                        ) {

                            sendMSG(user.getChatId(), "Ордер на " + (orderNotifications.get(user).equals("BUY") ? "покупку" : "продажу") + " по цене " + currentLimitOrderPrice + ", успешно создан");

                            deleteMSG(user.getChatId(), orderMessageId);

                            limitOrders.remove(user);
                            orderNotifications.remove(user);
                            currentLimitOrderPrice = null;
                            limitOrderSchedule();
                        }
                        break;
                    }
                }
            return;
        }

        if (user == null || user.getId() != update.getMessage().getFrom().getId()) {
            long id = update.getMessage().getFrom().getId();
            String username = update.getMessage().getFrom().getUserName();
            String firstName = update.getMessage().getFrom().getFirstName();
            long chatId = update.getMessage().getChatId();
            user = userService.saveOrFindUser(id, username, firstName, chatId);
        }

        if (limitOrders.get(user) != null && limitOrders.get(user)) {
            try {
                BigDecimal limitOrderPrice = new BigDecimal(update.getMessage().getText());
                LimitOrder limitOrder = new LimitOrder(limitOrderPrice, user);
                currentLimitOrderPrice = limitOrderPrice;
                limitOrderRepository.save(limitOrder);
            } catch (Exception e) {
                sendMSG(user.getChatId(), "Напиши цену только цифрами, пример: 1234.5, 500");
                e.printStackTrace();
                return;
            }

            if (orderNotifications.get(user) != null) {

                sendMSG(user.getChatId(), "Ордер на " + (orderNotifications.get(user).equals("BUY") ? "покупку" : "продажу") + " по цене " + currentLimitOrderPrice + ", успешно создан");

                deleteMSG(user.getChatId(), orderMessageId);

                limitOrders.remove(user);
                orderNotifications.remove(user);
                currentLimitOrderPrice = null;
                limitOrderSchedule();
            }

            return;
        }

        if (update.getMessage().getText().charAt(0) == '/') {
            commands(update);
            return;
        }

        sendMSG(user.getChatId(), "Привет");
    }

    public void commands(Update update) {
        String text = update.getMessage().getText();
        long chatId = update.getMessage().getChatId();

        switch (text) {
            case "/activate": {
                scheduler.scheduleAtFixedRate(() -> {
                    if (checkTimeDiff(procent, false, "1m") >= procent) sendMSG(chatId, String.format("1m price: %.2f%%", checkTimeDiff(procent, false, "1m")));
                    if (checkTimeDiff(procent, true, "1m") >= procent) sendMSG(chatId, String.format("1m OI: %.2f%%", checkTimeDiff(procent, true, "1m")));
                }, 0, 1, TimeUnit.MINUTES);

                scheduler.scheduleAtFixedRate(() -> {
                    if (checkTimeDiff(procent, false, "2m") >= procent) sendMSG(chatId, String.format("2m price: %.2f%%", checkTimeDiff(procent, false, "2m")));
                    if (checkTimeDiff(procent, true, "2m") >= procent) sendMSG(chatId, String.format("2m OI: %.2f%%", checkTimeDiff(procent, true, "2m")));
                }, 0, 2, TimeUnit.MINUTES);

                scheduler.scheduleAtFixedRate(() -> {
                    if (checkTimeDiff(procent, false, "5m") >= procent) sendMSG(chatId, String.format("5m price: %.2f%%", checkTimeDiff(procent, false, "5m")));
                    if (checkTimeDiff(procent, true, "5m") >= procent) sendMSG(chatId, String.format("5m OI: %.2f%%", checkTimeDiff(procent, true, "5m")));
                }, 0, 5, TimeUnit.MINUTES);

                scheduler.scheduleAtFixedRate(() -> {
                    if (checkTimeDiff(procent, false, "10m") >= procent) sendMSG(chatId, String.format("10m price: %.2f%%", checkTimeDiff(procent, false, "10m")));
                    if (checkTimeDiff(procent, true, "10m") >= procent) sendMSG(chatId, String.format("10m OI: %.2f%%", checkTimeDiff(procent, true, "10m")));
                }, 0, 10, TimeUnit.MINUTES);

                scheduler.scheduleAtFixedRate(() -> {
                    if (checkTimeDiff(procent, false, "1h") >= procent) sendMSG(chatId, String.format("1h price: %.2f%%", checkTimeDiff(procent, false, "1h")));
                    if (checkTimeDiff(procent, true, "1h") >= procent) sendMSG(chatId, String.format("1h OI: %.2f%%", checkTimeDiff(procent, true, "1h")));
                }, 0, 1, TimeUnit.HOURS);

                scheduler.scheduleAtFixedRate(() -> {
                    if (checkTimeDiff(procent, false, "24h") >= procent) sendMSG(chatId, String.format("24h price: %.2f%%", checkTimeDiff(procent, false, "24h")));
                }, 0, 1, TimeUnit.DAYS);

                sendMSG(chatId, "Включено");

                break;
            }
            case "/deactivate": {
                scheduler.shutdown();
                sendMSG(chatId, "Выключено");
                break;
            }
            case "/order": {
                InlineKeyboardButton btn1 = new InlineKeyboardButton("Buy");
                btn1.setCallbackData("BUY");
                InlineKeyboardButton btn2 = new InlineKeyboardButton("Sell");
                btn2.setCallbackData("SELL");

                InlineKeyboardButton btn3 = new InlineKeyboardButton("Cancel");
                btn3.setCallbackData("CANCEL");

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                markup.setKeyboard(List.of(List.of(btn1, btn2),List.of(btn3)));

                limitOrders.put(user, true);
                orderMessageId = sendMSG(chatId, "Напиши цену и укажи предназначение лимитного ордера", markup);
                limitOrderSchedule();
                break;
            }
            case "/delorder": {
                if (limitOrderRepository.findAllByUserId(user.getId()).isEmpty()) {
                    sendMSG(chatId, "У тебя нет лимитных ордеров");
                    break;
                }

                List<LimitOrder> orderList = limitOrderRepository.findAllByUserId(user.getId());
                List<List<InlineKeyboardButton>> btns = new ArrayList<>();
                int i = 0;
                for (LimitOrder limitOrder : orderList) {
                    InlineKeyboardButton btn = new InlineKeyboardButton(String.format("№%d (%.2f)", ++i, limitOrder.getPrice()));
                    btn.setCallbackData(String.format("&%s", limitOrder.getId()));
                    btns.add(List.of(btn));
                }

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                markup.setKeyboard(btns);

                orderMessageId = sendMSG(chatId, "Выбери ордер который хочешь удалить", markup);
                break;
            }
            case "/situation": {
                sendMSG(chatId, String.format("1m` price: %.2f%%", checkTimeDiff(procent, false, "1m")));
                sendMSG(chatId, String.format("1m` OI: %.2f%%", checkTimeDiff(procent, true, "1m")));

                sendMSG(chatId, String.format("2m` price: %.2f%%", checkTimeDiff(procent, false, "2m")));
                sendMSG(chatId, String.format("2m` OI: %.2f%%", checkTimeDiff(procent, true, "2m")));

                sendMSG(chatId, String.format("5m` price: %.2f%%", checkTimeDiff(procent, false, "5m")));
                sendMSG(chatId, String.format("5m` OI: %.2f%%", checkTimeDiff(procent, true, "5m")));

                sendMSG(chatId, String.format("10m` price: %.2f%%", checkTimeDiff(procent, false, "10m")));
                sendMSG(chatId, String.format("10m` OI: %.2f%%", checkTimeDiff(procent, true, "10m")));

                sendMSG(chatId, String.format("1h` price: %.2f%%", checkTimeDiff(procent, false, "1h")));
                sendMSG(chatId, String.format("1h` OI: %.2f%%", checkTimeDiff(procent, true, "1h")));

                sendMSG(chatId, String.format("24h` price: %.2f%%", checkTimeDiff(procent, false, "24h")));
                break;
            }
            default: {
                sendMSG(chatId, "Нет такой команды");
                break;
            }
        }
    }

    public void limitOrderSchedule() {
        if (limitOrderRepository.findAllByUserId(user.getId()).isEmpty()) return;

        List<LimitOrder> orderList = limitOrderRepository.findAllByUserId(user.getId());
        if (procentCounter == 0) {
            limitProcents = new ArrayList<>(Collections.nCopies(orderList.size(), limitProcent));
            procentCounter++;
        } else {
            limitProcents.add(limitProcent);
        }
        for (int i = 0; i < orderList.size(); i++) {
            int finalI = i;
            orderScheduler.scheduleAtFixedRate(() -> {
                BigDecimal lastPrice = frameRepository.findTop1ByOrderByIdDesc().getPrice();
                BigDecimal orderPrice = orderList.get(finalI).getPrice();
                if (Math.abs(procentDiff(orderPrice, lastPrice)) <= limitProcents.get(finalI)) {
                    sendMSG(user.getChatId(), "Цена приблизилась к цене ордера №" + (finalI + 1) + " (" + orderPrice + ")" + ", цена сечас: " + lastPrice);
                    limitProcents.set(finalI, limitProcents.get(finalI) / 2);
                }

                if (limitProcents.get(finalI) < 0.1F) {
                    limitOrderRepository.deleteById(orderList.get(finalI).getId());
                    limitOrders.remove(user);
                    orderScheduler.shutdown();
                }
            }, 0, 5, TimeUnit.SECONDS);
        }
    }

    public Integer sendMSG(long chatId, String text) {
        SendMessage sendMessage = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();

        Message message = null;
        try {
            message = execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        if (message != null) {
            return message.getMessageId();
        }
        return -1;
    }

    public Integer sendMSG(long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage sendMessage = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(markup)
                .build();

        Message message = null;
        try {
            message = execute(sendMessage);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        if (message != null) {
            return message.getMessageId();
        }
        return -1;
    }

    public void deleteMSG(long chatId, Integer messageId) {
        DeleteMessage deleteMessage = new DeleteMessage();
        deleteMessage.setChatId(chatId);
        deleteMessage.setMessageId(messageId);
        try {
            execute(deleteMessage);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    public void editMSG(long chatId, Integer messageId, InlineKeyboardMarkup markup) {
        EditMessageReplyMarkup editMessageReplyMarkup = new EditMessageReplyMarkup();
        editMessageReplyMarkup.setChatId(chatId);
        editMessageReplyMarkup.setMessageId(messageId);
        editMessageReplyMarkup.setReplyMarkup(markup);
        try {
            execute(editMessageReplyMarkup);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    public float procentDiff(BigDecimal oldPrice, BigDecimal newPrice) {
        return (newPrice.floatValue() - oldPrice.floatValue()) / oldPrice.floatValue() * 100;
    }

    public double checkTimeDiff(float procent, boolean isOI, String timeFrame) {
        switch (timeFrame) {
            case "1m": {
                return !isOI ? procentDiff(frameRepository.findTop12ByOrderByIdDesc().get(11).getPrice(), frameRepository.findTop1ByOrderByIdDesc().getPrice()) :
                        procentDiff(frameRepository.findTop12ByOrderByIdDesc().get(11).getOpenInterest(), frameRepository.findTop1ByOrderByIdDesc().getOpenInterest());
            }
            case "2m": {
                return !isOI ? procentDiff(frameRepository.findTop24ByOrderByIdDesc().get(23).getPrice(), frameRepository.findTop1ByOrderByIdDesc().getPrice()) :
                        procentDiff(frameRepository.findTop24ByOrderByIdDesc().get(23).getOpenInterest(), frameRepository.findTop1ByOrderByIdDesc().getOpenInterest());
            }
            case "5m": {
                return !isOI ? procentDiff(frameRepository.findTop60ByOrderByIdDesc().get(59).getPrice(), frameRepository.findTop1ByOrderByIdDesc().getPrice()) :
                        procentDiff(frameRepository.findTop60ByOrderByIdDesc().get(59).getOpenInterest(), frameRepository.findTop1ByOrderByIdDesc().getOpenInterest());
            }
            case "10m": {
                return !isOI ? procentDiff(frameRepository.findTop120ByOrderByIdDesc().get(119).getPrice(), frameRepository.findTop1ByOrderByIdDesc().getPrice()) :
                        procentDiff(frameRepository.findTop120ByOrderByIdDesc().get(119).getOpenInterest(), frameRepository.findTop1ByOrderByIdDesc().getOpenInterest());
            }
            case "1h": {
                JsonNode json = null;
                HttpResponse<String> response = null;
                if (!isOI) {
                    try{
                        response =
                                priceClient.send(priceRequest, HttpResponse.BodyHandlers.ofString());
                        json = mapper.readTree(response.body());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    return procentDiff(BigDecimal.valueOf(json.get(0).get(4).asDouble()), frameRepository.findTop1ByOrderByIdDesc().getPrice());
                }

                try {
                    response = OIClient.send(OIRequest, HttpResponse.BodyHandlers.ofString());
                    json = mapper.readTree(response.body());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                return procentDiff(BigDecimal.valueOf(json.get(0).get("sumOpenInterest").asDouble()), frameRepository.findTop1ByOrderByIdDesc().getOpenInterest());
            }
            case "24h": {
                JsonNode json = null;
                HttpResponse<String> response = null;
                if (!isOI) {
                    try{
                        response =
                               priceClient24h.send(priceRequest24h, HttpResponse.BodyHandlers.ofString());
                        json = mapper.readTree(response.body());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    return json.get("priceChangePercent").asDouble();
                }
            }
        }

        return -1;
    }
}
