import QtQuick 2.15
import QtQuick.Controls 2.15

Rectangle {
    id: toast
    width: 300
    height: 50
    radius: 10
    color: "#2A2A2A"
    border.color: "#4A4A4A"
    border.width: 1
    opacity: 0.9
    
    property alias text: toastText.text
    
    Text {
        id: toastText
        anchors.centerIn: parent
        color: "#FFFFFF"
        font.pixelSize: 14
        font.bold: true
        wrapMode: Text.WordWrap
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
    }
    
    function showToast(message) {
        toast.text = message
        toast.opacity = 1.0
        return true
    }
}
