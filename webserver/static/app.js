/**
 * ShareSync — Client-side JavaScript
 *
 * Features:
 *  • EventSource (SSE) for real-time file-upload & chat notifications
 *  • Fetch API for file upload (POST /upload) and chat (POST /chat)
 *  • Drag-and-drop file upload with progress UI
 *  • Dynamic DOM rendering for files and chat messages
 */

// ═══════════════════════════════════════════════════════
//  Constants & State
// ═══════════════════════════════════════════════════════
const MAX_FILE_SIZE = 50 * 1024 * 1024; // 50 MB

const state = {
    files: [],
    connected: false,
    eventSource: null,
};

// ═══════════════════════════════════════════════════════
//  DOM References
// ═══════════════════════════════════════════════════════
const $ = (id) => document.getElementById(id);

const dom = {
    connectionStatus:  $('connection-status'),
    statusText:        $('connection-status')?.querySelector('.status-text'),
    uploadZone:        $('upload-zone'),
    uploadZoneInner:   $('upload-zone-inner'),
    uploadProgress:    $('upload-progress'),
    progressCircle:    $('progress-circle'),
    progressPercent:   $('progress-percent'),
    progressLabel:     $('progress-label'),
    browseBtn:         $('browse-btn'),
    fileInput:         $('file-input'),
    uploaderName:      $('uploader-name'),
    fileList:          $('file-list'),
    fileListEmpty:     $('file-list-empty'),
    fileCount:         $('file-count'),
    chatMessages:      $('chat-messages'),
    chatWelcome:       $('chat-welcome'),
    chatUsername:       $('chat-username'),
    chatMessage:       $('chat-message'),
    sendBtn:           $('send-btn'),
    onlineCount:       $('online-count'),
    toastContainer:    $('toast-container'),
};

// ═══════════════════════════════════════════════════════
//  SSE Connection
// ═══════════════════════════════════════════════════════
function connectSSE() {
    if (state.eventSource) {
        state.eventSource.close();
    }

    const es = new EventSource('/events');
    state.eventSource = es;

    es.addEventListener('open', () => {
        setConnectionStatus(true);
        console.log('[SSE] Connected');
    });

    // Custom event: connected
    es.addEventListener('connected', (e) => {
        const data = JSON.parse(e.data);
        console.log('[SSE] Welcome:', data.message);
        toast('Connected to ShareSync', 'success');
    });

    // Custom event: file-upload
    es.addEventListener('file-upload', (e) => {
        const file = JSON.parse(e.data);
        console.log('[SSE] File uploaded:', file);
        addFileToList(file);
        addSystemMessage(`📁 <span class="system-highlight">${escapeHtml(file.uploader)}</span> shared <span class="system-highlight">${escapeHtml(file.fileName)}</span> (${file.readableSize})`);
        toast(`${file.uploader} shared ${file.fileName}`, 'info');
    });

    // Custom event: chat-message
    es.addEventListener('chat-message', (e) => {
        const msg = JSON.parse(e.data);
        console.log('[SSE] Chat:', msg);
        addChatMessage(msg);
    });

    es.addEventListener('error', () => {
        setConnectionStatus(false);
        console.warn('[SSE] Disconnected, retrying in 3s…');
        setTimeout(connectSSE, 3000);
    });
}

function setConnectionStatus(connected) {
    state.connected = connected;
    dom.connectionStatus.className = 'connection-status ' + (connected ? 'connected' : 'disconnected');
    dom.statusText.textContent = connected ? 'Connected' : 'Disconnected';
}

// ═══════════════════════════════════════════════════════
//  File Upload
// ═══════════════════════════════════════════════════════

// Browse button
dom.browseBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    dom.fileInput.click();
});

dom.uploadZoneInner.addEventListener('click', () => {
    dom.fileInput.click();
});

dom.fileInput.addEventListener('change', () => {
    if (dom.fileInput.files.length > 0) {
        uploadFiles(dom.fileInput.files);
        dom.fileInput.value = '';
    }
});

// Drag & Drop
dom.uploadZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dom.uploadZone.classList.add('drag-over');
});

dom.uploadZone.addEventListener('dragleave', (e) => {
    e.preventDefault();
    dom.uploadZone.classList.remove('drag-over');
});

dom.uploadZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dom.uploadZone.classList.remove('drag-over');
    if (e.dataTransfer.files.length > 0) {
        uploadFiles(e.dataTransfer.files);
    }
});

async function uploadFiles(fileList) {
    for (const file of fileList) {
        if (file.size > MAX_FILE_SIZE) {
            toast(`${file.name} exceeds 50MB limit`, 'error');
            continue;
        }
        await uploadSingleFile(file);
    }
}

