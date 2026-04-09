# Use an official JDK 21 image from Eclipse Temurin
FROM eclipse-temurin:21-jdk-jammy

# Set the working directory inside the container
WORKDIR /app

# Copy all project files into the container
COPY . .

# Compile the Java source code
RUN javac webserver/src/*.java

# Run the platform when the container starts
CMD ["java", "-cp", ".", "webserver.src.ShareSync"]
