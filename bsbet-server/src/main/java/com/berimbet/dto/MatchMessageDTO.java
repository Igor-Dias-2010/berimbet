package com.berimbet.dto;

import lombok.AllArgsConstructor; 
import lombok.Data;

@Data 
@AllArgsConstructor
public class MatchMessageDTO { 
    private String type; 
    private String message; 
    private int homeScore; 
    private int awayScore; 
}