#!/bin/bash
# Railway/Linux start script for ShareSync

# 1. Compile all Java files
echo "🛠️ Compiling ShareSync..."
javac webserver/src/*.java

# 2. Run the platform
# We use the PORT environment variable provided by Railway
echo "🚀 Launching ShareSync on Port ${PORT:-8080}..."
java -cp . webserver.src.ShareSync
