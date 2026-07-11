# =============================================================================
# cs-cdp Dockerfile - 数字孪生 360 - 客户画像/标签/行为事件流
# 多阶段构建: maven:3.8 编译 → eclipse-temurin:17-jre 运行时
# 端口: 8083 (standalone) / 9006 (默认)
# =============================================================================

# ---------- Stage 1: 编译 ----------
FROM maven:3.8-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml /build/pom.xml
COPY cs-common /build/cs-common
COPY cs-cdp /build/cs-cdp

RUN mvn -B -q -DskipTests -pl cs-common,cs-cdp -am install \
 && mvn -B -q -DskipTests -pl cs-cdp -am package \
 && cp /build/cs-cdp/target/cs-cdp.jar /build/app.jar

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre AS runtime
LABEL service="cs-cdp" \
      port="8083" \
      profile="standalone"

# 容器健康检查
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=30s \
  CMD wget -q -O- http://127.0.0.1:8083/actuator/health 2>/dev/null \
   || wget -q -O- http://127.0.0.1:8083/api/cdp/health 2>/dev/null \
   || exit 1

ENV TZ=Asia/Shanghai \
    LANG=zh_CN.UTF-8 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC" \
    SPRING_PROFILES_ACTIVE=standalone

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar

EXPOSE 8083
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
