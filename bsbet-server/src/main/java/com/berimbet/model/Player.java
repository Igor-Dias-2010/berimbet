package com.berimbet.model;

import com.berimbet.engine.MatchEngine;
import lombok.Data;

/**
 * IA por posição inspirada em futebol real (4-3-3):
 * - GK: linha do gol, sai só na área
 * - DEF: linha defensiva, marca atacantes, não sobe à toa
 * - MID: liga setores, apoia posse, cobre transição
 * - FWD: profundidade, finalização, pressão alta
 */
@Data
public class Player {
    private int id;
    private Enums.TeamSide team;
    private Enums.PlayerRole role;
    private Enums.PlayerState state;
    private Vector2D position, velocity, homePosition;
    private PlayerAttributes attributes;
    private int cooldownTicks = 0;

    /** Velocidade base baixa → jogo legível; escala por role no execute */
    private static final double SPEED_SCALE = 0.32;
    private static final double ACCEL_SCALE = 0.40;

    public Player(int id, Enums.TeamSide team, Enums.PlayerRole role, Vector2D startPos) {
        this.id = id;
        this.team = team;
        this.role = role;
        this.homePosition = new Vector2D(startPos.x, startPos.y);
        this.position = new Vector2D(startPos.x, startPos.y);
        this.velocity = new Vector2D(0, 0);
        this.state = Enums.PlayerState.IDLE;
        this.attributes = PlayerAttributes.generateForRole(role);
    }

