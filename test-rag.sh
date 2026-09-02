#!/bin/bash

# RAG Demo 测试脚本
# 用于验证完整的RAG流程

BASE_URL="http://localhost:8088/api/rag"

echo "========================================="
echo "RAG Demo 测试脚本"
echo "========================================="
echo ""

# 1. 健康检查
echo "1. 健康检查..."
curl -s ${BASE_URL}/health | jq .
echo ""
echo "---"
echo ""

# 2. 上传并处理Markdown文档
echo "2. 上传Markdown文档..."
if [ -f "test-documents/tesla-model3-manual.md" ]; then
    curl -s -X POST ${BASE_URL}/upload \
        -F "file=@test-documents/tesla-model3-manual.md" | jq .
else
    echo "警告: test-documents/tesla-model3-manual.md 不存在，跳过此测试"
fi
echo ""
echo "---"
echo ""

# 3. 上传并处理PDF文档 (如果有)
echo "3. 上传PDF文档..."
PDF_FILE=$(find test-documents -name "*.pdf" 2>/dev/null | head -1)
if [ -n "$PDF_FILE" ] && [ -f "$PDF_FILE" ]; then
    curl -s -X POST ${BASE_URL}/upload \
        -F "file=@${PDF_FILE}" | jq .
else
    echo "提示: 没有找到PDF文件，跳过此测试"
fi
echo ""
echo "---"
echo ""

# 4. RAG查询（带会话ID）
echo "4. RAG查询测试（第一轮）..."
curl -s -X POST ${BASE_URL}/query \
    -H "Content-Type: application/json" \
    -d '{
        "sessionId": "test_session_001",
        "question": "Tesla Model 3的主要特点是什么?"
    }' | jq .
echo ""
echo "---"
echo ""

# 5. 连续对话（第二轮）
echo "5. 连续对话测试（第二轮，带上下文）..."
curl -s -X POST ${BASE_URL}/query \
    -H "Content-Type: application/json" \
    -d '{
        "sessionId": "test_session_001",
        "question": "那Model Y呢？有什么不一样？"
    }' | jq .
echo ""
echo "---"
echo ""

# 6. 简单查询(上传+问答)
echo "6. 简单查询测试 (上传+问答一步完成)..."
if [ -f "test-documents/tesla-model3-manual.md" ]; then
    curl -s -X POST ${BASE_URL}/simple-query \
        -F "file=@test-documents/tesla-model3-manual.md" \
        -F "question=Tesla Model 3的续航里程是多少?" | jq .
else
    echo "警告: test-documents/tesla-model3-manual.md 不存在，跳过此测试"
fi
echo ""
echo "---"
echo ""

echo "========================================="
echo "测试完成!"
echo "========================================="
