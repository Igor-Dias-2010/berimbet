FROM maven:3.9-eclipse-temurin-17 AS build
COPY . .
# Adicionamos a flag para garantir que o Lombok consiga fazer o código compilar
ARG MAVEN_OPTS="-Djansi.passthrough=true"
RUN mvn clean package -Dmaven.test.skip=true

FROM eclipse-temurin:17-jre-jammy
COPY --from=build target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app.jar"]
