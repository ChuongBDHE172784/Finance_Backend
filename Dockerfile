# Step 1: Build the application (Dùng Temurin thay cho openjdk)
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -X -DskipTests

# Step 2: Run the application (Dùng Temurin thay cho openjdk)
FROM eclipse-temurin:17-jre
WORKDIR /app
# File jar sẽ được copy từ giai đoạn build sang
COPY --from=build /app/target/Finance_Backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
