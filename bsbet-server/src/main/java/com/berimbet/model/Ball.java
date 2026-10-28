package com.berimbet.model;

import lombok.Data;

@Data
public class Ball {
    private Vector2D position = new Vector2D(50, 50);
    private Vector2D velocity = new Vector2D(0, 0);
    private Player owner = null;

    public void updatePhysics() {
        if (owner != null) {
            double offsetX = owner.getTeam() == Enums.TeamSide.HOME ? 1.35 : -1.35;
            position.x = owner.getPosition().x + offsetX;
            position.y = owner.getPosition().y;
            velocity.x = 0;
            velocity.y = 0;
            return;
        }
        position.add(velocity);
        velocity.mult(0.93);

        if (position.y <= 2) { position.y = 2; velocity.y *= -0.7; }
        if (position.y >= 98) { position.y = 98; velocity.y *= -0.7; }

        boolean outsideMouth = position.y < 39 || position.y > 61;
        if (position.x <= 1.4 && outsideMouth) { position.x = 1.4; velocity.x *= -0.7; }
        if (position.x >= 98.6 && outsideMouth) { position.x = 98.6; velocity.x *= -0.7; }

        if (velocity.mag() < 0.045) {
            velocity.x = 0;
            velocity.y = 0;
        }
    }

    public void kick(Vector2D force) {
        owner = null;
        velocity.x = force.x;
        velocity.y = force.y;
    }
}