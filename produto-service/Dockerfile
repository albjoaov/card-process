FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared-contracts shared-contracts
COPY produto-service produto-service
COPY portador-service/pom.xml portador-service/pom.xml
COPY cartao-service/pom.xml cartao-service/pom.xml
RUN mvn -q -B -pl produto-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/produto-service/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
