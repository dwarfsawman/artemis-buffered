#include "serverpermissions.h"
#include <QDebug>

ServerPermissions::ServerPermissions(QObject *parent)
    : QObject(parent)
{
}

quint32 ServerPermissions::parsePermissions(const QString &hexString)
{
    if (hexString.isEmpty()) {
        return 0;
    }
    
    QString cleanHex = hexString;
    
    // Remove "0x" prefix if present
    if (cleanHex.startsWith("0x", Qt::CaseInsensitive)) {
        cleanHex = cleanHex.mid(2);
    }
    
    bool ok;
    quint32 permissions = cleanHex.toUInt(&ok, 16);
    
    if (!ok) {
        qWarning() << "ServerPermissions: Failed to parse hex string:" << hexString;
        return 0;
    }
    
    return permissions;
}

bool ServerPermissions::hasPermission(quint32 permissions, PermissionFlags flag)
{
    return (permissions & static_cast<quint32>(flag)) != 0;
}

QString ServerPermissions::formatPermissions(quint32 permissions, bool showHex)
{
    QString result;
    
    if (showHex) {
        result = QString("0x%1").arg(permissions, 0, 16);
    }
    
    if (permissions == 0) {
        return showHex ? result + " (No permissions)" : "No permissions";
    }
    
    QStringList enabledPermissions;
    
    if (hasPermission(permissions, CONTROLLER_INPUT)) enabledPermissions << "Controller";
    if (hasPermission(permissions, TOUCH_INPUT)) enabledPermissions << "Touch";
    if (hasPermission(permissions, PEN_INPUT)) enabledPermissions << "Pen";
    if (hasPermission(permissions, MOUSE_INPUT)) enabledPermissions << "Mouse";
    if (hasPermission(permissions, KEYBOARD_INPUT)) enabledPermissions << "Keyboard";
    if (hasPermission(permissions, CLIPBOARD_SET)) enabledPermissions << "Set Clipboard";
    if (hasPermission(permissions, CLIPBOARD_READ)) enabledPermissions << "Read Clipboard";
    if (hasPermission(permissions, FILE_UPLOAD)) enabledPermissions << "Upload Files";
    if (hasPermission(permissions, FILE_DOWNLOAD)) enabledPermissions << "Download Files";
    if (hasPermission(permissions, SERVER_COMMAND)) enabledPermissions << "Server Commands";
    if (hasPermission(permissions, LIST_APPS)) enabledPermissions << "List Apps";
    if (hasPermission(permissions, VIEW_STREAMS)) enabledPermissions << "View Streams";
    if (hasPermission(permissions, LAUNCH_APPS)) enabledPermissions << "Launch Apps";
    
    if (showHex && !enabledPermissions.isEmpty()) {
        result += " (" + enabledPermissions.join(", ") + ")";
    } else if (!enabledPermissions.isEmpty()) {
        result = enabledPermissions.join(", ");
    }
    
    return result;
}

