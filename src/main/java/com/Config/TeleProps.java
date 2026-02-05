package com.Config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "telegram.bot")
@Data
@Component
public class TeleProps {

    private String token;
    private String username;

}
