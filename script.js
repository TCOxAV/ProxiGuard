// script.js - With Working Admin Delete
let map;
let userMarker;
let userLocation = null;
let reports = [];
let audioContext = null;
let isAudioEnabled = true;
let currentAlertInterval = null;
let isMuted = false;
let isAdminMode = false;

const ADMIN_PASSWORD = "admin123";

const dangerTypes = {
    accident: { name: "🚗 Accident", range: 200, color: "#ff4757" },
    fire: { name: "🔥 Fire", range: 300, color: "#ff6b6b" },
    danger: { name: "⚠️ Danger", range: 150, color: "#ffa502" },
    police: { name: "👮 Police", range: 100, color: "#4ecdc4" }
};

function loadReports() {
    const saved = localStorage.getItem("proxiguard_reports");
    if (saved) {
        reports = JSON.parse(saved);
        updateMapMarkers();
        updateReportsList();
        if (isAdminMode) updateAdminPanel();
        checkProximity();
    }
}

function saveReports() {
    localStorage.setItem("proxiguard_reports", JSON.stringify(reports));
    if (isAdminMode) updateAdminPanel();
}

function initMap() {
    map = L.map('map').setView([20.5937, 78.9629], 5);
    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© OpenStreetMap contributors'
    }).addTo(map);
}

function updateUserLocation(position) {
    const lat = position.coords.latitude;
    const lng = position.coords.longitude;
    userLocation = { lat, lng };
    
    document.getElementById("locationStatus").innerHTML = "📍 Location active";
    document.getElementById("locationStatus").style.background = "#1e3a2f";
    
    if (userMarker) {
        userMarker.setLatLng([lat, lng]);
    } else {
        userMarker = L.marker([lat, lng], {
            icon: L.divIcon({
                className: 'user-marker',
                html: '📍',
                iconSize: [24, 24]
            })
        }).addTo(map);
        userMarker.bindPopup("You are here").openPopup();
    }
    
    map.setView([lat, lng], 15);
    checkProximity();
}

function locationError() {
    document.getElementById("locationStatus").innerHTML = "⚠️ Location unavailable";
}

function getUserLocation() {
    if ("geolocation" in navigator) {
        navigator.geolocation.getCurrentPosition(updateUserLocation, locationError, {
            enableHighAccuracy: true,
            maximumAge: 5000
        });
        navigator.geolocation.watchPosition(updateUserLocation, locationError, {
            enableHighAccuracy: true,
            maximumAge: 5000
        });
    }
}

