FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY shared-contracts shared-contracts
COPY cartao-service cartao-service
COPY produto-service/pom.xml produto-service/pom.xml
COPY portador-service/pom.xml portador-service/pom.xml
RUN mvn -q -B -pl cartao-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /workspace/cartao-service/target/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
