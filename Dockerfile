FROM java:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/bad-bot-0.0.1-SNAPSHOT-standalone.jar /bad-bot/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/bad-bot/app.jar"]
