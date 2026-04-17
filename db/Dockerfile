# PostgreSQL数据库初始化容器
# 用于在容器启动时自动创建表结构和导入CSV数据

FROM postgres:15-alpine

# 设置工作目录
WORKDIR /docker-entrypoint-initdb.d

# 复制初始化脚本
COPY init.sql /docker-entrypoint-initdb.d/
COPY import-data.sh /docker-entrypoint-initdb.d/

# 设置脚本执行权限
RUN chmod +x /docker-entrypoint-initdb.d/import-data.sh

# 创建数据目录用于挂载CSV文件
RUN mkdir -p /data/csv

# 设置环境变量
ENV POSTGRES_USER=postgres
ENV POSTGRES_PASSWORD=ltqltq2005
ENV POSTGRES_DB=energy_management
ENV TZ=Asia/Shanghai

# 暴露端口
EXPOSE 5432

# 使用PostgreSQL默认启动命令
CMD ["postgres"]