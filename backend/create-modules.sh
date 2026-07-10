#!/bin/bash
# 一键创建 6 个新模块骨架
set -e

cd /workspace/online-chat/backend

create_module() {
  local NAME=$1      # 例如 cs-prediction
  local ARTIFACT=$2  # 例如 cs-prediction
  local PORT=$3      # 例如 9005
  local PKG=$4       # 例如 com.chat.prediction
  local TITLE=$5     # 例如 "预见式服务"
  local DESC=$6      # 例如 "订单异常检测/流失预警/价值客户关怀"
  local DIR=$7       # 例如 prediction
  local NEED_WS=$8   # true/false

  echo "Creating $NAME..."

  # 1. pom.xml
  cat > $NAME/pom.xml <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.chat</groupId>
        <artifactId>online-chat-parent</artifactId>
        <version>1.0.0</version>
    </parent>
    <artifactId>$ARTIFACT</artifactId>
    <name>$ARTIFACT</name>
    <description>$TITLE - $DESC</description>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        $(if [ "$NEED_WS" = "true" ]; then echo '<dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>'; fi)
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.chat</groupId>
            <artifactId>cs-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.xiaoymin</groupId>
            <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
        </dependency>
    </dependencies>
    <build>
        <finalName>\${project.artifactId}</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
POM

  # 2. Application
  mkdir -p $NAME/src/main/java/com/chat/$DIR
  cat > $NAME/src/main/java/com/chat/$DIR/Cs${DIR^}Application.java <<JAVA
package com.chat.$DIR;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cs${DIR^}Application - $TITLE 启动类.
 * ----------------------------------------------------------------------------
 * 服务职责: $DESC
 * 端口: $PORT
 * 模块路径: $NAME
 */
@EnableAsync                                                                  // 异步 (事件处理)
@EnableScheduling                                                              // 定时任务 (规则引擎)
@SpringBootApplication(scanBasePackages = {"com.chat.$DIR", "com.chat.common"})
@MapperScan("com.chat.$DIR.mapper")
public class Cs${DIR^}Application {
    public static void main(String[] args) {
        SpringApplication.run(Cs${DIR^}Application.class, args);
    }
}
JAVA

  # 3. application.yml
  mkdir -p $NAME/src/main/resources
  cat > $NAME/src/main/resources/application.yml <<YML
server:
  port: $PORT

spring:
  application:
    name: $ARTIFACT
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/online_chat?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
    username: root
    password: 951127
  data:
    redis:
      host: localhost
      port: 6379
      password:
      timeout: 3000
  jackson:
    time-zone: Asia/Shanghai

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto

chat:
  jwt:
    secret: 5vQeSUDLFEt81MPAmQetxV7i8fBJLTSjPCf15seKgOI7PTXou5vgcc4M01aoseUJ
    ttl-ms: 86400000
  m3:
    adapter-url: http://localhost:8084
    default-model: minimax3

logging:
  level:
    com.chat: DEBUG
YML

  # 4. 占位 controller
  mkdir -p $NAME/src/main/java/com/chat/$DIR/controller
  cat > $NAME/src/main/java/com/chat/$DIR/controller/HealthController.java <<JAVA
package com.chat.$DIR.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * HealthController - $TITLE 健康检查.
 */
@Tag(name = "$TITLE - 健康")
@RestController
@RequestMapping("/api/$DIR")
public class HealthController {

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "service", "$ARTIFACT",
            "status", "UP",
            "ts", LocalDateTime.now().toString()
        );
    }
}
JAVA

  echo "  ✓ $NAME created"
}

# cs-prediction - 预见式服务 (需要 WS, 因为要推送到 cs-im)
create_module "cs-prediction" "cs-prediction" "9005" "com.chat.prediction" \
  "预见式服务" "订单异常检测/流失预警/价值客户关怀/主动推送" "prediction" "false"

# cs-cdp - 数字孪生
create_module "cs-cdp" "cs-cdp" "9006" "com.chat.cdp" \
  "数字孪生 360" "客户画像/标签/行为事件流" "cdp" "false"

# cs-customer-success - 客户成功
create_module "cs-customer-success" "cs-customer-success" "9007" "com.chat.success" \
  "客户成功" "健康分/onboarding/续约预测" "success" "false"

# cs-community - 群体智能社区
create_module "cs-community" "cs-community" "9008" "com.chat.community" \
  "群体智能社区" "客户发帖/UGC知识库/激励机制" "community" "false"

# cs-video - 视频会话 (需要 WS, 信令)
create_module "cs-video" "cs-video" "9009" "com.chat.video" \
  "视频会话" "WebRTC P2P 1v1/屏幕共享/合规录制" "video" "true"

# cs-voice - 智能电话 (需要 WS, 通话中推送)
create_module "cs-voice" "cs-voice" "9010" "com.chat.voice" \
  "智能语音电话" "ASR/TTS/AI Agent/通话录音" "voice" "true"

echo ""
echo "✓ 6 modules created successfully"
ls -d cs-*