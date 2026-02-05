package com.Entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.util.List;

@Getter
@Entity
@Table(name = "users")
public class User {
    @Id
    private long id;

    private String username;
    private String firstName;
    private long chatId;

    @OneToMany(mappedBy = "user")
    private List<LimitOrder> limitOrders;

    public User(long id, String username, String firstName, long chatId) {
        this.id = id;
        this.username = username;
        this.firstName = firstName;
        this.chatId = chatId;
    }

    public User() {}
}