    public void update(MatchEngine engine, Player closestHome, Player closestAway) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            applyFriction();
            this.position.add(this.velocity);
            keepInBounds();
            return;
        }
        decideState(engine, closestHome, closestAway);
        executeState(engine, closestHome, closestAway);
        this.position.add(this.velocity);
        applyFriction();
        keepInBounds();
    }

    private void decideState(MatchEngine engine, Player closestHome, Player closestAway) {
        Ball ball = engine.getBall();
        if (role == Enums.PlayerRole.GOALKEEPER) {
            this.state = Enums.PlayerState.IDLE;
            return;
        }
        if (ball.getOwner() == this) {
            this.state = Enums.PlayerState.WITH_BALL;
            return;
        }
        if (ball.getOwner() != null && ball.getOwner().getTeam() == this.team) {
            this.state = Enums.PlayerState.SUPPORTING;
            return;
        }
        if (ball.getOwner() != null && ball.getOwner().getTeam() != this.team) {
            this.state = Enums.PlayerState.DEFENDING;
            return;
        }
        // Bola solta: só o mais próximo do time vai; os outros mantêm forma
        Player closest = (this.team == Enums.TeamSide.HOME) ? closestHome : closestAway;
        this.state = (closest == this) ? Enums.PlayerState.SUPPORTING : Enums.PlayerState.IDLE;
    }

    private void executeState(MatchEngine engine, Player closestHome, Player closestAway) {
        switch (role) {
            case GOALKEEPER -> behaveGoalkeeper(engine);
            case DEFENDER -> behaveDefender(engine);
            case MIDFIELDER -> behaveMidfielder(engine);
            case FORWARD -> behaveForward(engine);
            default -> holdShape(0.45);
        }
    }

    // ───────────────── GOLEIRO ─────────────────
    // Fica na linha, acompanha o Y da bola na área, só sai se bola muito perto.
    private void behaveGoalkeeper(MatchEngine engine) {
        Ball ball = engine.getBall();
        double goalX = (team == Enums.TeamSide.HOME) ? 4.0 : 96.0;
        double boxLimitX = (team == Enums.TeamSide.HOME) ? 18.0 : 82.0;

        if (ball.getOwner() == this) {
            // Reposição: chuta longo pro meio-campo / lado do campo
            double targetY = 30 + Math.random() * 40;
            double targetX = (team == Enums.TeamSide.HOME) ? 45 : 55;
            Vector2D dir = Vector2D.sub(new Vector2D(targetX, targetY), this.position);
            dir.normalize();
            dir.mult(1.6 + attributes.getPassing() * 0.4);
            ball.kick(dir);
            this.cooldownTicks = 14;
            return;
        }

        double ballY = clamp(ball.getPosition().y, 36, 64);
        double targetX = goalX;
        // Sai da linha se bola está na área e sem dono / adversário perto
        boolean ballInBox = team == Enums.TeamSide.HOME
                ? ball.getPosition().x < 18
                : ball.getPosition().x > 82;
        if (ballInBox && ball.getOwner() == null && this.position.distance(ball.getPosition()) < 12) {
            targetX = clamp(ball.getPosition().x, Math.min(goalX, boxLimitX), Math.max(goalX, boxLimitX));
            steerTowards(new Vector2D(targetX, ball.getPosition().y), attributes.getSpeed() * SPEED_SCALE * 1.1);
            // Tenta pegar a bola
            if (this.position.distance(ball.getPosition()) < 2.5) {
                ball.setOwner(this);
            }
            return;
        }
        steerTowards(new Vector2D(goalX, ballY), attributes.getSpeed() * SPEED_SCALE * 0.95);
    }

    // ───────────────── ZAGUEIRO ─────────────────
    // Linha defensiva, marca atacante mais próximo, limpa a área, não sobe além do meio.
    private void behaveDefender(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE * 0.9;
        double midLine = 50;
        double maxPushX = team == Enums.TeamSide.HOME ? 52 : 48;

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, false); // defensor prefere passar
            return;
        }

        if (state == Enums.PlayerState.DEFENDING) {
            Player threat = nearestOpponentForward(engine);
            Vector2D markPos;
            if (threat != null) {
                // Marca entre o atacante e o gol
                double goalX = team == Enums.TeamSide.HOME ? 0 : 100;
                markPos = new Vector2D(
                    (threat.getPosition().x * 0.65 + goalX * 0.35),
                    threat.getPosition().y * 0.7 + homePosition.y * 0.3
                );
            } else {
                markPos = defensiveLinePos(ball);
            }
            // Carrinho se perto do portador
            if (ball.getOwner() != null && this.position.distance(ball.getOwner().getPosition()) < 3.0
                    && Math.random() < attributes.getTackling() * 0.07) {
                ball.getOwner().setCooldownTicks(20);
                ball.setOwner(this);
                return;
            }
            steerTowards(markPos, spd);
            return;
        }

        if (state == Enums.PlayerState.SUPPORTING && ball.getOwner() == null) {
            // Único zagueiro mais perto pode ir na bola se ela estiver no próprio campo
            boolean inOwnHalf = team == Enums.TeamSide.HOME
                    ? ball.getPosition().x < midLine
                    : ball.getPosition().x > midLine;
            if (inOwnHalf) {
                steerTowards(ball.getPosition(), spd * 1.05);
                return;
            }
        }

        // Forma: linha defensiva acompanhando a bola no eixo Y, limitada no X
        Vector2D shape = defensiveLinePos(ball);
        if (team == Enums.TeamSide.HOME && shape.x > maxPushX) shape.x = maxPushX;
        if (team == Enums.TeamSide.AWAY && shape.x < maxPushX) shape.x = maxPushX;
        steerTowards(shape, spd * 0.75);
    }

    // ───────────────── MEIA ─────────────────
    // Conecta defesa/ataque, oferece opção de passe, pressiona no meio.
    private void behaveMidfielder(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE;

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, true); // meia pode arriscar mais
            return;
        }

        if (state == Enums.PlayerState.DEFENDING) {
            // Pressiona portador se estiver no meio; senão cobre espaço
            if (ball.getOwner() != null) {
                double dist = this.position.distance(ball.getOwner().getPosition());
                if (dist < 18) {
                    if (dist < 3.0 && Math.random() < attributes.getTackling() * 0.06) {
                        ball.getOwner().setCooldownTicks(18);
                        ball.setOwner(this);
                        return;
                    }
                    steerTowards(ball.getOwner().getPosition(), spd * 0.95);
                    return;
                }
            }
            Vector2D cover = new Vector2D(
                homePosition.x * 0.5 + ball.getPosition().x * 0.5,
                homePosition.y * 0.6 + ball.getPosition().y * 0.4
            );
            steerTowards(cover, spd * 0.8);
            return;
        }

        if (state == Enums.PlayerState.SUPPORTING) {
            if (ball.getOwner() == null) {
                steerTowards(ball.getPosition(), spd);
                return;
            }
            // Triângulo de apoio: ao lado e um pouco à frente do portador
            Player owner = ball.getOwner();
            double ahead = team == Enums.TeamSide.HOME ? 10 : -10;
            double side = (homePosition.y < 50) ? -12 : 12;
            Vector2D support = new Vector2D(
                clamp(owner.getPosition().x + ahead, 12, 88),
                clamp(owner.getPosition().y + side * 0.5, 8, 92)
            );
            // Mistura com posição tática pra não empilhar
            support.x = support.x * 0.55 + homePosition.x * 0.45;
            support.y = support.y * 0.5 + homePosition.y * 0.5;
            steerTowards(support, spd * 0.85);
            return;
        }

        holdShape(0.55);
    }

    // ───────────────── ATACANTE ─────────────────
    // Fica alto, faz diagonal, finaliza na área, pressão na zaga adversária.
    private void behaveForward(MatchEngine engine) {
        Ball ball = engine.getBall();
        double spd = attributes.getSpeed() * SPEED_SCALE * 1.05;
        double attackDir = team == Enums.TeamSide.HOME ? 1 : -1;
        double goalX = team == Enums.TeamSide.HOME ? 100 : 0;

        if (state == Enums.PlayerState.WITH_BALL) {
            playWithBall(engine, true);
            return;
        }

        if (state == Enums.PlayerState.DEFENDING) {
            // Pressão alta só se bola no campo adversário
            boolean highPress = team == Enums.TeamSide.HOME
                    ? ball.getPosition().x > 55
                    : ball.getPosition().x < 45;
            if (highPress && ball.getOwner() != null) {
                steerTowards(ball.getOwner().getPosition(), spd * 0.9);
            } else {
                // Recua um pouco mas mantém altura
                Vector2D pos = new Vector2D(
                    homePosition.x - attackDir * 8,
                    homePosition.y * 0.7 + ball.getPosition().y * 0.3
                );
                steerTowards(pos, spd * 0.7);
            }
            return;
        }

        if (state == Enums.PlayerState.SUPPORTING) {
            if (ball.getOwner() == null) {
                // Só o atacante mais perto disputa bola solta no ataque
                steerTowards(ball.getPosition(), spd);
                return;
            }
            // Desmarque em direção ao gol (corrida em diagonal)
            Player owner = ball.getOwner();
            double runX = owner.getPosition().x + attackDir * 14;
            double runY = homePosition.y + (50 - homePosition.y) * 0.15;
            // Abre a defesa: extremidade usa a lateral
            if (Math.abs(homePosition.y - 50) > 15) {
                runY = homePosition.y + (homePosition.y < 50 ? -5 : 5);
            }
            Vector2D run = new Vector2D(clamp(runX, 8, 92), clamp(runY, 10, 90));
            steerTowards(run, spd * 0.9);
            return;
        }

        // IDLE: mantém profundidade ofensiva
        Vector2D depth = new Vector2D(
            homePosition.x + attackDir * 3,
            homePosition.y * 0.8 + ball.getPosition().y * 0.2
        );
        steerTowards(depth, spd * 0.55);
    }

    // ───────────────── COM A BOLA ─────────────────
    private void playWithBall(MatchEngine engine, boolean canShootOften) {
        Ball ball = engine.getBall();
        double goalX = team == Enums.TeamSide.HOME ? 100 : 0;
        double distToGoal = Math.abs(this.position.x - goalX);
        double spd = attributes.getSpeed() * SPEED_SCALE * 0.7;

        // Finalização: mais provável dentro da área (~16.5m ≈ x>83 ou x<17)
        boolean inBox = distToGoal < 18;
        boolean edgeBox = distToGoal < 28;
        double shootChance = attributes.getShooting() * (inBox ? 0.08 : (edgeBox ? 0.03 : 0.008));
        if (canShootOften && distToGoal < 32 && Math.random() < shootChance) {
            double aimY = 50 + (Math.random() * 14 - 7);
            // Goleiro "fecha o ângulo": mira um pouco nas laterais do gol
            if (Math.random() < 0.35) aimY = Math.random() < 0.5 ? 42 : 58;
            Vector2D shot = Vector2D.sub(new Vector2D(goalX, aimY), this.position);
            shot.normalize();
            double power = inBox ? (1.5 + attributes.getShooting() * 0.7) : (1.2 + attributes.getShooting() * 0.45);
            shot.mult(power);
            ball.kick(shot);
            this.cooldownTicks = 12;
            return;
        }

        // Passe progressivo para o melhor companheiro
        Player best = engine.findBestPassOption(this);
        double passChance = attributes.getPassing() * (role == Enums.PlayerRole.DEFENDER ? 0.04 : 0.028);
        if (best != null && Math.random() < passChance) {
            Vector2D pass = Vector2D.sub(best.getPosition(), this.position);
            pass.normalize();
            pass.mult(1.0 + attributes.getPassing() * 0.55);
            ball.kick(pass);
            this.cooldownTicks = 8;
            return;
        }

        // Conduz em direção ao gol / canal
        double channelY = clamp(this.position.y + (50 - this.position.y) * 0.08, 12, 88);
        steerTowards(new Vector2D(goalX, channelY), spd);
    }

    private Vector2D defensiveLinePos(Ball ball) {
        // Linha ~12–18 unidades à frente do gol, sobe/desce com a bola
        double baseX = homePosition.x;
        double ballPull = (ball.getPosition().x - 50) * 0.12;
        if (team == Enums.TeamSide.HOME) baseX = clamp(homePosition.x + ballPull, 12, 48);
        else baseX = clamp(homePosition.x + ballPull, 52, 88);
        double y = homePosition.y * 0.65 + ball.getPosition().y * 0.35;
        return new Vector2D(baseX, clamp(y, 10, 90));
    }

    private Player nearestOpponentForward(MatchEngine engine) {
        Player best = null;
        double min = Double.MAX_VALUE;
        for (Player p : engine.getPlayers()) {
            if (p.getTeam() == this.team) continue;
            if (p.getRole() != Enums.PlayerRole.FORWARD && p.getRole() != Enums.PlayerRole.MIDFIELDER) continue;
            double d = this.position.distance(p.getPosition());
            if (d < min) { min = d; best = p; }
        }
        return best;
    }

    private void holdShape(double speedFactor) {
        steerTowards(homePosition, attributes.getSpeed() * SPEED_SCALE * speedFactor);
    }

    private void steerTowards(Vector2D target, double maxSpeed) {
        Vector2D desired = Vector2D.sub(target, this.position);
        double d = desired.mag();
        if (d > 0.35) {
            desired.normalize();
            desired.mult(d < 5.0 ? maxSpeed * (d / 5.0) : maxSpeed);
            Vector2D steer = Vector2D.sub(desired, this.velocity);
            steer.limit(attributes.getAcceleration() * ACCEL_SCALE);
            this.velocity.add(steer);
        }
    }

    private void applyFriction() {
        this.velocity.mult(0.87);
    }

    private void keepInBounds() {
        // Goleiro pode ficar mais perto da linha
        double minX = role == Enums.PlayerRole.GOALKEEPER ? 1.5 : 2.5;
        double maxX = role == Enums.PlayerRole.GOALKEEPER ? 98.5 : 97.5;
        if (this.position.x < minX) this.position.x = minX;
        if (this.position.x > maxX) this.position.x = maxX;
        if (this.position.y < 2) this.position.y = 2;
        if (this.position.y > 98) this.position.y = 98;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}