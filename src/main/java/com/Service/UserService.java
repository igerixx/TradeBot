package com.Service;

import com.Config.TeleProps;
import com.Controller.TeleBot;
import com.Entity.User;
import com.Repository.UserRepository;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Component
public class UserService {
    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User saveOrFindUser(long id, String username, String FLname, long chatId) {
        User user = new User(id, username, FLname, chatId);
        if (userRepository.findById(id).isPresent())
            return userRepository.findById(id).get();

        return userRepository.save(user);
    }
}
