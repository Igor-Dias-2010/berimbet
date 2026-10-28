package com.berimbet.model;

import lombok.Builder; 
import lombok.Data;

@Data 
@Builder
public class PlayerAttributes {
    private double speed, acceleration, passing, shooting, tackling, positioning;
    
    public static PlayerAttributes generateForRole(Enums.PlayerRole role) {
        return switch (role) {
            case GOALKEEPER -> PlayerAttributes.builder().speed(1.2).acceleration(0.1).passing(0.5).shooting(0.2).tackling(0.8).positioning(0.9).build();
            case DEFENDER -> PlayerAttributes.builder().speed(1.5).acceleration(0.15).passing(0.6).shooting(0.3).tackling(0.9).positioning(0.8).build();
            case MIDFIELDER -> PlayerAttributes.builder().speed(1.6).acceleration(0.18).passing(0.9).shooting(0.7).tackling(0.6).positioning(0.8).build();
            case FORWARD -> PlayerAttributes.builder().speed(1.8).acceleration(0.2).passing(0.6).shooting(0.9).tackling(0.3).positioning(0.9).build();
        };
    }
}