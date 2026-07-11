# =============================================================================
# cs-im Dockerfile - IM 核心服务 - WebSocket/会话/消息/坐席分发
# 多阶段构建: maven:3.8 编译 → eclipse-temurin:17-jre 运行时
# 端口: 8082 (standalone) / 9002 (默认)
# =============================================================================

# ---------- Stage 1: 编译 ----------
FROM maven:3.8-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml /build/pom.xml
COPY cs-common /build/cs-common
COPY cs-im /build/cs-im

RUN mvn -B -q -DskipTests -pl cs-common,cs-im -am install \
 && mvn -B -q -DskipTests -pl cs-im -am package \
 && cp /build/cs-im/target/cs-im.jar /build/app.jar

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre AS runtime
LABEL service="cs-im" \
      port="8082" \
      profile="standalone"

# 容器健康检查
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=30s \
  CMD wget -q -O- http://127.0.0.1:8082/actuator/health 2>/dev/null \
   || wget -q -O- http://127.0.0.1:8082/api/im/health 2>/dev/null \
   || exit 1

ENV TZ=Asia/Shanghai \
    LANG=zh_CN.UTF-8 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC" \
    SPRING_PROFILES_ACTIVE=standalone

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar

EXPOSE 8082
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
