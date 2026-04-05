# 阶段1：Maven构建（多阶段构建，减小最终镜像体积）
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app

# 1. 先复制pom.xml，下载依赖（利用Docker缓存，依赖不变则无需重新下载）
COPY pom.xml ./
RUN mvn dependency:go-offline -B

# 2. 复制源码，执行打包（跳过测试加速构建）
COPY src ./src
RUN mvn clean package -DskipTests

# 阶段2：运行镜像（轻量Alpine镜像）
FROM openjdk:17-alpine
WORKDIR /app

# 时区配置（解决日志/数据库时区问题）
RUN apk add --no-cache tzdata
ENV TZ=Asia/Shanghai

# 复制构建阶段的jar包（Spring Boot打包后在target目录）
COPY --from=build /app/target/*.jar app.jar

# 暴露Spring Boot默认端口
EXPOSE 8080

# 启动命令（添加JVM参数优化，可选）
ENTRYPOINT ["java", "-jar", "-Xms256m", "-Xmx512m", "app.jar"]