async function uploadSingleFile(file) {
    const uploader = dom.uploaderName.value.trim() || 'Anonymous';

    const formData = new FormData();
    formData.append('file', file);
    formData.append('uploader', uploader);

    // Show progress
    showProgress(true);
    setProgress(0, `Uploading ${file.name}…`);

    try {
        // Use XMLHttpRequest for upload progress tracking
        const result = await new Promise((resolve, reject) => {
            const xhr = new XMLHttpRequest();

            xhr.upload.addEventListener('progress', (e) => {
                if (e.lengthComputable) {
                    const pct = Math.round((e.loaded / e.total) * 100);
                    setProgress(pct, `Uploading ${file.name}…`);
                }
            });

            xhr.addEventListener('load', () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    resolve(JSON.parse(xhr.responseText));
                } else {
                    reject(new Error(`Upload failed: ${xhr.status}`));
                }
            });

            xhr.addEventListener('error', () => reject(new Error('Network error')));
            xhr.addEventListener('abort', () => reject(new Error('Upload aborted')));

            xhr.open('POST', '/upload');
            xhr.send(formData);
        });

        setProgress(100, 'Done!');
        setTimeout(() => showProgress(false), 600);
        console.log('[Upload] Success:', result);

    } catch (err) {
        showProgress(false);
        toast(`Upload failed: ${err.message}`, 'error');
        console.error('[Upload] Error:', err);
    }
}

function showProgress(visible) {
    dom.uploadProgress.classList.toggle('active', visible);
}

function setProgress(percent, label) {
    const circumference = 2 * Math.PI * 35; // r=35
    const offset = circumference - (percent / 100) * circumference;
    dom.progressCircle.style.strokeDashoffset = offset;
    dom.progressPercent.textContent = percent + '%';
    if (label) dom.progressLabel.textContent = label;
}

// ═══════════════════════════════════════════════════════
//  File List Rendering
// ═══════════════════════════════════════════════════════
function addFileToList(fileMeta) {
    state.files.unshift(fileMeta);
    renderFileList();
}

function renderFileList() {
    // Hide empty state
    if (dom.fileListEmpty) {
        dom.fileListEmpty.style.display = state.files.length === 0 ? 'flex' : 'none';
    }

    dom.fileCount.textContent = state.files.length + ' file' + (state.files.length !== 1 ? 's' : '');

    // Remove existing file items (keep the empty state div)
    dom.fileList.querySelectorAll('.file-item').forEach(el => el.remove());

    // Render
    state.files.forEach((file) => {
        const item = document.createElement('div');
        item.className = 'file-item';

        const typeClass = getFileTypeClass(file.mimeType, file.fileName);
        const icon = getFileIcon(file.mimeType, file.fileName);
        const time = formatTime(file.timestamp);

        item.innerHTML = `
            <div class="file-icon ${typeClass}">${icon}</div>
            <div class="file-info">
                <div class="file-name" title="${escapeHtml(file.fileName)}">${escapeHtml(file.fileName)}</div>
                <div class="file-meta">
                    <span>${file.readableSize}</span>
                    <span>${escapeHtml(file.uploader)}</span>
                    <span>${time}</span>
                </div>
            </div>
            <button class="file-download" title="Download" onclick="downloadFile('${encodeURIComponent(file.fileName)}')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/>
                    <polyline points="7,10 12,15 17,10"/>
                    <line x1="12" y1="15" x2="12" y2="3"/>
                </svg>
            </button>
        `;

        // Insert after empty state
        if (dom.fileListEmpty && dom.fileListEmpty.nextSibling) {
            dom.fileList.insertBefore(item, dom.fileListEmpty.nextSibling);
        } else {
            dom.fileList.appendChild(item);
        }
    });
}

function downloadFile(encodedName) {
    const a = document.createElement('a');
    a.href = '/download/' + encodedName;
    a.download = decodeURIComponent(encodedName);
    document.body.appendChild(a);
    a.click();
    a.remove();
}

function getFileTypeClass(mime, name) {
    if (!mime) mime = '';
    const lower = (name || '').toLowerCase();
    if (mime.startsWith('image/'))  return 'type-image';
    if (mime.startsWith('video/'))  return 'type-video';
    if (mime.startsWith('audio/'))  return 'type-audio';
    if (mime === 'application/pdf') return 'type-pdf';
    if (mime.includes('zip') || mime.includes('compressed') || lower.endsWith('.rar') || lower.endsWith('.7z'))
        return 'type-zip';
    if (lower.match(/\.(js|ts|py|java|c|cpp|h|go|rs|rb|php|html|css|json|xml|yaml|yml|md|sql|sh|bat)$/))
        return 'type-code';
    if (lower.match(/\.(doc|docx|ppt|pptx|xls|xlsx|odt|rtf|txt|csv)$/))
        return 'type-doc';
    return 'type-other';
}

