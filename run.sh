#!/bin/bash

# 初始化jenv
export PATH="$HOME/.jenv/bin:$PATH"
eval "$(jenv init -)"

echo "当前使用的Java版本："
java -version

echo ""
echo "启动Spring Boot应用..."
./mvnw spring-boot:run -Dmaven.test.skip=true 