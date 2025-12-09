#!/bin/bash

echo "=== 编译项目 ==="
mvn clean test-compile -q

if [ $? -ne 0 ]; then
    echo "编译失败"
    exit 1
fi

echo ""
echo "=== 运行详细内存分析测试 ==="
echo ""

java -cp target/classes:target/test-classes \
    -Xms512m \
    -Xmx2g \
    com.rogue.compare.DetailedMemoryAnalysisTest

echo ""
echo "测试完成！"
