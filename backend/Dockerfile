# 构建阶段：使用Maven+Temurin 17构建项目（兼容JDK17，镜像维护活跃）
FROM maven:3.9.8-eclipse-temurin-17 AS build
WORKDIR /app

# 第一步：复制pom.xml，优先下载依赖（利用Docker缓存，避免每次改代码都重新下载依赖）
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# 第二步：复制源码，执行打包（跳过测试加速构建，生产环境可根据需要移除-DskipTests）
COPY src ./src
RUN mvn clean package -DskipTests

# 运行阶段：使用轻量的Temurin 17 JRE Alpine镜像（体积小、安全）
FROM --platform=$TARGETPLATFORM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 安装时区依赖，解决Java时区偏移问题（Alpine镜像默认无时区配置）
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 从构建阶段复制打包好的jar包到运行镜像
COPY --from=build /app/target/*.jar app.jar

# 暴露应用端口（与Spring Boot配置的server.port一致）
EXPOSE 8080

# JVM优化参数：设置堆内存大小，避免OOM；指定字符集
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "-Dfile.encoding=UTF-8", "app.jar"]