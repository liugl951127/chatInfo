# =============================================================================
# cs-auth Dockerfile - 鉴权服务 (登录/注册/JWT签发)
# 多阶段构建: maven:3.8 编译 → eclipse-temurin:17-jre 运行时
# 端口: 8081 (standalone) / 9001 (默认)
# =============================================================================

# ---------- Stage 1: 编译 ----------
FROM maven:3.8-eclipse-temurin-17 AS build
WORKDIR /build

# 先复制所有模块的 pom (cs-common 依赖)
COPY pom.xml /build/pom.xml
COPY cs-common /build/cs-common
COPY cs-auth /build/cs-auth
# m3-adapter 是 Python 服务, 不需要 Java 依赖; 但为保险起见, 一并复制
COPY m3-adapter /build/m3-adapter

# 离线友好: 先 install cs-common + cs-auth 到本地仓库
RUN mvn -B -q -DskipTests -pl cs-common,cs-auth -am install

# 二次打包 (跳过测试, 产出可执行 jar)
RUN mvn -B -q -DskipTests -pl cs-auth -am package \
 && cp /build/cs-auth/target/cs-auth.jar /build/app.jar

# ---------- Stage 2: 运行时 ----------
FROM eclipse-temurin:17-jre AS runtime
LABEL service="cs-auth" \
      port="8081" \
      profile="standalone"

# 容器健康检查 (Spring Boot Actuator 需 enabled; 这里退化为端口探活)
HEALTHCHECK --interval=15s --timeout=3s --retries=5 --start-period=30s \
  CMD wget -q -O- http://127.0.0.1:8081/actuator/health 2>/dev/null \
   || wget -q -O- http://127.0.0.1:8081/api/auth/health 2>/dev/null \
   || exit 1

# 时区 + 中文环境
ENV TZ=Asia/Shanghai \
    LANG=zh_CN.UTF-8 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC" \
    SPRING_PROFILES_ACTIVE=standalone

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar

EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