function calculateDistance(lat1, lng1, lat2, lng2) {
    const R = 6371e3;
    const φ1 = lat1 * Math.PI / 180;
    const φ2 = lat2 * Math.PI / 180;
    const Δφ = (lat2 - lat1) * Math.PI / 180;
    const Δλ = (lng2 - lng1) * Math.PI / 180;
    
    const a = Math.sin(Δφ/2) * Math.sin(Δφ/2) +
              Math.cos(φ1) * Math.cos(φ2) *
              Math.sin(Δλ/2) * Math.sin(Δλ/2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    
    return R * c;
}

function checkProximity() {
    if (!userLocation) return;
    
    let closestReport = null;
    let closestDistance = Infinity;
    
    for (const report of reports) {
        const distance = calculateDistance(
            userLocation.lat, userLocation.lng,
            report.lat, report.lng
        );
        report.currentDistance = distance;
        
        const dangerRange = dangerTypes[report.type].range;
        
        if (distance < dangerRange && distance < closestDistance) {
            closestDistance = distance;
            closestReport = report;
        }
    }
    
    if (closestReport && !isMuted) {
        showAlert(closestReport, closestDistance);
    } else if (!closestReport) {
        hideAlert();
    }
    
    updateReportsList();
    updateMapMarkers();
}

function showAlert(report, distance) {
    const panel = document.getElementById("alertPanel");
    const dangerTypeSpan = document.getElementById("dangerType");
    const distanceFill = document.getElementById("distanceFill");
    const distanceText = document.getElementById("distanceText");
    
    const type = dangerTypes[report.type];
    const range = type.range;
    const proximityPercent = ((range - distance) / range) * 100;
    const beepInterval = Math.max(100, 1000 - (proximityPercent * 9));
    
    dangerTypeSpan.innerHTML = type.name;
    distanceFill.style.width = `${Math.min(100, proximityPercent)}%`;
    distanceText.innerHTML = `${Math.round(distance)}m away — ${Math.round(proximityPercent)}% danger zone`;
    
    panel.style.display = "block";
    
    if (isAudioEnabled && audioContext) {
        if (currentAlertInterval) clearInterval(currentAlertInterval);
        
        currentAlertInterval = setInterval(() => {
            if (!isMuted && document.hasFocus()) {
                playBeep(proximityPercent);
            }
        }, beepInterval);
    }
}

function hideAlert() {
    const panel = document.getElementById("alertPanel");
    panel.style.display = "none";
    if (currentAlertInterval) {
        clearInterval(currentAlertInterval);
        currentAlertInterval = null;
    }
}

function playBeep(intensity) {
    if (!audioContext) return;
    
    const now = audioContext.currentTime;
    const oscillator = audioContext.createOscillator();
    const gain = audioContext.createGain();
    
    oscillator.connect(gain);
    gain.connect(audioContext.destination);
    
    oscillator.frequency.value = 440 + (intensity * 5);
    gain.gain.value = 0.3;
    
    oscillator.start();
    gain.gain.exponentialRampToValueAtTime(0.00001, now + 0.1);
    oscillator.stop(now + 0.1);
}

function initAudio() {
    if (!audioContext) {
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
        audioContext.resume();
        isAudioEnabled = true;
        const warning = document.querySelector(".audio-warning");
        if (warning) warning.remove();
    }
}

function reportIncident() {
    if (!userLocation) {
        alert("Waiting for your location...");
        return;
    }
    
    const type = document.getElementById("reportType").value;
    const reportId = Date.now() + '_' + Math.random().toString(36).substr(2, 9);
    
    const report = {
        id: reportId,
        type: type,
        lat: userLocation.lat,
        lng: userLocation.lng,
        timestamp: new Date().toISOString(),
        reportedBy: "user",
        currentDistance: 0
    };
    
    reports.push(report);
    saveReports();
    
    // Auto-expire after 2 hours
    setTimeout(() => {
        reports = reports.filter(r => r.id !== report.id);
        saveReports();
        updateMapMarkers();
        updateReportsList();
        if (isAdminMode) updateAdminPanel();
        checkProximity();
    }, 7200000);
    
    updateMapMarkers();
    updateReportsList();
    if (isAdminMode) updateAdminPanel();
    checkProximity();
    
    const btn = document.getElementById("reportBtn");
    const originalText = btn.innerHTML;
    btn.innerHTML = "✅ Reported!";
    setTimeout(() => {
        btn.innerHTML = originalText;
    }, 2000);
}

// GLOBAL DELETE FUNCTION - accessible from anywhere
window.deleteReport = function(reportId) {
    const reportToDelete = reports.find(r => r.id === reportId);
    if (!reportToDelete) {
        console.log("Report not found:", reportId);
        return;
    }
    
    const typeName = dangerTypes[reportToDelete.type].name;
    const confirmed = confirm(`Delete ${typeName} report?\n\nLocation: ${reportToDelete.lat.toFixed(4)}, ${reportToDelete.lng.toFixed(4)}\nTime: ${new Date(reportToDelete.timestamp).toLocaleString()}`);
    
    if (confirmed) {
        reports = reports.filter(r => r.id !== reportId);
        saveReports();
        updateMapMarkers();
        updateReportsList();
        if (isAdminMode) updateAdminPanel();
        checkProximity();
        showTemporaryMessage(`🗑️ Deleted: ${typeName}`, "#ff4757");
        hideAlert();
    }
};

function showTemporaryMessage(message, color) {
    const msgDiv = document.createElement("div");
    msgDiv.textContent = message;
    msgDiv.style.position = "fixed";
    msgDiv.style.bottom = "80px";
    msgDiv.style.left = "50%";
    msgDiv.style.transform = "translateX(-50%)";
    msgDiv.style.backgroundColor = color;
    msgDiv.style.color = "white";
    msgDiv.style.padding = "12px 24px";
    msgDiv.style.borderRadius = "8px";
    msgDiv.style.zIndex = "1000";
    msgDiv.style.fontSize = "14px";
    msgDiv.style.fontWeight = "bold";
    msgDiv.style.boxShadow = "0 4px 12px rgba(0,0,0,0.3)";
    
    document.body.appendChild(msgDiv);
    
    setTimeout(() => {
        msgDiv.remove();
    }, 2000);
}

function updateMapMarkers() {
    if (!map) return;
    
    map.eachLayer(layer => {
        if (layer.options && layer.options.reportId) {
            map.removeLayer(layer);
        }
    });
    
    for (const report of reports) {
        const type = dangerTypes[report.type];
        const distance = report.currentDistance || 0;
        const isInRange = distance < type.range;
        
        // Add delete button in popup for admin mode
        const deleteButton = isAdminMode ? `<br><button onclick="deleteReport('${report.id}')" style="margin-top: 8px; background: #ff4757; color: white; border: none; padding: 5px 12px; border-radius: 5px; cursor: pointer;">🗑️ Delete (Admin)</button>` : '';
        
        const popupContent = `
            <div style="min-width: 150px;">
                <strong>${type.name}</strong><br>
                Reported: ${new Date(report.timestamp).toLocaleTimeString()}<br>
                ${isInRange ? `<span style="color:red">⚠️ ${Math.round(distance)}m away — DANGER ZONE</span>` : `${Math.round(distance)}m away`}
                ${deleteButton}
            </div>
        `;
        
        const marker = L.marker([report.lat, report.lng], {
            icon: L.divIcon({
                className: 'report-marker',
                html: type.name.split(' ')[0],
                iconSize: [30, 30]
            }),
            reportId: report.id
        }).addTo(map);
        
        marker.bindPopup(popupContent);
        
        L.circle([report.lat, report.lng], {
            radius: type.range,
            color: type.color,
            fillColor: type.color,
            fillOpacity: 0.1,
            reportId: report.id
        }).addTo(map);
    }
}

function updateReportsList() {
    const container = document.getElementById("reportsList");
    if (!userLocation) {
        container.innerHTML = "<div class='report-item'>Waiting for location...</div>";
        return;
    }
    
    if (reports.length === 0) {
        container.innerHTML = "<div class='report-item'>No reports nearby</div>";
        return;
    }
    
    const sorted = [...reports].sort((a, b) => a.currentDistance - b.currentDistance);
    
    container.innerHTML = sorted.map(report => {
        const type = dangerTypes[report.type];
        const distance = report.currentDistance;
        const isInRange = distance < type.range;
        
        // Add delete button in list for admin mode
        const deleteBtn = isAdminMode ? `<button onclick="deleteReport('${report.id}')" style="background: #ff4757; color: white; border: none; padding: 6px 12px; border-radius: 20px; cursor: pointer; font-size: 12px; font-weight: bold;">🗑️</button>` : '';
        
        return `
            <div class="report-item ${isInRange ? 'report-danger' : 'report-safe'}">
                <div style="flex: 1;">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <strong>${type.name}</strong>
                        ${deleteBtn}
                    </div>
                    <div class="report-distance">${Math.round(distance)}m away</div>
                    <div class="report-time">${new Date(report.timestamp).toLocaleTimeString()}</div>
                </div>
                <div class="danger-bar" style="background: ${type.color}; width: ${Math.min(100, Math.max(0, (type.range - distance) / type.range * 100))}%; height: 4px; border-radius: 2px;"></div>
            </div>
        `;
    }).join("");
}

function updateAdminPanel() {
    const container = document.getElementById("adminReportsList");
    const statsDiv = document.getElementById("adminStats");
    
    const total = reports.length;
    const byType = {
        accident: reports.filter(r => r.type === "accident").length,
        fire: reports.filter(r => r.type === "fire").length,
        danger: reports.filter(r => r.type === "danger").length,
        police: reports.filter(r => r.type === "police").length
    };
    
    statsDiv.innerHTML = `
        <strong>📊 Report Stats</strong><br>
        Total: ${total} | 🚗 ${byType.accident} | 🔥 ${byType.fire} | ⚠️ ${byType.danger} | 👮 ${byType.police}
        <button onclick="deleteAllReports()" style="margin-left: 10px; background: #ff4757; color: white; border: none; padding: 4px 12px; border-radius: 15px; cursor: pointer; font-size: 11px;">🗑️ Delete All</button>
    `;
    
    if (reports.length === 0) {
        container.innerHTML = "<div class='admin-report-item'>No reports</div>";
        return;
    }
    
    const sorted = [...reports].sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
    
    container.innerHTML = sorted.map(report => {
        const type = dangerTypes[report.type];
        return `
            <div class="admin-report-item">
                <div class="admin-report-info">
                    <div class="admin-report-type">${type.name}</div>
                    <div class="admin-report-details">
                        📍 ${report.lat.toFixed(6)}, ${report.lng.toFixed(6)}<br>
                        🕐 ${new Date(report.timestamp).toLocaleString()}
                    </div>
                </div>
                <button class="admin-delete-btn" onclick="deleteReport('${report.id}')">🗑️ Delete</button>
            </div>
        `;
    }).join("");
}

// Delete all reports (admin only)
window.deleteAllReports = function() {
    if (!isAdminMode) return;
    const confirmed = confirm(`⚠️ DELETE ALL REPORTS?\n\nThis will remove ${reports.length} reports permanently.`);
    if (confirmed) {
        reports = [];
        saveReports();
        updateMapMarkers();
        updateReportsList();
        updateAdminPanel();
        checkProximity();
        showTemporaryMessage(`🗑️ Deleted all ${reports.length} reports`, "#ff4757");
        hideAlert();
    }
};

function enterAdminMode() {
    const password = prompt("Enter admin password:");
    if (password === ADMIN_PASSWORD) {
        isAdminMode = true;
        document.getElementById("userPanel").style.display = "none";
        document.getElementById("adminPanel").style.display = "block";
        document.getElementById("modeBadge").innerHTML = "🔧 Admin Mode";
        document.getElementById("adminAccessBtn").style.display = "none";
        updateAdminPanel();
        updateMapMarkers(); // Refresh map to show admin delete buttons in popups
        updateReportsList(); // Refresh list to show admin delete buttons
        showTemporaryMessage("🔐 Admin Mode Activated", "#4ecdc4");
    } else {
        alert("Wrong password!");
    }
}

function exitAdminMode() {
    isAdminMode = false;
    document.getElementById("userPanel").style.display = "block";
    document.getElementById("adminPanel").style.display = "none";
    document.getElementById("modeBadge").innerHTML = "👤 User Mode";
    document.getElementById("adminAccessBtn").style.display = "block";
    updateMapMarkers(); // Refresh map to remove admin delete buttons
    updateReportsList(); // Refresh list to remove admin delete buttons
    showTemporaryMessage("👤 Exited Admin Mode", "#ffa502");
}

function silenceAlert() {
    isMuted = true;
    hideAlert();
    setTimeout(() => {
        isMuted = false;
    }, 30000);
    
    const btn = document.getElementById("silenceBtn");
    const originalText = btn.innerHTML;
    btn.innerHTML = "🔇 Muted for 30s";
    setTimeout(() => {
        btn.innerHTML = "🔇 Mute for 30s";
    }, 3000);
}

document.addEventListener("DOMContentLoaded", () => {
    initMap();
    getUserLocation();
    loadReports();
    
    setInterval(checkProximity, 2000);
    
    document.getElementById("reportBtn").addEventListener("click", reportIncident);
    document.getElementById("silenceBtn").addEventListener("click", silenceAlert);
    document.getElementById("adminAccessBtn").addEventListener("click", enterAdminMode);
    document.getElementById("exitAdminBtn").addEventListener("click", exitAdminMode);
    
    const audioWarning = document.createElement("div");
    audioWarning.className = "audio-warning";
    audioWarning.innerHTML = "🔊 Tap here to enable audio alerts";
    audioWarning.onclick = () => {
        initAudio();
        audioWarning.remove();
    };
    document.body.appendChild(audioWarning);
    
    document.body.addEventListener("touchstart", () => {
        if (!audioContext) initAudio();
    }, { once: true });
});