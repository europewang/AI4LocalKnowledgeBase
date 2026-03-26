# Build Stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
# 配置阿里云 Maven 镜像加速构建
RUN mkdir -p /root/.m2 && \
    echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd"><mirrors><mirror><id>aliyunmaven</id><mirrorOf>*</mirrorOf><name>阿里云公共仓库</name><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml

# 下载依赖 (利用 Docker 缓存)
RUN mvn dependency:go-offline -B

# 复制源码并构建
COPY src ./src
RUN mvn package -DskipTests

# Run Stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y python3 python3-pip && rm -rf /var/lib/apt/lists/*
RUN pip3 install --no-cache-dir ezdxf openpyxl
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /app/src/main/java/com/ai4kb/backend/skill/impl/cad_text_extractor /app/skill_scripts/cad_text_extractor
ENV SKILL_CAD_SCRIPT_DIR=/app/skill_scripts/cad_text_extractor

# 暴露端口
EXPOSE 8083

# 启动命令
ENTRYPOINT ["java", "-jar", "app.jar"]
