# =============================================================================
# cs-prediction Dockerfile - 预见式服务 - 订单异常检测/流失预警/价值客户关怀
# 多阶段构建: maven:3.8 编译 → eclipse-temurin:17-jre 运行时
# 端口: 8085 (standalone) / 9005 (默认)
# =============================================================================

# ---------- Stage 1: 编译 ----------
FROM maven:3.8-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml /build/pom.xml
COPY cs-common /build/cs-common
COPY cs-prediction /build/cs-prediction

RUN mvn -B -q -DskipTests -pl cs-common,cs-prediction -am install \
 && mvn -B -q -DskipTests -pl cs-prediction -am package \
 && cp /build/cs-prediction/target/cs-prediction.jar /build/app.jar

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre AS runtime
LABEL service="cs-prediction" \
      port="8085" \
      profile="standalone"

# 容器健康检查
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=30s \
  CMD wget -q -O- http://127.0.0.1:8085/actuator/health 2>/dev/null \
   || wget -q -O- http://127.0.0.1:8085/api/prediction/health 2>/dev/null \
   || exit 1

ENV TZ=Asia/Shanghai \
    LANG=zh_CN.UTF-8 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC" \
    SPRING_PROFILES_ACTIVE=standalone

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar

EXPOSE 8085
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