function getFileIcon(mime, name) {
    if (!mime) mime = '';
    const lower = (name || '').toLowerCase();
    if (mime.startsWith('image/'))  return '🖼️';
    if (mime.startsWith('video/'))  return '🎬';
    if (mime.startsWith('audio/'))  return '🎵';
    if (mime === 'application/pdf') return '📄';
    if (mime.includes('zip') || lower.endsWith('.rar') || lower.endsWith('.7z'))
        return '📦';
    if (lower.match(/\.(js|ts|py|java|c|cpp|go|rs)$/))
        return '💻';
    if (lower.match(/\.(doc|docx|txt|rtf)$/))
        return '📝';
    return '📎';
}

// ═══════════════════════════════════════════════════════
//  Chat
// ═══════════════════════════════════════════════════════

dom.sendBtn.addEventListener('click', sendChatMessage);
dom.chatMessage.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendChatMessage();
    }
});

async function sendChatMessage() {
    const user    = dom.chatUsername.value.trim() || 'Anonymous';
    const message = dom.chatMessage.value.trim();

    if (!message) return;

    dom.chatMessage.value = '';

    try {
        const res = await fetch('/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ user, message }),
        });

        if (!res.ok) {
            toast('Failed to send message', 'error');
        }
    } catch (err) {
        toast('Network error', 'error');
        console.error('[Chat] Error:', err);
    }
}

function addChatMessage(msg) {
    // Hide welcome
    if (dom.chatWelcome) {
        dom.chatWelcome.style.display = 'none';
    }

    const el = document.createElement('div');
    el.className = 'chat-msg';

    const color = stringToColor(msg.user);
    const initials = getInitials(msg.user);
    const time = formatTime(msg.timestamp);

    el.innerHTML = `
        <div class="chat-avatar" style="background:${color}">${initials}</div>
        <div class="chat-bubble">
            <div class="chat-bubble-sender">${escapeHtml(msg.user)}</div>
            <div class="chat-bubble-text">${escapeHtml(msg.message)}</div>
            <div class="chat-bubble-time">${time}</div>
        </div>
    `;

    dom.chatMessages.appendChild(el);
    dom.chatMessages.scrollTop = dom.chatMessages.scrollHeight;
}

function addSystemMessage(html) {
    const el = document.createElement('div');
    el.className = 'chat-system';
    el.innerHTML = html;
    dom.chatMessages.appendChild(el);
    dom.chatMessages.scrollTop = dom.chatMessages.scrollHeight;

    // Hide welcome
    if (dom.chatWelcome) {
        dom.chatWelcome.style.display = 'none';
    }
}

// ═══════════════════════════════════════════════════════
//  Load Existing Files
// ═══════════════════════════════════════════════════════
async function loadExistingFiles() {
    try {
        const res = await fetch('/files');
        if (!res.ok) return;
        const files = await res.json();
        state.files = files.sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
        renderFileList();
    } catch (err) {
        console.error('[Files] Could not load file list:', err);
    }
}

// ═══════════════════════════════════════════════════════
//  Utility Functions
// ═══════════════════════════════════════════════════════
function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str || '';
    return div.innerHTML;
}

function formatTime(timestamp) {
    if (!timestamp) return '';
    const d = new Date(timestamp);
    const now = new Date();
    const diffMs = now - d;

    if (diffMs < 60000) return 'just now';
    if (diffMs < 3600000) return Math.floor(diffMs / 60000) + 'm ago';
    if (diffMs < 86400000) return Math.floor(diffMs / 3600000) + 'h ago';

    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function stringToColor(str) {
    let hash = 0;
    for (let i = 0; i < (str || '').length; i++) {
        hash = str.charCodeAt(i) + ((hash << 5) - hash);
    }
    const h = Math.abs(hash) % 360;
    return `hsl(${h}, 55%, 42%)`;
}

function getInitials(name) {
    if (!name) return '?';
    const parts = name.trim().split(/\s+/);
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
    return name.substring(0, 2).toUpperCase();
}

function toast(message, type = 'info') {
    const icons = { success: '✅', error: '❌', info: 'ℹ️' };
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.innerHTML = `<span class="toast-icon">${icons[type] || 'ℹ️'}</span><span>${escapeHtml(message)}</span>`;
    dom.toastContainer.appendChild(el);

    // Auto-remove after animation
    setTimeout(() => el.remove(), 3500);
}

// ═══════════════════════════════════════════════════════
//  Init
// ═══════════════════════════════════════════════════════
document.addEventListener('DOMContentLoaded', () => {
    loadExistingFiles();
    connectSSE();

    // Sync username fields
    dom.uploaderName.addEventListener('input', () => {
        if (!dom.chatUsername.value) dom.chatUsername.value = dom.uploaderName.value;
    });
    dom.chatUsername.addEventListener('input', () => {
        if (!dom.uploaderName.value) dom.uploaderName.value = dom.chatUsername.value;
    });
});
