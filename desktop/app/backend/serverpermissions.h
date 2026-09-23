#pragma once

#include <QString>
#include <QObject>

/**
 * @brief Utility class for parsing Apollo server permissions
 * 
 * This class parses server permissions from the hex value received
 * from Apollo servers, similar to the Android implementation.
 * 
 * Based on the Android permission system, permissions are stored
 * as a hexadecimal bitmask where each bit represents a specific capability.
 */
class ServerPermissions : public QObject
{
    Q_OBJECT

public:
    // Permission bit flags (based on Android Apollo client)
    enum PermissionFlags {
        // Input permission group
        CONTROLLER_INPUT = 0x00000100,      // Allow controller input
        TOUCH_INPUT      = 0x00000200,      // Allow touch input  
        PEN_INPUT        = 0x00000400,      // Allow pen input
        MOUSE_INPUT      = 0x00000800,      // Allow mouse input
        KEYBOARD_INPUT   = 0x00001000,      // Allow keyboard input
        
        // Operation permission group
        CLIPBOARD_SET    = 0x00010000,      // Allow set clipboard from client
        CLIPBOARD_READ   = 0x00020000,      // Allow read clipboard from host
        FILE_UPLOAD      = 0x00040000,      // Allow upload files to host
        FILE_DOWNLOAD    = 0x00080000,      // Allow download files from host
        SERVER_COMMAND   = 0x00100000,      // Allow execute server cmd
        
        // Action permission group
        LIST_APPS        = 0x01000000,      // Allow list apps
        VIEW_STREAMS     = 0x02000000,      // Allow view streams
        LAUNCH_APPS      = 0x04000000,      // Allow launch apps
    };

    explicit ServerPermissions(QObject *parent = nullptr);
    
    /**
     * @brief Parse permissions from a hex string
     * @param hexString Hex string like "0x7131a00"
     * @return Parsed permission flags
     */
    Q_INVOKABLE static quint32 parsePermissions(const QString &hexString);
    
    /**
     * @brief Check if a specific permission is enabled
     * @param permissions Permission flags value
     * @param flag Permission flag to check
     * @return true if permission is enabled
     */
    Q_INVOKABLE static bool hasPermission(quint32 permissions, PermissionFlags flag);
    
    /**
     * @brief Get a human-readable permissions string
     * @param permissions Permission flags value
     * @param showHex Whether to include the hex value
     * @return Formatted permissions string
     */
    Q_INVOKABLE static QString formatPermissions(quint32 permissions, bool showHex = true);
    
    /**
     * @brief Get detailed permissions breakdown
     * @param permissions Permission flags value
     * @return Multi-line string with detailed permission breakdown
     */
    Q_INVOKABLE static QString getDetailedPermissions(quint32 permissions);
    
    /**
     * @brief Get detailed permissions breakdown with HTML formatting
     * @param permissions Permission flags value
     * @return HTML formatted string with detailed permission breakdown
     */
    Q_INVOKABLE static QString getDetailedPermissionsHtml(quint32 permissions);

private:
    static QString getPermissionName(PermissionFlags flag);
    static QString getPermissionDescription(PermissionFlags flag);
};
