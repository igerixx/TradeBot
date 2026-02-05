package com.Entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "frames")
public class Frame {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    private String symbol;
    private BigDecimal price;
    private BigDecimal openInterest;
    private long timestamp;

    public Frame(String symbol, BigDecimal price, BigDecimal openInterest, long timestamp) {
        this.symbol = symbol;
        this.price = price;
        this.openInterest = openInterest;
        this.timestamp = timestamp;
    }

    public Frame() {}
}
