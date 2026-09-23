import QtQuick 2.9
import QtQuick.Controls 2.2
import QtQuick.Layouts 1.2

import ServerCommandManager 1.0

GroupBox {
    id: serverCommandsGroupBox
    width: (parent.width - (parent.leftPadding + parent.rightPadding))
    padding: 12
    title: "<font color=\"skyblue\">" + qsTr("Server Commands") + "</font>"
    font.pointSize: 12

    Column {
        anchors.fill: parent
        spacing: 10

        // Description
        Label {
            width: parent.width
            text: qsTr("Execute commands on the streaming server during game sessions. Requires Apollo server with command permissions enabled.")
            font.pointSize: 9
            wrapMode: Text.Wrap
            color: "#cccccc"
        }

        // Note about when commands are available
        Label {
            width: parent.width
            text: qsTr("Note: Commands will be available during streaming sessions when connected to Apollo servers.")
            font.pointSize: 9
            wrapMode: Text.Wrap
            color: "#aaaaaa"
        }

        // Command buttons grid
        GridLayout {
            width: parent.width
            columns: 2
            columnSpacing: 10
            rowSpacing: 8

            // Restart button
            Button {
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                text: qsTr("Restart Server")
                font.pointSize: 10
                enabled: ServerCommandManager.hasPermission && !ServerCommandManager.isExecuting

                onClicked: {
                    confirmDialog.commandId = "restart_server"
                    confirmDialog.commandName = qsTr("Restart Server")
                    confirmDialog.commandDescription = qsTr("This will restart the streaming server. You will be disconnected.")
                    confirmDialog.open()
                }
            }

            // Shutdown button
            Button {
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                text: qsTr("Shutdown Server")
                font.pointSize: 10
                enabled: ServerCommandManager.hasPermission && !ServerCommandManager.isExecuting

                onClicked: {
                    confirmDialog.commandId = "shutdown_server"
                    confirmDialog.commandName = qsTr("Shutdown Server")
                    confirmDialog.commandDescription = qsTr("This will shut down the streaming server. You will be disconnected.")
                    confirmDialog.open()
                }
            }

            // Suspend button
            Button {
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                text: qsTr("Suspend Server")
                font.pointSize: 10
                enabled: ServerCommandManager.hasPermission && !ServerCommandManager.isExecuting

                onClicked: {
                    confirmDialog.commandId = "suspend_computer"
                    confirmDialog.commandName = qsTr("Suspend Computer")
                    confirmDialog.commandDescription = qsTr("This will suspend the host computer. You will be disconnected.")
                    confirmDialog.open()
                }
            }

            // Custom command button
            Button {
                Layout.fillWidth: true
                Layout.preferredHeight: 40
                text: qsTr("Custom Command")
                font.pointSize: 10
                enabled: ServerCommandManager.hasPermission && !ServerCommandManager.isExecuting

                onClicked: {
                    customCommandDialog.open()
                }
            }
        }

    }

    // Confirmation dialog
    Dialog {
        id: confirmDialog
        anchors.centerIn: parent
        width: Math.min(400, parent.width * 0.9)
        height: Math.min(200, parent.height * 0.8)
        
        property string commandId: ""
        property string commandName: ""
        property string commandDescription: ""

        title: qsTr("Confirm Command")
        modal: true

        Column {
            anchors.fill: parent
            spacing: 15

            Label {
                width: parent.width
                text: confirmDialog.commandName
                font.pointSize: 14
                font.bold: true
                wrapMode: Text.Wrap
            }

            Label {
                width: parent.width
                text: confirmDialog.commandDescription
                font.pointSize: 11
                wrapMode: Text.Wrap
                color: "#cccccc"
            }

            Label {
                width: parent.width
                text: qsTr("Are you sure you want to continue?")
                font.pointSize: 11
                wrapMode: Text.Wrap
            }
        }

        standardButtons: Dialog.Yes | Dialog.No

        onAccepted: {
            ServerCommandManager.executeCommand(commandId)
        }
    }

    // Custom command dialog
    Dialog {
        id: customCommandDialog
        anchors.centerIn: parent
        width: Math.min(400, parent.width * 0.9)
        height: Math.min(250, parent.height * 0.8)

        title: qsTr("Custom Command")
        modal: true

        Column {
            anchors.fill: parent
            spacing: 15

            Label {
                width: parent.width
                text: qsTr("Enter a custom command to execute on the server:")
                font.pointSize: 11
                wrapMode: Text.Wrap
            }

            TextField {
                id: customCommandField
                width: parent.width
                placeholderText: qsTr("e.g., custom-script.sh")
                font.pointSize: 11
            }

            Label {
                width: parent.width
                text: qsTr("Warning: Only execute commands you trust. Custom commands may have different security implications.")
                font.pointSize: 9
                wrapMode: Text.Wrap
                color: "#FFC107"
            }
        }

        standardButtons: Dialog.Ok | Dialog.Cancel

        onAccepted: {
            if (customCommandField.text.trim() !== "") {
                ServerCommandManager.executeCustomCommand(customCommandField.text.trim())
                customCommandField.text = ""
            }
        }

        onRejected: {
            customCommandField.text = ""
        }
    }

    // Connect to signals
    Connections {
        target: ServerCommandManager

        function onCommandExecuted(commandId, success, message) {
            if (success) {
                successToast.text = qsTr("Command '%1' executed successfully").arg(commandId)
                successToast.show()
            } else {
                errorToast.text = qsTr("Command '%1' failed: %2").arg(commandId).arg(message)
                errorToast.show()
            }
        }

        function onCommandFailed(commandId, error) {
            errorToast.text = qsTr("Command '%1' failed: %2").arg(commandId).arg(error)
            errorToast.show()
        }
    }

    // Toast notifications (simplified - would need proper toast component)
    Rectangle {
        id: successToast
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(300, parent.width * 0.8)
        height: 40
        color: "#4CAF50"
        radius: 5
        visible: false
        
        property string text: ""

        Label {
            anchors.centerIn: parent
            text: successToast.text
            color: "white"
            font.pointSize: 10
        }

        function show() {
            visible = true
            hideTimer.start()
        }

        Timer {
            id: hideTimer
            interval: 3000
            onTriggered: successToast.visible = false
        }
    }

    Rectangle {
        id: errorToast
        anchors.bottom: parent.bottom
        anchors.horizontalCenter: parent.horizontalCenter
        width: Math.min(300, parent.width * 0.8)
        height: 40
        color: "#F44336"
        radius: 5
        visible: false
        
        property string text: ""

        Label {
            anchors.centerIn: parent
            text: errorToast.text
            color: "white"
            font.pointSize: 10
        }

        function show() {
            visible = true
            errorHideTimer.start()
        }

        Timer {
            id: errorHideTimer
            interval: 5000
            onTriggered: errorToast.visible = false
        }
    }
}