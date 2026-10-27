# ===== Stage 1: Build =====
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copia o pom.xml primeiro (melhor cache de dependências)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copia o código fonte e gera o jar
COPY src ./src
RUN mvn clean package -DskipTests

# ===== Stage 2: Runtime (imagem pequena) =====
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Cria usuário não-root (boa prática)
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copia o jar gerado na stage anterior
COPY --from=builder /app/target/*.jar app.jar

# Porta padrão do Spring Boot
EXPOSE 8080

# Flags de memória otimizadas pro plano free (512 MB)
ENTRYPOINT ["java", \
  "-Xmx400m", \
    "-Xms200m", \
      "-XX:+UseSerialGC", \
        "-XX:MaxRAMPercentage=75.0", \
          "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "app.jar"]