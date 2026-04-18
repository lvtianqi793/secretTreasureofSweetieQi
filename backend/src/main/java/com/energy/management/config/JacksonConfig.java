package com.energy.management.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Jackson 时间格式配置
 * 支持多种常见日期字符串格式: "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd"
 */
@Configuration
public class JacksonConfig {

    private static final List<DateTimeFormatter> DATETIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    );

    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            JavaTimeModule javaTimeModule = new JavaTimeModule();
            SimpleModule flexibleModule = new SimpleModule();
            flexibleModule.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
            flexibleModule.addDeserializer(LocalDate.class, new FlexibleLocalDateDeserializer());
            builder.modules(javaTimeModule, flexibleModule);
        };
    }

    static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) return null;
            String s = text.trim();
            for (DateTimeFormatter f : DATETIME_FORMATTERS) {
                try {
                    return LocalDateTime.parse(s, f);
                } catch (DateTimeParseException ignore) {
                }
            }
            try {
                return LocalDate.parse(s, DATE_ONLY).atStartOfDay();
            } catch (DateTimeParseException ignore) {
            }
            throw new IOException("无法解析日期时间: " + s
                    + " (支持: yyyy-MM-dd HH:mm:ss / yyyy-MM-dd'T'HH:mm:ss / yyyy-MM-dd)");
        }
    }

    static class FlexibleLocalDateDeserializer extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) return null;
            String s = text.trim();
            try {
                return LocalDate.parse(s, DATE_ONLY);
            } catch (DateTimeParseException ignore) {
            }
            for (DateTimeFormatter f : DATETIME_FORMATTERS) {
                try {
                    return LocalDateTime.parse(s, f).toLocalDate();
                } catch (DateTimeParseException ignore) {
                }
            }
            throw new IOException("无法解析日期: " + s);
        }
    }
}
