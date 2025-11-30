#!/bin/bash

# This script generates a condensed summary of the codebase using gh-llm-loader.
# The output is intended to be used as context for an LLM.

/Users/him/.venv/bin/gh-llm-loader \
    --base-dir . \
    --output-file codebase_summary.md \
    --ignored-folders .git .gradle build .cxx gradle/wrapper .idea app/src/main/res \
    --ignored-files gradlew gradlew.bat local.properties "*.DS_Store" *.hprof

echo "Codebase summary saved to codebase_summary.md"
