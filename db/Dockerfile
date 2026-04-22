# PostgreSQL数据库初始化容器
# 用于在容器启动时自动创建表结构和导入CSV数据

FROM postgres:15-alpine

# 设置工作目录
WORKDIR /docker-entrypoint-initdb.d

# 复制初始化脚本（按字母顺序执行：01-init.sql 先执行，02-import-data.sh 后执行）
COPY init.sql /docker-entrypoint-initdb.d/01-init.sql
COPY import-data.sh /docker-entrypoint-initdb.d/02-import-data.sh

# 设置脚本执行权限
RUN chmod +x /docker-entrypoint-initdb.d/02-import-data.sh

# 创建数据目录用于挂载CSV文件和状态标记
RUN mkdir -p /data/csv /data/status && \
    chown -R postgres:postgres /data && \
    chmod -R 755 /data

# 设置环境变量（通过.env.db文件或docker run命令传入）
ENV POSTGRES_USER=${POSTGRES_USER:-postgres}
ENV POSTGRES_PASSWORD=${POSTGRES_PASSWORD:-}
ENV POSTGRES_DB=${POSTGRES_DB:-energy_management}
ENV TZ=${TZ:-Asia/Shanghai}

# 暴露端口
EXPOSE 5432

# 使用PostgreSQL默认启动命令
CMD ["postgres"]