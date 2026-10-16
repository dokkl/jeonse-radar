# 로컬에서 ./gradlew bootJar로 만든 jar를 그대로 싣는다.
# jar는 아키텍처 무관이라 Apple Silicon에서 빌드해도 Lightsail(x86_64)에서 돈다.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app.jar app.jar
ENV TZ=Asia/Seoul
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar \"$@\"", "--"]