QString ServerPermissions::getDetailedPermissions(quint32 permissions)
{
    QStringList lines;
    
    // Input capabilities with enhanced formatting
    lines << "";  // Start with spacing
    lines << tr("INPUT CAPABILITIES");
    lines << tr("─────────────────");
    lines << QString("  • Controller: %1").arg(hasPermission(permissions, CONTROLLER_INPUT) ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • Touch: %1").arg(hasPermission(permissions, TOUCH_INPUT) ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • Pen: %1").arg(hasPermission(permissions, PEN_INPUT) ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • Mouse: %1").arg(hasPermission(permissions, MOUSE_INPUT) ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • Keyboard: %1").arg(hasPermission(permissions, KEYBOARD_INPUT) ? "✓ Enabled" : "✗ Disabled");
    
    lines << ""; // Empty line for spacing
    
    // System capabilities with enhanced formatting
    lines << tr("SYSTEM CAPABILITIES");
    lines << tr("──────────────────");
    lines << QString("  • Clipboard Read: %1").arg(hasPermission(permissions, CLIPBOARD_READ) ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • Clipboard Write: %1").arg(hasPermission(permissions, CLIPBOARD_SET) ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • Server Commands: %1").arg(hasPermission(permissions, SERVER_COMMAND) ? "✓ Enabled" : "✗ Disabled");
    
    lines << ""; // Empty line for spacing
    
    // App management capabilities with enhanced formatting
    lines << tr("APP MANAGEMENT");
    lines << tr("─────────────");
    lines << QString("  • List Apps: %1").arg(hasPermission(permissions, LIST_APPS) ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • View Streams: %1").arg((permissions & (VIEW_STREAMS | LIST_APPS)) != 0 ? "✓ Enabled" : "✗ Disabled");
    lines << QString("  • Launch Apps: %1").arg((permissions & (LAUNCH_APPS | VIEW_STREAMS | LIST_APPS)) != 0 ? "✓ Enabled" : "✗ Disabled");
    
    return lines.join('\n');
}

QString ServerPermissions::getDetailedPermissionsHtml(quint32 permissions)
{
    QStringList lines;
    
    // Start with a more attractive header
    lines << "<div style='font-family: SF Pro Display, Segoe UI, system-ui, Arial; color: #2c3e50;'>";
    
    // Input capabilities with enhanced styling
    lines << "<div style='margin-bottom: 16px;'>";
    lines << "<h3 style='color: #3498db; font-size: 14px; font-weight: 600; margin: 0 0 8px 0; border-bottom: 1px solid #bdc3c7; padding-bottom: 4px;'>Input Capabilities</h3>";
    lines << QString("<div style='margin-left: 12px; line-height: 1.5;'>");
    lines << QString("  <span style='%1'>•</span> <strong>Controller:</strong> %2<br/>").arg(hasPermission(permissions, CONTROLLER_INPUT) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, CONTROLLER_INPUT) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>Touch:</strong> %2<br/>").arg(hasPermission(permissions, TOUCH_INPUT) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, TOUCH_INPUT) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>Pen:</strong> %2<br/>").arg(hasPermission(permissions, PEN_INPUT) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, PEN_INPUT) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>Mouse:</strong> %2<br/>").arg(hasPermission(permissions, MOUSE_INPUT) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, MOUSE_INPUT) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>Keyboard:</strong> %2").arg(hasPermission(permissions, KEYBOARD_INPUT) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, KEYBOARD_INPUT) ? "Enabled" : "Disabled");
    lines << "</div>";
    lines << "</div>";
    
    // System capabilities
    lines << "<div style='margin-bottom: 16px;'>";
    lines << "<h3 style='color: #9b59b6; font-size: 14px; font-weight: 600; margin: 0 0 8px 0; border-bottom: 1px solid #bdc3c7; padding-bottom: 4px;'>System Capabilities</h3>";
    lines << QString("<div style='margin-left: 12px; line-height: 1.5;'>");
    lines << QString("  <span style='%1'>•</span> <strong>Clipboard Read:</strong> %2<br/>").arg(hasPermission(permissions, CLIPBOARD_READ) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, CLIPBOARD_READ) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>Clipboard Write:</strong> %2<br/>").arg(hasPermission(permissions, CLIPBOARD_SET) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, CLIPBOARD_SET) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>File Upload:</strong> %2<br/>").arg(hasPermission(permissions, FILE_UPLOAD) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, FILE_UPLOAD) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>File Download:</strong> %2<br/>").arg(hasPermission(permissions, FILE_DOWNLOAD) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, FILE_DOWNLOAD) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>Server Commands:</strong> %2").arg(hasPermission(permissions, SERVER_COMMAND) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, SERVER_COMMAND) ? "Enabled" : "Disabled");
    lines << "</div>";
    lines << "</div>";
    
    // App management capabilities
    lines << "<div style='margin-bottom: 0;'>";
    lines << "<h3 style='color: #e67e22; font-size: 14px; font-weight: 600; margin: 0 0 8px 0; border-bottom: 1px solid #bdc3c7; padding-bottom: 4px;'>App Management</h3>";
    lines << QString("<div style='margin-left: 12px; line-height: 1.5;'>");
    lines << QString("  <span style='%1'>•</span> <strong>List Apps:</strong> %2<br/>").arg(hasPermission(permissions, LIST_APPS) ? "color: #27ae60;" : "color: #e74c3c;").arg(hasPermission(permissions, LIST_APPS) ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>View Streams:</strong> %2<br/>").arg((permissions & (VIEW_STREAMS | LIST_APPS)) != 0 ? "color: #27ae60;" : "color: #e74c3c;").arg((permissions & (VIEW_STREAMS | LIST_APPS)) != 0 ? "Enabled" : "Disabled");
    lines << QString("  <span style='%1'>•</span> <strong>Launch Apps:</strong> %2").arg((permissions & (LAUNCH_APPS | VIEW_STREAMS | LIST_APPS)) != 0 ? "color: #27ae60;" : "color: #e74c3c;").arg((permissions & (LAUNCH_APPS | VIEW_STREAMS | LIST_APPS)) != 0 ? "Enabled" : "Disabled");
    lines << "</div>";
    lines << "</div>";
    
    lines << "</div>";
    
    return lines.join("");
}

QString ServerPermissions::getPermissionName(PermissionFlags flag)
{
    switch (flag) {
    case CONTROLLER_INPUT: return "Controller Input";
    case TOUCH_INPUT: return "Touch Input";
    case PEN_INPUT: return "Pen Input";
    case MOUSE_INPUT: return "Mouse Input";
    case KEYBOARD_INPUT: return "Keyboard Input";
    case SERVER_COMMAND: return "Server Commands";
    case LIST_APPS: return "List Apps";
    case VIEW_STREAMS: return "View Streams";
    case LAUNCH_APPS: return "Launch Apps";
    default: return "Unknown";
    }
}

QString ServerPermissions::getPermissionDescription(PermissionFlags flag)
{
    switch (flag) {
    case CONTROLLER_INPUT: return "Send controller input to server";
    case TOUCH_INPUT: return "Send touch input to server";
    case PEN_INPUT: return "Send pen input to server";
    case MOUSE_INPUT: return "Send mouse input to server";
    case KEYBOARD_INPUT: return "Send keyboard input to server";
    case SERVER_COMMAND: return "Execute server commands";
    case LIST_APPS: return "List available applications";
    case VIEW_STREAMS: return "View active streaming sessions";
    case LAUNCH_APPS: return "Launch applications on server";
    default: return "Unknown permission";
    }
}
