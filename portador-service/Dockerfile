FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared-contracts shared-contracts
COPY portador-service portador-service
COPY produto-service/pom.xml produto-service/pom.xml
COPY cartao-service/pom.xml cartao-service/pom.xml
RUN mvn -q -B -pl portador-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/portador-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
