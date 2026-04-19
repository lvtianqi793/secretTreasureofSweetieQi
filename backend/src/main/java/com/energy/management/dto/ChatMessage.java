package com.energy.management.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    /** 角色: user / assistant / system */
    private String role;
    /** 消息内容 */
    private String content;
}
