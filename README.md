# 🚀 ShareSync

ShareSync is a high-performance, real-time file sharing and chat platform built from the ground up using **Pure Java Networking** and modern web technologies. No Spring, no external JSON libraries—just raw sockets, standard libraries, and high-quality engineering.

![ShareSync UI](webserver/static/screenshot.png) (Coming Soon)

## 🏗️ Architecture
The system employs a unique **3-Server Architecture** running within a single JVM:
1.  **Proxy Server (8080)**: Acts as a reverse proxy, routing traffic to either the File or Chat servers based on path patterns.
2.  **File Server (8081)**: Handles binary multipart uploads, file downloads, and static asset serving.
3.  **Chat Server (8082)**: Manages persistent SSE (Server-Sent Events) streams for real-time messaging and system notifications.

## ✨ Features
-   **Real-time File Sharing**: Upload files of any type with instant feedback and shared event notifications.
-   **Live Messaging**: High-frequency chat using SSE for zero-latency communication.
-   **Server Health Dashboard**: Monitor JVM memory, uptime, file stats, and active connections in real-time.
-   **Network Sync**: Join from other devices on the same Wi-Fi using the auto-discovered **Join URL**.
-   **Image Previews**: Visual thumbnails for images automatically generated in the file list.
-   **Safe Downloads**: RFC 5987 compliant headers ensure files are saved with original names and extensions.
-   **Glassmorphism UI**: A premium, dark-themed interface with smooth animations and responsive design.

## 🛠️ Technology Stack
-   **Backend**: Java (Threads, Sockets, NIO, AtomicInteger).
-   **Frontend**: Vanilla HTML5, CSS3 (Glassmorphism), and JavaScript (ES6+).
-   **Real-time**: SSE (Server-Sent Events) for server-to-client push.
-   **I/O**: Custom Multipart/Form-data parser and binary byte-relay proxy.

## 🚀 Getting Started

### Prerequisites
-   Java Development Kit (JDK) 11 or higher.

### Compilation
Compile all source files from the root directory:
```powershell
javac webserver/src/*.java
```

### Execution
Run the main launcher:
```powershell
java -cp . webserver.src.ShareSync
```

### Access
Open your browser to:
**[http://localhost:8080](http://localhost:8080)**

## ☁️ Cloud Deployment (Railway/Heroku)
This project is configured to work out-of-the-box on cloud platforms like **Railway** or **Heroku**.

1.  **Port Mapping**: The app automatically reads the `$PORT` environment variable. The Proxy Server will bind to this port to ensure the container is marked as "Healthy" and "Online".
2.  **Deployment Files**:
    *   `Procfile`: Tells the platform to run `bash start.sh`.
    *   `start.sh`: Handles compilation and execution in a Linux environment.
3.  **Persistence Note**: Cloud platforms usually have ephemeral filesystems. Files uploaded to `webserver/uploads` will be lost when the app restarts unless you mount a **Railway Volume** to that directory.

## 📂 Project Structure
```text
sharesync/
├── webserver/
│   ├── src/               # Java Source Code
│   │   ├── ShareSync.java     # Main Launcher
│   │   ├── ProxyServer.java   # Reverse Proxy
│   │   ├── FileHandler.java   # HTTP & File Logic
│   │   ├── ChatHandler.java   # SSE & Chat Logic
│   │   └── ...
│   ├── static/            # Frontend Assets
│   │   ├── index.html
│   │   ├── app.js
│   │   └── style.css
│   └── uploads/           # Shared Files (auto-created)
└── README.md
```

## 🔒 Security & Performance
-   **Directory Traversal Protection**: Sanitize all file paths to prevent unauthorized access.
-   **Memory Efficient**: Uses buffered streams and thread pools (Fixed & Cached) for scalability.
-   **Zero Dependencies**: Minimal attack surface area and zero external vulnerabilities.
